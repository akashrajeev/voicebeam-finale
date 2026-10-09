# ver4 clarity experiment (version113)

Base ver3b95d5ec4; keep DFN3 model/native bytes and GTCRN fallback. No main merge, no new UI. User reported good crowd reduction but unclear/scrambled target speech on ver3b. Text log proves DFN3 active76sampledrows, no fallback or playback underruns; does NOT identify audible distortion or true all-frame latency.

## Bounded changes

- Fixed native attenuation50->18dB. Native implementation mixes12.59% original spectrum with87.41% enhanced spectrum BEFORE synthesis, aligned inside DFN. This is NOT an arbitrary out-of-time app dry blend. Some crowd noise can return. Nonzero Noise removal remains full output of this native path, not slider-proportional mix. Explicit Off renders raw hearing before gate/filter/boost/limiter, but native still runs to keep state current.
- Native postfilterbeta explicitly0. It was already OFF in pinned runtime defaults; this pins behavior rather than claiming to remove an active postfilter.
- Rate converter31->95tap FIR, cutoff7->7.6kHz. Calculated paired response improves6kHz from-3.76dB to approximately0dB,7kHz from-12.02dB to-.80dB. Group delay up+down changes.625->1.958ms(+1.333ms); adapter startup16ms and nativeSTFT delay remain. No new48k microphone path and no change to rawVAD/enrollment/query/captions.
- Verify JNI preconditions(pointer/direct/capacity), finite score/output. LSNR and native errors share a float return, so do not reject-10/-20 blindly. Log latestLSNR and cumulative values outside modelconfigrange[-15,35]; suspicious is not authenticated error. No crash/hang protection claim.
- Persistentheader labels attenuation/resampler/fullwet/Off cost. Gate/boost/envelope,80Hzhighpass and route safety unchanged. Output-currentgate timing on delayed hearing remains a hypothesis, not fixed blindly.

## Evidence and tests

JVM checks add response at3/6/7k, impulsepeak delay/finite tail, no chunk-boundary jumps for continuous1k tone. Existing framing/gain/finite/invalid-frame/fullscale tests remain. ActualAPK checks verifygzipmodel+arm64JNIhash. No host JVM test runs the native model. These checks don't prove target word clarity.

Phone A/B: same room, target, crowd, distance, Bluetooth route,6dBboost and Noise removal nonzero. Compare ver3b andver4 on whole-word understanding, consonants and naturalness AND retained crowd reduction, not output volume alone. VerifybackendDFN3/fallback0 and suspiciousscorecount. Five-to-ten-minute soak and actual mic-to-ear latency still required before trusted/demo use. Native16ms deadline checked only after call returns and cannot interrupt native hang/crash.

Sources:
- https://raw.githubusercontent.com/KaleyraVideo/DeepFilterNet/681e0947f5145eea62cb6547d1c59090d0680b1f/libDF/src/tract.rs (aligned spectral attenuation mix and postfilter defaults)
- https://raw.githubusercontent.com/KaleyraVideo/DeepFilterNet/681e0947f5145eea62cb6547d1c59090d0680b1f/libDF/src/android.rs (PCM16/JNI/error codes)
- https://github.com/Rikorose/DeepFilterNet/issues/139 (author suggests lowering attenuation to recover original signal)
- https://github.com/KaleyraVideo/AndroidDeepFilterNet (pinned wrapper/native model provenance; notices bundled)
