# Local spatial + solo-target experiment

Base: exp-13-recall-gemma, commit 1f3bb666ec997f9a24306f910d1af7d5a51ec76e.
Published source branch: alan/face-lock. No release, public APK or remote CI.
Experimental app ID: com.akashrajeev.voicebeam.spatialrecall.

## What changed

Listen has two opt-in switches in Settings > Experimental direction probe.
Both default Off. Restart Listen after changing direction capture. Solo noise
focus applies at runtime. The existing standalone stereo mic test remains.

Direction capture requests MIC, stereo PCM float, 48 kHz, with a preferred
built-in microphone. Initialization failure falls back to the original mono
path. A capture-start or read failure stops Listen with an explicit error.
It does not silently claim stereo worked. External mic routes cannot supply
the spatial cue. Route changes reset its calibration.

The mono hearing/ASR feed is the mean of the channels through a 63-tap
anti-alias FIR decimator at 16 kHz. Its group delay is about 0.65 ms. The timing
estimator checks lags -20 through +20 samples on first differences. Duplicate
channels, quiet channels, nonfinite input, weak correlation, ambiguous peaks
and zero lag are rejected. These conservative thresholds need real-room tuning.

Mic timing is NOT mapped to camera-left/right. Bottom/back mics have a different
axis from the preview. Instead, 500 ms of consistent, identity-confirmed solo
target speech binds a timing signature to the selected face. Calibration needs
fresh voice confidence >0.85, clear target lip motion and no other speaking face.
Motion, face loss, lock changes, mic-route changes or a 10-second solo-update
expiry invalidate it. Gyroscope motion blocks learning for a further 700 ms.
Without a gyroscope the user must keep the phone still.

In multi-person scenes, a timing agreement can resolve only a middle-confidence
voice decision with supporting target lip motion. Strong voice rejection,
other-speaker evidence, stale vision, missing enrollment, wearer veto and overlap
retain the baseline priority. Custom target thresholds are respected. Timing
never separates two simultaneous voices or opens the overlap gate.

Solo-target noise focus requires exactly one fresh visible face, fresh strong
voice match, active speech, lip motion and calibrated timing agreement. It asks
for full-wet denoising on that target turn, respecting Noise removal Off and the
existing hearing boost/limiter. DFN already runs full wet in this base, so the
extra wet-mix change mainly affects the GTCRN fallback. No model retraining,
beamformer or new source-separation model was added. An off-camera talker can
still contaminate mixed audio. One face on screen does not prove one speaker.

Calibration and logs stay in RAM. Logs include technical timing/route state,
not audio, captions, face coordinates, speech, identities or device IDs.
The user chooses whether to share a log.

## Verification

- Android app Kotlin/Java compilation: passed.
- 48 JVM suites, 410 tests: passed, zero failures/errors.
- 12 new tests cover positive/negative timing, duplicate/quiet/invalid channels,
  enrollment/freshness guards, calibrated agreement, movement/lock/expiry,
  overlap preservation, custom thresholds, interrupted calibration, solo guards,
  baseline behavior, decimator stopband and block-boundary continuity.
- Full APK packaging not completed in the constrained build environment.
- Diagnostics UI has not been visually checked on a running device.
- No phone acoustic, earphone, battery, capture latency or real-room test.
- Existing offline Recall/Gemma and strict gate behavior were preserved, not
  separately phone-tested in this experiment.

## First phone check

Build using JDK 17 and Android SDK 35, fetch pinned dependencies/models with
scripts/fetch_models.sh, then run ./gradlew testDebugUnitTest assembleDebug.
Install the separate experimental app, never over the finale demo app.

1. Stop Listen and Recall. Run the existing Stereo microphone test.
2. Enable Direction tiebreaker. Start Listen with earphones, tap the target and
   explicitly enroll their voice. Ask them to speak alone, phone still.
3. Inspect timing status. Duplicate/ambiguous channels must show disabled or
   learning, not a usable vote. Check actual capture route/rate/channel logs.
4. Target alone, another person alone, both at once. Overlap must stay marked
   overlap/uncertain, never be presented as isolated target audio.
5. Move the phone, move/re-lock the face, change the microphone route. Each
   should require re-calibration rather than reusing the old timing signature.
6. Enable Solo stronger noise removal. Compare with it Off. Compare DFN and
   fallback separately. Noise removal Off must stay Off. Avoid extra volume.
7. Share the technical log only if desired. Do not treat unit tests as proof
   of real-room speech quality.
