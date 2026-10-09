exec(open('/tmp/enh-simulator/run_enh.py').read().split('rows=')[0])
rows=[]
for scene in sorted((r/'scenes').iterdir()):
 truth=json.load(open(scene/'truth.json'));x,sr=sf.read(scene/'mix.wav',dtype='float32');den=np.fromfile(scene/'clean.bin',dtype='>f4').astype('float32');n=len(den);x=x[:n];t=next(z for z in truth['turns'] if z['speaker']=='target');enroll,_=sf.read('/tmp/enh6-bench/fixtures/'+t['clip_id']+'.wav',dtype='float32');e=emb(np.resize(enroll,48000));scorev=.5;meta=[];fill=[]
 # VoiceLearner queues voiced frames; score each3s voiced samples; no async worker modeled.
 for off in range(0,n,256):
  if np.sqrt(np.mean(x[off:off+256]**2))>.005:
   fill.extend(x[off:off+256])
   if len(fill)>=48000:
    q=emb(np.array(fill[:48000],np.float32));fill=[];scorev=float(np.clip(((float(q@e) if q is not None else 0)-.25)/.35,0,1))
  f=off//160;meta.append(','.join([truth['frames'][k][f] for k in ['target','other','wearer']])+','+str(scorev))
 (scene/'meta.csv').write_text('\n'.join(meta))
 for arm,y in [('raw',x),('GTCRN_core',den)]:z=score(scene,y);z['arm']=arm;rows.append(z)
 for arm,w,jar in [('ENH6_scripted',.2,'enh-run.jar'),('ENH5_scripted',0,'enh5-run.jar')]:
  (x*w+den*(1-w)).astype('>f4').tofile(scene/'clean2.bin');p=subprocess.run(['java','-jar',str(r/jar),str(scene/'clean2.bin'),str(scene/'out.bin'),str(scene/'meta.csv')]+(['5'] if w==0 else []),text=True,capture_output=True,check=True);y=np.fromfile(scene/'out.bin',dtype='>f4').astype('float32');sf.write(scene/(arm+'.wav'),y,sr,subtype='FLOAT');z=score(scene,y);z.update(arm=arm,state_counters=p.stdout.strip());rows.append(z)
 print(scene.name,flush=True)
json.dump(rows,open(r/'enh-refined.json','w'),indent=2)
