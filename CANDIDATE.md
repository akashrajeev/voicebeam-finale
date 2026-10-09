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

## Experiment 6: DPDFNet-2 captions, off experiment-3

DPDFNet-2 is enabled for captions by default, with a RAW fallback switch in Settings. Matcher still defaults to RAW, TitaNet Small stays, hearing still uses GTCRN with the existing mix. No GTCRN output reaches the caption recognizer.

The caption worker owns a separate streaming sherpa-onnx v1.13.8 OnlineSpeechDenoiser using dpdfnet config, models/dpdfnet2.onnx (10,249,356 bytes, SHA256 ce35d6025fc71df0ef10d1540e1b7916837bbfe5f6896deb744508d2cad487a9). fetch_models.sh checks the digest. Input queue always carries raw mic packets; native STFT framing remains sherpa-owned. Heavy caption enhancement is off the hearing/matcher thread. Empty warmup output waits rather than substituting raw; initialization/process failure logs an explicit RAW fallback, resets ASR on a process failure, and logs effective source. Queue drop resets denoiser and ASR so recurrent state is not carried across missing input.

Switching caption input stops listening and resets caption state on restart, preserving voice templates. Both caption lanes use the same scene-mic-selected AudioRecord input as the hearing pipeline, not a second microphone. Scene mic uses CAMCORDER with VOICE_RECOGNITION/MIC fallback; builtin mic is the preferred device. Capture-clock labels remain approximate under native buffering; target labels/VAD follow raw capture metadata, not sample-accurate DPDF output alignment.

106 host JVM tests; Android compile/native inference checks require CI/phone. Benchmark WER selection is noisy proxy evidence, not a proven user-room result. Need phone verification of caption delay, throughput, resource load, source logs and Settings pixels. No hearing quality or real-time guarantee is claimed.
