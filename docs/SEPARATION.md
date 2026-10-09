# Separation track (branch `separation`)

Goal: hear the locked person even when someone else talks at the same time. `main` cannot do this: it only turns people up and down when they speak one at a time.

## Akash's product rule

"Lock a face and hear them." Explicit voice enrollment is seen as not optimal, so the preferred direction is extraction that works from the face lock alone. Enrollment-based extraction stays as a fallback.

## What has been tried (private lab, hash references only)

| Try | Result |
|---|---|
| Block separation + TitaNet pick (SEP-6) | Clip quality +12.7 to +13.1 dB on a real Pixel 8, but 27 to 37% of live frames missed. Too slow live. |
| Pretrained causal SkiM, 25 ms blocks (SEP-9/10/11, lab commit 6555ced) | About 2.7 ms per block on host and +13.8 dB, but auto target pick accepted only 4/10 windows and leaked almost like raw on the road test and rig. |
| Earlier two-voice split | +14 dB on 2-speaker clips, but about 330 ms per 250 ms hop on Pixel 8: too slow. Three voices only +2.5 dB. |

## Candidates to screen next (host first, then phone)

Audio-visual target extraction exists in research form: Dolphin (MIT), ClearerVoice-Studio AV_MossFormer2 (Apache-2.0), AV-SkiM, Swift-Net, RAVEN. None has been shown running on an Android phone. Check weights, license and size before any port. Phone is a Snapdragon 8 Elite Gen 5, so more compute is available than on the Pixel 8 used for the earlier tests.

## Go / no-go before anything touches `main`

1. Live budget: per-frame time well under frame length on the demo phone, no dropped frames over 5 minutes.
2. Quality: target gain on two-people-at-once clips beats ENH-7's mixed output with no new leak on single-speaker turns.
3. Safety: never louder than the limiter, falls back to passthrough when unsure.
4. Bluetooth earbuds and a real room.

## In this branch

- `separation/TargetExtractor.kt`: the interface and a `PassthroughExtractor`. Not wired into the pipeline.
- `TargetExtractorContractTest`: contract check for the passthrough. Code-complete, not run yet.
- `research/tse/`: how to screen candidates on the host with the existing scene rig in `evidence/simulator-2026-10-08/`.
