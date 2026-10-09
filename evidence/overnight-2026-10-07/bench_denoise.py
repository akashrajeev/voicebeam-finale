import json,time,numpy as np,soundfile as sf,sherpa_onnx,pathlib
m=json.load(open('fixtures/manifest.json'));rows=[];aug=[]
c=sherpa_onnx.OfflineSpeechDenoiser(sherpa_onnx.OfflineSpeechDenoiserConfig(model=sherpa_onnx.OfflineSpeechDenoiserModelConfig(gtcrn=sherpa_onnx.OfflineSpeechDenoiserGtcrnModelConfig(model='models/gtcrn_simple.onnx'))))
def sisdr(target,estimate):
 target=target-target.mean();estimate=estimate-estimate.mean();proj=target*(estimate@target)/max(target@target,1e-9);return float(10*np.log10(max(proj@proj,1e-9)/max(np.sum((estimate-proj)**2),1e-9)))
for a in m:
 if a['scenario']=='enroll':continue
 x,sr=sf.read(a['wav'],dtype='float32');t=time.perf_counter();y=np.asarray(c.run(x,sr).samples);dt=time.perf_counter()-t
 # Keep original sample count; offset aligns by known lag selected on clean first clip and fixed below.
 p='fixtures/'+a['id']+'-gtcrn.wav';sf.write(p,y,16000)
 aug.append({**a,'wav':p,'scenario':a['scenario']+'-gtcrn'})
 r=dict(id=a['id'],scenario=a['scenario'],rtf=dt/a['duration'])
 if a['scenario']=='indian-noise-10dB':
  target,_=sf.read('fixtures/'+a['id'].replace('-noise','')+'.wav',dtype='float32')
  # Cross correlation lag measured, metrics aligned; does not hide delay (reported).
  from scipy.signal import correlate,correlation_lags
  lags=correlation_lags(len(y),len(target));corr=correlate(y,target,method='fft');mask=np.abs(lags)<1600;lag=int(lags[mask][np.argmax(corr[mask])]);start=max(lag,0);offset=max(-lag,0);n=min(len(target)-offset,len(y)-start)
  r.update(lag_samples=lag,sisdr_input=sisdr(target[:len(x)],x),sisdr_output=sisdr(target[offset:offset+n],y[start:start+n]))
 rows.append(r)
json.dump(rows,open('results/denoise.json','w'),indent=2);json.dump(aug,open('fixtures/denoised.json','w'),indent=2)
print('done',len(rows))
