# experiment-2: gate candidate, not a verified hearing fix

Base: main ea46608b70a90fcb67451f5c9ea3cae169d33549. No experiment-1 HUD/replay changes.
No merge, PR or CI dispatch. Akash reviews and owns any merge.

## Behavior changes

- Voice-led TARGET rule: match >0.8 now needs lips >0.1 instead of >0.3.
  Positive speech, learned template, visibility, negative match, wearer veto and
  existing OTHER/OVERLAP guards remain. A .22 lip score with match1 now qualifies.
  This does not bypass raw VAD. Measure target-only TARGET coverage/false OTHER.
- Target gap boost hold: 700ms instead of300ms. Face loss or active contradictory
  speech still cancels boost. State may read UNCERTAIN while gap boost is held.
  Measure short-gap boost continuity and other-turn leakage. Longer hold can boost
  background/another speaker during a VAD miss. It cannot isolate overlap.
- Visible locked unlearned UNCERTAIN gets provisional configured monitor boost,
  with unity gate gain, without claiming TARGET or speaker identity. Offscreen and
  audio-only unlearned stay unboosted; learned UNCERTAIN/OTHER/OVERLAP stay unboosted
  except the existing target-gap hold. This general boost also amplifies unwanted
  voices/noise and can affect enrollment listening comfort. Existing envelope,
  limiter/headphone-route safety unchanged. Measure prelearn output/raw digital
  levels and clipping/comfort on fixed safe volume, not acoustic separation.
- Changing face ID keeps the frozen voice template and any enrollment progress,
  but clears query audio and stale target/wearer scores. Same-ID retap unchanged.
  Retapping a DIFFERENT PERSON now also keeps the old person's template: deliberately
  use Forget target voice before learning a new person. New visible Forget target
  voice button clears template while keeping face lock. Explicit Unlock and Restart
  learning still reset, as do app model release/recreation; no persistent storage.
  Measure leave-view/reacquire survival, fresh-score delay and wrong-person targeting.

## Validation and limits

98 JVM tests pass via direct Kotlin/JUnit run, including all existing test classes
and5candidate regressions. Candidate checks cover threshold boundary, speech guard,
700ms expiry, cancellation, provisional boost boundaries, preserved centroid/query
clear and explicit reset. Existing incomplete-enrollment expectations changed
intentionally. Engine/Compose Android code is not compiled in this environment and
Forget button pixels are unverified. No recorded-scene replay, device hearing or
before/after acoustic metric has run. Synthetic tests are not phone evidence.

The second phone log's main target-blocking condition was raw VAD false in86/89
sampled frames. These changes do not fix that, embedding contamination/calibration,
or mixed-voice separation. Do not call the candidate demo-ready before controlled
same-headset target-alone/other-alone/offcamera/overlap retest, with raw/video/log.
