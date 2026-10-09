# Offline shadow-policy replay

Supporting laptop experiment, NOT app live telemetry or an audio-control change.
Read RESULTS.md before interpreting scores. No model, fixture or audio bytes are
committed. This folder uses only the existing Kotlin helper in core/ProofTelemetry.kt.
Noise-floor policy has no audio action and cannot improve audio by itself.

## Reproduce

Use Python3.10, JDK11+, Kotlin2.1.0 and JUnit4.13.2 for helper tests.
Install numpy, soundfile, pystoi, onnxruntime, sherpa-onnx==1.13.8.
Create ROOT/{scripts,fixtures,models,results}. Place the supplied evaluation
harness.py in ROOT/scripts/harness.py and exact fixtures+manifest in ROOT/fixtures.
Fetch gtcrn_simple.onnx and silero_vad_v5.onnx using URLs in RESULTS.md to ROOT/models.
The script resolves manifest fixture basenames locally, not original private paths.

Compile from the repository root:

    kotlinc app/src/main/java/com/akashrajeev/voicebeam/core/{TargetGate,ProofTelemetry}.kt research/shadow-eval/PolicyReplay.kt -include-runtime -d ROOT/policy-replay.jar
    python3 research/shadow-eval/evaluate.py --root ROOT --jar ROOT/policy-replay.jar

Copy ROOT/results/shadow_results.{csv,json} into this folder, then run summarize.py.
Primary adaptive replay uses synthetic gates and measured raw Silero probabilities.
Oracle sensitivity is explicitly marked. Fixed1.0 isolates simply increasing wet mix.
No noise-floor suppression rule is invented. RTF excludes metrics/JVM replay/VAD.

The result CSV and summary are the structured deliverables. Tick logs stay local
unless needed; the script writes one per case for reproducibility. Dependencies
and hashes are recorded in environment.json. No running harness is touched.
