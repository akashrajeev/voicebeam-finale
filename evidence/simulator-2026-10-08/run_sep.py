exec(open('/tmp/enh-simulator/run_enh.py').read().split('rows=')[0])
import onnxruntime as ort
from scipy.signal import lfilter
opts=ort.SessionOptions();opts.intra_op_num_threads=2;opts.inter_op_num_threads=1
ss=ort.InferenceSession('/home/sandbox/sep11-sim/app/src/main/assets/models/skim-chunk-int8.onnx',opts,providers=['CPUExecutionProvider']);names={i.name for i in ss.get_inputs()}
taps=np.array([-.00365145,0,.01617925,0,-.06841178,0,.30494752,.50187293,.30494752,0,-.06841178,0,.01617925,0,-.00365145],np.float32)
rows=json.load(open(r/'sep-scorecards.json')) if (r/'sep-scorecards.json').exists() else []
for scene in sorted((r/'scenes').iterdir())[int(sys.argv[1]):int(sys.argv[2])]:
 if any(z['scene']==scene.name for z in rows):continue
 truth=json.load(open(scene/'truth.json'));x,sr=sf.read(scene/'mix.wav',dtype='float32');t=next(z for z in truth['turns'] if z['speaker']=='target');enroll,_=sf.read('/tmp/enh6-bench/fixtures/'+t['clip_id']+'.wav',dtype='float32');e=emb(np.resize(enroll,48000));down=lfilter(taps,[1.],x).astype('float32')[1::2];out=np.zeros((2,len(down)),np.float32);states=[np.zeros((1,1,384),np.float32) for _ in range(14)];gain=None;tail=np.zeros((2,4),np.float32)
 for i in range(0,len(down)-204,200):
  feed={'audio':down[i:i+204][None,:],'active':np.array(float(i>0),np.float32),**{f'state_{j}':v for j,v in enumerate(states)}};ys=ss.run(None,{k:v for k,v in feed.items() if k in names});sources=ys[0][0].copy();sources[:,:4]+=tail;tail=ys[0][0][:,200:204].copy();sources=sources[:,:200];summ=sources.sum(axis=0);measured=float(np.dot(summ,down[i:i+200])/(np.dot(summ,summ)+1e-12));gain=measured if gain is None or gain*measured<=0 else .8*gain+.2*measured;sources*=gain;peak=np.abs(sources).max();sources*=min(1.,.95/(peak+1e-12));out[:,i:i+200]=sources;states=ys[1:]
 ups=[]
 for y in out:
  u=np.zeros(len(y)*2,np.float32);u[1::2]=y;u[::2]=(np.r_[0,y[:-1]]+y)*.5;ups.append(np.pad(u[7:],(0,7)))
 delivered=x[:len(ups[0])].copy();selected=0;decisions=[]
 for end in range(16000,len(delivered)-16000,16000):
  sc=[float(q@e) if (q:=emb(y[end-16000:end])) is not None else -1 for y in ups];best=int(np.argmax(sc));yes=sc[best]>.35 and sc[best]-sc[1-best]>.1
  # optimistic instantaneous worker: score prior1s; next1s source; fail-open raw. No queues or200ms renderer.
  if yes:delivered[end:end+16000]=ups[best][end:end+16000];selected+=1
  decisions.append(dict(end_s=end/16000,scores=sc,accepted=bool(yes)))
 z=score(scene,delivered);z.update(arm='SEP11_host_optimistic',selected_seconds=selected,decisions=decisions,finite=bool(np.isfinite(delivered).all()));rows.append(z);sf.write(scene/'sep-host.wav',delivered,16000,subtype='FLOAT');json.dump(rows,open(r/'sep-scorecards.json','w'),indent=2);print(scene.name,selected,flush=True)
