#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PYTHON_BIN="${PYTHON_BIN:-python3}"
PIP_BIN="${PIP_BIN:-$PYTHON_BIN -m pip}"

require_command() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Missing required command: $cmd" >&2
    exit 1
  fi
}

usage() {
  cat <<'EOF'
Usage:
  ./setup.sh

What it does:
  1. installs Python requirements
  2. downloads/stages local models into models/

Environment overrides:
  PYTHON_BIN=python3.11
  PIP_BIN="python3 -m pip"
  INCLUDE_DEEPFILTER=0
  INCLUDE_SPECIALISTS=1
  INCLUDE_QWEN_1_5B=1
  INCLUDE_QWEN_3B=0
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

cd "$ROOT"

require_command "$PYTHON_BIN"
require_command ffmpeg
require_command curl
require_command tar

echo "[1/2] Installing Python requirements"
eval "$PIP_BIN install -r requirements.txt"

echo "[2/2] Preparing offline models"
INCLUDE_SPECIALISTS="${INCLUDE_SPECIALISTS:-1}" \
INCLUDE_DEEPFILTER="${INCLUDE_DEEPFILTER:-1}" \
INCLUDE_QWEN_1_5B="${INCLUDE_QWEN_1_5B:-1}" \
INCLUDE_QWEN_3B="${INCLUDE_QWEN_3B:-0}" \
"$ROOT/scripts/prepare_offline_models.sh"

cat <<'EOF'

Setup complete.

Run:
  ./run.sh input.wav

Default setup includes:
  - faster-whisper tiny
  - faster-whisper small
  - Qwen 1.5B
  - DeepFilterNet binary
  - specialist models for mandarin, malay, cantonese, hokkien
EOF
