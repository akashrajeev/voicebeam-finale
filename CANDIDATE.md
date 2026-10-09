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

# experiment-3 additions (off experiment-2 944110e)

Strict focus defaults ON in app Settings, residual amplitude0.2, hangover700ms,
voice threshold0.8. Runtime Settings controls persist and update the running gate.
GateTuning defaults strictOFF for legacy direct gate/replay callers; app explicitly
supplies Settings. Existing98 tests retained;5 new strict/config tests,103 total.

Learned speech-active UNCERTAIN settles to residual only after a bounded recent
TARGET hangover. Rise remains25ms; strict fall250ms. OTHER evidence, overlap,
face loss, unlock/unlearned clear hangover. Quiet frames pass unboosted as before;
explicit OTHER retains quietOthers attenuation. Overlap passes mixed audio.
Target hangover protects gain, NOT boosts ambiguous active speech. State enum
stays UNCERTAIN while attenuated; do not infer output gain from state alone.

Matcher defaults to RAW; the toggle selects pure denoised PREgate/PREboost audio, independent of hearing
mix slider. Both enrollment and query use the selected feed. Denoised is a runtime comparison switch;
changing it STOPS listening and clears BOTH templates, then user must restart and
relearn. Raw VAD still chooses query eligibility, with existing lips/energy fallback.
Captions and raw recording remain RAW. Denoising can DAMAGE identity cues; no
recorded-scene bake-off proves it better. Warmup empty denoised frames are skipped.

SpeakerProfile.DEFAULT binds asset models/speaker.onnx and cosine remap(.25,.60)
for both target/wearer scores; change this one profile with the packaged candidate
asset+measured calibration. TitaNet Small still shipped; no CAM++/ERes2Net winner
invented. fetch_models.sh supports VB_SPEAKER_URL/VB_SPEAKER_ASSET for packaging.
Changing asset/calibration requires model reload/build; gate sliders do NOT.

Risks: strict focus can mute target on failed match or face loss; VAD misses still
pass unknown speech because strict only acts on detected speech. Explicit OTHER
may mute an overlapping target; no source separation. Enrollment phrase count/UI
unchanged. Android compilation/Settings pixels still pending CI/device. No main
merge. Test original recording before declaring a hearing improvement.

Proxy bake-off reported by research: TitaNet gap0.669 raw vs0.485 GTCRN and0.578 DPDFNet-2. Therefore RAW stays default; denoising is not assumed better. Synthetic proxy, not this room. Enroll/test RAW first, then toggle denoised, restart/relearn and repeat.


# experiment-4: CAM++ zh_en COMMON ADVANCED, untuned A/B candidate

Base experiment-3 e8e30ce. Same strict settings and RAW matcher default. Asset
3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx via sherpa-onnx,
packaged as models/campplus_advanced.onnx. Not English VoxCeleb or WeSpeaker LM.
Proxy raw-cosine min target.864/max other.202/gap.662 vs TitaNet.845/.177/.669:
CAM++ ties, NOT a winner. These are synthetic proxy results relayed from research,
not measured on the user's room. Native compatibility/phone hearing still need CI/device.

Initial conservative remap low.35/high.75; runtime score threshold default.8 means
raw cosine.67. This is deliberately NOT TitaNet's cosine.53 threshold. It lies between
proxy max-other.202 and min-target.864, but is UNTUNED, not validated operating point.
Target high-lips fallback still exists, so this threshold alone doesn't control all
TARGET decisions. Real-room target-only/other-only false accept/reject data needed.
Install, learn SAME target cleanly, fixed volume/protocol, compare to experiment-3 RAW.
Do not compare app score numbers across profiles as identical probabilities.
