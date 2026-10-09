# ver3: experimental AndroidDeepFilterNet candidate

Base ver2; version111. No main merge. This is a speech denoiser, NOT enrolled-speaker extraction. The candidate is audible for nonzero Noise removal; explicit Off stays raw. DFN state continues processing while Off so re-enabling does not play stale queued audio. There is no new button. Compare ver2 APK against ver3 APK for backend A/B. Intermediate Noise removal values do not vary the DFN3 candidate: it is full-wet with native attenuation limit50dB, preventing unaligned raw leak. Diagnostics show active backend and processing time. Keep this distinction visible in test reports.

Kaleyra AndroidDeepFilterNet source pinned9fff40b97bb8754afe6530ffdc234cb15b8d32da provides matching JNI arm64 binary and mobile DFN3 model. Apache2 wrapper; upstream DeepFilterNet dualMIT/Apache2; license copies included in APK assets. JNI exports verified statically. Native disassembly establishes frameLength is960bytes (480PCM16 samples), capacity must equal960, processing converts PCM16 to float internally and writes PCM16 in-place. Wrapperexamples were ambiguous; do not use FloatBuffer. Model config inspected:48kHz,480hop,960FFT,conv/DFlookahead0. The binary is prebuilt, not compiled locally. Android device execution and paced runtime are NOT verified by host JVM tests. Native crashes cannot be caught by Kotlin exception fencing.

## Path

16k mono mic stays unchanged for raw VAD, enrollment, rolling query and captions. Hearing-only converter uses31tap windowed sinc interpolation3x to48k with7kHzcutoff,480sample native chunks, matching anti-alias decimation3x and a256sample startup queue. This does not restore missing high-frequency input. Adds16ms adapter startup buffering plus approximately0.625ms FIR group delay and nativeSTFT delay (end-to-end phone measurement needed). Full-wet candidate replaces GTCRN, never cascades; GTCRN remains loaded fallback. Existing gate/boost/80Hzfilter/limiter retained.

Load/process/nonfinite/wronglength or >16ms per256sample batch failure disables candidate for session and resets GTCRN/alignedraw fallback. A deadline is detected after processing, not a way to interrupt native work. Hard native hangs/crashes remain a risk. Small-model latency on another platform is not a phone guarantee. Candidate native state is released on stop; next session reconstructs it. Bounded first/every5sfallback events expose failures. Phone initial-load cost and thermal/runtime stability must be measured.

## Checks and acceptance

JVM tests cover framing/finiteness, startupqueue, steadygain, malformedoutput and fullscale. They DO NOT execute arm64 model. Hashes pin actual downloaded binary/model before APK packaging. Test on phone: verify activeDFN3 (notfallbackGTCRN),targetwords/naturalness,crowdabsent/alone/overlap,longsilenceonset,faceoff/retap,5mincontinuousframep95/underruns/thermal. Checktrue mic-to-ear latency. Reject speech damage/extra delay/instability. No crowd elimination or clinical benefit claim.

Sources:
- https://github.com/KaleyraVideo/AndroidDeepFilterNet
- https://raw.githubusercontent.com/KaleyraVideo/AndroidDeepFilterNet/9fff40b97bb8754afe6530ffdc234cb15b8d32da/noise-filter/src/main/java/com/kaleyra/noise_filter/NativeDeepFilterNet.kt
- https://raw.githubusercontent.com/KaleyraVideo/AndroidDeepFilterNet/9fff40b97bb8754afe6530ffdc234cb15b8d32da/noise-filter/src/bundledModel/res/raw/deep_filter_mobile_model
- https://raw.githubusercontent.com/KaleyraVideo/AndroidDeepFilterNet/9fff40b97bb8754afe6530ffdc234cb15b8d32da/noise-filter/src/main/jniLibs/arm64-v8a/libdf.so
- https://github.com/Rikorose/DeepFilterNet

Weights modelSHA5600b6857117ecc7cf460b8ec4841963bfa6d718921d424d42dea5d3d37a8c32. Binary hash is recorded in fetch_models.sh and checked in CI.
