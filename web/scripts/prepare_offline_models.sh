#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODELS_DIR="$ROOT/models"
SPECIALIST_DIR="$ROOT/models-specialist"
PYTHON_BIN="${PYTHON_BIN:-python3}"
INCLUDE_QWEN_1_5B="${INCLUDE_QWEN_1_5B:-0}"
INCLUDE_QWEN_3B="${INCLUDE_QWEN_3B:-0}"
INCLUDE_DEEPFILTER="${INCLUDE_DEEPFILTER:-1}"
INCLUDE_SPECIALISTS="${INCLUDE_SPECIALISTS:-1}"

mkdir -p "$MODELS_DIR"

GITHUB_RELEASE_BASE="https://github.com/nano-rex/transcriber-desktop/releases/download"
MODEL_RELEASE_TAG="desktop-default-models-2026-03-27"

download_file_if_missing() {
  local url="$1"
  local out="$2"
  if [[ -f "$out" ]]; then
    return 0
  fi
  mkdir -p "$(dirname "$out")"
  curl -L --fail --retry 3 --retry-delay 2 -o "$out" "$url"
}

extract_tarball_if_needed() {
  local tar_path="$1"
  local target_dir="$2"
  if [[ -d "$target_dir" ]] && find "$target_dir" -mindepth 1 -maxdepth 1 | read -r _; then
    return 0
  fi
  mkdir -p "$(dirname "$target_dir")"
  tar -xzf "$tar_path" -C "$(dirname "$target_dir")"
}

download_qwen_1_5b_from_github() {
  local tar_path="$MODELS_DIR/.downloads/Qwen2.5-1.5B-Instruct.tar.gz"
  download_file_if_missing \
    "$GITHUB_RELEASE_BASE/desktop-model-qwen2.5-1.5b-instruct/Qwen2.5-1.5B-Instruct.tar.gz" \
    "$tar_path"
  extract_tarball_if_needed "$tar_path" "$MODELS_DIR/Qwen2.5-1.5B-Instruct"
}

download_default_model_bundle() {
  local tar_name="$1"
  local target_dir="$2"
  local tar_path="$MODELS_DIR/.downloads/$tar_name"
  download_file_if_missing \
    "$GITHUB_RELEASE_BASE/$MODEL_RELEASE_TAG/$tar_name" \
    "$tar_path"
  extract_tarball_if_needed "$tar_path" "$target_dir"
}

download_specialist_bundle() {
  local key="$1"
  local tar_name="$2"
  local target_dir="$SPECIALIST_DIR/$key"
  local tar_path="$MODELS_DIR/.downloads/$tar_name"
  download_file_if_missing \
    "$GITHUB_RELEASE_BASE/$MODEL_RELEASE_TAG/$tar_name" \
    "$tar_path"
  if [[ -d "$target_dir" ]] && find "$target_dir" -mindepth 1 -maxdepth 1 | read -r _; then
    return 0
  fi
  mkdir -p "$SPECIALIST_DIR"
  tar -xzf "$tar_path" -C "$ROOT"
}

download_deepfilter_binary() {
  local arch
  arch="$(uname -m)"
  local out="$MODELS_DIR/deepfilter/deep-filter"
  local url=""
  case "$arch" in
    x86_64)
      url="https://github.com/Rikorose/DeepFilterNet/releases/download/v0.5.6/deep-filter-0.5.6-x86_64-unknown-linux-musl"
      ;;
    aarch64)
      url="https://github.com/Rikorose/DeepFilterNet/releases/download/v0.5.6/deep-filter-0.5.6-aarch64-unknown-linux-gnu"
      ;;
    armv7l|armv7)
      url="https://github.com/Rikorose/DeepFilterNet/releases/download/v0.5.6/deep-filter-0.5.6-armv7-unknown-linux-gnueabihf"
      ;;
    *)
      echo "Unsupported architecture for DeepFilterNet binary: $arch" >&2
      return 1
      ;;
  esac
  download_file_if_missing "$url" "$out"
  chmod +x "$out"
}

download_default_model_bundle "faster-whisper-tiny.tar.gz" "$MODELS_DIR/faster-whisper-tiny"
download_default_model_bundle "faster-whisper-small.tar.gz" "$MODELS_DIR/faster-whisper-small"
if [[ "$INCLUDE_SPECIALISTS" == "1" ]]; then
  download_specialist_bundle "mandarin" "whisper-small-mandarin.tar.gz"
  download_specialist_bundle "malay" "whisper-small-malay.tar.gz"
  download_specialist_bundle "cantonese" "whisper-small-cantonese.tar.gz"
  download_specialist_bundle "hokkien" "whisper-small-hokkien.tar.gz"
fi

if [[ "$INCLUDE_DEEPFILTER" == "1" ]]; then
  download_deepfilter_binary || true
fi

if [[ "$INCLUDE_QWEN_1_5B" == "1" ]]; then
  download_qwen_1_5b_from_github
fi

if [[ "$INCLUDE_QWEN_3B" == "1" ]]; then
  echo "Qwen 3B is not hosted in releases yet. Stage it manually if needed." >&2
fi

cat <<EOF
Offline models ready under:
  $MODELS_DIR

Notes:
  - Default setup models are downloaded from repository release assets.
  - Specialist language models are downloaded from repository release assets.
  - Set INCLUDE_DEEPFILTER=1 to stage the local DeepFilterNet binary for AI enhancement.
  - Set INCLUDE_SPECIALISTS=0 to skip mandarin/malay/cantonese/hokkien specialist downloads.
  - Set INCLUDE_QWEN_1_5B=1 for the larger summary model.
EOF
