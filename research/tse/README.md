# Screening target-speaker extraction on the host (branch `enh-exp`)

Goal: hear the locked person even when someone else talks at the same time.
ENH-7 on `main` only turns alternating turns up and down; overlap passes mixed.

## Protocol

1. Record or pick a 20 to 30 s clip of two people talking at once with the
   face visible (needed for audio-visual candidates).
2. Build two-speaker scenes (target + interferer at several SNRs) and score each
   candidate on: target gain on overlap, other-voice leak, missed target
   speech, and per-block compute on host CPU.
3. For each candidate record: license, weight size, streaming/causal support,
   algorithmic latency, and int8/NNAPI feasibility.
4. Only candidates that are causal, run well under their frame length on host,
   and gain on overlap go on to a phone latency test (5 minutes, no dropped
   frames, limiter-safe, Bluetooth earbuds, real room).

Keep results in `research/tse/results/` as small JSON or markdown.
Do not commit model weights.

## Live seam

`separation/TargetExtractor.kt` defines the interface; `core/TseStage.kt` wraps
it disabled-by-default with fallback-to-input on any contract breach.
`AudioPipeline` runs the hearing path (and only the hearing path) through the
stage; VAD, voice fingerprint and captions stay on the pre-TSE signals.
No real extractor is wired in yet: `PassthroughExtractor` keeps behavior
identical to ENH-7 until a candidate passes the phone test above.
