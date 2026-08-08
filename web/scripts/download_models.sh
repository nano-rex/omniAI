#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MODELS_DIR="$PROJECT_DIR/models"
mkdir -p "$MODELS_DIR"

SPECIALIST_DIR="$PROJECT_DIR/models-specialist"
mkdir -p "$SPECIALIST_DIR"
if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <tiny|small|qwen-1.5b|deepfilter|specialist-mandarin|specialist-malay|specialist-cantonese|specialist-hokkien|specialists|core|all>"
  exit 1
fi

TIER="$1"
DOWNLOADS_DIR="$MODELS_DIR/.downloads"
mkdir -p "$DOWNLOADS_DIR"
MODEL_RELEASE_TAG="desktop-default-models-2026-03-27"

download_if_missing() {
  local url="$1"
  local out="$2"
  if [[ -f "$out" ]]; then
    return 0
  fi
  curl -fL --retry 3 --retry-delay 2 "$url" -o "$out"
}

extract_tarball_if_needed() {
  local tar_path="$1"
  local target_dir="$2"
  local base_dir="${3:-$MODELS_DIR}"
  if [[ -d "$target_dir" ]] && find "$target_dir" -mindepth 1 -maxdepth 1 | read -r _; then
    return 0
  fi
  tar -xzf "$tar_path" -C "$base_dir"
}

download_default_model_bundle() {
  local tar_name="$1"
  local target_dir="$2"
  local tar_path="$DOWNLOADS_DIR/$tar_name"
  download_if_missing "https://github.com/nano-rex/transcriber-desktop/releases/download/$MODEL_RELEASE_TAG/$tar_name" "$tar_path"
  extract_tarball_if_needed "$tar_path" "$target_dir"
}

case "$TIER" in
  core)
    "$0" tiny
    "$0" small
    "$0" deepfilter
    exit 0
    ;;
  specialists)
    "$0" specialist-mandarin
    "$0" specialist-malay
    "$0" specialist-cantonese
    "$0" specialist-hokkien
    exit 0
    ;;
  all)
    "$0" core
    "$0" specialists
    exit 0
    ;;
  tiny)
    TARGET_DIR="$MODELS_DIR/faster-whisper-tiny"
    download_default_model_bundle "faster-whisper-tiny.tar.gz" "$TARGET_DIR"
    echo "Done: $TARGET_DIR"
    du -sh "$TARGET_DIR"
    exit 0
    ;;
  small)
    TARGET_DIR="$MODELS_DIR/faster-whisper-small"
    download_default_model_bundle "faster-whisper-small.tar.gz" "$TARGET_DIR"
    echo "Done: $TARGET_DIR"
    du -sh "$TARGET_DIR"
    ;;
  qwen-1.5b)
    TAR_PATH="$DOWNLOADS_DIR/Qwen2.5-1.5B-Instruct.tar.gz"
    TARGET_DIR="$MODELS_DIR/Qwen2.5-1.5B-Instruct"
    download_if_missing "https://github.com/nano-rex/transcriber-desktop/releases/download/desktop-model-qwen2.5-1.5b-instruct/Qwen2.5-1.5B-Instruct.tar.gz" "$TAR_PATH"
    extract_tarball_if_needed "$TAR_PATH" "$TARGET_DIR"
    echo "Done: $TARGET_DIR"
    du -sh "$TARGET_DIR"
    exit 0
    ;;
  specialist-mandarin)
    TAR_PATH="$DOWNLOADS_DIR/whisper-small-mandarin.tar.gz"
    TARGET_DIR="$SPECIALIST_DIR/mandarin"
    download_if_missing "https://github.com/nano-rex/transcriber-desktop/releases/download/$MODEL_RELEASE_TAG/whisper-small-mandarin.tar.gz" "$TAR_PATH"
    extract_tarball_if_needed "$TAR_PATH" "$TARGET_DIR" "$PROJECT_DIR"
    echo "Done: $TARGET_DIR"
    du -sh "$TARGET_DIR"
    exit 0
    ;;
  specialist-malay)
    TAR_PATH="$DOWNLOADS_DIR/whisper-small-malay.tar.gz"
    TARGET_DIR="$SPECIALIST_DIR/malay"
    download_if_missing "https://github.com/nano-rex/transcriber-desktop/releases/download/$MODEL_RELEASE_TAG/whisper-small-malay.tar.gz" "$TAR_PATH"
    extract_tarball_if_needed "$TAR_PATH" "$TARGET_DIR" "$PROJECT_DIR"
    echo "Done: $TARGET_DIR"
    du -sh "$TARGET_DIR"
    exit 0
    ;;
  specialist-cantonese)
    TAR_PATH="$DOWNLOADS_DIR/whisper-small-cantonese.tar.gz"
    TARGET_DIR="$SPECIALIST_DIR/cantonese"
    download_if_missing "https://github.com/nano-rex/transcriber-desktop/releases/download/$MODEL_RELEASE_TAG/whisper-small-cantonese.tar.gz" "$TAR_PATH"
    extract_tarball_if_needed "$TAR_PATH" "$TARGET_DIR" "$PROJECT_DIR"
    echo "Done: $TARGET_DIR"
    du -sh "$TARGET_DIR"
    exit 0
    ;;
  specialist-hokkien)
    TAR_PATH="$DOWNLOADS_DIR/whisper-small-hokkien.tar.gz"
    TARGET_DIR="$SPECIALIST_DIR/hokkien"
    download_if_missing "https://github.com/nano-rex/transcriber-desktop/releases/download/$MODEL_RELEASE_TAG/whisper-small-hokkien.tar.gz" "$TAR_PATH"
    extract_tarball_if_needed "$TAR_PATH" "$TARGET_DIR" "$PROJECT_DIR"
    echo "Done: $TARGET_DIR"
    du -sh "$TARGET_DIR"
    exit 0
    ;;
  deepfilter)
    ARCH="$(uname -m)"
    case "$ARCH" in
      x86_64)
        URL="https://github.com/Rikorose/DeepFilterNet/releases/download/v0.5.6/deep-filter-0.5.6-x86_64-unknown-linux-musl"
        ;;
      aarch64)
        URL="https://github.com/Rikorose/DeepFilterNet/releases/download/v0.5.6/deep-filter-0.5.6-aarch64-unknown-linux-gnu"
        ;;
      armv7l|armv7)
        URL="https://github.com/Rikorose/DeepFilterNet/releases/download/v0.5.6/deep-filter-0.5.6-armv7-unknown-linux-gnueabihf"
        ;;
      *)
        echo "Unsupported architecture for deepfilter: $ARCH"
        exit 2
        ;;
    esac
    OUT="$MODELS_DIR/deepfilter/deep-filter"
    mkdir -p "$(dirname "$OUT")"
    echo "Downloading deepfilter binary to $OUT"
    download_if_missing "$URL" "$OUT"
    chmod +x "$OUT"
    echo "Done: $OUT"
    ls -lh "$OUT"
    exit 0
    ;;
  *)
    echo "Unsupported tier: $TIER"
    echo "Use one of: tiny, small, qwen-1.5b, deepfilter, specialist-mandarin, specialist-malay, specialist-cantonese, specialist-hokkien, specialists, core, all"
    exit 2
    ;;
esac

exit 0
