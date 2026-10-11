#!/usr/bin/env python3
"""Create reproducible held-out ASR fixtures from LibriSpeech test-clean.
Input: clone https://github.com/csukuangfj/librispeech-subset and pass its root.
Fixture audio is kept out of git; the manifest records source IDs and references.
"""
import json, subprocess, sys, pathlib
root = pathlib.Path(sys.argv[1]); out = pathlib.Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)
(out / "LIBRISPEECH-LICENSE.txt").write_bytes((root / "LICENSE.TXT").read_bytes())
ids = ['1089-134686-0002', '1089-134686-0013', '121-121726-0008', '121-121726-0012', '1221-135767-0005', '1221-135767-0024']
refs = {}
for transcript in root.glob('test-clean/*/*/*.trans.txt'):
    for row in transcript.read_text().splitlines():
        key, text = row.split(' ', 1); refs[key] = text
manifest = []
for key in ids:
    src = next(root.glob(f'test-clean/{key.split("-")[0]}/*/{key}.flac'))
    target = out / f'{key}.wav'
    subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-i',str(src),'-ar','16000','-ac','1',str(target)], check=True)
    manifest.append({'id':key,'scenario':'clean','wav':target.name,'reference':refs[key], 'speaker':key.split('-')[0]})
# Deterministic distractors and noise. Reference remains target; overlaps are deliberately hard.
a = out / f'{ids[0]}.wav'; b = out / f'{ids[2]}.wav'
for scenario, other, volume in [('other-speaker',b,'0.25'), ('strong-overlap',b,'0.55')]:
    key = f'{ids[0]}-{scenario}'; target=out / f'{key}.wav'
    subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-i',str(a),'-i',str(other),'-filter_complex',f'[1:a]volume={volume}[v];[0:a][v]amix=inputs=2:duration=first:normalize=0,alimiter=limit=0.95','-ar','16000','-ac','1',str(target)],check=True)
    manifest.append({'id':key,'scenario':scenario,'wav':target.name,'reference':refs[ids[0]],'speaker':'1089'})
for scenario, noise in [('mild-noise','0.008'),('heavy-noise','0.04')]:
    key=f'{ids[4]}-{scenario}'; target=out / f'{key}.wav'
    subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-i',str(out/f'{ids[4]}.wav'),'-f','lavfi','-i',f'anoisesrc=c=white:a={noise}:r=16000:seed=42','-filter_complex','[0:a][1:a]amix=inputs=2:duration=first:normalize=0,alimiter=limit=0.95','-ar','16000','-ac','1',str(target)],check=True)
    manifest.append({'id':key,'scenario':scenario,'wav':target.name,'reference':refs[ids[4]],'speaker':'1221'})
(out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print('wrote',len(manifest),'fixtures to',out)
