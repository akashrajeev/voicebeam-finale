#!/usr/bin/env bash
# Downloads the open-source models and the sherpa-onnx Android library.
# Everything runs on the phone; nothing is called over the network at runtime.
set -euo pipefail
cd "$(dirname "$0")/.."
SHERPA_VERSION=1.13.8
ASSETS=app/src/main/assets/models
mkdir -p "$ASSETS" app/libs
REL=https://github.com/k2-fsa/sherpa-onnx/releases/download

if [ ! -f app/libs/sherpa-onnx.aar ]; then
  curl -fL -o app/libs/sherpa-onnx.aar "$REL/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar"
fi

# 1. Lab ASR winner on NPTEL Pure-Set: Moonshine Tiny int8 (not streaming).
ASR=sherpa-onnx-moonshine-tiny-en-int8
if [ ! -f "$ASSETS/asr/preprocess.onnx" ]; then
  tmp=$(mktemp -d)
  curl -fL -o "$tmp/asr.tar.bz2" "$REL/asr-models/$ASR.tar.bz2"
  tar -xjf "$tmp/asr.tar.bz2" -C "$tmp"
  mkdir -p "$ASSETS/asr"
  cp "$tmp/$ASR/"*.onnx "$tmp/$ASR/tokens.txt" "$ASSETS/asr/"
  rm -rf "$tmp"
fi
[ -f "$ASSETS/silero_vad_v5.onnx" ] || curl -fL -o "$ASSETS/silero_vad_v5.onnx" "$REL/asr-models/silero_vad_v5.onnx"

# 2. Speech enhancement (GTCRN, ~0.5 MB)
[ -f "$ASSETS/gtcrn.onnx" ] || curl -fL -o "$ASSETS/gtcrn.onnx" "$REL/speech-enhancement-models/gtcrn_simple.onnx"

# 3. Speaker embedding (TitaNet Small English, lab trial)
[ -f "$ASSETS/speaker.onnx" ] || curl -fL -o "$ASSETS/speaker.onnx" "$REL/speaker-recongition-models/nemo_en_titanet_small.onnx"

# 4. Face + lip landmarks (MediaPipe Face Landmarker)
[ -f "$ASSETS/face_landmarker.task" ] || curl -fL -o "$ASSETS/face_landmarker.task" "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"

# Noisy sample for the instrumented denoiser test
mkdir -p app/src/androidTest/assets
[ -f app/src/androidTest/assets/noisy_speech.wav ] || curl -fL -o app/src/androidTest/assets/noisy_speech.wav "$REL/speech-enhancement-models/speech_with_noise.wav" || true

ls -la "$ASSETS" "$ASSETS/asr"

# Experimental DFN3 mobile binary/model, pinned Apache2 Android wrapper release source.
# No runtime downloads. Verify bytes before packaging.
DFN_REV=9fff40b97bb8754afe6530ffdc234cb15b8d32da
DFN_BASE=https://raw.githubusercontent.com/KaleyraVideo/AndroidDeepFilterNet/$DFN_REV/noise-filter/src
mkdir -p app/src/main/jniLibs/arm64-v8a
[ -f "$ASSETS/dfn3-mobile.bin" ] || curl -fL -o "$ASSETS/dfn3-mobile.bin" "$DFN_BASE/bundledModel/res/raw/deep_filter_mobile_model"
[ -f app/src/main/jniLibs/arm64-v8a/libdf.so ] || curl -fL -o app/src/main/jniLibs/arm64-v8a/libdf.so "$DFN_BASE/main/jniLibs/arm64-v8a/libdf.so"
printf '%s\n' '5600b6857117ecc7cf460b8ec4841963bfa6d718921d424d42dea5d3d37a8c32  app/src/main/assets/models/dfn3-mobile.bin' | sha256sum -c -
printf '%s\n' '0ec8bc3971bbb8a804b4b53910e8cad3626b9db6b4c44167eba9b669f58f0584  app/src/main/jniLibs/arm64-v8a/libdf.so' | sha256sum -c -
# Offline SpeakerBeam is committed as a pinned single-file ONNX, never silently skipped.
printf '%s\n' 'e9bdb6c0a8e51b8341435f49abe59ead136cd2b9d6b6c178990fdc50bf54c9eb  app/src/main/assets/models/speakerbeam-8k.onnx' | sha256sum -c -
