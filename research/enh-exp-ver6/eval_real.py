import wave,numpy as np,onnxruntime as ort,time,json
from pathlib import Path
root=Path('/home/sandbox/finale-fix/app/src/main/assets/enhfixtures')
def read(n):
 with wave.open(str(root/n)) as w:
  x=np.frombuffer(w.readframes(w.getnframes()),dtype='<i2').astype(np.float32)/32768; sr=w.getframerate()
 return x[::sr//8000]
a=read('1089-134686-0002.wav');b=read('1221-135767-0005.wav');ra=read('1089-134686-0013.wav');rb=read('1221-135767-0024.wav')
n=min(len(a),len(b));n=(n-32)//16*16+32;a=a[:n];b=b[:n];mix=(a+b)*.5
op=ort.SessionOptions();op.intra_op_num_threads=1;s=ort.InferenceSession('/tmp/deep-research/tse-replacements/speakerbeam-8k.onnx',op,providers=['CPUExecutionProvider'])
def sisdr(y,t):
 t=t-t.mean();y=y-y.mean();p=t*np.dot(y,t)/(np.dot(t,t)+1e-9);return float(10*np.log10((np.dot(p,p)+1e-9)/(np.dot(y-p,y-p)+1e-9)))
for target,ref,label in [(a,ra,'A'),(b,rb,'B')]:
 t=time.time();y=s.run(None,{'mixture':mix[None],'enrollment':ref[None]})[0][0];dt=time.time()-t
 print(label,'secs',dt,'duration',n/8000,'finite',np.isfinite(y).all(),'mixtureSISDR',sisdr(mix,target),'outputSISDR',sisdr(y,target),'gain',np.sqrt(np.mean(y*y)/np.mean(target*target)),flush=True)
 with wave.open('/tmp/tse-'+label+'.wav','wb') as w:w.setnchannels(1);w.setsampwidth(2);w.setframerate(8000);w.writeframes((np.clip(y,-1,1)*32767).astype('<i2').tobytes())
