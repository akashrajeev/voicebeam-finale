# Setup

1. Android Studio (current stable), SDK Platform 35 and platform-tools.
2. JDK 17 (Android Studio's bundled JDK is fine). Set `JAVA_HOME` if Gradle complains.
3. Git and Git Bash on Windows.
4. Clone this repo.
5. In Git Bash: `bash scripts/fetch_models.sh`. This downloads the speech models and the sherpa-onnx library into `app/src/main/assets/models/` and `app/libs/` (both git-ignored). Run it before the first build.
6. Phone: Developer options, USB debugging on, plug in, accept the prompt.
7. `./gradlew testDebugUnitTest` then `./gradlew installDebug` (or Run in Android Studio).

Application id is `com.akashrajeev.voicebeam.finale`.

## Two laptops, one phone

Each laptop has its own debug signing key. If the phone already has the app from the other laptop, Android refuses the install with a signature mismatch. Uninstall first (`adb uninstall com.akashrajeev.voicebeam.finale`), then install. This clears the app's saved settings and voice enrollment. Do not copy a debug keystore between laptops and never commit one.

## CI

GitHub Actions workflows are stored as `ci-templates/*.disabled` so pushes cost no Actions minutes. To enable one, move it to `.github/workflows/` and rename it to `.yml`, only after checking the minutes budget.

## Test fixtures

`app/src/main/assets/enhfixtures/` holds four LibriSpeech test-clean clips (CC BY 4.0, see ATTRIBUTION.txt) used by the in-app self-test and the instrumented fixture tests. They were regenerated from the public LibriSpeech subset for this repo, so they are not byte-identical to the lab originals. Instrumented tests `AsrBenchTest` and `ModelsTest` need assets that were not carried over (`asr_bench/`, `test_speech.wav`); regenerate with `scripts/prepare_asr_bench.py` style tooling in the lab or skip them.
