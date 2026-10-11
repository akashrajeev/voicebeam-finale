# Silent live monitor investigation

Base APK: 4c9811f on lab/hardening-2026-10-07.

The output wrote nothing until AudioTrack.routedDevice was a headphone route.
A stream may not establish its route until it consumes buffers. This can leave
startup stuck: no route means no write, and no write means no active route.
The same guard exists in public main, so this is a latent defect, not a proven
new regression. No phone logs were available to identify the user's exact cause.

Fix: prime the track with silence and keep silence flowing on unknown/speaker
routes. Only an actual headphone route gets live microphone samples. USB audio
adapters are recognized consistently. Negative read/write results and audio
thread exceptions are reported; the UI no longer stays silently listening after
an audio failure. The pipeline's Silero instance is released at thread exit.

Gate arithmetic and defaults do not fully mute speech: quietOthers defaults to
0.8 and boost to +12 dB. Moonshine and TitaNet do not directly control playback.
Silero errors can stop the audio thread, which previously only logged them.
The DSP hardening math matches the earlier inline implementation.
Microphone permission remains declared. AudioTrack does not require an added
Bluetooth scan/connect permission just to play via an existing media route.

Four policy tests cover unknown-route priming, headphone speech, mute, and route
changes. They do not simulate Android AudioTrack or prove behavior on iQOO.

Device checks still needed: earphone route (wired/USB/Bluetooth vs speaker),
media volume, model-ready state, moving input meter and captions, and logcat
VoiceBeamAudio/VoiceBeamModels/VoiceBeamEngine output. Try connecting headphones
before start, then after start, then unplugging (speech must not reach speaker).
This patch is not an overlap separator and makes no isolation-quality claim.

Android routing contract:
https://developer.android.com/reference/android/media/AudioRouting#getRoutedDevice()

## Local diagnostics
Settings > Live diagnostics updates once a second. The latest 300 technical
events are held in RAM, with route type, audio RMS/gain/VAD/gate, write results,
denoiser/VAD/gate/playback/ASR/speaker timings and caption backlog. Model and
audio failures log only exception class, not arbitrary exception content.
No audio samples, captions, face data, device names or identifiers are included.
There is no remote feed or upload endpoint. Settings > Share log exports a
text snapshot through Android's chooser; select WhatsApp and the Instinct chat.
Clear log clears RAM. Restarting the process also clears it. A previous exported
snapshot can remain in app cache until replaced or the cache is cleared.

## Follow-up: Bluetooth SCO in the phone log

The user log from build 9e0df94 shows route type 7 (Bluetooth SCO), monitor=true,
and write=256, with a running pipeline and nonzero audio levels. It disproves
startup deadlock and an audio-thread crash for that particular test. It does not
prove earbud audibility. The logged gain is gate attenuation, NOT hearing boost;
the code applies boost separately (default +12 dB). No blind gain increase made.

Code defect: the first enumerated headphone device could be SCO and the media
monitor accepted it. SCO is a telephony route, whereas this track uses media.
Now route selection prioritizes wired/USB then A2DP/LE media; SCO cannot receive
live microphone speech via the media track. Request the built-in phone mic.
Requests are preferences, not guarantees, so log their acceptance and actual
input/output routes. Output RMS, raw RMS, boost setting and music stream volume/
mute now distinguish silent DSP, low volume and unsupported Bluetooth routing.
No system volume is changed. Earbuds with only call audio need Media audio
turned on or a wired/USB alternative. Phone test still required.

## Earlier releases compared
First source commit 4ed6e30 used USAGE_MEDIA, 16 kHz float PCM, CAMCORDER/
VOICE_RECOGNITION mic and default OS output routing with unconditional writes.
Commit d5daa41 on Sep 25 introduced first-enumerated headphone preference and
actual-route checking. No BLUETOOTH_CONNECT or communication-device/mode calls
were present in either the earlier main builds or this lab.
Hardening 46c8081 removed MODIFY_AUDIO_SETTINGS; restore this normal permission
to preserve the original audio capability. It is not proof that this permission
caused the observed SCO playback failure. No exact last working Bluetooth APK
has been established by a user test tied to a commit.
