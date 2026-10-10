#!/usr/bin/env python3
"""WER with per-fixture and per-scenario error counts. Usage: score_asr.py manifest.json predictions.json"""
import collections, json, re, sys
manifest = json.load(open(sys.argv[1])); pred = json.load(open(sys.argv[2])); totals = collections.defaultdict(lambda: [0,0,0,0])
def words(s): return re.findall(r"[A-Z0-9]+", s.upper())
def counts(ref, hyp):
    r=words(ref); h=words(hyp); d=[[(i,0,0,0) for i in range(len(r)+1)]]
    for j in range(1,len(h)+1):
        row=[(j,0,j,0)]
        for i in range(1,len(r)+1):
            a=d[-1][i-1]
            choices=[(a[0]+(r[i-1]!=h[j-1]),a[1]+(r[i-1]!=h[j-1]),a[2],a[3]),
                     tuple(x+y for x,y in zip(row[i-1],(1,0,0,1))),
                     tuple(x+y for x,y in zip(d[-1][i],(1,0,1,0)))]
            row.append(min(choices))
        d.append(row)
    _,sub,ins,dele=d[-1][-1]; return sub,ins,dele,len(r)
for f in manifest:
    if f['id'] not in pred: raise SystemExit('missing prediction '+f['id'])
    c=counts(f['reference'],pred[f['id']]); t=totals[f['scenario']]
    for i,n in enumerate(c):t[i]+=n
    print(f["scenario"],f['id'],'S/I/D/N=%s/%s/%s/%s'%c,'WER=%.3f'%(sum(c[:3])/max(1,c[3])), 'hyp=',pred[f['id']])
for label, c in totals.items():print('SCENARIO',label,'S/I/D/N=%s/%s/%s/%s'%tuple(c),'WER=%.3f'%(sum(c[:3])/max(1,c[3])))
