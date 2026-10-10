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
SPEAKER_ASSET=${VB_SPEAKER_ASSET:-speaker.onnx}
SPEAKER_URL=${VB_SPEAKER_URL:-$REL/speaker-recongition-models/nemo_en_titanet_small.onnx}
[ -f "$ASSETS/$SPEAKER_ASSET" ] || curl -fL -o "$ASSETS/$SPEAKER_ASSET" "$SPEAKER_URL"

# 4. Face + lip landmarks (MediaPipe Face Landmarker)
[ -f "$ASSETS/face_landmarker.task" ] || curl -fL -o "$ASSETS/face_landmarker.task" "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"

# Noisy sample for the instrumented denoiser test
mkdir -p app/src/androidTest/assets
[ -f app/src/androidTest/assets/noisy_speech.wav ] || curl -fL -o app/src/androidTest/assets/noisy_speech.wav "$REL/speech-enhancement-models/speech_with_noise.wav" || true

ls -la "$ASSETS" "$ASSETS/asr"

# Offline model donor pinned to an exact commit; missing or mismatched bytes fail the build.
[ -f "$ASSETS/speakerbeam-8k.onnx" ] || curl -fL -o "$ASSETS/speakerbeam-8k.onnx" "https://raw.githubusercontent.com/akashrajeev/voicebeam-finale/cc9b86e1a0f99b8ff8c7de52d120752039665e85/app/src/main/assets/models/speakerbeam-8k.onnx"
printf '%s\n' 'e9bdb6c0a8e51b8341435f49abe59ead136cd2b9d6b6c178990fdc50bf54c9eb  app/src/main/assets/models/speakerbeam-8k.onnx' | sha256sum -c -

# Isolate Java ORT1.20 core+JNI, retain sherpa1.28.2 core unchanged.
ORT_AAR=app/libs/onnxruntime-java-jni.aar
if [ ! -f "$ORT_AAR" ]; then
  curl -fL -o /tmp/ort-java-source.aar https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.20.0/onnxruntime-android-1.20.0.aar
  echo '07a8f71ef890afed8c6087a56220e6d558a492804276ee2dd7cb7f6262242027  /tmp/ort-java-source.aar' | sha256sum -c -
  python3 -m pip install patchelf==0.17.2.4
  python3 scripts/isolate_ort.py
fi
