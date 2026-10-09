#!/usr/bin/env python3
"""Score a pipeline output against a scene's ground truth (numpy + scipy only).
Usage: python3 score.py --scene scenes/s03_... --out out.wav [--hyp captions.txt] [--json res.json]
       python3 score.py --selftest scenes/      (checks scorer with oracle / passthrough / oracle-gate outputs)
out.wav must be 16k mono and the same clock as mix.wav (lag up to +-300 ms is estimated and removed; reported).
Metrics (per scene), frames are 10 ms, analysed in 30 ms windows:
 target_keep_db_mean   mean level of target in output vs in mix over target-only frames (0 dB = fully kept; -inf = muted)
 missed_target_frac    fraction of target-only frames with target kept below -10 dB (speech lost)
 longest_miss_ms       longest run of missed target frames (continuity)
 target_in_overlap_db  same keep level on frames where target and other/wearer talk together
 other_leak_db / wearer_leak_db  level of the other talker / wearer in output vs mix on frames where only they talk (more negative = better; 0 = leaked fully)
 leak_frac_other / leak_frac_wearer  fraction of those frames with leak above -10 dB (audible wrong voice)
 gate_flips_per_min    on/off flips of output vs truth flips (flapping)
 clip_frac, peak       output samples above 0.99 and peak
 start_loss_ms         silence at the start of the first target turn (startup block loss)
 wer_pct               caption WER vs target reference if --hyp given (caption path should be fed raw audio, so this scores ASR on mix, not on out.wav)
"""
import argparse, json, pathlib, re, sys
import numpy as np
from scipy.io import wavfile
from scipy.signal import correlate, correlation_lags
SR = 16000; FR = 160; W = 3  # window in frames

def rd(p):
    sr, x = wavfile.read(p); assert sr == SR, f'{p}: need 16 kHz'
    if x.ndim > 1: x = x[:, 0]
    return x.astype(np.float32) / (32768.0 if x.dtype == np.int16 else 1.0)

def wer(ref, hyp):
    n = lambda s: re.sub(r'[^a-z0-9 ]', ' ', s.lower()).split()
    r, h = n(ref), n(hyp); d = np.arange(len(h) + 1)
    for i, rw in enumerate(r, 1):
        nd = np.empty_like(d); nd[0] = i
        for j, hw in enumerate(h, 1): nd[j] = min(d[j] + 1, nd[j - 1] + 1, d[j - 1] + (rw != hw))
        d = nd
    return 100.0 * d[-1] / max(len(r), 1)

def est_lag(y, mix, maxlag=4800):
    m = min(len(y), len(mix), SR * 40); c = correlate(y[:m], mix[:m], mode='full', method='fft'); l = correlation_lags(m, m)
    k = np.abs(l) <= maxlag; return int(l[k][np.argmax(c[k])])

def runs(b):
    best = cur = 0
    for v in b:
        cur = cur + 1 if v else 0; best = max(best, cur)
    return best

def score(scene, y, hyp=None):
    scene = pathlib.Path(scene); t = json.load(open(scene / 'truth.json')); mix = rd(scene / 'mix.wav')
    st = {k: rd(scene / 'stems' / f'{k}.wav') for k in ['target', 'other', 'wearer']}
    lag = est_lag(y, mix)
    if lag > 0: y = np.pad(y[lag:], (0, lag))
    elif lag < 0: y = np.pad(y[:lag], (-lag, 0))
    n = len(mix); y = np.pad(y, (0, max(0, n - len(y))))[:n]
    nf = n // FR; lab = {k: np.array([c == '1' for c in t['frames'][k]][:nf]) for k in st}
    lab = {k: np.pad(v, (0, nf - len(v))) for k, v in lab.items()}
    def gain_db(stem, f):  # level of stem in y vs in mix, window around frame f
        a, b = max(0, f - W // 2) * FR, min(nf, f + W // 2 + 1) * FR; s = st[stem][a:b]; ss = float(s @ s)
        if ss < 1e-8: return None
        g = float(y[a:b] @ s) / ss; return 20 * np.log10(max(abs(g), 1e-4))
    tgt_only = lab['target'] & ~lab['other'] & ~lab['wearer']; tgt_ov = lab['target'] & (lab['other'] | lab['wearer'])
    res = {'scene': t['scene_id'], 'kind': t['kind'], 'snr_db': t['snr_db'], 'rt60': t['rt60'], 'lag_ms': 1000 * lag / SR}
    kg = [g for f in np.where(tgt_only)[0] if (g := gain_db('target', f)) is not None]
    res['target_keep_db_mean'] = float(np.mean(kg)) if kg else None
    miss = np.array([g < -10 for g in kg]) if kg else np.array([])
    res['missed_target_frac'] = float(miss.mean()) if kg else None
    miss_timeline = np.zeros(nf, bool)
    for f in np.where(tgt_only)[0]:
        g = gain_db('target', f)
        if g is not None: miss_timeline[f] = g < -10
    res['longest_miss_ms'] = 10 * runs(miss_timeline) if kg else None
    og = [g for f in np.where(tgt_ov)[0] if (g := gain_db('target', f)) is not None]
    res['target_in_overlap_db'] = float(np.mean(og)) if og else None
    for who in ['other', 'wearer']:
        only = lab[who] & ~lab['target']
        lg = [g for f in np.where(only)[0] if (g := gain_db(who, f)) is not None]
        res[f'{who}_leak_db'] = float(np.mean(lg)) if lg else None
        res[f'leak_frac_{who}'] = float(np.mean([g > -10 for g in lg])) if lg else None
    # gate flips: frame energy above -50 dB of peak
    e = np.array([np.mean(y[i * FR:(i + 1) * FR] ** 2) for i in range(nf)]); on = 10 * np.log10(e + 1e-12) > (10 * np.log10(e.max() + 1e-12) - 40)
    truth_on = lab['target'] | lab['other'] | lab['wearer']
    res['gate_flips_per_min'] = float(np.abs(np.diff(on.astype(int))).sum() / (n / SR / 60))
    res['truth_flips_per_min'] = float(np.abs(np.diff(truth_on.astype(int))).sum() / (n / SR / 60))
    res['clip_frac'] = float(np.mean(np.abs(y) > 0.99)); res['peak'] = float(np.abs(y).max())
    ft = np.where(lab['target'])[0]
    if len(ft):
        a = ft[0]; seg = [gain_db('target', f) for f in range(a, min(a + 100, nf))]; seg = [g for g in seg if g is not None]
        k = next((i for i, g in enumerate(seg) if g > -10), len(seg)); res['start_loss_ms'] = 10 * k
    if hyp is not None: res['wer_pct'] = wer(t['target_reference'], hyp)
    return res

def selftest(root):
    FRn = FR
    for d in sorted(pathlib.Path(root).iterdir())[:4]:
        if not (d / 'truth.json').exists(): continue
        t = json.load(open(d / 'truth.json')); mix = rd(d / 'mix.wav'); tg = rd(d / 'stems' / 'target.wav')
        m = np.zeros(len(mix), np.float32)
        for i, c in enumerate(t['frames']['target']): m[i * FRn:(i + 1) * FRn] = c == '1'
        m = np.convolve(m, np.ones(480) / 480, 'same')
        for name, y in [('oracle_target_only', tg), ('passthrough_mix', mix), ('oracle_gate_on_mix', mix * m), ('silence', mix * 0)]:
            r = score(d, y)
            print(f"{r['scene'][:28]:28} {name:20} keep={r['target_keep_db_mean']} miss={r['missed_target_frac']} otherLeak={r['other_leak_db']} wearerLeak={r['wearer_leak_db']} flips={r['gate_flips_per_min']:.0f}")

if __name__ == '__main__':
    ap = argparse.ArgumentParser(); ap.add_argument('--scene'); ap.add_argument('--out'); ap.add_argument('--hyp'); ap.add_argument('--json'); ap.add_argument('--selftest')
    a = ap.parse_args()
    if a.selftest: selftest(a.selftest); sys.exit()
    r = score(a.scene, rd(a.out), open(a.hyp).read() if a.hyp else None); print(json.dumps(r, indent=1))
    if a.json: json.dump(r, open(a.json, 'w'), indent=1)
