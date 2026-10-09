import json,subprocess,pathlib
p=pathlib.Path('fixtures/manifest.json');orig=p.read_text();m=json.loads(orig);sub=[x for x in m if x['scenario']=='indian-clean'][:6]
try:
 p.write_text(json.dumps(sub))
 for arm in ['current','bigger','moonshine']:
  target=pathlib.Path('results/'+arm+'.json');orig_r=target.read_text()
  subprocess.run(['venv/bin/python','bench_asr.py',arm],check=True,stdout=subprocess.DEVNULL)
  target.rename('results/'+arm+'-serial.json');target.write_text(orig_r)
finally:p.write_text(orig)
