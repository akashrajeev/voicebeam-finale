# EXP0: real-scene replay (experiment-1 only)

New test/tool files only. No app behavior changes. No APK/device-validation claim.
Host TargetGate replay and Python metric tests run here; Android test sources need
compile + actual iQOO execution. Do not merge without Akash review and those checks.

## What already exists

- engine/Settings.kt keepRawAudio, SettingsScreen switch, Focus record sheet checkbox.
- core/WavWriter.kt writes16kHz mono PCM16 WAV; engine/VoiceBeamEngine.kt starts raw.wav
  only for AUDIO/AUDIO_VIDEO when keepRawAudio=true. clean.wav is preboost gated audio,
  NOT exact postlimiter earphone output. Keep distinction.
- record/SessionStore.kt saves files/sessions/<session-id>/raw.wav,clean.wav,clean.m4a,
  video.mp4,session.json, captions. VoiceBeamEngine normally deletes camera.mp4 after
  muxing to video.mp4. Final video preserves camera frames, uses processed audio.
- engine/DebugAudioFeed.kt reads fixed assets/feed/test_audio.wav, assumes44byte PCM16
  header, loops; not an arbitrary file selector.
- vision/DebugVideoFeed.kt reads fixed480JPEGframes f0001..f0480 at8fps,60s loop,
  optionally fixed scene lips.json on emulator fallback. No MP4/file selector.
- vision/FaceProcessor.kt asynchronous LIVE_STREAM bitmap analysis; frame dropping
  and callback timing mean it is not inherently deterministic.
- core/FaceTracker.kt, LipActivity.kt, TargetGate.kt are host-testable pure Kotlin.

## Capture script on iQOO (90 seconds, consenting participants)

Use debug build, wired earphones, stable phone/mic position, target on left, other on
right, no moving camera. Settings: Keep raw copy ON, Audio+video, captions None or
SRT (no burned captions covering mouth), denoise0.7, quietothers0.86, boost6dB, wearer
vetoOFF, Demo feedOFF. These are experiment controls, not recommended hearing settings.
Use one continuous recording. Both faces/mouths unobstructed. Do not enroll target
for this first repeatable UNENROLLED lane. Tap target once; do not retap after walkoff.
Check camera bound and recording confirms VIDEO rather than audio-only fallback.

- 0-10s: visible+audible clap at2s, target in left position, tap lock at3s; quiet setup.
- 10-25s: target alone speaks a repeated sentence, other silent.
- 25-40s: other alone speaks same sentence, target silent; clean turn switch.
- 40-55s: target speaks again, other silent; switch back.
- 55-70s: both talk continuously; label overlap, not target-source extraction truth.
- 70-85s: target leaves camera view (at70s), other continues; do not retap.
- 85-90s: quiet + visible/audible ending clap near87s to measure clock drift.

Actual onset/offset/clap times must be annotated from recording. labels.example.json
is a script template, NOT observed truth. Adjust boundaries, exclude transitional
speech or declare allowed states; report all edits. Wrong-state% is against your
chosen gate-policy truth, not automatic speaker-identity accuracy. Offscreen target
expects UNCERTAIN safety, not OTHER suppression.

## Retrieve without uploading the scene

Enable USB debugging; authorize your own laptop. App ID at current source:
com.akashrajeev.voicebeam.finale. Debug build supports run-as. Example:

    adb shell run-as com.akashrajeev.voicebeam.finale ls files/sessions
    adb exec-out run-as com.akashrajeev.voicebeam.finale cat files/sessions/ID/raw.wav > raw.wav
    adb exec-out run-as com.akashrajeev.voicebeam.finale cat files/sessions/ID/video.mp4 > video.mp4
    adb exec-out run-as com.akashrajeev.voicebeam.finale cat files/sessions/ID/session.json > session.json

Replace ID with actual directory. Confirm WAV16kmonoPCM16 and MP4 exists, no audio-only
fallback. Hash originals. Preserve private locally; do not commit people/audio/video.
Extract the visible clap videoPTS and raw clap audio time; videoOffsetMs=videoPTS-audioMs.
Do same at ending clap. If drift>one frame, document/drift-correct preprocessing, don't
pretend one constant offset synchronizes. No source-grounded common recording clock.

## Device lane (new instrumentation tests only)

Build matching app+test APK on team's Android SDK setup:

    ./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

Close VoiceBeam and stop its live engine before this test. It creates separate models;
no ASR/voice learner threads. Do not run another listener concurrently. Wired headset
recommended; existing pipeline output safety remains active. This test may play hearing
output on routed earphones, so use safe volume. It does not touch microphone recording.

Stage local files with adb push to/data/local/tmp then run-as copy:

    adb push raw.wav /data/local/tmp/vb-raw.wav
    adb push video.mp4 /data/local/tmp/vb-video.mp4
    adb shell run-as com.akashrajeev.voicebeam.finale mkdir -p files/replay
    adb shell run-as com.akashrajeev.voicebeam.finale cp /data/local/tmp/vb-raw.wav files/replay/raw.wav
    adb shell run-as com.akashrajeev.voicebeam.finale cp /data/local/tmp/vb-video.mp4 files/replay/video.mp4

Run extraction with verified normalized lock coordinates and measured offset:

    adb shell am instrument -w -e class com.akashrajeev.voicebeam.SceneSignalExtractionTest -e lockX 0.25 -e lockY 0.5 -e lockMs 3000 -e videoOffsetMs YOUR_MEASURED_OFFSET com.akashrajeev.voicebeam.finale.test/androidx.test.runner.AndroidJUnitRunner

VIDEO-mode synchronous MediaPipe CPU/default delegate at8fps -> real FaceTracker/LipActivity,
no voice inference. Creates signals.csv. This is not live FaceAnalyzer or complete engine
replay. If target lock fails, test FAILS rather than choosing another person silently.
Verify coordinates from actual scene; values above are placeholders. Face observations
recomputed may vary by native build/device; freeze signals.csv for repeatable gate tests.

Then actual AudioPipeline paced raw replay (no audio source looping):

    adb shell am instrument -w -e class com.akashrajeev.voicebeam.SceneReplayTest -e quietOthers 0.86 -e denoiseMix 0.7 -e boostDb 6 com.akashrajeev.voicebeam.finale.test/androidx.test.runner.AndroidJUnitRunner

Loads existing models, GTCRN/rawVAD/alignment/gate/envelope/output unchanged. Synthetic
signal trace is held between video samples with visibility age advancing. It does not
perform enrollment, ASR decoding or live speaker queries. Creates frame_inputs.csv and
diagnostics.txt in files/replay. Pipeline onFrame exposes gain/probability/rawVAD but
NOT exact gate enum; the host replay below reconstructs enum with exact TargetGate and
asserts gain/probability equality. If checks fail, do not emit accuracy claims.

Pull signals/frame_inputs/diagnostics via adb exec-out run-as cat, as for raw.wav.
Repeat with identical raw/signals/config and compare trace hashes/numerical output.
Device scheduler/native inference timing can still alter paced wall-time behavior;
fixed signal inputs remove live face-callback variation, not every runtime variable.

## Host exact gate replay and metrics

    kotlinc app/src/main/java/com/akashrajeev/voicebeam/core/TargetGate.kt research/replay/GateTraceReplay.kt -include-runtime -d replay.jar
    java -jar replay.jar frame_inputs.csv 0.86 16 > gates.csv
    python3 research/replay/metrics.py gates.csv labels.json --diagnostics diagnostics.txt --out metrics.json
    cd research/replay && python3 -m unittest test_metrics.py

16ms is256samples/16k GTCRN gate frame cadence. Verify actual frameShift from device
model, change argument if different. Exact production gate code invoked; compare its
gain/probability against recorded values with1e-5tolerance. No score-to-state threshold.
Wrong-state% uses every reconstructed gate frame vs manually annotated half-open labels.
Switch latency: earliest acceptable state sustained300ms after annotated switch; no
stable run means null, not zero. Overlap expected allowlist must be stated beforehand.
Per-state ratio:10log10(sum outputRms²/sum rawRms²), >=3valid rawRms>=1e-5 samples;
TARGET/OTHER/UNCERTAIN separate. Same ratio math as HUD, full replay aggregation rather
than15srolling window. Sparse1Hz diagnostics include gate/boost/denoise/limiter. Not
acoustic attenuation, isolated voice, or clean.wav-to-headphone equivalence.

## Remaining blocks

No real recorded scene supplied yet; no actual wrong-state/switch/dB result exists.
Android tests not compiled/run here. Need device build, offset/annotation verification,
lock identity/pixel check, full replay tests and exact gate equality before trusting output.
Native MediaPipe/sherpa model files come from app's existing assets. No new models added.
Exp1 lip/audio-correlation work is not started.
