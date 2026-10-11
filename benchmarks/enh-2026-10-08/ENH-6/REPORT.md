# ENH-6 candidate, Oct8
Verified source30NPTEL waveform hashes and STRAFFICch1 match prior benchmark. Mixtures recreated by exact manifest recipes, but FLOAT WAV container hashes differ. Signal metrics replicate prior baselines; WER road baseline32.23%raw/33.33%GTCRN differs from earlier31.13/33.11. Same model hashes; current runtime/re-encoding/numerical sensitivity unresolved. Do not claim a statistically proved improvement.

Road10dB (30clips,453words):
Raw: WER32.23%, STOI.893, PESQ1.760, SI-SDR10.00dB.
0%raw: WER33.33%, STOI.892, PESQ2.681, gain8.71dB.
10%raw: WER29.14%, STOI.903, PESQ2.758, gain8.47dB.
20%raw: WER29.14%, STOI.904, PESQ2.605, gain7.69dB.
30%raw: WER29.80%, STOI.903, PESQ2.379, gain6.67dB.
Clean originals: WERraw28.04%,full29.58%,10%30.02%,20%27.81%,30%27.15%.20% chosen provisionally for clean/road balance, not because captions now consume it. Captions remain raw. No clean SI-SDR delta (identity baseline infinite).
6overlap negative controls:20%raw WER136.78%, targetSI-SDR-.178dB; NOT separation.

Code: sample-linear gate/boost changes; limiter after boost at.95, immediate peak attack/100msrelease. Gate attenuation release160ms vs80; target reopening25ms retained;2%amplitude floor at max slider.16focused Kotlin tests pass. Scripted20turn/1200target-frame benchmark0below10%gain before and after, not proof of fewer real misses. Raw ASR enqueue now includes GTCRN first no-output warmup frame; enrollment also keeps it. Phone/Bluetooth/UI/natural lips/realroom unverified.

Default denoise=.8 on new installs; existing saved preference preserved. Full setting remains user selectable. No source/model/routing/manifest change. Final compilation/phone evidence pending.
Sources: https://arxiv.org/html/2404.14860 https://arxiv.org/html/2512.17562v1 https://github.com/AI4Bharat/NPTEL2020-Indian-English-Speech-Dataset/releases/download/v0.1/nptel-pure-set.tar.gz https://zenodo.org/records/1227121
