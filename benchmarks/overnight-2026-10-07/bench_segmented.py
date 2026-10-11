import json,time,numpy as np,soundfile as sf,sherpa_onnx,re,jiwer
p='models/sherpa-onnx-moonshine-tiny-en-int8/'
r=sherpa_onnx.OfflineRecognizer.from_moonshine(p+'preprocess.onnx',p+'encode.int8.onnx',p+'uncached_decode.int8.onnx',p+'cached_decode.int8.onnx',p+'tokens.txt',num_threads=1)
rows=[]
def norm(s):return ' '.join(re.sub(r'[^a-z0-9 ]',' ',s.lower()).split())
for a in json.load(open('fixtures/manifest.json')):
 if a['scenario']=='enroll':continue
 x,sr=sf.read(a['wav'],dtype='float32');z=np.r_[x,np.zeros(16000)].astype('float32')
 v=sherpa_onnx.VoiceActivityDetector(sherpa_onnx.VadModelConfig(silero_vad=sherpa_onnx.SileroVadModelConfig(model='models/silero_vad_v5.onnx',min_speech_duration=.1,min_silence_duration=.5,max_speech_duration=12),sample_rate=16000,num_threads=1),buffer_size_in_seconds=30)
 segments=[];t=time.perf_counter()
 def drain():
  while not v.empty():
   seg=v.front;start=max(0,seg.start-16000);end=min(len(z),seg.start+len(seg.samples)+1600)
   s=r.create_stream();s.accept_waveform(sr,z[start:end]);r.decode_stream(s);segments.append(s.result.text);v.pop()
 for k in range(0,len(z),512):
  f=z[k:k+512];f=np.pad(f,(0,512-len(f)));v.accept_waveform(f);drain()
 v.flush();drain();hyp=' '.join(segments);score=jiwer.process_words(norm(a['reference']),norm(hyp));rows.append({**a,'hypothesis':hyp,'S':score.substitutions,'D':score.deletions,'I':score.insertions,'N':len(norm(a['reference']).split()),'WER':score.wer,'seconds':time.perf_counter()-t,'segments':len(segments)})
 json.dump(rows,open('results/moonshine-segmented.json','w'),indent=2)
print('segmented',sum(x['S']+x['D']+x['I'] for x in rows)/sum(x['N'] for x in rows))
