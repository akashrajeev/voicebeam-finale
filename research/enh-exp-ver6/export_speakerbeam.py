import torch,torch.nn as nn,time,sys,json
from local_tcn import TemporalConvNet,TemporalConvNetInformed
torch.set_num_threads(1)
class Encoder(nn.Module):
 def __init__(self):
  super().__init__();self.conv1d=nn.Conv1d(1,256,32,stride=16,bias=False)
 def forward(self,x):return torch.relu(self.conv1d(x.unsqueeze(1)))
class Extractor(nn.Module):
 def __init__(self):
  super().__init__();kw=dict(N=256,B=256,H=512,P=3,X=8,Sc=256,norm_type='gLN',causal=False,pre_mask_nonlinear='prelu')
  self.tcn=TemporalConvNetInformed(**kw,R=4,i_adapt_layer=7,adapt_layer_type='mul',adapt_enroll_dim=256,mask_nonlinear='relu')
  self.auxiliary_net=TemporalConvNet(**kw,R=1,C=1,out_channel=512,mask_nonlinear='linear')
class Decoder(nn.Module):
 def __init__(self):super().__init__();self.convtrans1d=nn.ConvTranspose1d(256,1,32,stride=16,bias=False)
class Model(nn.Module):
 def __init__(self):super().__init__();self.encoder=Encoder();self.extractor=Extractor();self.decoder=Decoder()
 def forward(self,mixture,enrollment):
  e=self.encoder(mixture);ref=self.encoder(enrollment);emb=self.extractor.auxiliary_net(ref).squeeze(1).mean(-1)
  return self.decoder.convtrans1d(e*self.extractor.tcn(e,emb)).squeeze(1)
m=Model().eval();x=torch.load('/tmp/deep-research/tse-replacements/speakerbeam99.pth',map_location='cpu',weights_only=True);m.load_state_dict(x,strict=True)
print('strict load OK params',sum(p.numel() for p in m.parameters()),flush=True)
a=torch.randn(1,8000)*.01;b=torch.randn(1,24000)*.01
with torch.no_grad():
 for n in [800,2000,4000,8000,16000]:
  t=time.time();y=m(torch.randn(1,n)*.01,b);print('samples',n,'seconds',time.time()-t,'finite',bool(torch.isfinite(y).all()),'rms',float(y.square().mean().sqrt()),flush=True)
 torch.onnx.export(m,(a,b),'/tmp/deep-research/tse-replacements/speakerbeam-8k.onnx',input_names=['mixture','enrollment'],output_names=['extracted'],opset_version=17,dynamo=False,dynamic_axes={'mixture':{1:'samples'},'enrollment':{1:'ref_samples'},'extracted':{1:'samples'}})
print('export OK',flush=True)
import numpy as np,onnxruntime as ort
op=ort.SessionOptions();op.intra_op_num_threads=1;s=ort.InferenceSession('/tmp/deep-research/tse-replacements/speakerbeam-8k.onnx',op,providers=['CPUExecutionProvider'])
with torch.no_grad():p=m(a,b).numpy()
t=time.time();q=s.run(None,{'mixture':a.numpy(),'enrollment':b.numpy()})[0]
print('ORT sec',time.time()-t,'maxdiff',np.max(np.abs(q-p)),'finite',np.isfinite(q).all(),flush=True)
