#!/usr/bin/env python3
"""Summarize explicitly labelled solo-turn log windows, without audio contents."""
import argparse, re, math, json
p=argparse.ArgumentParser()
p.add_argument('log')
p.add_argument('--target', required=True, help='inclusive milliseconds START:END, target alone')
p.add_argument('--other', required=True, help='inclusive milliseconds START:END, other alone')
p.add_argument('--both', help='inclusive milliseconds START:END, both speaking; not isolation evidence')
a=p.parse_args()
rows=[]
for line in open(a.log,encoding='utf-8'):
    m=re.match(r'(\d+)ms audio ',line)
    if not m: continue
    row={'time':int(m[1])}
    for k,v in re.findall(r'(\w+)=([^\s]+)',line):
        try: row[k]=float(v)
        except ValueError: row[k]=v
    rows.append(row)
def summarize(name, span):
    lo,hi=map(int,span.split(':'))
    if lo>=hi: raise ValueError('Window start must be before end')
    selected=[r for r in rows if lo<=r['time']<=hi and r.get('monitor')=='true']
    valid=[r for r in selected if r.get('rawRms',0)>1e-5 and 'outputRms' in r]
    if len(valid)<3:
        return {'window':name,'error':'Need at least 3 monitored samples with nonzero rawRms and outputRms'}
    raw=sum(r['rawRms']**2 for r in valid)
    out=sum(r['outputRms']**2 for r in valid)
    gain=10*math.log10(max(out,1e-30)/raw)
    result={'window':name,'diagnostic_samples':len(valid),'sampled_digital_output_gain_dB':round(gain,2),
            'routes':sorted(set(r.get('route') for r in valid),key=str),
            'quietOthers':sorted(set(r.get('quietOthers') for r in valid),key=str),
            'mediaVolume':sorted(set(r.get('mediaVolume') for r in valid),key=str),
            'gate_states':{s:sum(r.get('gate')==s for r in valid) for s in sorted(set(r.get('gate','unknown') for r in valid))}}
    if name=='other':result['sampled_digital_attenuation_dB']=round(-gain,2)
    if name=='both':result['warning']='Mixture gain only. Cannot measure target retention or distractor removal from total RMS.'
    return result
results=[summarize('target',a.target),summarize('other',a.other)]
if a.both:results.append(summarize('both',a.both))
print(json.dumps({'method':'Sparse approximately 1 Hz technical-log samples, not a continuous waveform measurement or acoustic earbud output test',
'caveats':['Windows must be labelled by the tester; this script cannot recognize who spoke.',
'Keep volume, boost, suppression and mic/earbud position fixed for an A/B comparison.',
'Total digital output gain includes denoiser, gate and hearing boost.',
'Diagnostics are sparse snapshots and may miss transients. Do not claim population accuracy or overlap separation.'],
'windows':results},indent=2))
