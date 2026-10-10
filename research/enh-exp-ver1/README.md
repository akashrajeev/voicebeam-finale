# enh-exp-ver1: less raw bypass, not voice extraction

Experimental branch from ea46608, versionCode109. No main merge. No measured phone hearing benefit yet.

## Findings and chosen changes

Current main uses GTCRN speech enhancement plus scalar target gating. A 70% wet mix leaves30% aligned raw microphone audio in every state. The target gain boosts both the selected voice and any retained crowd. Reducing raw bypass can reduce residual noise; it cannot separate simultaneous speech that GTCRN retains. The recent logs show mostly TARGET/UNCERTAIN and no reliable negative-speaker trial, so no detection threshold changes are justified.

The hearing-only policy uses a wet minimum85% for TARGET or incomplete enrollment,95% for UNCERTAIN,100% for OTHER/OVERLAP/missing visual target. A stronger saved user value wins; explicit Noise removal Off and unlocked general monitoring keep the user's value. Mix changes approach the new value over80ms with existing aligned raw buffering. These are test hypotheses, not validated best settings. In particular target quality may worsen with stronger denoising.

Raw VAD, frozen enrollment, rolling voice query and raw captions are unchanged. No gate relaxation, no extra boost, no new controls. Face-loss intent and no-boost safeguards remain. Optional diagnostics now count denoiser fallback, record first failure and at most every5s, and reject wrong-size/nonfinite outputs before playback. Exception text/audio is never logged. Denoiser failure retains the original raw fallback behavior, which can temporarily reintroduce crowd.

## Why no new model tonight

- VoiceFilter-Lite is designed for ASR filterbanks, not a drop-in earphone waveform processor.
- Personalized DeepFilterNet2 uses its own192D ECAPA encoder and reported40ms lookahead. The app's TitaNet template cannot simply replace that conditioning.
- Look Once to Hear uses binaural hardware and its own cue/training. This app currently has one mono microphone stream.
- DPDFNet, UL-UNAS and FastEnhancer offer useful streaming noise-enhancement candidates, not automatic enrolled-voice isolation. App phone latency and quality evidence is missing.

## Acceptance

Run the same target/crowd recordings and fixed earbud volume with main versus this build. Include target-alone, crowd-alone, overlap, low-volume target consonants,15s silence then onset, missing face10s/return/retap, and five-minute continuous operation. Count target words, crowd leakage and artifacts separately. Reject worse target words/onsets, failures, underruns or excessive latency. Full crowd elimination and clinical benefit are not claimed.

Diagnostic `denoiseMix` is the stored user setting; `hearingMix` is the smoothed effective value; `hearingMixTarget` is requested policy value; `denoiseFallbacks` is cumulative per session. HearingMix tests prove control and synthetic raw-bypass math only, not GTCRN crowd separation. No automatic SNR estimate from total RMS, no acoustic speaker-count claim.

## Sources checked

1. GTCRN official implementation, streaming support and MIT license: https://github.com/Xiaobin-Rong/gtcrn
2. Official sherpa DPDFNet streaming/offline docs; attenuationLimitDb is offline-only: https://k2-fsa.github.io/sherpa/onnx/speech-enhancement/dpdfnet.html
3. Google VoiceFilter-Lite ASR design: https://google.github.io/speaker-id/publications/VoiceFilter-Lite/
4. Personalized DeepFilterNet2 paper: https://arxiv.org/html/2404.08022v1
5. Look Once to Hear official implementation: https://github.com/vb000/LookOnceToHear
6. UL-UNAS official checkpoint/streaming code: https://github.com/Xiaobin-Rong/ul-unas
7. Community FastEnhancer port and paced-versus-raced benchmarks, not this phone's evidence: https://github.com/kdrkdrkdr/faster-enhancer.c
8. GTCRN-guided extraction research, distinct from GTCRN alone: https://arxiv.org/html/2508.19583

Primary research and community benchmarks are source evidence, not independent local or phone replication. Speaker extraction remains a separate model/training/latency project.
