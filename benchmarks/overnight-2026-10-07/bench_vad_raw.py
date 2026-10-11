import numpy as np,time,json,sherpa_onnx,soundfile as sf,onnxruntime as ort
samples=json.load(open('fixtures/manifest.json'));rng=np.random.default_rng(9);rows=[]
for arm in ['energy','silero']:
 for scenario in ['silence','white-noise','hum','speech-clean','speech-noise-10dB']:
  for a in [a for a in samples if a['scenario']=='indian-clean'][:12]:
   x,_=sf.read(a['wav'],dtype='float32');noise=rng.normal(size=len(x));noise*=.04/np.sqrt(np.mean(noise**2))
   if scenario=='silence':x=np.zeros_like(x)
   elif scenario=='white-noise':x=noise
   elif scenario=='hum':x=(.05*np.sin(np.arange(len(x))*2*np.pi*120/16000)).astype('float32')
   elif scenario=='speech-noise-10dB':x=x+noise*np.sqrt(np.mean(x*x)/.0016/10)
   # One second inserted background before and after clip. Clip's own silence is not labelled.
   pad=np.zeros(16000,dtype='float32');z=np.r_[pad,x,pad].astype('float32'); predictions=[];floor=-60;first=None
   if arm=='silero':
    cfg=sherpa_onnx.VadModelConfig(silero_vad=sherpa_onnx.SileroVadModelConfig(model='models/silero_vad_v5.onnx',min_speech_duration=.1,min_silence_duration=.2),sample_rate=16000,num_threads=1)
    opts=ort.SessionOptions();opts.intra_op_num_threads=1;v=ort.InferenceSession("models/silero_vad_v5.onnx",sess_options=opts);state=np.zeros((2,1,128),dtype="float32");context=np.zeros((1,64),dtype="float32");step=512
   else:step=256
   t=time.perf_counter()
   for k in range(0,len(z)-step+1,step):
    f=z[k:k+step]
    if arm=='silero':out,state=v.run(None,{"input":np.concatenate([context,f[None]],axis=1),"state":state,"sr":np.array(16000,dtype="int64")});context=f[-64:][None];p=float(out[0,0])>=.5
    else:
     db=20*np.log10(max(np.sqrt(np.mean(f*f)),1e-9));floor+= (db-floor)*(.2 if db<floor else .002);floor=np.clip(floor,-90,-20);p=db>floor+9 and db>-55
    mid=(k+step/2)/16000
    if p and first is None and mid>=1:first=(k+step)/16000-1
    if 1<=mid<1+len(x)/16000:predictions.append(bool(p))
   rows.append(dict(arm=arm,scenario=scenario,id=a['id'],positive_fraction=float(np.mean(predictions)),first_detection_from_inserted_boundary_s=first,rtf=(time.perf_counter()-t)/(len(z)/16000)))
 json.dump(rows,open('results/vad-raw.json','w'),indent=2)
 print(arm,'done',flush=True)
