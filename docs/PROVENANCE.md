# Provenance

## Source

`main` is a clean import of the ENH-7 build from the private lab repository `akashrajeev/voicebeam-lab`, commit `792bff3b71a51426eead4ac116236eec34c69fdb` (ENH-7: safe enhancement and enrollment). History here starts fresh; the lab history stays private.

Event rule: the lab README says its code should not be carried into a finale with a fresh-code rule. Akash's team understanding is that building on an existing repository is allowed; confirm that wording with the organisers on site if asked.

## Changes from the lab commit

- Application id `com.akashrajeev.voicebeam.finale`, version name `ENH-7`.
- Gradle JVM heap 3 GB (laptops, not the lab's CI box).
- GitHub workflows moved to `ci-templates/*.disabled`.
- Lab docs moved to `docs/lab-notes/`; benchmarks moved to `evidence/`.
- Gradle wrapper jar replaced by the official 8.9 jar; LibriSpeech fixtures regenerated.
- Not carried over: debug-only sample video/audio feed assets, androidTest sample audio, emulator smoke binaries, README screenshots, any keystore or secret. None existed in the lab source.
- App code is otherwise verbatim. No logic was edited.

## What was verified (at the lab commit, not re-run here)

- 69 JVM unit tests and a debug APK built on GitHub Actions for ENH-7 (lab run, private).
- Controlled-device run on a Pixel 8 through Firebase Test Lab: passed 2/2 at defaults denoise 70%, quiet 86% (own voice about -0.5 dB, others about -34 dB, noise about -31 dB).
- Scripted-scene host rig: missed target speech 1.2% (ENH-5 was 7.6%), other-voice leak about -26 dB.

## What is not verified

- This fresh repo has not been built yet. It was assembled without an Android SDK or JDK 17. First laptop build is the real check.
- Real room, live lip tracking, Bluetooth earbuds, long-run heat and battery on the demo phone.
- Overlapping speech: stays mixed.

## Evidence

`evidence/` holds the benchmark reports and scripts from the lab (overnight component selection, ENH-1..6 reports, simulator rig). Host-CPU numbers there are not phone measurements.
