# enh-exp-ver2: documented Sound Amplifier ideas, not Google's model

Based on ver1. Adds a hearing-only80Hz Butterworth high-pass before final limiter. This removes DC/sub-bass rumble, not other speakers. Tests measure20/80/300/1000Hz response, finite safety, reset and frame continuity. Expected20Hz reduction is about24dB;300Hz speech-band attenuation is under0.05dB. These are DSP transfer measurements, not crowd SIR or clinical/listener benefit. GTCRN, adaptive wet mix, raw identity/captions and face-loss safety stay. No new buttons or external audioeffects. Fixed filtering may alter bass; compare target words and naturalness at matched loudness before keeping it.

Google documents Sound Amplifier frequency tuning, noise reduction, boost and Pixel-only Conversation mode. A2018 Android Developers talk introduced Sound Amplifier using public DynamicsProcessing EQ/multiband compression/limiter. A2021 Google Pixel announcement describes Conversation mode as on-device ML. No model architecture, weights or third-party conversation API was verified. Do not call this a port of Google Sound Amplifier or its ML.

Public Android pieces: NoiseSuppressor/AGC capture effects are device-dependent and can alter raw enrollment/detection; stacking them blindly with GTCRN can damage speech. AGC/LoudnessEnhancer can amplify crowd. DynamicsProcessing provides playback EQ/compression, but backend latency and a post-track effect's headroom need phone checks. Oboe/AAudio reduce buffering in supported configurations, not acoustic crowd separation; Bluetooth codec/headset buffering remains. ASHA/LE audio depends on compatible hearing devices, not a target-extraction network.

Primary sources:
- https://support.google.com/accessibility/android/answer/9157755?hl=en
- https://blog.google/products-and-platforms/devices/pixel/snap-faster-hear-better-and-do-more-your-pixel/
- https://www.youtube.com/watch?v=_hPlNoF1Tyc
- https://research.google/pubs/the-new-dynamics-processing-effect-in-android-open-source-project/
- https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing
- https://developer.android.com/reference/android/media/audiofx/NoiseSuppressor
- https://developer.android.com/reference/android/media/audiofx/AutomaticGainControl
- https://developer.android.com/reference/android/media/audiofx/LoudnessEnhancer
- https://source.android.com/docs/core/audio/implement-pre-processing
- https://github.com/google/oboe/wiki/TechNote_BluetoothAudio
- https://developer.android.com/games/sdk/oboe/low-latency-audio
- https://source.android.com/docs/core/connect/bluetooth/asha

Phone acceptance: same target/crowd samples, fixed volume/distance, ver1 versus ver2, including deep voices, quiet consonants, low rumble, overlap, long silence/onset, face-off10s/return/retap, five-minute thermal/underrun check. Reject worse target words or discomfort. No "by a mile" or crowd elimination claim.
