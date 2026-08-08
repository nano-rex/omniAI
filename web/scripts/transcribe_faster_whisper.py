#!/usr/bin/env python3
import argparse
import array
import json
import math
import os
import re
import shutil
import subprocess
import tempfile
import wave
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from faster_whisper import WhisperModel


def run_command(cmd: List[str]) -> None:
    subprocess.run(cmd, check=True)


def load_local_env(project_root: Path) -> None:
    env_path = project_root / ".env"
    if not env_path.exists():
        return

    for raw_line in env_path.read_text(encoding="utf-8", errors="ignore").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip("'").strip('"')
        if key and key not in os.environ:
            os.environ[key] = value


def ensure_runtime_dirs() -> Dict[str, Path]:
    env_home = os.environ.get("LINUX_TRANSCRIBER_HOME")
    if env_home:
        project_root = Path(env_home).expanduser().resolve()
    else:
        project_root = Path(__file__).resolve().parents[1]
    load_local_env(project_root)
    base = project_root / ".runtime"
    tmp_dir = base / "tmp"
    mpl_dir = base / "mpl"
    hf_dir = base / "hf"
    for path in (base, tmp_dir, mpl_dir, hf_dir):
        path.mkdir(parents=True, exist_ok=True)

    os.environ.setdefault("TMPDIR", str(tmp_dir))
    os.environ.setdefault("MPLCONFIGDIR", str(mpl_dir))
    os.environ.setdefault("HF_HOME", str(hf_dir))
    os.environ.setdefault("XDG_CACHE_HOME", str(base / "xdg-cache"))
    Path(os.environ["XDG_CACHE_HOME"]).mkdir(parents=True, exist_ok=True)
    return {
        "base": base,
        "tmp": tmp_dir,
        "mpl": mpl_dir,
        "hf": hf_dir,
    }


def resolve_offline_model_paths(project_root: Path, args: argparse.Namespace) -> Dict[str, str]:
    models_dir = project_root / "models"
    specialist_dir = project_root / "models-specialist"
    resolved = {
        "whisper_model": args.model,
        "summary_model": args.summary_model,
        "cantonese_model": args.cantonese_specialist_model,
        "mandarin_model": args.mandarin_specialist_model,
        "malay_model": args.malay_specialist_model,
        "hokkien_model": args.hokkien_specialist_model,
        "deepfilter_binary": args.deepfilter_binary,
    }

    whisper_aliases = {
        "tiny": models_dir / "faster-whisper-tiny",
        "small": models_dir / "faster-whisper-small",
    }
    summary_aliases = {
        "Qwen/Qwen2.5-1.5B-Instruct": models_dir / "Qwen2.5-1.5B-Instruct",
        "Qwen/Qwen2.5-3B-Instruct": models_dir / "Qwen2.5-3B-Instruct",
    }
    cantonese_aliases = {
        "cantonese": root / "models-specialist" / "cantonese",
        "specialist:cantonese": specialist_dir / "cantonese",
    }
    mandarin_aliases = {
        "specialist:mandarin": specialist_dir / "mandarin",
    }
    malay_aliases = {
        "specialist:malay": specialist_dir / "malay",
    }
    hokkien_aliases = {
        "specialist:hokkien": specialist_dir / "hokkien",
    }

    whisper_path = whisper_aliases.get(str(args.model))
    if whisper_path and whisper_path.exists():
        resolved["whisper_model"] = str(whisper_path)

    summary_path = summary_aliases.get(str(args.summary_model))
    if summary_path and summary_path.exists():
        resolved["summary_model"] = str(summary_path)

    cantonese_path = cantonese_aliases.get(str(args.cantonese_specialist_model))
    if cantonese_path and cantonese_path.exists():
        resolved["cantonese_model"] = str(cantonese_path)

    mandarin_path = mandarin_aliases.get(str(args.mandarin_specialist_model))
    if mandarin_path and mandarin_path.exists():
        resolved["mandarin_model"] = str(mandarin_path)

    malay_path = malay_aliases.get(str(args.malay_specialist_model))
    if malay_path and malay_path.exists():
        resolved["malay_model"] = str(malay_path)

    hokkien_path = hokkien_aliases.get(str(args.hokkien_specialist_model))
    if hokkien_path and hokkien_path.exists():
        resolved["hokkien_model"] = str(hokkien_path)

    deepfilter_default = models_dir / "deepfilter" / "deep-filter"
    if (not args.deepfilter_binary or args.deepfilter_binary == "deep-filter") and deepfilter_default.exists():
        resolved["deepfilter_binary"] = str(deepfilter_default)

    return resolved


def decode_audio_to_wav(
    audio_path: Path,
    tmp_dir: Path,
    sample_rate: int = 16000,
) -> Path:
    decoded_path = tmp_dir / f"{audio_path.stem}.decoded.wav"
    run_command([
        "ffmpeg", "-y", "-loglevel", "error", "-i", str(audio_path),
        "-ac", "1", "-ar", str(sample_rate), str(decoded_path),
    ])
    return decoded_path


def split_audio_to_wav_chunks(
    audio_path: Path,
    tmp_dir: Path,
    chunk_seconds: int,
    sample_rate: int = 16000,
) -> List[Path]:
    chunk_dir = tmp_dir / f"{audio_path.stem}.chunks"
    chunk_dir.mkdir(parents=True, exist_ok=True)
    pattern = chunk_dir / "chunk_%04d.wav"
    run_command([
        "ffmpeg",
        "-y",
        "-loglevel",
        "error",
        "-i",
        str(audio_path),
        "-ac",
        "1",
        "-ar",
        str(sample_rate),
        "-f",
        "segment",
        "-segment_time",
        str(chunk_seconds),
        "-c",
        "pcm_s16le",
        str(pattern),
    ])
    return sorted(chunk_dir.glob("chunk_*.wav"))


def cleanup_temporary_chunk_wavs(chunk_paths: List[Path]) -> None:
    if len(chunk_paths) <= 1:
        return
    parent_dirs = {chunk_path.parent for chunk_path in chunk_paths}
    for chunk_path in chunk_paths:
        chunk_path.unlink(missing_ok=True)
    for parent_dir in parent_dirs:
        try:
            parent_dir.rmdir()
        except OSError:
            pass


def enhance_audio_for_transcription(
    wav_path: Path,
    tmp_dir: Path,
    preprocess: bool,
    enhance_mode: str,
    deepfilter_binary: Optional[str],
) -> Path:
    prepared_path = tmp_dir / f"{wav_path.stem}.prepared.wav"
    ffmpeg_filter = (
        "highpass=f=100,"
        "lowpass=f=7200,"
        "afftdn=nf=-24:nt=w,"
        "anlmdn=s=0.00008:p=0.002:r=0.01,"
        "agate=threshold=0.008:ratio=1.4:attack=20:release=250:range=0.22,"
        "acompressor=threshold=-22dB:ratio=2.3:attack=10:release=180:makeup=3dB:knee=2.5,"
        "alimiter=limit=0.90"
    )

    if not preprocess or enhance_mode == "none":
        shutil.copyfile(wav_path, prepared_path)
        return prepared_path

    if enhance_mode == "denoiser":
        try:
            from argparse import Namespace
            from denoiser import enhance as denoiser_enhance  # type: ignore
        except Exception as exc:
            raise SystemExit("Denoiser enhancement requested but denoiser is not installed. Run: python3 -m pip install denoiser") from exc

        denoise_in_dir = tmp_dir / f"{wav_path.stem}.denoiser.in"
        denoise_out_dir = tmp_dir / f"{wav_path.stem}.denoiser.out"
        denoise_in_dir.mkdir(parents=True, exist_ok=True)
        denoise_out_dir.mkdir(parents=True, exist_ok=True)
        denoise_input = denoise_in_dir / wav_path.name
        shutil.copyfile(wav_path, denoise_input)
        args = Namespace(
            model_path=None,
            dns48=True,
            dns64=False,
            master64=False,
            valentini_nc=False,
            device="cpu",
            dry=0.0,
            num_workers=1,
            streaming=False,
            noisy_dir=str(denoise_in_dir),
            noisy_json=None,
            out_dir=str(denoise_out_dir),
        )
        denoiser_enhance.enhance(args)
        denoised = denoise_out_dir / wav_path.name
        if not denoised.exists():
            raise SystemExit(f"denoiser did not produce expected output: {denoised}")
        shutil.copyfile(denoised, prepared_path)
        return prepared_path

    if enhance_mode == "voicefixer":
        try:
            from voicefixer import VoiceFixer  # type: ignore
        except Exception as exc:
            raise SystemExit("VoiceFixer enhancement requested but voicefixer is not installed. Run: python3 -m pip install voicefixer") from exc

        restored_44k = tmp_dir / f"{wav_path.stem}.voicefixer.44k.wav"
        fixer = VoiceFixer()
        # mode=2 is the package's more aggressive restoration path for badly damaged speech.
        fixer.restore(str(wav_path), str(restored_44k), cuda=False, mode=2)
        run_command([
            "ffmpeg", "-y", "-loglevel", "error", "-i", str(restored_44k),
            "-ac", "1", "-ar", "16000", str(prepared_path),
        ])
        return prepared_path

    if enhance_mode == "ai":
        if not deepfilter_binary:
            raise SystemExit("AI enhancement requested but no deep-filter binary is configured. Run ./scripts/download_models.sh deepfilter")
        deepfilter = Path(deepfilter_binary)
        if not deepfilter.exists():
            raise SystemExit(f"AI enhancement requested but deep-filter binary not found: {deepfilter}")
        samples = read_wav_mono(wav_path)
        if not samples:
            raise SystemExit(f"Could not read decoded WAV for AI enhancement: {wav_path}")
        chunk_samples = 16000 * 300
        enhanced_chunks: List[float] = []
        for chunk_index, start in enumerate(range(0, len(samples), chunk_samples)):
            end = min(len(samples), start + chunk_samples)
            chunk_wav = tmp_dir / f"{wav_path.stem}.chunk{chunk_index:04d}.wav"
            chunk_48k = tmp_dir / f"{wav_path.stem}.chunk{chunk_index:04d}.48k.wav"
            chunk_out_dir = tmp_dir / f"{wav_path.stem}.chunk{chunk_index:04d}.deepfilter.out"
            chunk_out_dir.mkdir(parents=True, exist_ok=True)
            chunk_enhanced_48k = chunk_out_dir / chunk_48k.name
            chunk_enhanced_16k = tmp_dir / f"{wav_path.stem}.chunk{chunk_index:04d}.enhanced.wav"
            try:
                write_wav_mono(chunk_wav, samples[start:end], sample_rate=16000)
                run_command([
                    "ffmpeg", "-y", "-loglevel", "error", "-i", str(chunk_wav),
                    "-ac", "1", "-ar", "48000", str(chunk_48k),
                ])
                run_command([str(deepfilter), "-o", str(chunk_out_dir), str(chunk_48k)])
                if not chunk_enhanced_48k.exists():
                    raise SystemExit(f"deep-filter did not produce expected output: {chunk_enhanced_48k}")
                run_command([
                    "ffmpeg", "-y", "-loglevel", "error", "-i", str(chunk_enhanced_48k),
                    "-ac", "1", "-ar", "16000", str(chunk_enhanced_16k),
                ])
                enhanced_chunks.extend(read_wav_mono(chunk_enhanced_16k))
            finally:
                chunk_wav.unlink(missing_ok=True)
                chunk_48k.unlink(missing_ok=True)
                chunk_enhanced_16k.unlink(missing_ok=True)
                if chunk_enhanced_48k.exists():
                    chunk_enhanced_48k.unlink(missing_ok=True)
                shutil.rmtree(chunk_out_dir, ignore_errors=True)
        write_wav_mono(prepared_path, enhanced_chunks, sample_rate=16000)
        return prepared_path

    cmd = [
        "ffmpeg",
        "-y",
        "-loglevel",
        "error",
        "-i", str(wav_path),
        "-af",
        ffmpeg_filter,
        str(prepared_path),
    ]
    run_command(cmd)
    return prepared_path


def probe_duration(path: Path) -> Optional[float]:
    result = subprocess.run(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            str(path),
        ],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        return None
    try:
        return float(result.stdout.strip())
    except Exception:
        return None


def trim_audio_ffmpeg(audio_path: Path, tmp_dir: Path, aggressiveness: int = 2) -> Tuple[Path, Dict[str, Any]]:
    trimmed_path = tmp_dir / f"{audio_path.stem}.trimmed.wav"
    input_duration = probe_duration(audio_path)

    start_thresholds = {0: "-45dB", 1: "-40dB", 2: "-36dB", 3: "-32dB"}
    stop_thresholds = {0: "-48dB", 1: "-44dB", 2: "-40dB", 3: "-36dB"}
    trim_filter = (
        "silenceremove="
        f"start_periods=1:start_duration=0.20:start_threshold={start_thresholds[aggressiveness]}:"
        f"stop_periods=-1:stop_duration=0.50:stop_threshold={stop_thresholds[aggressiveness]}"
    )

    run_command([
        "ffmpeg", "-y", "-loglevel", "error", "-i", str(audio_path),
        "-af", trim_filter,
        "-ac", "1", "-ar", "16000", "-sample_fmt", "s16",
        str(trimmed_path),
    ])

    output_duration = probe_duration(trimmed_path)
    if input_duration is None or output_duration is None:
        return trimmed_path, {"trimmed": True, "aggressiveness": aggressiveness}
    removed_ms = max(0, int(round((input_duration - output_duration) * 1000)))
    return trimmed_path, {
        "trimmed": removed_ms > 0,
        "backend": "ffmpeg",
        "aggressiveness": aggressiveness,
        "input_ms": int(round(input_duration * 1000)),
        "output_ms": int(round(output_duration * 1000)),
        "removed_ms": removed_ms,
    }


def trim_audio_vad(audio_path: Path, tmp_dir: Path, aggressiveness: int = 2) -> Tuple[Path, Dict[str, Any]]:
    try:
        import torch
        from silero_vad import get_speech_timestamps, load_silero_vad
    except Exception as exc:
        raise SystemExit(
            "VAD trimming requested but silero-vad is not installed. Run: python3 -m pip install -r requirements.txt"
        ) from exc

    trimmed_path = tmp_dir / f"{audio_path.stem}.trimmed.wav"
    input_ms = probe_duration(audio_path)
    wav_samples = read_wav_mono(audio_path)
    if not wav_samples:
        shutil.copyfile(audio_path, trimmed_path)
        return trimmed_path, {
            "trimmed": False,
            "backend": "vad",
            "aggressiveness": aggressiveness,
            "reason": "audio read failed",
        }
    audio = torch.tensor(wav_samples, dtype=torch.float32)
    model = load_silero_vad(onnx=True)

    threshold_map = {0: 0.42, 1: 0.48, 2: 0.54, 3: 0.60}
    silence_map = {0: 180, 1: 150, 2: 120, 3: 90}
    speech_map = {0: 180, 1: 220, 2: 260, 3: 320}
    pad_map = {0: 140, 1: 120, 2: 100, 3: 80}

    speech_timestamps = get_speech_timestamps(
        audio,
        model,
        threshold=threshold_map[aggressiveness],
        sampling_rate=16000,
        min_speech_duration_ms=speech_map[aggressiveness],
        min_silence_duration_ms=silence_map[aggressiveness],
        speech_pad_ms=pad_map[aggressiveness],
        return_seconds=False,
    )

    if not speech_timestamps:
        shutil.copyfile(audio_path, trimmed_path)
        return trimmed_path, {
            "trimmed": False,
            "backend": "vad",
            "aggressiveness": aggressiveness,
            "reason": "no speech detected",
        }

    kept = [audio[item["start"]:item["end"]] for item in speech_timestamps if item["end"] > item["start"]]
    merged = torch.cat(kept) if kept else audio
    write_wav_mono(trimmed_path, merged.tolist(), sample_rate=16000)

    output_ms = probe_duration(trimmed_path)
    if input_ms is None or output_ms is None:
        return trimmed_path, {
            "trimmed": True,
            "backend": "vad",
            "aggressiveness": aggressiveness,
            "segments_kept": len(speech_timestamps),
        }

    removed_ms = max(0, int(round(input_ms * 1000 - output_ms * 1000)))
    return trimmed_path, {
        "trimmed": removed_ms > 0,
        "backend": "vad",
        "aggressiveness": aggressiveness,
        "segments_kept": len(speech_timestamps),
        "input_ms": int(round(input_ms * 1000)),
        "output_ms": int(round(output_ms * 1000)),
        "removed_ms": removed_ms,
    }


def trim_audio(audio_path: Path, tmp_dir: Path, trim_mode: str, aggressiveness: int = 2) -> Tuple[Path, Dict[str, Any]]:
    if trim_mode == "ffmpeg":
        return trim_audio_ffmpeg(audio_path, tmp_dir, aggressiveness)
    return trim_audio_vad(audio_path, tmp_dir, aggressiveness)


def format_ts(sec: float) -> str:
    t = int(max(0, sec))
    h = t // 3600
    m = (t % 3600) // 60
    s = t % 60
    return f"{h:02d}:{m:02d}:{s:02d}"


def format_srt_ts(sec: float) -> str:
    total_ms = int(round(max(0.0, sec) * 1000.0))
    h = total_ms // 3600000
    m = (total_ms % 3600000) // 60000
    s = (total_ms % 60000) // 1000
    ms = total_ms % 1000
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


def overlap(a_start: float, a_end: float, b_start: float, b_end: float) -> float:
    return max(0.0, min(a_end, b_end) - max(a_start, b_start))


def assign_speakers_to_segments(
    segments: List[Dict[str, Any]],
    diar_turns: List[Tuple[float, float, str]],
) -> List[Dict[str, Any]]:
    out = []
    for s in segments:
        s_start, s_end = float(s["start"]), float(s["end"])
        best_label = "Speaker ?"
        best_ov = 0.0
        for d_start, d_end, label in diar_turns:
            ov = overlap(s_start, s_end, d_start, d_end)
            if ov > best_ov:
                best_ov = ov
                best_label = label
        ss = dict(s)
        ss["speaker"] = best_label
        out.append(ss)
    return out


def run_pyannote_diarization(
    audio_path: Path,
    num_speakers: Optional[int],
    hf_token: Optional[str],
) -> Tuple[List[Tuple[float, float, str]], Optional[str]]:
    try:
        from pyannote.audio import Pipeline  # type: ignore
    except Exception:
        return [], "pyannote.audio is not installed"

    if not hf_token:
        return [], "HF token missing. Set HF_TOKEN environment variable for pyannote model access"

    try:
        pipeline = Pipeline.from_pretrained(
            "pyannote/speaker-diarization-3.1",
            use_auth_token=hf_token,
        )
        kwargs: Dict[str, Any] = {}
        if num_speakers:
            kwargs["num_speakers"] = num_speakers
        diar = pipeline(str(audio_path), **kwargs)
    except Exception as e:
        return [], f"diarization failed: {e}"

    turns: List[Tuple[float, float, str]] = []
    for turn, _, speaker in diar.itertracks(yield_label=True):
        turns.append((float(turn.start), float(turn.end), str(speaker)))
    return turns, None


def read_wav_mono(path: Path) -> Optional[List[float]]:
    try:
        with wave.open(str(path), "rb") as wf:
            n_channels = wf.getnchannels()
            sample_width = wf.getsampwidth()
            sample_rate = wf.getframerate()
            frames = wf.readframes(wf.getnframes())
    except Exception:
        return None

    if sample_width not in (1, 2, 4):
        return None
    if sample_width == 2:
        arr = array.array("h")
        arr.frombytes(frames)
        if n_channels == 1:
            return [x / 32768.0 for x in arr]
        out: List[float] = []
        for i in range(0, len(arr), n_channels):
            chunk = arr[i:i + n_channels]
            if not chunk:
                continue
            out.append((sum(chunk) / len(chunk)) / 32768.0)
        return out
    if sample_width == 1:
        vals = [(b - 128) / 128.0 for b in frames]
        if n_channels == 1:
            return vals
        out: List[float] = []
        for i in range(0, len(vals), n_channels):
            chunk = vals[i:i + n_channels]
            if not chunk:
                continue
            out.append(sum(chunk) / len(chunk))
        return out
    arr = array.array("i")
    arr.frombytes(frames)
    if n_channels == 1:
        return [x / 2147483648.0 for x in arr]
    out: List[float] = []
    for i in range(0, len(arr), n_channels):
        chunk = arr[i:i + n_channels]
        if not chunk:
            continue
        out.append((sum(chunk) / len(chunk)) / 2147483648.0)
    return out


def write_wav_mono(path: Path, samples: List[float], sample_rate: int = 16000) -> None:
    pcm = array.array("h")
    for sample in samples:
        value = max(-1.0, min(1.0, float(sample)))
        pcm.append(int(round(value * 32767.0)))
    with wave.open(str(path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(pcm.tobytes())


def energy_diarization(
    audio_path: Path,
    segments: List[Dict[str, Any]],
    sample_rate: int = 16000,
) -> Tuple[List[Dict[str, Any]], Optional[str]]:
    if not segments:
        return [], "no segments to diarize"

    wav_path = audio_path
    tmp_wav: Optional[Path] = None
    if audio_path.suffix.lower() != ".wav":
        try:
            fd, tmp_name = tempfile.mkstemp(suffix=".wav")
            os.close(fd)
            tmp_wav = Path(tmp_name)
            run_command(
                ["ffmpeg", "-y", "-loglevel", "error", "-i", str(audio_path), "-ac", "1", "-ar", str(sample_rate), str(tmp_wav)],
            )
            wav_path = tmp_wav
        except Exception as e:
            return [], f"fallback diarization conversion failed: {e}"

    samples = read_wav_mono(wav_path)
    if tmp_wav and tmp_wav.exists():
        tmp_wav.unlink(missing_ok=True)
    if not samples:
        return [], "fallback diarization could not read audio"

    energies: List[float] = []
    for s in segments:
        a = max(0, int(float(s["start"]) * sample_rate))
        b = max(a + 1, int(float(s["end"]) * sample_rate))
        b = min(b, len(samples))
        if a >= b:
            energies.append(0.0)
            continue
        seg = samples[a:b]
        rms = (sum(x * x for x in seg) / max(1, len(seg))) ** 0.5
        energies.append(rms)

    c1 = min(energies)
    c2 = max(energies)
    if abs(c1 - c2) < 1e-9:
        out = [dict(s, speaker="Speaker 1") for s in segments]
        return out, None

    for _ in range(8):
        g1 = [e for e in energies if abs(e - c1) <= abs(e - c2)]
        g2 = [e for e in energies if abs(e - c1) > abs(e - c2)]
        if g1:
            c1 = sum(g1) / len(g1)
        if g2:
            c2 = sum(g2) / len(g2)

    low_is_s1 = c1 <= c2
    diarized: List[Dict[str, Any]] = []
    for s, e in zip(segments, energies):
        near_c1 = abs(e - c1) <= abs(e - c2)
        if low_is_s1:
            speaker = "Speaker 1" if near_c1 else "Speaker 2"
        else:
            speaker = "Speaker 2" if near_c1 else "Speaker 1"
        diarized.append(dict(s, speaker=speaker))
    return diarized, None


def extract_segment_wav(
    audio_path: Path,
    start_sec: float,
    end_sec: float,
    output_path: Path,
) -> None:
    run_command(
        [
            "ffmpeg",
            "-y",
            "-loglevel",
            "error",
            "-ss",
            f"{max(0.0, start_sec):.3f}",
            "-to",
            f"{max(start_sec, end_sec):.3f}",
            "-i",
            str(audio_path),
            "-ac",
            "1",
            "-ar",
            "16000",
            str(output_path),
        ]
    )


def hz_to_mel(hz: float) -> float:
    return 2595.0 * math.log10(1.0 + hz / 700.0)


def mel_to_hz(mel: float) -> float:
    return 700.0 * (10 ** (mel / 2595.0) - 1.0)


def mel_filterbank(
    sample_rate: int,
    n_fft: int,
    n_mels: int = 26,
    fmin: float = 80.0,
    fmax: Optional[float] = None,
):
    import numpy as np  # type: ignore

    if fmax is None:
        fmax = sample_rate / 2.0

    mel_points = np.linspace(hz_to_mel(fmin), hz_to_mel(fmax), n_mels + 2)
    hz_points = [mel_to_hz(float(m)) for m in mel_points]
    bins = np.floor((n_fft + 1) * np.asarray(hz_points) / sample_rate).astype(int)

    bank = np.zeros((n_mels, n_fft // 2 + 1), dtype=float)
    for i in range(1, n_mels + 1):
        left = max(0, int(bins[i - 1]))
        center = max(left + 1, int(bins[i]))
        right = max(center + 1, int(bins[i + 1]))
        right = min(right, n_fft // 2 + 1)
        center = min(center, right - 1)
        for j in range(left, center):
            bank[i - 1, j] = (j - left) / max(1, center - left)
        for j in range(center, right):
            bank[i - 1, j] = (right - j) / max(1, right - center)
    return bank


def infer_segment_language(text: str) -> str:
    t = text.lower()
    if any(token in text for token in ("粵", "廣東話", "我係", "講者", "冇", "咩", "呢")):
        return "yue"
    if any("\u4e00" <= ch <= "\u9fff" for ch in text):
        return "zh"
    if (
        t.startswith("helo")
        or t.startswith("saya")
        or any(token in t for token in (" bahasa melayu", " penutur ", " ialah ", " sekali lagi", " sekarang ", " sambung "))
    ):
        return "ms"
    if (
        t.startswith("hello")
        or any(token in t for token in (" speaker ", " english", "conversation", "testing", "switch back"))
    ):
        return "en"
    return "other"


def segment_acoustic_features(
    segment_samples: List[float],
    sample_rate: int,
) -> List[float]:
    import numpy as np  # type: ignore
    from scipy.fftpack import dct  # type: ignore

    arr = np.asarray(segment_samples, dtype=float)
    if arr.size == 0:
        return [0.0] * 22

    if arr.size < int(0.25 * sample_rate):
        pad = int(0.25 * sample_rate) - arr.size
        arr = np.pad(arr, (0, pad))

    arr = arr - np.mean(arr)
    peak = np.max(np.abs(arr))
    if peak > 1e-9:
        arr = arr / peak

    pre_emphasis = 0.97
    emphasized = np.append(arr[0], arr[1:] - pre_emphasis * arr[:-1])

    frame_len = max(256, int(0.025 * sample_rate))
    hop_len = max(128, int(0.010 * sample_rate))
    n_fft = 1
    while n_fft < frame_len:
        n_fft *= 2

    frames = []
    for start in range(0, max(1, emphasized.size - frame_len + 1), hop_len):
        frame = emphasized[start:start + frame_len]
        if frame.size < frame_len:
            frame = np.pad(frame, (0, frame_len - frame.size))
        frames.append(frame * np.hamming(frame_len))
    if not frames:
        frames = [np.pad(emphasized, (0, max(0, frame_len - emphasized.size)))[:frame_len] * np.hamming(frame_len)]

    frame_arr = np.vstack(frames)
    spectrum = np.abs(np.fft.rfft(frame_arr, n=n_fft, axis=1)) ** 2
    bank = mel_filterbank(sample_rate, n_fft, n_mels=26, fmin=80.0, fmax=min(7600.0, sample_rate / 2.0))
    mel_energy = np.maximum(1e-10, spectrum @ bank.T)
    log_mel = np.log(mel_energy)
    mfcc = dct(log_mel, type=2, axis=1, norm="ortho")[:, :13]

    delta = np.diff(mfcc, axis=0)
    if delta.size == 0:
        delta_mean = np.zeros(13, dtype=float)
    else:
        delta_mean = delta.mean(axis=0)

    rms = float(np.sqrt(np.mean(arr ** 2)))
    zcr = float(np.mean(arr[:-1] * arr[1:] < 0)) if arr.size > 1 else 0.0
    freqs = np.fft.rfftfreq(arr.size, d=1.0 / sample_rate)
    whole_spec = np.abs(np.fft.rfft(arr))
    if whole_spec.sum() <= 1e-12:
        centroid = 0.0
        bandwidth = 0.0
        rolloff = 0.0
    else:
        centroid = float(np.sum(freqs * whole_spec) / np.sum(whole_spec))
        bandwidth = float(np.sqrt(np.sum(((freqs - centroid) ** 2) * whole_spec) / np.sum(whole_spec)))
        cumsum = np.cumsum(whole_spec)
        rolloff_index = int(np.searchsorted(cumsum, 0.85 * cumsum[-1]))
        rolloff = float(freqs[min(rolloff_index, len(freqs) - 1)])

    mean_abs = float(np.mean(np.abs(arr)))
    std = float(np.std(arr))
    feature_vec = np.concatenate(
        [
            mfcc.mean(axis=0),
            delta_mean,
            np.asarray([rms, zcr, centroid / 8000.0, bandwidth / 8000.0, rolloff / 8000.0, mean_abs + std], dtype=float),
        ]
    )
    return feature_vec.tolist()


def acoustic_cluster_diarization(
    audio_path: Path,
    segments: List[Dict[str, Any]],
    sample_rate: int = 16000,
) -> Tuple[List[Dict[str, Any]], Optional[str]]:
    try:
        from sklearn.cluster import AgglomerativeClustering  # type: ignore
        from sklearn.metrics import silhouette_score  # type: ignore
        from sklearn.preprocessing import StandardScaler  # type: ignore
        import numpy as np  # type: ignore
    except Exception as exc:
        return [], f"acoustic diarization dependencies unavailable: {exc}"

    wav_path = audio_path
    tmp_wav: Optional[Path] = None
    if audio_path.suffix.lower() != ".wav":
        try:
            fd, tmp_name = tempfile.mkstemp(suffix=".wav")
            os.close(fd)
            tmp_wav = Path(tmp_name)
            extract_segment_wav(audio_path, 0.0, 10**9, tmp_wav)
            wav_path = tmp_wav
        except Exception as exc:
            return [], f"acoustic diarization conversion failed: {exc}"

    samples = read_wav_mono(wav_path)
    if tmp_wav and tmp_wav.exists():
        tmp_wav.unlink(missing_ok=True)
    if not samples:
        return [], "acoustic diarization could not read audio"

    usable: List[Tuple[int, List[float], str]] = []
    language_order = ["en", "ms", "zh", "yue", "other"]
    for idx, seg in enumerate(segments):
        start = max(0, int(float(seg["start"]) * sample_rate))
        end = min(len(samples), max(start + 1, int(float(seg["end"]) * sample_rate)))
        if end - start < int(0.6 * sample_rate):
            continue
        feats = segment_acoustic_features(samples[start:end], sample_rate)
        seg_lang = infer_segment_language(str(seg.get("text", "")))
        lang_one_hot = [0.0] * len(language_order)
        lang_one_hot[language_order.index(seg_lang)] = 0.7
        usable.append((idx, feats + lang_one_hot, seg_lang))

    if len(usable) < 2:
        return [], "acoustic diarization found too few usable segments"

    vectors = np.array([f for _, f, _ in usable], dtype=float)
    vectors = StandardScaler().fit_transform(vectors)
    if vectors.shape[0] == 2:
        labels = np.array([0, 1], dtype=int)
    else:
        max_k = min(4, len(usable) - 1)
        best_k = 2
        best_score = -1.0
        scores: Dict[int, float] = {}
        for k in range(2, max_k + 1):
            labels_k = AgglomerativeClustering(n_clusters=k, metric="euclidean", linkage="ward").fit_predict(vectors)
            score = silhouette_score(vectors, labels_k)
            scores[k] = float(score)
            if score > best_score:
                best_score = score
                best_k = k
        distinct_langs = len({lang for _, _, lang in usable if lang != "other"})
        if 2 <= distinct_langs <= max_k:
            lang_score = scores.get(distinct_langs, -1.0)
            if lang_score >= best_score - 0.08:
                best_k = distinct_langs
        labels = AgglomerativeClustering(n_clusters=best_k, metric="euclidean", linkage="ward").fit_predict(vectors)

    index_to_speaker: Dict[int, str] = {}
    label_map: Dict[int, int] = {}
    next_label = 1
    for (seg_index, _, _), label in zip(usable, labels):
        if int(label) not in label_map:
            label_map[int(label)] = next_label
            next_label += 1
        index_to_speaker[seg_index] = f"Speaker {label_map[int(label)]}"

    diarized: List[Dict[str, Any]] = []
    prev_speaker = "Speaker 1"
    for idx, seg in enumerate(segments):
        speaker = index_to_speaker.get(idx)
        if not speaker:
            speaker = prev_speaker
        diarized.append(dict(seg, speaker=speaker))
        prev_speaker = speaker
    return diarized, None


def redetect_segments(
    model: WhisperModel,
    audio_path: Path,
    segments: List[Dict[str, Any]],
    tmp_dir: Path,
) -> Tuple[List[Dict[str, Any]], List[str]]:
    refined: List[Dict[str, Any]] = []
    notes: List[str] = []

    for index, seg in enumerate(segments):
        start_sec = float(seg["start"])
        end_sec = float(seg["end"])
        if end_sec - start_sec < 0.4:
            refined.append(seg)
            continue

        clip_path = tmp_dir / f"segment_{index:04d}.wav"
        try:
            extract_segment_wav(audio_path, start_sec, end_sec, clip_path)
            seg_iter, seg_info = model.transcribe(
                str(clip_path),
                language=None,
                task="transcribe",
                vad_filter=False,
                beam_size=3,
            )
            seg_text = " ".join(s.text.strip() for s in seg_iter if s.text.strip()).strip()
            updated = dict(seg)
            if seg_text:
                updated["text"] = seg_text
            updated["detected_language"] = seg_info.language
            updated["detected_language_probability"] = seg_info.language_probability
            refined.append(updated)
        except Exception as exc:
            notes.append(f"segment {index} redetect failed: {exc}")
            refined.append(seg)
        finally:
            clip_path.unlink(missing_ok=True)

    return refined, notes


def parse_route_languages(raw: str) -> List[str]:
    langs: List[str] = []
    for item in raw.split(","):
        lang = item.strip().lower()
        if not lang:
            continue
        if lang not in langs:
            langs.append(lang)
    return langs


def route_language_for_segment(
    model: WhisperModel,
    audio_path: Path,
    seg: Dict[str, Any],
    tmp_dir: Path,
    route_languages: List[str],
    index: int,
) -> Tuple[Dict[str, Any], List[str]]:
    notes: List[str] = []
    start_sec = float(seg["start"])
    end_sec = float(seg["end"])
    if end_sec - start_sec < 0.4:
        return seg, notes

    clip_path = tmp_dir / f"route_segment_{index:04d}.wav"
    try:
        extract_segment_wav(audio_path, start_sec, end_sec, clip_path)
        candidate_langs = list(route_languages)
        detected = str(seg.get("detected_language") or "").strip().lower()
        if detected and detected not in candidate_langs:
            candidate_langs.append(detected)
        if "auto" not in candidate_langs:
            candidate_langs.append("auto")

        best_score = -999.0
        best_text = str(seg.get("text", ""))
        best_lang = detected or "auto"
        best_method = "existing"

        for lang in candidate_langs:
            try:
                kwargs: Dict[str, Any] = {
                    "task": "transcribe",
                    "vad_filter": False,
                    "beam_size": 5,
                }
                if lang != "auto":
                    kwargs["language"] = lang
                seg_iter, seg_info = model.transcribe(str(clip_path), **kwargs)
                pieces = []
                scores = []
                for item in seg_iter:
                    text = item.text.strip()
                    if text:
                        pieces.append(text)
                    scores.append(float(getattr(item, "avg_logprob", -5.0)))
                text = " ".join(pieces).strip()
                if not text:
                    score = -999.0
                else:
                    score = (sum(scores) / max(1, len(scores))) + min(1.5, len(text) / 80.0)
                if score > best_score:
                    best_score = score
                    best_text = text or best_text
                    best_lang = lang if lang != "auto" else str(getattr(seg_info, "language", "auto"))
                    best_method = "forced" if lang != "auto" else "auto"
            except Exception as exc:
                notes.append(f"segment {index} route {lang} failed: {exc}")

        updated = dict(seg)
        updated["text"] = best_text
        updated["routed_language"] = best_lang
        updated["language_route_method"] = best_method
        updated["language_route_score"] = round(best_score, 4)
        return updated, notes
    finally:
        clip_path.unlink(missing_ok=True)


def route_segments_by_language(
    model: WhisperModel,
    audio_path: Path,
    segments: List[Dict[str, Any]],
    tmp_dir: Path,
    route_languages: List[str],
) -> Tuple[List[Dict[str, Any]], List[str]]:
    routed: List[Dict[str, Any]] = []
    notes: List[str] = []
    for index, seg in enumerate(segments):
        updated, seg_notes = route_language_for_segment(
            model,
            audio_path,
            seg,
            tmp_dir,
            route_languages,
            index,
        )
        routed.append(updated)
        notes.extend(seg_notes)
    return routed, notes


def needs_cantonese_specialist(seg: Dict[str, Any]) -> bool:
    text = str(seg.get("text", ""))
    cantonese_markers = (
        "粵", "廣東話", "廣東", "講者", "我係", "係", "佢", "喺", "咗", "啱", "冇", "呢", "咩",
    )
    return any(marker in text for marker in cantonese_markers)


def load_specialist_model_bundle(
    model_id: str,
    cache: Dict[str, Tuple[Any, Any]],
) -> Tuple[Any, Any]:
    if model_id in cache:
        return cache[model_id]

    import torch  # type: ignore
    from transformers import AutoModelForSpeechSeq2Seq, AutoProcessor  # type: ignore

    model = AutoModelForSpeechSeq2Seq.from_pretrained(
        model_id,
        torch_dtype=torch.float32,
        low_cpu_mem_usage=True,
        use_safetensors=True,
    )
    processor = AutoProcessor.from_pretrained(model_id)
    cache[model_id] = (model, processor)
    return model, processor


def run_specialist_inference(
    clip_path: Path,
    model_id: str,
    language_hint: Optional[str],
    cache: Dict[str, Tuple[Any, Any]],
) -> str:
    import numpy as np  # type: ignore

    model, processor = load_specialist_model_bundle(model_id, cache)
    samples = read_wav_mono(clip_path)
    if not samples:
        raise RuntimeError("could not decode extracted segment")
    inputs = processor(
        np.asarray(samples, dtype=np.float32),
        sampling_rate=16000,
        return_tensors="pt",
    )
    generate_kwargs: Dict[str, Any] = {"task": "transcribe"}
    if language_hint:
        generate_kwargs["language"] = language_hint
    if "input_features" in inputs:
        generated_ids = model.generate(
            input_features=inputs["input_features"],
            **generate_kwargs,
        )
    elif "input_values" in inputs:
        generated_ids = model.generate(
            inputs=inputs["input_values"],
            **generate_kwargs,
        )
    else:
        raise RuntimeError(f"unsupported processor outputs: {list(inputs.keys())}")
    return processor.batch_decode(generated_ids, skip_special_tokens=True)[0].strip()


def specialist_target_for_segment(
    seg: Dict[str, Any],
    resolved_models: Dict[str, str],
) -> Optional[Tuple[str, Optional[str], str]]:
    routed = str(seg.get("routed_language") or seg.get("detected_language") or "").lower()
    text = str(seg.get("text", ""))
    if any(code in routed for code in ("yue", "zh-hk", "zh-yue")) or needs_cantonese_specialist(seg):
        return resolved_models["cantonese_model"], "zh", "cantonese"
    if any(code in routed for code in ("nan", "hok", "tai")) or "台語" in text or "taigi" in text.lower():
        return resolved_models["hokkien_model"], None, "hokkien"
    if routed in {"ms"}:
        return resolved_models["malay_model"], "ms", "malay"
    if routed.startswith("zh") or routed in {"cmn"}:
        return resolved_models["mandarin_model"], "zh", "mandarin"
    return None


def run_language_specialists(
    audio_path: Path,
    segments: List[Dict[str, Any]],
    tmp_dir: Path,
    resolved_models: Dict[str, str],
) -> Tuple[List[Dict[str, Any]], List[str]]:
    notes: List[str] = []
    refined: List[Dict[str, Any]] = []
    cache: Dict[str, Tuple[Any, Any]] = {}
    try:
        import torch  # type: ignore
        import numpy as np  # type: ignore
        from transformers import AutoModelForSpeechSeq2Seq, AutoProcessor  # type: ignore
        _ = (torch, np, AutoModelForSpeechSeq2Seq, AutoProcessor)
    except Exception as exc:
        return segments, [f"specialist models unavailable: {exc}"]

    for index, seg in enumerate(segments):
        target = specialist_target_for_segment(seg, resolved_models)
        if not target:
            refined.append(seg)
            continue
        model_id, language_hint, specialist_name = target
        clip_path = tmp_dir / f"specialist_segment_{index:04d}.wav"
        try:
            extract_segment_wav(audio_path, float(seg["start"]), float(seg["end"]), clip_path)
            text = run_specialist_inference(
                clip_path,
                model_id,
                language_hint,
                cache,
            )
            updated = dict(seg)
            if text:
                updated["text"] = text
            updated["specialist_model"] = model_id
            updated["specialist_name"] = specialist_name
            refined.append(updated)
        except Exception as exc:
            notes.append(f"segment {index} specialist {specialist_name} failed: {exc}")
            refined.append(seg)
        finally:
            clip_path.unlink(missing_ok=True)
    return refined, notes


def run_cantonese_specialist(
    audio_path: Path,
    segments: List[Dict[str, Any]],
    tmp_dir: Path,
    model_id: str,
) -> Tuple[List[Dict[str, Any]], List[str]]:
    notes: List[str] = []
    try:
        import numpy as np  # type: ignore
        import torch  # type: ignore
        from transformers import AutoModelForSpeechSeq2Seq, AutoProcessor  # type: ignore
    except Exception as exc:
        return segments, [f"cantonese specialist unavailable: {exc}"]

    device = "cpu"
    torch_dtype = torch.float32

    model = AutoModelForSpeechSeq2Seq.from_pretrained(
        model_id,
        torch_dtype=torch_dtype,
        low_cpu_mem_usage=True,
        use_safetensors=True,
    )
    processor = AutoProcessor.from_pretrained(model_id)

    refined: List[Dict[str, Any]] = []
    for index, seg in enumerate(segments):
        if not needs_cantonese_specialist(seg):
            refined.append(seg)
            continue

        clip_path = tmp_dir / f"cant_segment_{index:04d}.wav"
        try:
            extract_segment_wav(audio_path, float(seg["start"]), float(seg["end"]), clip_path)
            samples = read_wav_mono(clip_path)
            if not samples:
                raise RuntimeError("could not decode extracted segment")
            inputs = processor(
                np.asarray(samples, dtype=np.float32),
                sampling_rate=16000,
                return_tensors="pt",
            )
            generate_kwargs = {"task": "transcribe", "language": "zh"}
            if "input_features" in inputs:
                generated_ids = model.generate(
                    input_features=inputs["input_features"],
                    **generate_kwargs,
                )
            elif "input_values" in inputs:
                generated_ids = model.generate(
                    inputs=inputs["input_values"],
                    **generate_kwargs,
                )
            else:
                raise RuntimeError(f"unsupported processor outputs: {list(inputs.keys())}")
            text = processor.batch_decode(generated_ids, skip_special_tokens=True)[0].strip()
            updated = dict(seg)
            if text:
                updated["text"] = text
                updated["cantonese_specialist_model"] = model_id
            refined.append(updated)
        except Exception as exc:
            notes.append(f"segment {index} cantonese specialist failed: {exc}")
            refined.append(seg)
        finally:
            clip_path.unlink(missing_ok=True)

    return refined, notes


def extract_json_block(text: str) -> Optional[Dict[str, Any]]:
    m = re.search(r"\{[\s\S]*\}", text)
    if not m:
        return None
    try:
        return json.loads(m.group(0))
    except Exception:
        return None


def run_summary_llm(
    transcript_text: str,
    model_id: str,
    max_new_tokens: int,
) -> Tuple[Optional[Dict[str, Any]], Optional[str], Optional[str]]:
    try:
        from transformers import pipeline  # type: ignore
    except Exception:
        return None, None, "transformers is not installed"

    prompt = (
        "You are a multilingual meeting analyst. Return ONLY valid JSON with keys "
        "summary, key_points, action_items.\n"
        "summary: short paragraph.\n"
        "key_points: array of concise bullets.\n"
        "action_items: array of tasks.\n\n"
        "Transcript:\n"
        + transcript_text[:12000]
    )

    try:
        gen = pipeline("text-generation", model=model_id)
        out = gen(
            prompt,
            max_new_tokens=max_new_tokens,
            do_sample=False,
            return_full_text=False,
        )[0]["generated_text"]
    except Exception as e:
        return None, None, f"summary generation failed: {e}"

    completion = out
    as_json = extract_json_block(completion)
    if as_json:
        return as_json, completion, None

    lines = [ln.strip("-* \t") for ln in completion.splitlines() if ln.strip()]
    summary = lines[0] if lines else completion.strip()
    key_points = [ln for ln in lines[1:6] if ln]
    if not key_points and summary:
        key_points = [summary]
    parsed = {
        "summary": summary or "Summary generated but empty output.",
        "key_points": key_points,
        "action_items": [],
        "_raw": completion,
    }
    return parsed, completion, None


def fallback_summary(transcript_text: str) -> Dict[str, Any]:
    clean = " ".join(transcript_text.split())
    if not clean:
        return {"summary": "No transcript content.", "key_points": [], "action_items": []}

    sentences = re.split(r"(?<=[.!?。！？])\\s+", clean)
    summary = " ".join(sentences[:2]).strip()
    if not summary:
        summary = clean[:280]

    key_points: List[str] = []
    for kw, msg in [
        ("meeting", "Meeting/discussion context appears."),
        ("invoice", "Invoice-related content is mentioned."),
        ("report", "Report-related content is mentioned."),
        ("tomorrow", "Near-term timeline reference detected."),
        ("next", "Future-oriented statement detected."),
    ]:
        if kw in clean.lower():
            key_points.append(msg)
    if not key_points:
        key_points = ["Review transcript manually for important names, dates, and amounts."]

    action_items = ["Validate critical details from transcript before making decisions."]
    return {"summary": summary, "key_points": key_points, "action_items": action_items}


def write_json(path: Path, payload: Dict[str, Any]) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def process_chunk_segments(
    chunk_path: Path,
    model: WhisperModel,
    args: argparse.Namespace,
    runtime_dirs: Dict[str, Path],
    resolved_models: Dict[str, str],
    route_languages: List[str],
) -> Tuple[Path, Path, Path, List[Dict[str, Any]], Dict[str, Any]]:
    enhanced_inp = enhance_audio_for_transcription(
        chunk_path,
        runtime_dirs["tmp"],
        preprocess=not args.no_preprocess,
        enhance_mode=args.enhance_mode,
        deepfilter_binary=resolved_models["deepfilter_binary"],
    )

    trim_meta: Optional[Dict[str, Any]] = None
    if args.trim:
        prepared_inp, trim_meta = trim_audio(
            enhanced_inp,
            runtime_dirs["tmp"],
            trim_mode=args.trim_mode,
            aggressiveness=args.trim_aggressiveness,
        )
    else:
        prepared_inp = enhanced_inp

    segments_it, info = model.transcribe(
        str(prepared_inp),
        language=None,
        task="transcribe",
        vad_filter=True,
        beam_size=5,
    )

    seg_rows: List[Dict[str, Any]] = []
    for s in segments_it:
        txt = s.text.strip()
        if not txt:
            continue
        seg_rows.append({"start": float(s.start), "end": float(s.end), "text": txt})

    if args.redetect_per_segment and seg_rows:
        seg_rows, redetect_notes = redetect_segments(model, prepared_inp, seg_rows, runtime_dirs["tmp"])
    else:
        redetect_notes = []

    if args.language_routing and seg_rows:
        seg_rows, routing_notes = route_segments_by_language(
            model,
            prepared_inp,
            seg_rows,
            runtime_dirs["tmp"],
            route_languages,
        )
    else:
        routing_notes = []

    if args.specialist_routing and seg_rows:
        seg_rows, specialist_notes = run_language_specialists(
            prepared_inp,
            seg_rows,
            runtime_dirs["tmp"],
            resolved_models,
        )
    else:
        specialist_notes = []

    if args.cantonese_specialist and seg_rows:
        seg_rows, cantonese_notes = run_cantonese_specialist(
            prepared_inp,
            seg_rows,
            runtime_dirs["tmp"],
            resolved_models["cantonese_model"],
        )
    else:
        cantonese_notes = []

    chunk_meta: Dict[str, Any] = {
        "language": info.language,
        "language_probability": info.language_probability,
        "duration": info.duration,
        "decoded_input": str(chunk_path),
        "enhanced_input": str(enhanced_inp),
        "prepared_input": str(prepared_inp),
    }
    if trim_meta is not None:
        chunk_meta["trim"] = trim_meta
    if redetect_notes:
        chunk_meta["per_segment_redetect_notes"] = redetect_notes
    if routing_notes:
        chunk_meta["language_routing_notes"] = routing_notes
    if specialist_notes:
        chunk_meta["specialist_routing_notes"] = specialist_notes
    if cantonese_notes:
        chunk_meta["cantonese_specialist_notes"] = cantonese_notes

    return enhanced_inp, prepared_inp, chunk_path, seg_rows, chunk_meta


def main() -> int:
    p = argparse.ArgumentParser(description="Multilingual transcription with optional diarization and summary")
    p.add_argument("input", help="Input audio path")
    p.add_argument("--model", default="small", help="faster-whisper model size or local path (tiny/small)")
    p.add_argument("--compute-type", default="int8", help="Compute type (int8/float16/float32)")
    p.add_argument("--device", default="cpu", help="cpu or cuda")
    p.add_argument("--output-dir", default="outputs", help="Output directory")
    p.add_argument("--no-preprocess", action="store_true", help="Skip default audio cleanup before transcription")
    p.add_argument("--enhance-mode", default="ffmpeg", choices=["ffmpeg", "ai", "denoiser", "voicefixer", "none"], help="Enhancement path to use before transcription")
    p.add_argument("--deepfilter-binary", default="deep-filter", help="Path to the local deep-filter binary used for AI enhancement")
    p.add_argument("--save-enhanced-audio", action="store_true", help="Save the cleaned WAV into the output directory")
    p.add_argument("--trim", action="store_true", help="Trim non-speech sections from the cleaned WAV before transcription")
    p.add_argument("--trim-mode", default="vad", choices=["vad", "ffmpeg"], help="Trim backend to use before transcription")
    p.add_argument("--trim-aggressiveness", type=int, default=2, choices=[0, 1, 2, 3], help="Trimming aggressiveness from 0 to 3")
    p.add_argument("--chunk-long-audio", action="store_true", default=True, help="Split long recordings into bounded WAV chunks before processing")
    p.add_argument("--chunk-seconds", type=int, default=180, help="Chunk size in seconds for long recordings")
    p.add_argument("--chunk-threshold-seconds", type=int, default=300, help="Only chunk recordings longer than this duration")

    p.add_argument("--diarize", action="store_true", help="Enable speaker diarization")
    p.add_argument("--num-speakers", type=int, default=0, help="Optional fixed speaker count for diarization")
    p.add_argument("--diarization-backend", default="local", choices=["local", "pyannote"], help="Use fully local diarization or pyannote when HF_TOKEN is available")
    p.add_argument("--redetect-per-segment", action="store_true", help="Re-run language detection/transcription per segment for mixed-language audio")
    p.add_argument("--language-routing", action="store_true", help="Route each segment through forced language-specific decoding and keep the best result")
    p.add_argument("--route-languages", default="en,ms,zh", help="Comma-separated language codes to try during language routing")
    p.add_argument("--specialist-routing", action="store_true", help="Run local specialist models for routed languages when available")
    p.add_argument("--cantonese-specialist", action="store_true", help="Re-transcribe Chinese/Cantonese-like segments with a Cantonese-specialized model")
    p.add_argument("--cantonese-specialist-model", default="cantonese", help="Local model alias or path for Cantonese specialist pass")
    p.add_argument("--mandarin-specialist-model", default="specialist:mandarin", help="Transformers model id or local path for the Mandarin specialist")
    p.add_argument("--malay-specialist-model", default="specialist:malay", help="Transformers model id or local path for the Malay specialist")
    p.add_argument("--hokkien-specialist-model", default="specialist:hokkien", help="Transformers model id or local path for the Hokkien specialist")

    p.add_argument("--summarize", action="store_true", help="Enable local LLM summary + key points")
    p.add_argument("--summary-model", default="Qwen/Qwen2.5-1.5B-Instruct", help="Transformers model id or local path for summary")
    p.add_argument("--summary-max-new-tokens", type=int, default=256)

    args = p.parse_args()
    runtime_dirs = ensure_runtime_dirs()
    project_root = Path(__file__).resolve().parents[1]
    resolved_models = resolve_offline_model_paths(project_root, args)

    inp = Path(args.input).resolve()
    if not inp.exists():
        raise SystemExit(f"Input not found: {inp}")

    out_dir = Path(args.output_dir).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    model = WhisperModel(resolved_models["whisper_model"], device=args.device, compute_type=args.compute_type)
    route_languages = parse_route_languages(args.route_languages)
    input_duration = probe_duration(inp) or 0.0
    chunk_paths: List[Path]
    if args.chunk_long_audio and input_duration >= float(args.chunk_threshold_seconds):
        chunk_paths = split_audio_to_wav_chunks(inp, runtime_dirs["tmp"], args.chunk_seconds)
    else:
        chunk_paths = [decode_audio_to_wav(inp, runtime_dirs["tmp"])]

    seg_rows: List[Dict[str, Any]] = []
    chunk_meta_rows: List[Dict[str, Any]] = []
    enhanced_chunk_paths: List[Path] = []
    prepared_inputs: List[str] = []
    enhanced_inputs: List[str] = []
    all_redetect_notes: List[str] = []
    all_routing_notes: List[str] = []
    all_specialist_notes: List[str] = []
    all_cantonese_notes: List[str] = []
    offset_seconds = 0.0

    for chunk_index, chunk_path in enumerate(chunk_paths):
        enhanced_inp, prepared_inp, _decoded_inp, chunk_seg_rows, chunk_meta = process_chunk_segments(
            chunk_path,
            model,
            args,
            runtime_dirs,
            resolved_models,
            route_languages,
        )
        duration = probe_duration(prepared_inp) or probe_duration(chunk_path) or 0.0
        for row in chunk_seg_rows:
            updated = dict(row)
            updated["start"] = float(updated["start"]) + offset_seconds
            updated["end"] = float(updated["end"]) + offset_seconds
            updated["chunk_index"] = chunk_index
            seg_rows.append(updated)
        chunk_meta["chunk_index"] = chunk_index
        chunk_meta["offset_seconds"] = round(offset_seconds, 3)
        chunk_meta["segment_count"] = len(chunk_seg_rows)
        chunk_meta_rows.append(chunk_meta)
        all_redetect_notes.extend(chunk_meta.get("per_segment_redetect_notes", []))
        all_routing_notes.extend(chunk_meta.get("language_routing_notes", []))
        all_specialist_notes.extend(chunk_meta.get("specialist_routing_notes", []))
        all_cantonese_notes.extend(chunk_meta.get("cantonese_specialist_notes", []))
        prepared_inputs.append(str(prepared_inp))
        enhanced_inputs.append(str(enhanced_inp))
        if args.save_enhanced_audio:
            enhanced_chunk_paths.append(prepared_inp)
        offset_seconds += duration

    text = " ".join(s["text"] for s in seg_rows)
    stem = inp.stem

    transcript_path = out_dir / f"{stem}.fw.transcript.txt"
    combined_text_path = out_dir / f"{stem}.fw.combined.txt"
    meta_path = out_dir / f"{stem}.fw.meta.json"
    enhanced_audio_path = out_dir / f"{stem}.fw.enhanced.wav"

    transcript_lines = [
        f"[{format_ts(s['start'])} - {format_ts(s['end'])}]\n{s['text']}\n"
        for s in seg_rows
    ]
    transcript_path.write_text("\n".join(transcript_lines), encoding="utf-8")
    combined_text_path.write_text(text, encoding="utf-8")

    meta: Dict[str, Any] = {
        "language": chunk_meta_rows[0]["language"] if chunk_meta_rows else None,
        "language_probability": chunk_meta_rows[0]["language_probability"] if chunk_meta_rows else None,
        "duration": input_duration,
        "model": args.model,
        "resolved_model": resolved_models["whisper_model"],
        "task": "transcribe",
        "input": str(inp),
        "combined_transcript_output": str(combined_text_path),
        "decoded_input": str(chunk_paths[0]) if len(chunk_paths) == 1 else None,
        "enhanced_input": enhanced_inputs[0] if len(enhanced_inputs) == 1 else None,
        "prepared_input": prepared_inputs[0] if len(prepared_inputs) == 1 else None,
        "audio_preprocessed": not args.no_preprocess,
        "enhance_mode": args.enhance_mode,
        "trim_enabled": args.trim,
        "trim_mode": args.trim_mode,
        "chunking_enabled": args.chunk_long_audio,
        "chunk_seconds": args.chunk_seconds,
        "chunk_threshold_seconds": args.chunk_threshold_seconds,
        "chunk_count": len(chunk_paths),
        "chunks": chunk_meta_rows,
        "runtime_dirs": {k: str(v) for k, v in runtime_dirs.items()},
    }
    if len(chunk_meta_rows) == 1 and chunk_meta_rows[0].get("trim") is not None:
        meta["trim"] = chunk_meta_rows[0]["trim"]
    if args.save_enhanced_audio and enhanced_chunk_paths:
        if len(enhanced_chunk_paths) == 1:
            shutil.copyfile(enhanced_chunk_paths[0], enhanced_audio_path)
        else:
            concat_list = runtime_dirs["tmp"] / f"{stem}.enhanced.concat.txt"
            concat_lines: List[str] = []
            for path in enhanced_chunk_paths:
                escaped = path.as_posix().replace("'", "'\\''")
                concat_lines.append(f"file '{escaped}'")
            concat_list.write_text(
                "\n".join(concat_lines),
                encoding="utf-8",
            )
            run_command([
                "ffmpeg",
                "-y",
                "-loglevel",
                "error",
                "-f",
                "concat",
                "-safe",
                "0",
                "-i",
                str(concat_list),
                "-c",
                "copy",
                str(enhanced_audio_path),
            ])
        meta["enhanced_audio_output"] = str(enhanced_audio_path)

    diarization_audio_path: Optional[Path] = None
    if args.diarize and seg_rows:
        if args.save_enhanced_audio and enhanced_audio_path.exists():
            diarization_audio_path = enhanced_audio_path
        elif len(enhanced_chunk_paths) == 1:
            diarization_audio_path = enhanced_chunk_paths[0]
        elif enhanced_chunk_paths:
            diarization_audio_path = runtime_dirs["tmp"] / f"{stem}.diarization.wav"
            concat_list = runtime_dirs["tmp"] / f"{stem}.diarization.concat.txt"
            concat_lines = []
            for path in enhanced_chunk_paths:
                escaped = path.as_posix().replace("'", "'\\''")
                concat_lines.append(f"file '{escaped}'")
            concat_list.write_text("\n".join(concat_lines), encoding="utf-8")
            run_command([
                "ffmpeg",
                "-y",
                "-loglevel",
                "error",
                "-f",
                "concat",
                "-safe",
                "0",
                "-i",
                str(concat_list),
                "-c",
                "copy",
                str(diarization_audio_path),
            ])
        elif len(chunk_paths) == 1:
            diarization_audio_path = chunk_paths[0]
    if resolved_models.get("deepfilter_binary"):
        meta["resolved_deepfilter_binary"] = resolved_models["deepfilter_binary"]
    if args.redetect_per_segment:
        meta["per_segment_redetect"] = True
        if all_redetect_notes:
            meta["per_segment_redetect_notes"] = all_redetect_notes
    if args.language_routing:
        meta["language_routing"] = True
        meta["route_languages"] = route_languages
        if all_routing_notes:
            meta["language_routing_notes"] = all_routing_notes
    if args.specialist_routing:
        meta["specialist_routing"] = True
        meta["resolved_mandarin_specialist"] = resolved_models["mandarin_model"]
        meta["resolved_malay_specialist"] = resolved_models["malay_model"]
        meta["resolved_hokkien_specialist"] = resolved_models["hokkien_model"]
        if all_specialist_notes:
            meta["specialist_routing_notes"] = all_specialist_notes
    if args.cantonese_specialist:
        meta["cantonese_specialist"] = args.cantonese_specialist_model
        meta["resolved_cantonese_specialist"] = resolved_models["cantonese_model"]
        if all_cantonese_notes:
            meta["cantonese_specialist_notes"] = all_cantonese_notes

    if args.diarize:
        diarized: List[Dict[str, Any]] = []
        turns = []
        err: Optional[str] = None

        if args.diarization_backend == "pyannote" and os.environ.get("HF_TOKEN"):
            turns, err = run_pyannote_diarization(
                diarization_audio_path or inp,
                args.num_speakers if args.num_speakers > 0 else None,
                os.environ.get("HF_TOKEN"),
            )
        else:
            if args.diarization_backend == "pyannote":
                err = "HF token missing. Set HF_TOKEN environment variable for pyannote model access"
            else:
                err = "local-only diarization selected"

        if not err:
            diarized = assign_speakers_to_segments(seg_rows, turns)
            meta["diarization"] = {
                "backend": "pyannote",
                "speakers_detected": sorted({x[2] for x in turns}),
                "turn_count": len(turns),
            }
        else:
            meta["diarization_error"] = err
            diarized, ac_err = acoustic_cluster_diarization(diarization_audio_path or inp, seg_rows)
            if ac_err:
                meta["diarization_acoustic_error"] = ac_err
                diarized, fb_err = energy_diarization(diarization_audio_path or inp, seg_rows)
                if fb_err:
                    meta["diarization_fallback_error"] = fb_err
                else:
                    meta["diarization_fallback"] = "energy-based"
            else:
                meta["diarization_fallback"] = "acoustic-cluster"
        if diarized:
            diarized_path = out_dir / f"{stem}.fw.diarized.srt"
            diarized_blocks = []
            for idx, s in enumerate(diarized, start=1):
                diarized_blocks.append(
                    f"{idx}\n"
                    f"{format_srt_ts(float(s['start']))} --> {format_srt_ts(float(s['end']))}\n"
                    f"{s['speaker']}: {s['text']}"
                )
            diarized_path.write_text("\n\n".join(diarized_blocks), encoding="utf-8")
            meta["diarization_output"] = str(diarized_path)

    if args.summarize:
        summary_obj, raw_completion, err = run_summary_llm(
            text,
            resolved_models["summary_model"],
            args.summary_max_new_tokens,
        )
        meta["resolved_summary_model"] = resolved_models["summary_model"]
        if err:
            meta["summary_error"] = err
            if raw_completion:
                (out_dir / f"{stem}.fw.summary.raw.txt").write_text(raw_completion, encoding="utf-8")
            summary_obj = fallback_summary(text)
            summary_path = out_dir / f"{stem}.fw.summary.json"
            write_json(summary_path, summary_obj)
            meta["summary_fallback"] = "heuristic"
            meta["summary_output"] = str(summary_path)
        else:
            summary_path = out_dir / f"{stem}.fw.summary.json"
            write_json(summary_path, summary_obj)
            meta["summary_output"] = str(summary_path)

    write_json(meta_path, meta)
    cleanup_temporary_chunk_wavs(chunk_paths)

    print(json.dumps(meta, ensure_ascii=False))
    print(f"Transcript saved to: {transcript_path}")
    print(f"Combined transcript saved to: {combined_text_path}")
    print(f"Meta saved to: {meta_path}")
    print("Preview:")
    print(text[:500])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
