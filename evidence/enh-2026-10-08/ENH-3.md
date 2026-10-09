# ENH-3 candidate
Base ENH-2. No SEP merge, no Bluetooth routing/manifest change.
- Enrollment keeps tail samples across 3s boundaries; oversized input no longer discards leftovers.
- Embeddings normalized before averaging; zero/nonfinite/wrong-dimension embeddings rejected with explicit failure instead of false LEARNED.
- Speaker scoring receives raw mic as enrollment does, avoiding the raw-enrollment versus denoised-query mismatch.
- Optional wearer veto expires after1s instead of2.8s, reducing stale identity mute risk. This cadence/threshold is provisional.
- New scripted CI policy benchmark reports target/other/overlap/ambiguous/conflict/incomplete gain/false-mute. Pure policy metrics are not acoustic measurements.
- ENH-2 Pixel run reports2/2 Passed via owner screenshot; raw fixture metrics and actual pixels still awaiting retrieval. ENH-3 native/live acoustic effect not device-verified. GTCRN defaults unchanged until measured arms read.
