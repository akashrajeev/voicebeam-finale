# VoiceBeam offline shadow replay - October 10, 2026

Adaptive mix is a tradeoff, not a general win. It improves target-plus-noise SI-SDR at low SNR, barely lowers STOI, and loses both metrics at high SNR and in overlap. Noise-only gaps get lower digital residual energy. A fixed fully-wet control explains most low-SNR and gap gains. The noise-floor helper does not control audio, so its scores equal fixed behavior exactly.

## Mean metrics by case group

Each cell is STOI / SI-SDR dB; higher is better. Four targets x three noises per speech row (12 cases).

| Case | Fixed 0.7 | Adaptive shadow | Floor only | Fixed 1.0 sensitivity | Oracle speech sensitivity |
|---|---:|---:|---:|---:|---:|
| A, SNR -5 dB | 0.7942 / -2.148 | 0.7936 / 0.055 | 0.7942 / -2.148 | 0.7932 / 0.288 | 0.7976 / -0.397 |
| A, SNR 0 dB | 0.8670 / 3.523 | 0.8658 / 4.868 | 0.8670 / 3.523 | 0.8659 / 5.049 | 0.8688 / 4.712 |
| A, SNR 5 dB | 0.9200 / 8.558 | 0.9188 / 9.079 | 0.9200 / 8.558 | 0.9177 / 9.185 | 0.9199 / 9.048 |
| A, SNR 10 dB | 0.9522 / 12.712 | 0.9492 / 12.620 | 0.9522 / 12.712 | 0.9481 / 12.576 | 0.9506 / 12.807 |
| B, SNR 0 dB | 0.6107 / -4.002 | 0.5977 / -4.166 | 0.6107 / -4.002 | 0.5808 / -4.285 | 0.6107 / -4.002 |
| B, SNR 5 dB | 0.6318 / -2.215 | 0.6210 / -2.432 | 0.6318 / -2.215 | 0.5983 / -2.861 | 0.6318 / -2.215 |

## 15-second speech-free gaps

Digital output RMS relative to noisy input. More negative means lower energy, not acoustic noise reduction or target isolation.

| Noise | Fixed 0.7 | Adaptive | Floor only | Extra drop vs fixed |
|---|---:|---:|---:|---:|
| pcafeter | -7.20 dB | -12.19 dB | -7.20 dB | 4.99 dB |
| straffic | -8.26 dB | -14.34 dB | -8.26 dB | 6.08 dB |
| dwashing | -1.18 dB | -1.73 dB | -1.18 dB | 0.55 dB |

## Assumptions and limits

- Exact transferred fixture archives: fc7afdf3c707f8bed75328d339269ea3154a3baab2c80205e9cfa774ccb92d29 and fc7a2253a174eb12ba7e8e74d930a8fd12ee6a301765c829cdcb5829b020f1bb. 83 PCM16 WAVs, 75 evaluation cases. Per-input hashes are in CSV. No uploaded/committed audio.
- Synthetic gates: A=TARGET, B=OVERLAP, gap=UNCERTAIN. These are fixture assumptions, not detected speaker states. B is 0 dB SIR. Its stated SNR is combined speech versus noise, not target versus noise.
- Raw Silero-v5 probability >=0.5, stateful 512-sample windows, sampled every approximately 1s at current 256-sample frame RMS. Uses direct ONNX calls, not Android/JNI. No exact live gate/VAD equivalence is claimed. First frame has no completed VAD window and defaults false.
- The exact Kotlin ShadowListeningPolicy is invoked in a JVM replay per case; fixed actualMix=0.7. Proposals are held until the next tick with no added smoothing. No clean target labels feed the primary VAD. Oracle sensitivity knowingly forces speech=true for A/B and false for gap.
- Noise-floor EMA receives only raw-VAD-negative sampled RMS. It does not propose gain, mix or a suppression threshold. Floor-only output equals fixed 0.7. Gap babble is not a clean physical noise floor; this estimate can be speech-contaminated.
- GTCRN offline sherpa-onnx1.13.8 CPU output, harness same-index dry/wet blend. NOT app DenoiseAlignment, gate, boost, limiter, routing, wall-clock jitter or device inference. A comparison of offline waveforms, not a live pipeline pass.
- Metrics copied from supplied harness: classic pystoi and SI-SDR projection. No PESQ, DNSMOS, WER or listening ratings in this run. No native phone runtime/thermal evidence.
- Residual RMS on known speech-free fixtures is digital mixture energy only. No speech quality metric is used for gaps.
- No general-win claim based on average SI-SDR alone. Speech quality, intelligibility, naturalness and onset continuity need listening/phone checks.
- PCM16 clipping fraction is measured as abs(sample)>=32767/32768. Maximum fraction 0.00004417. It does not prove an original unclipped waveform; all variants share identical input.
- Results are deterministic offline policy decisions. Recorded RTF excludes VAD, policy/JVM startup and metrics; it is GTCRN call time divided by duration, not overall replay or phone latency.

## Sources

- Supplied harness.py and manifest, read-only fixture copies from the existing evaluation run.
- Production helper on experiment-1: commit359c42dee4dc3b25372b61ced9beba0de80cd332, branch head18a2cd02a1897da5caf5f6bc5964aa8c4bcdf384.
- https://github.com/akashrajeev/voicebeam-finale/tree/experiment-1
- https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/gtcrn_simple.onnx
- https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad_v5.onnx
- LibriSpeech train-clean-5: https://www.openslr.org/resources/31/train-clean-5.tar.gz
- DEMAND: https://zenodo.org/records/1227121
