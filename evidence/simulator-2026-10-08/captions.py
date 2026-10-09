import os
os.environ['OMP_NUM_THREADS']='1'
import pathlib,json,soundfile as sf,sherpa_onnx,sys
sys.path.insert(0,'/tmp/enh-simulator');from score import wer
r=pathlib.Path('/tmp/enh-simulator');p=pathlib.Path('/tmp/enh6-bench/models/sherpa-onnx-moonshine-tiny-en-int8');rec=sherpa_onnx.OfflineRecognizer.from_moonshine(str(p/'preprocess.onnx'),str(p/'encode.int8.onnx'),str(p/'uncached_decode.int8.onnx'),str(p/'cached_decode.int8.onnx'),str(p/'tokens.txt'),num_threads=1)
rows=json.load(open(r/'captions.json')) if (r/'captions.json').exists() else []
for scene in sorted((r/'scenes').iterdir())[int(sys.argv[1]):int(sys.argv[2])]:
 if any(z['scene']==scene.name for z in rows):continue
 truth=json.load(open(scene/'truth.json'));x,sr=sf.read(scene/'mix.wav',dtype='float32');st=rec.create_stream();st.accept_waveform(sr,x);rec.decode_stream(st);hyp=st.result.text;z=dict(scene=scene.name,raw_hyp=hyp,ref=truth['target_reference'],wer_pct=wer(truth['target_reference'],hyp));rows.append(z);json.dump(rows,open(r/'captions.json','w'),indent=2);print(scene.name,z['wer_pct'],flush=True)
