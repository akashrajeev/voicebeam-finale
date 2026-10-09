# VoiceBeam Finale

Tap a face, follow the conversation. VoiceBeam is an Android app for enhanced listening through earphones, face-guided speaker focus and live captions.

Team Vanquishers - iQOO Grand Finale, Open Innovation track.

## ENH-7 enhanced listening

- Tap a face to lock the person you want to follow.
- Learn a voice fingerprint with three short speech samples.
- Face landmarks, lip activity, speech activity and voice similarity guide turn-based gain.
- GTCRN enhances microphone audio. Boost and a limiter shape the earphone output.
- Moonshine Tiny provides captions from microphone audio.
- Record sessions and export audio, video and captions. A local caption view supports a nearby PC.

The pipeline adjusts turns rather than extracting a separate source: simultaneous voices remain mixed. When attribution is uncertain, listening continues without target boost.

## Architecture

```
camera -> face landmarks -> lip activity ------+
                                              v
mic -> GTCRN -> audio blend -> TargetGate -> boost + limiter -> earphones
 |                               ^
 +-> raw speech -> voice fingerprint
 +-> raw audio -> speech segments -> captions
```

[Architecture](docs/ARCHITECTURE.md) describes the modules. [Setup](docs/SETUP.md) covers the build and phone installation.

## Build

```sh
bash scripts/fetch_models.sh
./gradlew testDebugUnitTest
./gradlew installDebug
```

Requires JDK 17, Android SDK 35 and a connected Android phone (API 26 or newer). Signing keys are supplied outside the repository.

## Validation

The rolling-query source revision passed 88 JVM tests and built a debug APK in the [recorded validation run](https://github.com/akashrajeev/voicebeam-finale/actions/runs/37963570037). Main also carries a face-loss safety change, which needs validation for its exact revision. Live-room hearing, camera/audio timing, Bluetooth routing and sustained device performance are evaluated on the test phone; unit tests do not establish those results.
