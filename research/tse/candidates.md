# TSE candidates (host screening queue)

Status: none has been shown running on an Android phone in this project.
Verify license, weights, size and causal mode before any port. Screen on host
first per `README.md`; only pass/fail summaries are committed.

| Candidate | Cue | Why | Watch-outs |
|---|---|---|---|
| VoiceFilter-Lite (Google, streaming, ~2.2 MB int8) | enrollment embedding | Only phone-proven design in this list; asymmetric loss + adaptive suppression; built for ASR features, not listening audio | needs waveform-output variant or feature-domain rework for earphone feed |
| LGTSE / D-LGTSE + SEF-PNet backbone (arXiv 2508.19583) | enrollment + GTCRN guide | Direct upgrade: GTCRN already in-pipe; reported +0.45/+0.89 SI-SDR on Libri2Mix 2-spk+noise | needs causal conversion + ONNX export + int8 |
| Causal SkiM / pDCATTUNet family | enrollment or blind+pick | Prior lab run: ~2.7 ms/block host, +13.8 dB, but auto target pick only 4/10 windows | selection is the failure point; condition on frozen centroid + lip gate |
| AV_MossFormer2 (ClearerVoice-Studio, Apache-2.0) | face/lips + audio | Matches product rule: lock a face, hear them, no enrollment | Real-time/causal mode and phone weight size unverified |
| AV-SkiM / Swift-Net / RAVEN | face/lips + audio | Audio-visual, noise-resistant target cues | Research code only; license + causal + size unverified |
| Dolphin (MIT) | face/lips + audio | Permissive license | Same as above; screen first |
| Dual-mic IVA + GTCRN refine (Samsung, Interspeech 2025 hybrid) | spatial | Helps low-SNR before any TSE; phone has 2+ mics | Needs stereo capture path; close mics limit separation |

## Prior lab data points (Pixel 8, for calibration only)

- Block separation + TitaNet pick: +12.7–13.1 dB clips, 27–37% live frames missed (too slow).
- Causal SkiM 25 ms blocks: +13.8 dB host, target pick 4/10, road-test leak ≈ raw.
- Two-voice split: +14 dB 2-spk clips, ~330 ms per 250 ms hop (too slow); 3 voices +2.5 dB.

Demo phone (Snapdragon 8 Elite Gen 5) has more headroom than the Pixel 8 used
above — retest there with QNN/NNAPI int8 before concluding.
