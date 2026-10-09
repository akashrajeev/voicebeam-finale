#!/usr/bin/env python3
"""Read-only offline GTCRN policy replay. Synthetic gates, not live attribution.
Requires transferred harness.py in ROOT/scripts and exact fixtures in ROOT/fixtures.
No audio path, gain, boost, limiter, or native Android changes.
"""
import argparse,csv,hashlib,importlib.util,json,subprocess,time
from pathlib import Path
import numpy as np
import onnxruntime as ort
import soundfile as sf

def main():
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--jar',type=Path,required=True)
    p.add_argument('--limit',type=int);a=p.parse_args();root=a.root
    spec=importlib.util.spec_from_file_location('harness',root/'scripts/harness.py');h=importlib.util.module_from_spec(spec);spec.loader.exec_module(h)
    res=root/'results';res.mkdir(exist_ok=True);(res/'ticks').mkdir(exist_ok=True)
    manifest=json.loads((root/'fixtures/manifest.json').read_text())
    cases=[m for m in manifest if m['kind'] in ('A','B','gap')][:a.limit]
    denoiser=h.make_denoiser('gtcrn_simple',1)
    opt=ort.SessionOptions();opt.intra_op_num_threads=1;opt.inter_op_num_threads=1
    vad_session=ort.InferenceSession(str(root/'models/silero_vad_v5.onnx'),sess_options=opt,providers=['CPUExecutionProvider'])
    rows=[]
    for idx,item in enumerate(cases):
        path=root/'fixtures'/Path(item['path']).name;x=h.read16(str(path))
        vad_state=np.zeros((2,1,128),np.float32);speech=False;fill=np.empty(0,np.float32);tick=[];next_at=0
        # Sample the current 256-sample frame at the first frame, then >=1s,
        # matching AudioPipeline's diagnostic wall-time cadence in paced input.
        for start in range(0,len(x),256):
            frame=x[start:start+256];fill=np.concatenate((fill,frame))
            while len(fill)>=512:
                prob,vad_state=vad_session.run(None,{'input':fill[:512][None,:], 'state':vad_state,'sr':np.array(16000,np.int64)})
                speech=bool(prob[0,0]>=.5);fill=fill[512:]
            if start>=next_at:
                gate={'A':'TARGET','B':'OVERLAP','gap':'UNCERTAIN'}[item['kind']]
                tick.append([start/16000,gate,str(speech).lower(),float(np.sqrt(np.mean(frame**2))),.7])
                next_at=start+16000
        stem=path.stem;inp=res/'ticks'/f'{stem}.csv'
        with inp.open('w') as f:
            w=csv.writer(f);w.writerow(['time_s','gate','raw_speech','raw_rms','actual_mix']);w.writerows(tick)
        proc=subprocess.run(['java','-jar',str(a.jar),str(inp)],capture_output=True,text=True,check=True)
        proposals=list(csv.DictReader(proc.stdout.splitlines()));(res/'ticks'/f'{stem}_proposals.csv').write_text(proc.stdout)
        # Oracle speech-presence sensitivity: knowingly privileged fixture labels.
        oracle_tick=[[*t[:2],str(item['kind']!='gap').lower(),*t[3:]] for t in tick]
        oracle_in=res/'ticks'/f'{stem}_oracle.csv'
        with oracle_in.open('w') as f:
            w=csv.writer(f);w.writerow(['time_s','gate','raw_speech','raw_rms','actual_mix']);w.writerows(oracle_tick)
        oracle_proc=subprocess.run(['java','-jar',str(a.jar),str(oracle_in)],capture_output=True,text=True,check=True)
        oracle_proposals=list(csv.DictReader(oracle_proc.stdout.splitlines()))
        t0=time.perf_counter();out=denoiser(x,16000);enh=np.asarray(out.samples,np.float32);dt=time.perf_counter()-t0
        n=min(len(x),len(enh));x=x[:n];enh=enh[:n]
        mix=np.full(n,.7,np.float32)
        for t,proposal in enumerate(proposals):
            st=round(float(proposal['time_s'])*16000);en=round(float(proposals[t+1]['time_s'])*16000) if t+1<len(proposals) else n
            mix[st:en]=float(proposal['mix'])
        oracle_mix=np.full(n,.7,np.float32)
        for t,proposal in enumerate(oracle_proposals):
            st=round(float(proposal['time_s'])*16000);en=round(float(oracle_proposals[t+1]['time_s'])*16000) if t+1<len(oracle_proposals) else n
            oracle_mix[st:en]=float(proposal['mix'])
        fixed=.7*enh+.3*x;adaptive=mix*enh+(1-mix)*x
        oracle_audio=oracle_mix*enh+(1-oracle_mix)*x
        variants={'fixed_0.7':fixed,'adaptive_shadow':adaptive,'noise_floor_no_action':fixed,'fixed_1.0_sensitivity':enh,'oracle_speech_sensitivity':oracle_audio}
        ref=h.read16(str(root/'fixtures'/item['clean']))[:n] if item['kind']!='gap' else None
        for name,y in variants.items():
            row={k:item.get(k,'') for k in ['kind','target','noise','snr','sir']};row.update(case=stem,variant=name,
                clipped_fraction=float(np.mean(np.abs(x)>=32767/32768)),rtf=dt/(n/16000),mean_mix=float(np.mean(oracle_mix)) if name=='oracle_speech_sensitivity' else float(np.mean(mix)) if name=='adaptive_shadow' else (1. if name=='fixed_1.0_sensitivity' else .7),
                input_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),rms=h.rms(y),floor_final=proposals[-1]['candidate_floor'])
            if ref is not None:row.update(stoi=h.stoi_score(ref,y),si_sdr=h.si_sdr(ref,y))
            else:row.update(residual_db=20*np.log10((h.rms(y)+1e-12)/(h.rms(x)+1e-12)))
            rows.append(row)
        print(f'[{idx+1}/{len(cases)}] {stem}',flush=True)
        with (res/'shadow_results.json').open('w') as f:json.dump(rows,f,indent=2)
    fields=sorted(set().union(*(r.keys() for r in rows)))
    with (res/'shadow_results.csv').open('w') as f:
        w=csv.DictWriter(f,fieldnames=fields);w.writeheader();w.writerows(rows)
if __name__=='__main__':main()
