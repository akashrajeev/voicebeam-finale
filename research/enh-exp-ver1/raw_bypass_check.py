#!/usr/bin/env python3
"""Known-component control math only. Does not measure a speech model."""
import math
for label,wet in [('main',.7),('target',.85),('uncertain',.95),('other/overlap/missing',1.)]:
    dry=1-wet
    print(label,'dry amplitude',round(dry,5),'dB',round(20*math.log10(dry),2) if dry else '-inf')
assert abs((1-.85)/(1-.7)-.5)<1e-12
# If the denoiser retains an interferer unchanged, full wet cannot remove it.
assert 1.*1.+0.*1. == 1.
