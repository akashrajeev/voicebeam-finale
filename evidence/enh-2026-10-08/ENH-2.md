# ENH-2 candidate
- Peak-safe boost cap at .95 instead of hard-clipping peaks. Effective boost logged; no guarantee of constant +12dB.
- Conflicting strong negative audio identity and positive visible target lips becomes UNCERTAIN, not OTHER; preserves target unboosted until fresh evidence. Thresholds provisional.
- Matched4s public LibriSpeech test-clean fixture suite, independent enrollment, target/other/overlap/target+noise/noise/silence/target entry. RMS .06, deterministic noise. Denoise mix0/.5/1 by requested boost0/6/12dB. Native compute, output RMS ratio, shifted target correlation, clipping and SCRIPTED-lip false mute reported. These do not prove natural camera tracking, real-room voices, intelligibility, Bluetooth or end-to-end delay.
- Raw capture and learned/failure UI retained. SEP not merged. Routing unchanged.
- Device results pending; no measured winner/default chosen until numbers read.
Sources: https://www.openslr.org/12 ; https://github.com/csukuangfj/librispeech-subset . Fixture attribution bundled.
