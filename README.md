# VoiceBeam Finale

Tap a face, follow the conversation. An Android app that locks onto one person with the camera, keeps their voice clear in your earphones, and shows live captions.

Team Vanquishers - iQOO Grand Finale, Open Innovation track.

## What is on each branch

| Branch | What it is |
|---|---|
| `main` | The enhanced pipeline (ENH-7). Face lock, lip activity and a learned voice fingerprint decide when the locked person is talking. Their voice is boosted, others are turned down, captions run on raw audio. |
| `separation` | Same app plus a clean seam for target-speaker extraction (separating two people talking at once). Research plan and an extractor interface, no working model yet. |

`main` is the finale baseline. Nothing from `separation` goes into `main` until it runs live on the phone.

## How it works (main)

```
camera -> face landmarks -> lip activity ----+
                                             v
mic -> noise removal (GTCRN) -> TargetGate -> boost + limiter -> earphones
  |                               ^
  |                      voice fingerprint (TitaNet)
  +-> raw audio -> voice activity (Silero) -> captions (Moonshine Tiny, sherpa-onnx)
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for modules and [docs/PROVENANCE.md](docs/PROVENANCE.md) for where the code and numbers come from.

## Honest limits

- Turn-based gain: it boosts the locked person and turns others down when they speak alone. Two people talking at the same time stay mixed.
- Voice lock needs a short enrollment (3 x 3 s). Face-only extraction is not built.
- Captions use raw audio because denoising did not improve caption accuracy.
- Audio plays through earphones only, never the phone speaker.
- Real room, live lips and Bluetooth earbuds still need on-device checks. See `docs/PROVENANCE.md` for what was verified.

## Build (Windows or Linux/macOS)

Full steps: [docs/SETUP.md](docs/SETUP.md). Short version:

```
git clone <this repo> && cd VoiceBeamFinale
bash scripts/fetch_models.sh      # Git Bash on Windows; downloads models + sherpa-onnx
./gradlew testDebugUnitTest
./gradlew installDebug            # phone connected by USB, debugging on
```

Requires JDK 17, Android SDK 35. Release signing comes from environment variables only. No keystore is stored in this repo.
