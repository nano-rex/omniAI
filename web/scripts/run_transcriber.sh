#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLI="$ROOT/scripts/transcribe_faster_whisper.py"

usage() {
  cat <<'EOF'
Usage:
  ./scripts/run_transcriber.sh <input-audio> [options] [-- extra-cli-flags]

Default behavior:
  - lightly enhance audio with ffmpeg
  - split long recordings into 3-minute WAV chunks
  - trim non-speech sections with VAD
  - save enhanced audio
  - local diarization enabled
  - summary runs once on the final combined transcript
  - per-segment language re-detection enabled
  - language routing enabled for en/ms/zh
  - specialist routing enabled for mandarin/malay/cantonese/hokkien when local models exist
  - Cantonese specialist enabled
  - model small
  - compute type int8
  - device cpu
  - output directory outputs/

Options:
  -m, --model <name-or-path>       Whisper model alias or path
  -o, --output-dir <dir>           Output directory
  -d, --device <cpu|cuda>          Inference device
  -c, --compute-type <type>        Compute type, e.g. int8
  -n, --num-speakers <count>       Fixed speaker count
      --no-diarize                 Disable diarization
      --no-summarize               Disable summary
      --no-redetect                Disable per-segment re-detection
      --no-language-routing        Disable forced language routing
      --no-specialist-routing      Disable specialist language-model routing
      --no-cantonese-specialist    Disable Cantonese specialist pass
      --enhance-mode <ai|denoiser|voicefixer|ffmpeg|none>  Select enhancement path
      --no-preprocess              Disable audio preprocessing
      --no-trim                    Disable non-speech trimming
      --no-save-enhanced-audio    Do not save the enhanced WAV output
  -h, --help                       Show this help

Examples:
  ./scripts/run_transcriber.sh meeting.wav
  ./scripts/run_transcriber.sh meeting.wav --model tiny --no-summarize
  ./scripts/run_transcriber.sh meeting.wav -- --summary-max-new-tokens 128
EOF
}

if [[ $# -lt 1 ]]; then
  usage
  exit 1
fi

INPUT=""
MODEL="small"
OUTPUT_DIR="outputs"
ENHANCE_MODE="ffmpeg"
DEVICE="cpu"
COMPUTE_TYPE="int8"
NUM_SPEAKERS="0"
ENABLE_DIARIZE=1
ENABLE_SUMMARIZE=1
ENABLE_REDETECT=1
ENABLE_LANGUAGE_ROUTING=1
ENABLE_SPECIALIST_ROUTING=1
ENABLE_CANTONESE=1
ENABLE_PREPROCESS=1
ENABLE_TRIM=1
SAVE_ENHANCED_AUDIO=1
EXTRA_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    -m|--model)
      MODEL="$2"
      shift 2
      ;;
    -o|--output-dir)
      OUTPUT_DIR="$2"
      shift 2
      ;;
    -d|--device)
      DEVICE="$2"
      shift 2
      ;;
    -c|--compute-type)
      COMPUTE_TYPE="$2"
      shift 2
      ;;
    -n|--num-speakers)
      NUM_SPEAKERS="$2"
      shift 2
      ;;
    --no-diarize)
      ENABLE_DIARIZE=0
      shift
      ;;
    --no-summarize)
      ENABLE_SUMMARIZE=0
      shift
      ;;
    --no-redetect)
      ENABLE_REDETECT=0
      shift
      ;;
    --no-language-routing)
      ENABLE_LANGUAGE_ROUTING=0
      shift
      ;;
    --no-specialist-routing)
      ENABLE_SPECIALIST_ROUTING=0
      shift
      ;;
    --no-cantonese-specialist)
      ENABLE_CANTONESE=0
      shift
      ;;
    --enhance-mode)
      ENHANCE_MODE="$2"
      shift 2
      ;;
    --no-preprocess)
      ENABLE_PREPROCESS=0
      shift
      ;;
    --no-trim)
      ENABLE_TRIM=0
      shift
      ;;
    --no-save-enhanced-audio)
      SAVE_ENHANCED_AUDIO=0
      shift
      ;;
    --)
      shift
      EXTRA_ARGS=("$@")
      break
      ;;
    -*)
      echo "Unknown option: $1" >&2
      usage
      exit 2
      ;;
    *)
      if [[ -z "$INPUT" ]]; then
        INPUT="$1"
      else
        EXTRA_ARGS+=("$1")
      fi
      shift
      ;;
  esac
done

if [[ -z "$INPUT" ]]; then
  echo "Input audio is required." >&2
  usage
  exit 1
fi

CMD=(
  python3
  "$CLI"
  "$INPUT"
  --model "$MODEL"
  --device "$DEVICE"
  --compute-type "$COMPUTE_TYPE"
  --output-dir "$OUTPUT_DIR"
  --enhance-mode "$ENHANCE_MODE"
)

if [[ "$ENABLE_PREPROCESS" -eq 0 ]]; then
  CMD+=(--no-preprocess)
fi
if [[ "$SAVE_ENHANCED_AUDIO" -eq 1 ]]; then
  CMD+=(--save-enhanced-audio)
fi
if [[ "$ENABLE_TRIM" -eq 1 ]]; then
  CMD+=(--trim)
fi
if [[ "$ENABLE_DIARIZE" -eq 1 ]]; then
  CMD+=(--diarize --diarization-backend local)
  if [[ "$NUM_SPEAKERS" != "0" ]]; then
    CMD+=(--num-speakers "$NUM_SPEAKERS")
  fi
fi
if [[ "$ENABLE_SUMMARIZE" -eq 1 ]]; then
  CMD+=(--summarize)
fi
if [[ "$ENABLE_REDETECT" -eq 1 ]]; then
  CMD+=(--redetect-per-segment)
fi
if [[ "$ENABLE_LANGUAGE_ROUTING" -eq 1 ]]; then
  CMD+=(--language-routing)
fi
if [[ "$ENABLE_SPECIALIST_ROUTING" -eq 1 ]]; then
  CMD+=(--specialist-routing)
fi
if [[ "$ENABLE_CANTONESE" -eq 1 ]]; then
  CMD+=(--cantonese-specialist)
fi
if [[ ${#EXTRA_ARGS[@]} -gt 0 ]]; then
  CMD+=("${EXTRA_ARGS[@]}")
fi

exec "${CMD[@]}"
