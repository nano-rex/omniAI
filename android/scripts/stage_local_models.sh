#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_MODEL="${1:-/home/user/github/transcriber-desktop/models/whisper-small.tflite}"
TARGET_DIR="$ROOT/app/src/main/assets/models"
TARGET_MODEL="$TARGET_DIR/whisper-small.tflite"

mkdir -p "$TARGET_DIR"

if [[ ! -f "$SOURCE_MODEL" ]]; then
  echo "missing source model: $SOURCE_MODEL" >&2
  exit 1
fi

cp -f "$SOURCE_MODEL" "$TARGET_MODEL"

cat <<EOF
Staged local model:
  source: $SOURCE_MODEL
  target: $TARGET_MODEL
EOF
