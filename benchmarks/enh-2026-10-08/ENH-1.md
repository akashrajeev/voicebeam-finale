# ENH-1
Base fb5fbb1, independent of SEP. No separator/native Java ORT library added.

- Deliberate raw-mic enrollment does not reset on lip/VAD dips; three 3-second chunks above RMS .005 required. Quiet and embedding failures are explicit.
- Target/wearer enrollment mutually exclusive; optional wearer veto disabled during capture. Frozen templates remain RAM-only.
- Complete enrollment required for turn suppression/boost. Uncertain and overlap pass enhanced audio without boost; clear negative voice evidence (<.2 mapped score), visible other-only speech, or confirmed optional wearer veto attenuate.
- Intermediate speaker scores no longer count as a negative. These are provisional policy thresholds, not calibrated probabilities.
- ENH-1 label and exact commit in diagnostic header. No overlap-isolation claim. Routing/manifest unchanged from stable d7af1ab chain.

Verification: unit tests and build pending. Physical ARM startup/model load and UI pixels pending. Live room/Indian voice/Bluetooth/heat remain unverified without owner logs. A scalar gate cannot remove the other voice while both people speak.
