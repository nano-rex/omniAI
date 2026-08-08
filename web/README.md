# Transcriber Desktop

CLI-only local transcription for Linux. The main entry points are `setup.sh` and `run.sh`.

## Layout
- `scripts/transcribe_faster_whisper.py`: main CLI
- `scripts/prepare_offline_models.sh`: download or stage local models into `models/`
- `scripts/download_models.sh`: fetch hosted desktop bundles, `faster-whisper` snapshots, and specialist language models
- `models/`: the only model folder used by this repo
- `assets/`: sample audio and vocab files

## Install

```bash
git clone https://github.com/nano-rex/transcriber-desktop
cd transcriber-desktop
./setup.sh
```

`setup.sh` installs Python requirements and downloads/stages the default local model set in one command.
By default it includes:
- `faster-whisper-tiny`
- `faster-whisper-small`
- `Qwen2.5-1.5B-Instruct`
- `deep-filter`
- specialist models for mandarin, malay, cantonese, and hokkien

Manual equivalent:

```bash
python3 -m pip install -r requirements.txt
INCLUDE_QWEN_1_5B=1 ./scripts/prepare_offline_models.sh
```

System requirements expected by `setup.sh`:
- `python3`
- `ffmpeg`
- `curl`
- `tar`

## Run

Basic transcription:

```bash
./scripts/transcribe_faster_whisper.py input.wav --model small
```

With diarization and summary:

```bash
./scripts/transcribe_faster_whisper.py input.wav --model small --diarize --summarize
```

Short wrapper with the normal recommended settings:

```bash
./run.sh input.wav
```

## Flags
- `--model`: Whisper model alias or path. Default `small`.
  Common aliases: `tiny`, `small`
- `--enhance-mode`: `ai`, `denoiser`, `voicefixer`, `ffmpeg`, or `none`. Default `ffmpeg` in the raw CLI.
- `--deepfilter-binary`: path to the local `deep-filter` binary used when `--enhance-mode ai`.
- `denoiser` uses the `facebookresearch/denoiser` Python package and downloads its pretrained checkpoint into the local Torch cache on first use.
- `voicefixer` uses the `voicefixer` Python package and expects its official checkpoints in `~/.cache/voicefixer/`.
- `--compute-type`: inference precision. Default `int8`.
- `--device`: `cpu` or `cuda`. Default `cpu`.
- `--output-dir`: where transcript files are written. Default `outputs`.
- `--no-preprocess`: skip the default voice-cleanup pass.
- `--save-enhanced-audio`: save the enhanced WAV into the output directory.
- `--trim`: trim quiet non-speech sections from the cleaned WAV before transcription.
- `--trim-mode`: trim backend. Default `vad`. Fallback option: `ffmpeg`.
- `--trim-aggressiveness`: trim aggressiveness from `0` to `3`. Default `2`.
- `--diarize`: enable speaker separation.
- `--num-speakers`: force a fixed speaker count for diarization.
- `--diarization-backend`: `local` or `pyannote`. Default `local`.
- `--redetect-per-segment`: re-transcribe each segment for mixed-language audio.
- `--language-routing`: try forced per-language decoding on each segment and keep the best result.
- `--route-languages`: comma-separated language codes used by language routing. Default `en,ms,zh`.
- `--cantonese-specialist`: re-run Chinese-like segments through the Cantonese specialist model.
- `--cantonese-specialist-model`: override the Cantonese specialist model path or id.
- `--summarize`: generate overview and key points.
- `--summary-model`: override the local summary model path or id.
  Common choices: `Qwen/Qwen2.5-1.5B-Instruct`, `Qwen/Qwen2.5-3B-Instruct`
- `--summary-max-new-tokens`: cap summary output length. Default `256`.

## Scripts
- `scripts/transcribe_faster_whisper.py`: recommended workflow. Multilingual transcription, preprocessing, local diarization, optional summary.
- `scripts/run_transcriber.sh`: short wrapper around the main CLI with the usual defaults already enabled.
- `scripts/prepare_offline_models.sh`: fills `models/` with `faster-whisper`, summary, DeepFilterNet, and specialist language models for offline use. Optional stronger summary models are controlled by env vars.
- `scripts/download_models.sh`: downloads hosted models such as `tiny`, `small`, all specialist language bundles, `qwen-1.5b`, and the local AI enhancement binary `deepfilter`.

Examples:

```bash
./scripts/download_models.sh tiny
./scripts/download_models.sh small
./scripts/download_models.sh qwen-1.5b
./scripts/download_models.sh deepfilter
./scripts/download_models.sh specialist-mandarin
./scripts/download_models.sh specialist-malay
./scripts/download_models.sh specialist-cantonese
./scripts/download_models.sh specialist-hokkien
./scripts/download_models.sh specialists
./scripts/download_models.sh all
```

## Wrapper Defaults
`scripts/run_transcriber.sh` enables these by default:
- light ffmpeg enhancement
- VAD trimming
- enhanced WAV output
- local diarization
- summary
- per-segment language re-detection
- per-segment language routing
- Cantonese specialist pass

Common examples:

```bash
./scripts/run_transcriber.sh meeting.wav
./scripts/run_transcriber.sh meeting.wav --enhance-mode ffmpeg
./scripts/run_transcriber.sh meeting.wav --enhance-mode denoiser
./scripts/run_transcriber.sh meeting.wav --enhance-mode voicefixer
./scripts/run_transcriber.sh meeting.wav --model tiny
./scripts/run_transcriber.sh meeting.wav --no-summarize --no-cantonese-specialist
./scripts/run_transcriber.sh meeting.wav -- --route-languages en,ms,zh,id
./scripts/run_transcriber.sh meeting.wav --no-language-routing
./scripts/run_transcriber.sh meeting.wav --no-trim
./scripts/run_transcriber.sh meeting.wav -- --trim-mode ffmpeg
./scripts/run_transcriber.sh meeting.wav --no-save-enhanced-audio
./scripts/run_transcriber.sh meeting.wav -- --summary-max-new-tokens 128
```

## Outputs
- `outputs/<name>.fw.transcript.txt`
- `outputs/<name>.fw.enhanced.wav`
- `outputs/<name>.fw.diarized.srt`
- `outputs/<name>.fw.summary.json`
- `outputs/<name>.fw.meta.json`
