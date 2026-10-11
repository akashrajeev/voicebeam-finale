import pathlib,json,time,sys,re,numpy as np,soundfile as sf,sherpa_onnx,jiwer
arm=sys.argv[1];root=pathlib.Path('models');out=pathlib.Path('results');out.mkdir(exist_ok=True)
if arm=='moonshine':
 p=root/'sherpa-onnx-moonshine-tiny-en-int8'; rec=sherpa_onnx.OfflineRecognizer.from_moonshine(str(p/'preprocess.onnx'),str(p/'encode.int8.onnx'),str(p/'uncached_decode.int8.onnx'),str(p/'cached_decode.int8.onnx'),str(p/'tokens.txt'),num_threads=1)
else:
 p=root/('sherpa-onnx-streaming-zipformer-en-20M-2023-02-17' if arm=='current' else 'sherpa-onnx-streaming-zipformer-en-2023-06-26')
 rec=sherpa_onnx.OnlineRecognizer.from_transducer(tokens=str(p/'tokens.txt'),encoder=str(next(p.glob('encoder*.int8.onnx'))),decoder=str(next(p.glob('decoder*.onnx'))),joiner=str(next(p.glob('joiner*.int8.onnx'))),num_threads=1,model_type='zipformer' if arm=='current' else 'zipformer2',enable_endpoint_detection=True,rule1_min_trailing_silence=2.0,rule2_min_trailing_silence=.9,rule3_min_utterance_length=12.)
def norm(s):return ' '.join(re.sub(r'[^a-z0-9 ]',' ',s.lower()).split())
rows=[]
for m in json.load(open('fixtures/manifest.json')):
 if m['scenario']=='enroll':continue
 x,sr=sf.read(m['wav'],dtype='float32');start=time.perf_counter();s=rec.create_stream();onset=None;segments=[]
 if arm=='moonshine':
  s.accept_waveform(sr,x);rec.decode_stream(s);hyp=s.result.text
 else:
  padded=np.r_[x,np.zeros(16000,dtype='float32')]
  for k in range(0,len(padded),160):
   s.accept_waveform(sr,padded[k:k+160]);
   while rec.is_ready(s):rec.decode_stream(s)
   t=rec.get_result(s)
   if t and onset is None:onset=(k+160)/sr
   if rec.is_endpoint(s):segments.append(t);rec.reset(s)
  s.input_finished()
  while rec.is_ready(s):rec.decode_stream(s)
  hyp=' '.join(segments+[rec.get_result(s)])
 elapsed=time.perf_counter()-start;r=jiwer.process_words(norm(m['reference']),norm(hyp))
 rows.append({**m,'arm':arm,'hypothesis':hyp,'seconds':elapsed,'rtf':elapsed/m['duration'],'first_partial_audio_s':onset,'S':r.substitutions,'D':r.deletions,'I':r.insertions,'N':len(norm(m['reference']).split()),'WER':r.wer})
 json.dump(rows,open(out/(arm+'.json'),'w'),indent=2)
 print(arm,m['id'],round(r.wer,3),round(elapsed,3),flush=True)
