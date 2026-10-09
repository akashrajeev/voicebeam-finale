#!/usr/bin/env python3
"""VoiceBeam multi-talker ground-truth scene generator (standalone: numpy + scipy only).
Usage: python3 scene_gen.py --bundle <dir with fixtures/inN.wav, clean-manifest.json, traffic/STRAFFIC/ch01.wav> --out scenes --n 12 --seed 1
Each scene dir has: mix.wav (16k mono float32, what the mic hears), stems/{target,other,wearer,noise}.wav
(aligned, already reverberated/scaled, mix = sum of stems, joint-scaled), truth.json (per-10ms-frame labels,
turns, reference text for the target, scene params). Pass mix.wav through the pipeline under test and score with score.py."""
import argparse, json, os, pathlib
import numpy as np
from scipy.io import wavfile
from scipy.signal import fftconvolve
SR = 16000; FR = 160  # 10 ms truth frames

def rd(p):
    sr, x = wavfile.read(p); assert sr == SR
    x = x.astype(np.float32)
    if x.dtype == np.float32 and np.abs(x).max() > 1.5: x /= 32768.0
    return x if x.ndim == 1 else x[:, 0]

def pcm16_fix(p):  # int16 -> float
    sr, x = wavfile.read(p)
    return (x.astype(np.float32) / 32768.0) if x.dtype == np.int16 else x.astype(np.float32)

def rir(rng, rt60):
    n = int(SR * max(rt60, 0.05)); t = np.arange(n) / SR
    h = rng.standard_normal(n) * np.exp(-6.9 * t / max(rt60, 0.05)); h[0] = 1.0
    return (h / np.sqrt((h ** 2).sum())).astype(np.float32)

def rms(x): return float(np.sqrt(np.mean(x ** 2) + 1e-12))

def build(bundle, rng, scene_id, kind, snr_db, rt60, other_db, wearer_db):
    man = json.load(open(bundle / 'clean-manifest.json'))
    idx = rng.permutation(len(man))[:8]
    clips = {k: man[i] for k, i in zip(['t1', 't2', 'o1', 'o2', 'w1', 'o3', 't3', 'w2'], idx)}
    def clip(k): return pcm16_fix(bundle / 'fixtures' / f"{clips[k]['id']}.wav")
    # timeline in seconds: (speaker, start, clipkey)  speaker in target/other/wearer
    # kinds: turns (alternating, gaps), overlap (other talks over target), wearer (wearer interrupts), mixed
    ev = []
    t = 0.5
    def add(spk, key, start, maxdur=None):
        x = clip(key)
        if maxdur: x = x[: int(maxdur * SR)]
        ev.append((spk, start, key, x)); return start + len(x) / SR
    if kind == 'turns':
        t = add('target', 't1', t) + 0.6; t = add('other', 'o1', t) + 0.6; t = add('target', 't2', t) + 0.6; t = add('other', 'o2', t) + 0.4
    elif kind == 'overlap':
        s = t; e = add('target', 't1', s); add('other', 'o1', s + 1.5, 4)  # other starts 1.5 s into target turn
        t = max(e, s + 5.5) + 0.6; t = add('target', 't2', t) + 0.4
    elif kind == 'wearer':
        t = add('target', 't1', t) + 0.4; t = add('wearer', 'w1', t) + 0.4; t = add('target', 't2', t) + 0.4; t = add('wearer', 'w2', t) + 0.3
    else:  # mixed
        t = add('target', 't1', t) + 0.5; t = add('other', 'o1', t) + 0.3; add('wearer', 'w1', t, 3); t += 3.4
        s = t; e = add('target', 't2', s); add('other', 'o2', s + 2.0, 3); t = max(e, s + 5.0) + 0.5; t = add('target', 't3', t) + 0.4
    total = int((t + 0.5) * SR)
    stems = {k: np.zeros(total, np.float32) for k in ['target', 'other', 'wearer', 'noise']}
    labels = {k: np.zeros(total // FR + 1, bool) for k in ['target', 'other', 'wearer']}
    turns = []; ref = []
    ref_gain = {'target': 0.0, 'other': other_db, 'wearer': wearer_db}
    h_other = rir(rng, rt60 + 0.15); h_target = rir(rng, rt60)
    for spk, start, key, x in sorted(ev, key=lambda e: e[1]):
        x = x / (rms(x) + 1e-9) * 0.05 * 10 ** (ref_gain[spk] / 20)
        if rt60 > 0.05 and spk != 'wearer':  # wearer is near-field, dry
            x = fftconvolve(x, h_target if spk == 'target' else h_other)[: len(x)].astype(np.float32)
        a = int(start * SR); b = min(a + len(x), total)
        stems[spk][a:b] += x[: b - a]
        # label only frames with real speech energy (skip leading/trailing silence in the clip)
        env = np.array([rms(x[i:i + FR]) for i in range(0, len(x) - FR, FR)])
        act = env > 0.25 * np.median(env[env > 0.02 * env.max()]) if len(env) else env
        for j, on in enumerate(act):
            f = (a // FR) + j
            if on and f < len(labels[spk]): labels[spk][f] = True
        turns.append({'speaker': spk, 'start_s': a / SR, 'end_s': b / SR, 'clip_id': clips[key]['id']})
        if spk == 'target': ref.append({'start_s': a / SR, 'text': clips[key]['reference']})
    # noise: DEMAND-style traffic at given SNR vs target+other speech power
    _, noise = wavfile.read(bundle / 'traffic/STRAFFIC/ch01.wav')
    noise = noise.astype(np.float32) / (32768.0 if noise.dtype == np.int16 else 1.0)
    st = int(rng.integers(0, len(noise) - total)) if len(noise) > total else 0
    n = np.take(noise, np.arange(st, st + total), mode='wrap')
    speech = stems['target'] + stems['other'] + stems['wearer']
    if snr_db is not None:
        n = n * rms(speech[np.abs(speech) > 1e-4]) / rms(n) / 10 ** (snr_db / 20)
    else: n = n * 0
    stems['noise'] = n.astype(np.float32)
    mix = sum(stems.values()); sc = min(1.0, 0.95 / (np.abs(mix).max() + 1e-9))
    mix *= sc
    for k in stems: stems[k] = stems[k] * sc
    truth = {'scene_id': scene_id, 'kind': kind, 'sr': SR, 'frame_samples': FR, 'snr_db': snr_db, 'rt60': rt60,
             'other_rel_db': other_db, 'wearer_rel_db': wearer_db, 'scale': float(sc), 'turns': turns,
             'target_reference': ' '.join(r['text'] for r in ref), 'target_turn_refs': ref,
             'frames': {k: ''.join('1' if v else '0' for v in labels[k]) for k in labels}}
    return mix.astype(np.float32), stems, truth

def main():
    ap = argparse.ArgumentParser(); ap.add_argument('--bundle', required=True); ap.add_argument('--out', required=True)
    ap.add_argument('--n', type=int, default=12); ap.add_argument('--seed', type=int, default=1); a = ap.parse_args()
    bundle = pathlib.Path(a.bundle); out = pathlib.Path(a.out); rng = np.random.default_rng(a.seed)
    kinds = ['turns', 'overlap', 'wearer', 'mixed']
    for i in range(a.n):
        kind = kinds[i % 4]; snr = [None, 10, 5, 0][(i // 4) % 4] if i >= 4 else None
        snr = [None, 10, 5][i % 3] if i >= 4 else None
        rt60 = [0.05, 0.3, 0.6][(i // 2) % 3]; other_db = [0, -6][i % 2]; wearer_db = 6
        sid = f"s{i:02d}_{kind}_snr{'clean' if snr is None else snr}_rt{int(rt60*1000)}"
        mix, stems, truth = build(bundle, rng, sid, kind, snr, rt60, other_db, wearer_db)
        d = out / sid; (d / 'stems').mkdir(parents=True, exist_ok=True)
        wavfile.write(d / 'mix.wav', SR, mix)
        for k, v in stems.items(): wavfile.write(d / 'stems' / f'{k}.wav', SR, v)
        json.dump(truth, open(d / 'truth.json', 'w'))
        print(sid, f'{len(mix)/SR:.1f}s')
if __name__ == '__main__': main()
