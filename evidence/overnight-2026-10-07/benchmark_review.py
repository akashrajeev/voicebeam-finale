import json, numpy as np, pathlib
# paired bootstrap WER improvement, 30 Indian clean clips, 2000 draws
r={a:json.load(open('results/'+a+'.json')) for a in ['current','bigger','moonshine']}
r={a:[x for x in v if x['scenario']=='indian-clean'] for a,v in r.items()}
rng=np.random.default_rng(20261007)
def wer(rows,idx):return sum(rows[i]['S']+rows[i]['D']+rows[i]['I'] for i in idx)/sum(rows[i]['N'] for i in idx)
for arm in ['bigger','moonshine']:
 delta=[wer(r['current'],idx)-wer(r[arm],idx) for idx in rng.integers(0,30,(2000,30))]
 print(arm,'paired WER improvement CI',np.quantile(delta,[.025,.975]))
# serial speed recheck subset avoids CPU contention speed measurements
