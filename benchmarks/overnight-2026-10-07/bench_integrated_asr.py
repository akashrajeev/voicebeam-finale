import json,time,numpy as np,soundfile as sf,sherpa_onnx,onnxruntime as ort,jiwer,re
p='models/sherpa-onnx-moonshine-tiny-en-int8/'
r=sherpa_onnx.OfflineRecognizer.from_moonshine(p+'preprocess.onnx',p+'encode.int8.onnx',p+'uncached_decode.int8.onnx',p+'cached_decode.int8.onnx',p+'tokens.txt',num_threads=1)
opts=ort.SessionOptions();opts.intra_op_num_threads=1;v=ort.InferenceSession('models/silero_vad_v5.onnx',sess_options=opts)
rows=[]
def norm(s):return ' '.join(re.sub(r'[^a-z0-9 ]',' ',s.lower()).split())
for a in json.load(open('fixtures/manifest.json')):
 if a['scenario']=='enroll':continue
 x,sr=sf.read(a['wav'],dtype='float32');z=np.r_[x,np.zeros(16000)].astype('float32');state=np.zeros((2,1,128),dtype='float32');ctx=np.zeros((1,64),dtype='float32');window=np.array([],dtype='float32');active=[];pre=[];silent=0;last=0;segments=[];prob=0.;first=None;t=time.perf_counter()
 # Android caption thread feeds blocks of 1600. Exact wrapper parity.
 for k in range(0,len(z)-1599,1600):
  block=z[k:k+1600];window=np.r_[window,block]
  while len(window)>=512:
   f=window[:512];window=window[512:];out,state=v.run(None,{'input':np.concatenate([ctx,f[None]],axis=1),'state':state,'sr':np.array(16000,dtype='int64')});ctx=f[-64:][None];prob=float(out[0,0])
  speech=prob>=.5
  if not active and not speech:pre=(pre+block.tolist())[-16000:];continue
  if not active:active=pre;pre=[]
  active+=block.tolist();silent=0 if speech else silent+1600;ended=silent>=9600 or len(active)>=192000
  if ended or len(active)-last>=32000:
   s=r.create_stream();s.accept_waveform(sr,np.array(active,dtype='float32'));r.decode_stream(s);hyp=s.result.text
   if hyp and first is None:first=(k+1600)/16000
   last=len(active)
   if ended:segments.append(hyp);active=[];silent=0;last=0
 hyp=' '.join(segments);score=jiwer.process_words(norm(a['reference']),norm(hyp));rows.append({**a,'hypothesis':hyp,'S':score.substitutions,'D':score.deletions,'I':score.insertions,'N':len(norm(a['reference']).split()),'WER':score.wer,'seconds':time.perf_counter()-t,'segments':len(segments),'first_partial_audio_s':first})
 json.dump(rows,open('results/moonshine-integrated.json','w'),indent=2)
print('done')
