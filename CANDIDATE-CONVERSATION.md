# Conversation A/B candidate - not the default

Based on Build6 2edb1a3, no main merge. Installed Build6 remains default until venue A/B selection. This is turn gating, not source separation.

Enrollment remains 3x3s with atomic consistency checks. Identity queries use the newest2s of voiced input, refreshed each0.5s. Single successful fresh high-score query can promote a learned visible target when no wearer veto or nearby lip conflict. Fresh strong negative evidence (score<=.35 with low target lips) can attenuate. Stale/null/invisible/overlap evidence passes unboosted, even in full strict. Existing baseline direct tests retain the old policy via explicit candidate flag; runtime selects the candidate.

127 local JVM tests passed. Exact-feed laptop calibration with3x3scentroid/GTCRN and speech-bearing windows:2s knownA,venue27s,field37s all high target score; knownB none. Single-reference/no-VAD sweeps fail badly with short windows, so these results are conditional on clean consistent enrollment and speech input, not a guarantee. Query holds2s of voiced audio, not2s wall-clock; gaps and turn mixtures remain risks.

Sparse two-person Build6 log counterfactual does not prove this query change: it contains3s scores and no verified turn labels. Acceptance under50%UNCERTAIN and zero verified target-suppressed windows remains unresolved without labeled real two-person audio/venue test. Do not deliver as proven better or replace default from unit tests alone.
