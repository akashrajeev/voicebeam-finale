# Architecture (main = ENH-7)

Kotlin, Jetpack Compose, CameraX, MediaPipe Face Landmarker, sherpa-onnx (ONNX Runtime) for the audio models. Audio and vision inference run on the phone.

Package `com.akashrajeev.voicebeam`:

| Package | Role |
|---|---|
| `core` | Pure Kotlin logic with unit tests: `TargetGate` (who is speaking: locked person, other, uncertain, overlap, gain smoothing), `LipActivity`, `FaceTracker`, `VoiceMatch`, `UtteranceBuffer`, `ListenEnvelope`, `DenoiseAlignment`, `DropOldestQueue`, `WavWriter`, `Transcript`. No Android dependencies, so these are the safest to change. |
| `engine` | `AudioPipeline` (mic thread: noise removal, gate, boost, limiter, earphone output, side outputs), `VoiceBeamEngine` (owns models, face lock, enrollment, recording, state), `VoiceLearner` (3 x 3 s voice fingerprint), `CaptionAssembler`, `Settings`, `LiveState`, `Diagnostics`. |
| `ml` | `Models.kt`: model wrappers. GTCRN speech enhancement, TitaNet Small speaker embedding, Silero VAD, Moonshine Tiny ASR via sherpa-onnx. |
| `vision` | CameraX analyzer and MediaPipe face landmarks, face processing. |
| `record` | Session storage and export of audio or audio+video with captions (Media3). |
| `stage` | Small local HTTP server for the PC caption view. |
| `ui` | Compose screens: Setup, Focus, Captions, Sessions, Settings. |

## Signal flow

1. Camera frames give face landmarks and lip activity for the locked face and the others.
2. Mic audio is denoised (GTCRN) and blended with some raw audio. The blend setting is `denoise` (default 0.7).
3. `TargetGate` combines lip activity, voice-activity and the voice match score into a target probability and a smoothed gain. Others are turned down by `quietOthers` (default 0.86). Uncertain audio passes without boost.
4. A limiter follows the boost so peaks stay safe.
5. Output goes to earphones only. Route selection prefers A2DP/LE/wired/USB media routes, not call (SCO) mode.
6. Captions use the raw audio path: Silero VAD cuts utterances, Moonshine Tiny transcribes. The gate state labels who spoke.

## Pipeline behavior

- Raw audio for captions, enhanced audio for listening.
- Gate decisions need fresh evidence; stale voice matches expire; a fresh negative match can veto.
- Optional wearer-voice veto is RAM-only and off by default.
- Caption queue is bounded and drops oldest, counted in diagnostics.
- Models are released on stop.

## Experimental extraction seam (branch `enh-exp`, disabled by default)

- `separation/TargetExtractor.kt` is the interface (streaming, causal, same
  length in/out, fallback to input when unsure) plus a `PassthroughExtractor`.
- `core/TseStage.kt` wraps it: disabled returns the frame untouched; enabled
  delegates with try/catch fallback and counts breaches for diagnostics.
- The hearing path runs denoised audio through the stage before gate/boost;
  VAD, voice fingerprint and captions stay on the pre-TSE signals so an
  experimental extractor cannot corrupt attribution.
- The audio diagnostic event reports `tseEnabled/tse/tseLatencyMs/tseUs/tseFallbacks`.
- Overlap is now observable: `FrameInfo.overlap` -> `LiveState.overlapNow` ->
  a "Two voices — mixed" chip, since the scalar gate passes overlap by design.
- `research/tse/` holds the host-screening protocol and candidate table. No
  real extractor is wired in; behavior is identical to ENH-7 until one passes
  the phone test.
