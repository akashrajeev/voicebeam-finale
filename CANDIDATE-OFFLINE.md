# Experiment 10: custom-video offline SpeakerBeam

Separate branch from Build 6, 2edb1a3. Live mic/camera policy unchanged. No main merge.

Sessions lets the user choose a local video up to 120 seconds /256 MB and mark a 1-10 second target-alone waveform interval. Not face-selected; target-alone reference must be supplied. Output uses noncausal 8 kHz SpeakerBeam extraction and exported MP4. Original stays in the private session folder and is playable independently. Failed import removes only its newly allocated folder. Processing stays on phone, no runtime uploads. At least 550 MB free is required. Nonzero audio/video offsets >50ms and duration differences >100ms are rejected rather than silently desynchronizing.

Weights and Java ORT isolation from pinned donor enh-exp-ver6-extract cc9b86e1. SpeakerBeam model SHA256 e9bdb6c0a8e51b8341435f49abe59ead136cd2b9d6b6c178990fdc50bf54c9eb. License/attribution retained. Java ORT1.20 has a separate native SONAME to coexist with sherpa.

Local seven JVM tests pass; decoder/SpeakerBeam compile against Android14+ORT1.20. Android build, real extractor fixture, native coexistence, picker and MP4 sync, UI pixels and device performance must be gated separately. No listening/separation quality claim from these unit tests. BUILD6 remains the demo-safe live candidate until those gates are checked.
