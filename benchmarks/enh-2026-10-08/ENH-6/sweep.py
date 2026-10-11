import os
os.environ['OMP_NUM_THREADS']='1'
import pathlib,json,time,re,sys,hashlib
import numpy as np,soundfile as sf,sherpa_onnx,jiwer
from pystoi import stoi
from pesq import pesq
r=pathlib.Path(__file__).parent
manifest=json.load(open(r/'manifest.json'))
cfg=sherpa_onnx.OnlineSpeechDenoiserConfig(model=sherpa_onnx.OfflineSpeechDenoiserModelConfig(gtcrn=sherpa_onnx.OfflineSpeechDenoiserGtcrnModelConfig(model='/tmp/enh-host/gtcrn.onnx'),num_threads=1))
p=r/'models/sherpa-onnx-moonshine-tiny-en-int8';rec=sherpa_onnx.OfflineRecognizer.from_moonshine(str(p/'preprocess.onnx'),str(p/'encode.int8.onnx'),str(p/'uncached_decode.int8.onnx'),str(p/'cached_decode.int8.onnx'),str(p/'tokens.txt'),num_threads=1)
def sdr(y,x):
 x=x-x.mean();y=y-y.mean();p=np.dot(y,x)*x/(np.dot(x,x)+1e-12);return float(10*np.log10((np.dot(p,p)+1e-12)/(np.dot(y-p,y-p)+1e-12)))
def norm(s):return ' '.join(re.sub('[^a-z0-9 ]',' ',s.lower()).split())
rows=json.load(open(r/'sweep-results.json')) if (r/'sweep-results.json').exists() else [];done={(z['id'],z['raw_weight']) for z in rows};limit=int(sys.argv[1]) if len(sys.argv)>1 else 20;n=0
for m in manifest:
 x,sr=sf.read(m['wav'],dtype='float32');ref,_=sf.read(m['clean_wav'],dtype='float32');ref*=m['scale']
 out=r/'gtcrn';out.mkdir(exist_ok=True);fp=out/(m['id']+'.wav')
 if fp.exists():den,_=sf.read(fp,dtype='float32')
 else:
  d=sherpa_onnx.OnlineSpeechDenoiser(cfg);o=[]
  for k in range(0,len(x),256):o.extend(d.run(x[k:k+256],16000).samples)
  o.extend(d.flush().samples);den=np.array(o,dtype='float32')[:len(x)];sf.write(fp,den,sr,subtype='FLOAT')
 for w in [1.,0.,.1,.2,.3]:
  if (m['id'],w) in done:continue
  y=den*(1-w)+x*w;assert len(y)==len(x) and np.isfinite(y).all()
  st=rec.create_stream();st.accept_waveform(sr,y);rec.decode_stream(st);hyp=st.result.text;a=jiwer.process_words(norm(m['reference']),norm(hyp))
  row={'id':m['id'],'case':m['case'],'raw_weight':w,'si_sdr':sdr(y,ref),'si_sdri':sdr(y,ref)-sdr(x,ref),'stoi':float(stoi(ref,y,sr)),'pesq_wb':float(pesq(sr,ref,y,'wb')),'peak':float(np.max(np.abs(y))),'hypothesis':hyp,'S':a.substitutions,'D':a.deletions,'I':a.insertions,'N':len(norm(m['reference']).split()),'WER':a.wer}
  rows.append(row);json.dump(rows,open(r/'sweep-results.json','w'),indent=2);print(m['id'],w,round(row['si_sdr'],2),round(a.wer,3),flush=True);n+=1
  if n>=limit:sys.exit(0)
