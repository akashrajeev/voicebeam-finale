#!/usr/bin/env bash
# Runs the instrumented tests on the CI emulator and keeps logs for review.
mkdir -p ci
adb devices > ci/adb.txt 2>&1
adb logcat -c || true
./gradlew --no-daemon connectedDebugAndroidTest > ci/device.log 2>&1
code=$?
echo $code > ci/device.exit
adb logcat -d -s VBSHT VoiceBeamTest VoiceBeamEngine VoiceBeamModels VoiceBeamAudio VoiceBeamUI VoiceBeamVision VoiceBeamWER AndroidRuntime TestRunner > ci/logcat.txt 2>&1 || true
python3 scripts/extract_shots.py ci/logcat.txt ci/shots >> ci/device.log 2>&1 || true
exit $code
