#!/usr/bin/env bash
# دانلود کتابخانه‌ی Sherpa-ONNX (فایل AAR) که برای ساخت برنامه لازم است.
# مدل‌های صدا داخل APK نیستند؛ خود برنامه هنگام نیاز دانلودشان می‌کند
# (فهرست صداها در VoiceDownloader.kt، شیء VoiceCatalog).
set -euo pipefail
cd "$(dirname "$0")/.."

SHERPA_VERSION="1.13.8"
BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download"

mkdir -p app/libs

AAR="app/libs/sherpa-onnx-${SHERPA_VERSION}.aar"
if [ ! -s "$AAR" ]; then
  echo ">> downloading $AAR"
  curl -L --fail --retry 3 -o "$AAR" "$BASE/v${SHERPA_VERSION}/sherpa-onnx-${SHERPA_VERSION}.aar"
fi

echo ">> done"
