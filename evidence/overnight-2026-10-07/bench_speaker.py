import json,time,pathlib,sherpa_onnx,numpy as np,soundfile as sf
m=json.load(open('fixtures/manifest.json')); rows=[]
for arm,name in [('campplus','3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx'),('resnet34','wespeaker_en_voxceleb_resnet34.onnx'),('titanet','nemo_en_titanet_small.onnx')]:
 ex=sherpa_onnx.SpeakerEmbeddingExtractor(sherpa_onnx.SpeakerEmbeddingExtractorConfig(model='models/'+name,num_threads=1))
 def emb(x):
  s=ex.create_stream();s.accept_waveform(16000,np.asarray(x,dtype='float32'));s.input_finished()
  if not ex.is_ready(s):raise RuntimeError('not ready')
  e=np.array(ex.compute(s));return e/max(np.linalg.norm(e),1e-9)
 for protocol in ['current-4.5s','long-9s']:
  cent={}
  for sp in ['1089','121','1221']:
   v=[]
   for a in [a for a in m if a.get('speaker')==sp and a['scenario']=='enroll']:
    x,_=sf.read(a['wav'],dtype='float32');v.append(emb(x[:24000 if protocol=='current-4.5s' else 48000]))
   c=np.mean(v,axis=0);cent[sp]=c/np.linalg.norm(c)
  for a in [a for a in m if a['scenario']=='speaker-test']:
   x,_=sf.read(a['wav'],dtype='float32'); x=x[:24000 if protocol=='current-4.5s' else 48000]
   for scenario in ['clean','noise-10dB']:
    z=x.copy()
    if scenario!='clean':
     r=np.random.default_rng(44).normal(size=len(x));z=(x+r*np.sqrt(np.mean(x*x)/np.mean(r*r)/10)).astype('float32')
    t=time.perf_counter();e=emb(z);dt=time.perf_counter()-t
    for sp,c in cent.items():rows.append(dict(arm=arm,protocol=protocol,scenario=scenario,id=a['id'],target=sp,same=a['speaker']==sp,cosine=float(e@c),seconds=dt,duration=len(z)/16000))
  json.dump(rows,open('results/speaker.json','w'),indent=2)
  print(arm,protocol,flush=True)
