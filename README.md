# VoiceBeam Lab (private)

Experimental component selection snapshot of VoiceBeam at 0c848260, dated 7 October 2026. Not a verified phone release. No GitHub Actions workflow executes in this snapshot.

Read [measured results and caveats](benchmarks/overnight-2026-10-07/REPORT.txt) and [reproduction instructions](benchmarks/overnight-2026-10-07/REPRODUCE.txt).

Changes: Moonshine Tiny chunked ASR on raw input, Silero VAD, TitaNet Small speaker embedding, longer frozen enrolment, explicit turn-taking state, capture-aligned caption metadata. Camera-free listening remains disabled and overlap is not isolated. Native Android runtime, UI and iQOO latency/heat are unverified. Twenty JVM tests pass. APK dex merge blocked by local memory constraints.

No code from this lab should be carried into a finale that requires fresh on-site code. Use its findings as a design record.
