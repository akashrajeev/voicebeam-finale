# VoiceBeam working-build notes

Purpose: make the current lab build work on Akash's phone before the finale;
learn the architecture and tests, then rebuild fresh in a separate repo later.
This record is a design/test reference, not permission to copy existing code into
an event with fresh-code rules. Main VoiceBeam repo is not changed here.

## Confirmed by user

- VoiceBeam-0.1.0-99de860.apk worked with Bluetooth (user-reported, not tested by us).
- d7af1ab / Actions #7 restores audible Bluetooth playback on his phone.
- The unwanted self-voice is an electronically delayed copy in the earbuds,
  not only natural air/bone-conduction feedback.
- Suppression on #7 remains poor when he locks another person and speaks himself.

## Bluetooth investigation

Phone log from 9e0df94: actual route type 7 (SCO), monitor=true, write=256;
pipeline active with nonzero levels. Therefore that test was not the suspected
startup deadlock or Silero audio-thread crash. Writing samples is not proof of
audible sound at the earbuds. 99de860 let Android choose the media route. Later
main change d5daa41 forced first-enumerated headphone, possibly telephony SCO.
Hardening 46c8081 also removed original MODIFY_AUDIO_SETTINGS permission.

Fixes in #7: prioritize A2DP/LE/wired/USB media, not SCO; request phone built-in
mic, keep silent buffers flowing until actual routing settles, restore original
normal audio permission. Preserve USAGE_MEDIA, avoid call/SCO modes. User says
this works; can't separate which component solved it from one combined test.

## Suppression findings (code facts, not phone metrics)

Gate OTHER residual was 0.2 at 80% quietOthers; +12dB boost multiplies it by 3.981,
net 0.796, only ~2dB below raw. UNCERTAIN 0.4 becomes net 1.59x raw. After VAD
false, target probability stayed .95 forever despite intended 300ms hold.
Off-camera speech with target lips still was UNCERTAIN rather than rejected.
Camera-mode speaker scores never expired and fresh negative match did not veto.

4856230 / #8: bounded hold, squared unknown residual, target-only boost, fresh
negative veto, match expiry. Eight deterministic gate tests added. Bluetooth
path unchanged. At 00:27 IST #8 built successfully. Phone effectiveness not tested and a
fresh suppression-case diagnostic log has not been received. Do not claim a
measured 26dB suppression gain: that was a steady-state arithmetic comparison.

## Enrollment reliability iteration

Current TitaNet learning needs 3 separate 3-second high-lip-confidence chunks;
partial phrases reset when confidence drops. Template frozen, 3-second scoring.
Make enrollment deliberate with visible phrase/sample progress. Don't claim
voice lock before template learned. No threshold changes from anecdotes.
A fresh negative match can reject a genuine speaker briefly at turn switches;
measure this before reducing thresholds. No real overlap separation exists.

## Measurement plan and status

scripts/suppression-measurement contains a technical-log analyzer and phone
protocol. Synthetic analyzer checks: 2x amplitude -> +6.02dB; 0.04x -> -27.96dB.
These only verify arithmetic. Sparse 1Hz RMS logs are not continuous waveform or
acoustic earbud-output measurements. Before judge-facing quantitative charts,
collect labelled continuous digital energy, fixed settings, repeated trials,
target pass loss, other attenuation and turn-transition latency. Both-speaking
mixture RMS cannot establish target/distractor separation.

## Next order

1. Test #8 target-alone/other-alone/transitions, preserve #7 rollback.
2. Explicit target enrollment + progress, test repeatability and target rejection.
3. Optional user-initiated wearer negative enrollment, local/clearable/off until
   validated. No absolute "never pass" claim.
4. Personal VAD and causal target extraction research. Only integrate after
   weights/license/runtime/embedding compatibility and phone latency are checked.
5. AEC only if an acoustic render-to-mic feedback loop is evidenced; delayed
   self-monitoring alone is not that evidence. Don't regress Bluetooth call mode.

No audio/caption content, credentials or personal voice embeddings in this file.

## Explicit enrollment / wearer veto

75b0fce / #9 compiled and unit tests passed, not phone/UI-tested. Target voice
learning is deliberate with visible progress, not silent auto-enrollment.
Optional wearer veto iteration: separate RAM-only wearer template, opt-in and
clearable, off by default. Learn my voice on the listening screen while only
wearer speaks; target learning pauses. Both target/wearer scores use the same
TitaNet query embedding, no duplicate extraction per query. Both templates
required to veto; wearer score >.9 and margin over target >.15. These are
conservative uncalibrated policies, not "never-pass" guarantees. 3-second query
windows cannot identify an immediate turn change or separate overlap. Validate
similar voices and target false rejection on phone before enabling in a demo.
