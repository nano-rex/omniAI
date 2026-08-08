# Models

This is the only model directory used by `transcriber-desktop`.

Files and subdirectories:
- `whisper-tiny.en.tflite`, `whisper-tiny.tflite`, `whisper-small.tflite`: native TFLite models
- `faster-whisper-*`: local `faster-whisper` model directories
- `Qwen2.5-0.5B-Instruct`: local summary model directory
- `whisper-small-cantonese-yue-english`: local Cantonese specialist directory

Populate this folder with:

```bash
./scripts/prepare_offline_models.sh
```

Or download a TFLite tier directly:

```bash
./scripts/download_models.sh small
```
