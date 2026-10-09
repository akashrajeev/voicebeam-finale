# Measurement HUD, experiment-1 only

Adaptive-mix runtime lane is retired. ShadowListeningPolicy remains uncalled in
app code for offline research reproducibility; its findings are in RESULTS.md.
No new suppression, boost, gate or hearing-path behavior is introduced.

## Live without the pipeline hook

Focus HUD reads existing LiveState targetProbability and voiceMatch while listening,
and existing settings.denoise/settings.quietOthers. Voice match unavailable is
pending, not zero. Target probability is a score, not calibrated accuracy.
When stopped, target score/match are paused/pending, never labeled live.

Exact gate enum cannot be recovered from targetProbability or gain. It remains
pending hook. raw/output RMS, VAD probabilities, underruns and meter readings
also remain pending. No fake/sample values are injected. STUB is explicit.

## Future hook contract (Akash owns AudioPipeline)

Call engine.acceptProofTelemetry(immutableSnapshot) at existing approximately1Hz
cadence; all fields come from the same sampled frame. sampledAtMs uses elapsedRealtime.
Gate must be the authoritative enum, not inferred from score. Missing optional VAD,
voice-match and underrun values use null. No model access from UI. No callback is
wired by this change. Deprecated proposedMix/candidateRawFloor remain unused
compatibility fields in ProofTelemetry; do not run the retired shadow helper.

DigitalStateMeter is session-local, locked in the engine bridge, reset on stop.
Per state TARGET/OTHER/UNCERTAIN only: ratio=10log10(sum outputRms^2/sum rawRms^2).
OVERLAP and UNLOCKED are not assigned to any of these buckets. Sparse samples,
not continuous waveform energy. The15,000ms trailing window requires at least3
accepted finite samples, rawRms>=1e-5 and outputRms>=0. At most1sample/1,000ms.
Minimum sample counts exclude invalid data. Zero summed output has no finite
reading; no invented dB floor is used. Counts and last-sample age are displayed.
No data/insufficient samples/stale state means pending rather than a number.

The ratio includes denoiser/mix, gate, boost and limiter. It is a digital level
comparison, NOT acoustic noise reduction, speaker separation or a clinical measure.
There are no constants chosen to produce desired demo numbers.

## Verification pending

13 JVM tests pass (7 meter +6 retired-helper regression). Android compile and actual
Focus pixel inspection remain required. Screenshot Focus portrait/large-font HUD,
STUB state and local synthetic populated/stale/zero-input meter tests. Check camera
area, captions, existing status row and controls remain reachable. Do not describe
synthetic readings as real phone measurements.
