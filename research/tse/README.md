# Screening target-speaker extraction on the host

1. Record or pick a 20 to 30 s clip of two people talking at once with the face visible (needed for audio-visual models).
2. Build scenes with `evidence/simulator-2026-10-08/scene_gen.py`, score with `score.py` (target gain, other-voice leak, missed speech).
3. For each candidate, record: license, weight size, per-second compute on host CPU, target gain on overlap clips, and whether a causal streaming mode exists.
4. Only candidates that are causal, under about 10 ms per 25 ms block on host, and gain on overlap go on to a phone latency test.

Keep results in `research/tse/results/` as small JSON or markdown. Do not commit model weights.
