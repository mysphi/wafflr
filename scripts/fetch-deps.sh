#!/usr/bin/env bash
# Downloads the large binary dependencies that are not stored in git:
#  - the sherpa-onnx Android library (.aar)
#  - the bundled default voice (Piper, British male "Alan")
set -euo pipefail

SHERPA_VERSION="${SHERPA_VERSION:-1.12.40}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS="$ROOT/app/libs"
VOICES="$ROOT/app/src/main/assets/voices"
BUNDLED_VOICE="vits-piper-en_GB-alan-medium"

mkdir -p "$LIBS" "$VOICES"

AAR="$LIBS/sherpa-onnx-$SHERPA_VERSION.aar"
if [ ! -s "$AAR" ]; then
  echo "Downloading sherpa-onnx $SHERPA_VERSION AAR"
  if ! curl -fSL --retry 3 -o "$AAR" \
      "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar"; then
    rm -f "$AAR"
    echo "Direct download failed; looking for the AAR in the release assets"
    name=$(gh release view "v$SHERPA_VERSION" -R k2-fsa/sherpa-onnx --json assets -q '.assets[].name' | grep -E '^sherpa-onnx-.*\.aar$' | head -1)
    gh release download "v$SHERPA_VERSION" -R k2-fsa/sherpa-onnx -p "$name" -O "$AAR"
  fi
fi
ls -lh "$AAR"

if [ ! -d "$VOICES/$BUNDLED_VOICE" ]; then
  echo "Downloading bundled voice $BUNDLED_VOICE"
  tmp="$(mktemp -d)"
  curl -fSL --retry 3 -o "$tmp/v.tar.bz2" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$BUNDLED_VOICE.tar.bz2"
  tar -xjf "$tmp/v.tar.bz2" -C "$VOICES"
  rm -rf "$tmp"
fi
# The app copies assets/voices/<pack>/ to internal storage on first run; it needs a file list.
( cd "$VOICES/$BUNDLED_VOICE" && find . -type f | sed 's|^\./||' | sort > files.txt )
du -sh "$VOICES/$BUNDLED_VOICE"
