#!/usr/bin/env python3
"""Authoritative gate-trace vs manually labeled truth; sparse digital ratios separate."""
import argparse,csv,json,math,re
from pathlib import Path

def metrics(rows,labels,digital):
    # Labels: half-open scene intervals; expected=[] excludes calibration/both ambiguity.
    counts={};wrong=0;total=0;switch=[]
    for lab in labels:
        allowed=lab.get('expected',[])
        rr=[r for r in rows if lab['start_ms']<=float(r['time_ms'])<lab['end_ms']]
        if not allowed:continue
        bad=sum(r['gate'] not in allowed for r in rr);wrong+=bad;total+=len(rr)
        counts[lab['name']]={'frames':len(rr),'wrong':bad,'wrong_state_pct':100*bad/len(rr) if rr else None}
        if lab.get('switch'):
            # First >=300ms continuously acceptable trace, not isolated single hit.
            latency=None
            for j,r in enumerate(rr):
                st=float(r['time_ms']);end=st+300
                run=[q for q in rr[j:] if float(q['time_ms'])<=end]
                if run and float(run[-1]['time_ms'])>=end-20 and all(q['gate'] in allowed for q in run):
                    latency=st-lab['start_ms'];break
            switch.append({'scene':lab['name'],'switch_latency_ms':latency,'stable_ms':300})
    meter={}
    for state in ['TARGET','OTHER','UNCERTAIN']:
        ss=[d for d in digital if d['gate']==state and math.isfinite(d['rawRms']) and math.isfinite(d['outputRms']) and d['rawRms']>=1e-5 and d['outputRms']>=0]
        raw=sum(x['rawRms']**2 for x in ss);out=sum(x['outputRms']**2 for x in ss)
        meter[state]={'sample_count':len(ss),'digital_output_vs_raw_db':10*math.log10(out/raw) if len(ss)>=3 and raw>0 and out>0 else None}
    return {'wrong_state_pct':100*wrong/total if total else None,'eligible_frames':total,'trace_first_ms':float(rows[0]['time_ms']) if rows else None,'trace_last_ms':float(rows[-1]['time_ms']) if rows else None,'scenes':counts,'switches':switch,'digital_sampled_ratios':meter,
        'caveat':'Gate replay from complete input trace; digital ratios are sparse pipeline diagnostics. Neither acoustic attenuation nor target-source isolation. Missing latency means no300ms stable state observed.'}
def main():
    p=argparse.ArgumentParser();p.add_argument('gate_csv');p.add_argument('labels');p.add_argument('--diagnostics');p.add_argument('--out',default='metrics.json');a=p.parse_args()
    rows=list(csv.DictReader(open(a.gate_csv)));labels=json.load(open(a.labels));digital=[]
    if a.diagnostics:
        for line in Path(a.diagnostics).read_text().splitlines():
            if 'ms audio ' not in line:continue
            fields=dict(re.findall(r'(\w+)=([^\s]+)',line))
            try:digital.append({'gate':fields['gate'],'rawRms':float(fields['rawRms']),'outputRms':float(fields['outputRms'])})
            except (KeyError,ValueError):continue
    result=metrics(rows,labels,digital);Path(a.out).write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))
if __name__=='__main__':main()
