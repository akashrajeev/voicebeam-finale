# VoiceBeam Finale

Tap a face, follow the conversation. VoiceBeam is an Android app for enhanced listening through earphones, face-guided speaker focus and live captions.

Team Vanquishers - iQOO Grand Finale, Open Innovation track.

## What it does

- Point the camera at a conversation. Every face in view is found and tracked.
- Tap a face. The person is asked for permission first: a thumbs-up or a spoken "I agree" starts the lock, and consent can be withdrawn at any time with one tap or a spoken phrase. A short torch blink confirms that consent was captured, and a local consent log can be viewed and deleted.
- VoiceBeam learns the voice of the locked person from a few short phrases and links it to the face.
- Lip activity, speech activity and voice similarity guide the gain: the selected person is brought forward and the rest of the room is turned down by a Quiet Others slider.
- Live captions follow the conversation. Moonshine Tiny runs on the phone; an optional online captioner (Deepgram, using the user's own key) can be switched on in Settings.
- Record sessions as audio, audio with video, or captions only, and export them with subtitles. A big-text mode and a local caption view for a nearby laptop suit classrooms and meetings.

## Two tracks

VoiceBeam is designed around two tracks that share the same camera, consent and caption layers.

**ENH - enhancement and turn-based focus.** Speech enhancement (GTCRN) cleans the microphone signal. A target gate combines lip activity, voice activity and voice similarity into a per-frame target probability and a smoothed gain. A raw-audio blend, boost and limiter shape what reaches the earphones.

**SEP - separation.** A causal streaming separation design: a SkiM-style separator processes short chunks, a target-speaker reference chooses the source to follow, an indexed renderer places the chosen source into the output with a short software delay, and a raw-audio fallback keeps sound flowing at every step.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for diagrams, the module map, the consent flow and both tracks.

## Open models

| Model | Role | Runtime |
|---|---|---|
| MediaPipe Face Landmarker | face detection, landmarks, lip activity | MediaPipe Tasks Vision |
| MediaPipe Gesture Recognizer | thumbs-up consent | MediaPipe Tasks Vision |
| GTCRN | speech enhancement | sherpa-onnx |
| Silero VAD v5 | speech activity | sherpa-onnx |
| TitaNet Small | speaker embedding (voice fingerprint) | sherpa-onnx |
| Moonshine Tiny (en, int8) | on-device captions | sherpa-onnx |

Models run on the phone. `scripts/fetch_models.sh` downloads them from their public sources.

## Architecture

```
camera -> face landmarks -> lip activity ------+
   |                                           v
   +-> hand gesture -> consent gate      TargetGate -> boost + limiter -> earphones
                                               ^
mic -> GTCRN -> audio blend -------------------+
 |
 +-> raw speech -> voice fingerprint (TitaNet)
 +-> raw audio -> Silero VAD -> Moonshine / optional online captions -> caption view, recordings
```

## Build

```sh
bash scripts/fetch_models.sh
./gradlew testDebugUnitTest
./gradlew installDebug
```

Requires JDK 17, Android SDK 35 and an Android phone (API 26 or newer). Signing keys are supplied outside the repository. A GitHub Actions workflow runs the unit tests and builds a debug APK on every push to main.

## Documentation

- [Architecture](docs/ARCHITECTURE.md): modules, signal flow, consent flow, ENH and SEP tracks
- [Setup](docs/SETUP.md): build and phone installation
- `benchmarks/`: measurement scripts and results for the model choices
- `docs/`: working notes on audio routing and suppression
