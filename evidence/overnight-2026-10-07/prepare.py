import pathlib,json,hashlib,numpy as np,soundfile as sf
from scipy.signal import resample_poly
root=pathlib.Path('fixtures');root.mkdir(exist_ok=True)
manifest=[]
def save(id,x,ref,scenario,source,extra={}):
 p=root/(id+'.wav');sf.write(p,x,16000,subtype='PCM_16')
 manifest.append(dict(id=id,wav=str(p),reference=ref,scenario=scenario,source=source,duration=len(x)/16000,sha256=hashlib.sha256(p.read_bytes()).hexdigest(),**extra))
# Deterministic first 30 valid clips, no model-dependent choice.
chosen=[]
for f in sorted(pathlib.Path('nptel-pure/corrected_txt').glob('*.txt')):
 x,sr=sf.read(pathlib.Path('nptel-pure/wav')/(f.stem+'.wav'),dtype='float32');ref=f.read_text().strip()
 if x.ndim!=1 or not 3<=len(x)/sr<=15 or len(ref.split())<6:continue
 m=json.loads((pathlib.Path('nptel-pure/metadata')/(f.stem+'.json')).read_text())
 chosen.append((f.stem,x,ref,m['metadata']['webpage_url']))
 if len(chosen)==30:break
rng=np.random.default_rng(777)
for i,(id,x,ref,url) in enumerate(chosen):
 save('in'+str(i),x,ref,'indian-clean',url,{'dataset_id':id})
 if i<12:
  noise=rng.normal(size=len(x)).astype('float32');noise*=np.sqrt(np.mean(x*x)/np.mean(noise*noise)/10) #10dB
  save('in'+str(i)+'-noise',np.clip(x+noise,-1,1),ref,'indian-noise-10dB',url)
# 3 labelled speakers from LibriSpeech test-clean; enrolment files distinct from held-out tests.
refs={}
for f in pathlib.Path('librispeech/test-clean').glob('*/*/*.trans.txt'):
 for l in f.read_text().splitlines():a,b=l.split(' ',1);refs[a]=b
for spk in ['1089','121','1221']:
 files=sorted(pathlib.Path('librispeech/test-clean').glob(spk+'/*/*.flac'))[:8]
 for i,f in enumerate(files):
  x,sr=sf.read(f,dtype='float32');save(f.stem,x,refs[f.stem],'enroll' if i<3 else 'speaker-test','https://github.com/csukuangfj/librispeech-subset',{'speaker':spk})
# known additive sources: equal-energy distractor, target-only transcript, not dialogue transcription
for i in range(6):
 a=manifest[i*2]; b=next(m for m in manifest if m.get('speaker')=='121' and m['scenario']=='speaker-test')
 x,_=sf.read(a['wav'],dtype='float32');y,_=sf.read(b['wav'],dtype='float32');y=np.resize(y,len(x)); y*=np.sqrt(np.mean(x*x)/max(np.mean(y*y),1e-9));scale=max(np.max(np.abs(x+y)),1)
 save('overlap'+str(i),(x+y)/scale,a['reference'],'overlap-0dB',a['source'],{'target':a['wav'],'interferer':b['wav'],'scale':float(scale)})
json.dump(manifest,open('fixtures/manifest.json','w'),indent=2)
print('fixtures',len(manifest),'seconds',sum(x['duration'] for x in manifest))
