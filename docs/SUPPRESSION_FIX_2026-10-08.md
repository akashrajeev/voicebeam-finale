# Target-turn suppression correction

Base d7af1ab: Bluetooth playback confirmed working by the user. He reports a
delayed electronic copy of his own voice while another face is locked. No fresh
suppression log yet, so exact per-turn cause is not established.

Code-grounded defects: +12 dB hearing boost also amplified the OTHER floor;
80% quiet-others meant 20% residual x 3.981 boost, about 0.796 of raw input.
UNCERTAIN retained 40% before boost. VAD=false left last target probability
unchanged indefinitely, despite the nominal 300ms hold. Camera-mode match scores
never expired and negative matches could not veto visual mouth motion.

Fix: bound target hold to 300ms; unconfirmed/off-camera turns use squared slider
residual (80% -> 4%, 100% -> zero), no hearing boost except confirmed/held target.
New locks do not inherit the open unlocked monitor. Negative fresh voice match
vetoes mouth motion; scores expire after 2800ms in camera mode as in audio-only.
Bluetooth routing, model choice, enrollment requirements and match threshold are
unchanged. Logs add lip confidence, slider strength, match, boost permission and
applied boost. No audio/caption content is logged.

Eight deterministic gate tests cover these policy behaviors. Numbers above are
arithmetic, NOT measured phone suppression. Lip tracking can mistake movement
for speech; enrollment still needs 3 x 3-second high-confidence target speech.
A fresh negative 3-second embedding may temporarily suppress a genuine target
at a speaker change. Single-mic overlap cannot be separated by a scalar gate.
Target words may be attenuated if target evidence is weak. Real-phone tests and
fresh technical logs are needed. Do not call this target-speaker separation.
