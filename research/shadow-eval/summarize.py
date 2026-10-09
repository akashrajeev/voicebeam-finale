#!/usr/bin/env python3
import json,collections,csv
from pathlib import Path
root=Path(__file__).resolve().parent
rows=list(csv.DictReader((root/'shadow_results.csv').open()))
for r in rows:
    for k in ['stoi','si_sdr','rms','residual_db','clipped_fraction']:
        if r.get(k,'')!='':r[k]=float(r[k])
g=collections.defaultdict(dict)
for r in rows:g[(r['kind'],str(r['snr']),r['case'])][r['variant']]=r
s=collections.defaultdict(list)
for (kind,snr,case),vs in g.items():
    f=vs['fixed_0.7']; a=vs['adaptive_shadow']; n=vs['noise_floor_no_action']
    for k in ['stoi','si_sdr','rms','residual_db']:
        if f.get(k,'')!='': assert f[k]==n[k],(case,k)
    s[(kind,snr)].append(vs)
lines=['# VoiceBeam offline shadow replay - October 10, 2026','',
'Adaptive mix is a tradeoff, not a general win. It improves target-plus-noise SI-SDR at low SNR, barely lowers STOI, and loses both metrics at high SNR and in overlap. Noise-only gaps get lower digital residual energy. A fixed fully-wet control explains most low-SNR and gap gains. The noise-floor helper does not control audio, so its scores equal fixed behavior exactly.','',
'## Mean metrics by case group','',
'Each cell is STOI / SI-SDR dB; higher is better. Four targets x three noises per speech row (12 cases).','',
'| Case | Fixed 0.7 | Adaptive shadow | Floor only | Fixed 1.0 sensitivity | Oracle speech sensitivity |','|---|---:|---:|---:|---:|---:|']
agg=[]
variants=['fixed_0.7','adaptive_shadow','noise_floor_no_action','fixed_1.0_sensitivity','oracle_speech_sensitivity']
for (kind,snr),vs in s.items():
    if kind=='gap':continue
    vals=[]
    for v in variants:
        st=sum(x[v]['stoi'] for x in vs)/len(vs);si=sum(x[v]['si_sdr'] for x in vs)/len(vs)
        vals.append(f'{st:.4f} / {si:.3f}')
        agg.append(dict(kind=kind,snr=snr,variant=v,count=len(vs),stoi=st,si_sdr=si))
    lines.append('| '+f'{kind}, SNR {snr} dB'+' | '+' | '.join(vals)+' |')
lines+=['','## 15-second speech-free gaps','', 'Digital output RMS relative to noisy input. More negative means lower energy, not acoustic noise reduction or target isolation.','', '| Noise | Fixed 0.7 | Adaptive | Floor only | Extra drop vs fixed |','|---|---:|---:|---:|---:|']
for (kind,snr,case),vs in g.items():
    if kind!='gap':continue
    f=vs['fixed_0.7']['residual_db'];a=vs['adaptive_shadow']['residual_db']
    lines.append(f'| {vs["fixed_0.7"]["noise"]} | {f:.2f} dB | {a:.2f} dB | {f:.2f} dB | {f-a:.2f} dB |')
lines+=['','## Assumptions and limits','',
'- Exact transferred fixture archives: fc7afdf3c707f8bed75328d339269ea3154a3baab2c80205e9cfa774ccb92d29 and fc7a2253a174eb12ba7e8e74d930a8fd12ee6a301765c829cdcb5829b020f1bb. 83 PCM16 WAVs, 75 evaluation cases. Per-input hashes are in CSV. No uploaded/committed audio.',
'- Synthetic gates: A=TARGET, B=OVERLAP, gap=UNCERTAIN. These are fixture assumptions, not detected speaker states. B is 0 dB SIR. Its stated SNR is combined speech versus noise, not target versus noise.',
'- Raw Silero-v5 probability >=0.5, stateful 512-sample windows, sampled every approximately 1s at current 256-sample frame RMS. Uses direct ONNX calls, not Android/JNI. No exact live gate/VAD equivalence is claimed. First frame has no completed VAD window and defaults false.',
'- The exact Kotlin ShadowListeningPolicy is invoked in a JVM replay per case; fixed actualMix=0.7. Proposals are held until the next tick with no added smoothing. No clean target labels feed the primary VAD. Oracle sensitivity knowingly forces speech=true for A/B and false for gap.',
'- Noise-floor EMA receives only raw-VAD-negative sampled RMS. It does not propose gain, mix or a suppression threshold. Floor-only output equals fixed 0.7. Gap babble is not a clean physical noise floor; this estimate can be speech-contaminated.',
'- GTCRN offline sherpa-onnx1.13.8 CPU output, harness same-index dry/wet blend. NOT app DenoiseAlignment, gate, boost, limiter, routing, wall-clock jitter or device inference. A comparison of offline waveforms, not a live pipeline pass.',
'- Metrics copied from supplied harness: classic pystoi and SI-SDR projection. No PESQ, DNSMOS, WER or listening ratings in this run. No native phone runtime/thermal evidence.',
'- Residual RMS on known speech-free fixtures is digital mixture energy only. No speech quality metric is used for gaps.',
'- No general-win claim based on average SI-SDR alone. Speech quality, intelligibility, naturalness and onset continuity need listening/phone checks.',
'- PCM16 clipping fraction is measured as abs(sample)>=32767/32768. Maximum fraction '+f'{max(r["clipped_fraction"] for r in rows):.8f}'+'. It does not prove an original unclipped waveform; all variants share identical input.',
'- Results are deterministic offline policy decisions. Recorded RTF excludes VAD, policy/JVM startup and metrics; it is GTCRN call time divided by duration, not overall replay or phone latency.','',
'## Sources','',
'- Supplied harness.py and manifest, read-only fixture copies from the existing evaluation run.',
'- Production helper on experiment-1: commit359c42dee4dc3b25372b61ced9beba0de80cd332, branch head18a2cd02a1897da5caf5f6bc5964aa8c4bcdf384.',
'- https://github.com/akashrajeev/voicebeam-finale/tree/experiment-1',
'- https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/gtcrn_simple.onnx',
'- https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad_v5.onnx',
'- LibriSpeech train-clean-5: https://www.openslr.org/resources/31/train-clean-5.tar.gz',
'- DEMAND: https://zenodo.org/records/1227121','']
(root/'RESULTS.md').write_text('\n'.join(lines))
with (root/'summary.csv').open('w') as f:
    w=csv.DictWriter(f,fieldnames=['kind','snr','variant','count','stoi','si_sdr']);w.writeheader();w.writerows(agg)
print('\n'.join(lines[:30]))
