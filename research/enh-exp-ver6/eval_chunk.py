exec(open('/tmp/tse-export-src/eval_real.py').read().split('for target,ref,label')[0])
for span in [800,4000,8000,16000,48000]:
 out=np.zeros(n,np.float32);t=time.time()
 for off in range(0,n,span):
  end=min(off+span,n);left=max(0,off-4000);right=min(n,end+4000);orig=right-left;size=max(32,((orig-32+15)//16)*16+32)
  block=np.zeros(size,np.float32);block[:orig]=mix[left:right]
  y=s.run(None,{'mixture':block[None],'enrollment':ra[None]})[0][0];out[off:end]=y[off-left:end-left]
 print('central',span/8000,'s time',time.time()-t,'SISDR',sisdr(out,a),'finite',np.isfinite(out).all(),flush=True)
