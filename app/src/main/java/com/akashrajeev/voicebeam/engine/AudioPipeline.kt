package com.akashrajeev.voicebeam.engine

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioDeviceInfo
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.MicrophoneDirection
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.akashrajeev.voicebeam.core.DropOldestQueue
import com.akashrajeev.voicebeam.core.MonitorOutput
import com.akashrajeev.voicebeam.core.MonitorRoute
import com.akashrajeev.voicebeam.core.DenoiseAlignment
import com.akashrajeev.voicebeam.core.FrameDsp
import com.akashrajeev.voicebeam.core.GateInputs
import com.akashrajeev.voicebeam.core.TargetGate
import com.akashrajeev.voicebeam.core.WavWriter
import com.akashrajeev.voicebeam.ml.AudioModels
import com.akashrajeev.voicebeam.ml.SAMPLE_RATE
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Mic -> noise removal -> target gate -> earphones, with side outputs for
 * captions, the voice fingerprint and recording. Runs on its own thread.
 * When [debugFeed] is set (debug builds only), bundled samples replace the mic.
 */
class AudioPipeline(
    private val models: AudioModels,
    private val app: android.content.Context,
    private val signals: () -> GateInputs,   // latest vision + voice info (voiceActive is filled here)
    private val onError: (Throwable) -> Unit = {},
    private val onFrame: (FrameInfo) -> Unit,
) {
    data class FrameInfo(val level: Float, val gain: Float, val probability: Float, val voiceActive: Boolean, val monitorRoute: String?)
    data class CaptionBlock(val samples: FloatArray, val captureMs: Long, val probability: Float, val voiceActive: Boolean)
    private var capturedSamples = 0L
    val droppedCaptionBlocks: Long get() = asrQueue.dropped

    var enrollmentActive: () -> Boolean = { false }
    var enrollmentStatus: () -> String = { "unknown" }
    @Volatile var quietOthers = 0.8f
    @Volatile var boostDb = 6f
    @Volatile var denoiseMix = .8f          // 0 = raw, 1 = fully denoised
    @Volatile var monitorEnabled = true    // hard-limited to an actual headphone route
    @Volatile var rawWriter: WavWriter? = null
    @Volatile var cleanWriter: WavWriter? = null
    /** Debug builds only: when set, called with the frame size to produce mic input. */
    @Volatile var debugFeed: ((Int) -> FloatArray)? = null

    /**
     * Raw audio for captions (consumer: caption thread). Streaming ASR needs ordered audio, so this is
     * a FIFO, not latest-only. The bound is small (about 1.5 s); on overload the oldest block is dropped
     * and counted. Each block keeps its capture time so the caption timeline stays correct after a drop.
     */
    val asrQueue = DropOldestQueue<CaptionBlock>(ASR_BACKLOG_BLOCKS)
    /**
     * Denoised audio + whether the locked face was clearly talking (consumer: voice-print thread).
     * Voice fingerprints only need recent speech, so overload drops the oldest block here too.
     */
    val voiceQueue = DropOldestQueue<Pair<FloatArray, Float>>(VOICE_BACKLOG_BLOCKS)

    private val gate = TargetGate(frameMs = (models.denoiser.frameShift.takeIf { it > 0 } ?: 256) * 1000f / SAMPLE_RATE)
    private val cleanVad = com.akashrajeev.voicebeam.core.OptionalDiagnostic(
        factory = { com.akashrajeev.voicebeam.ml.NeuralVad(app.assets) },
        release = { it.release() },
        onFailure = { Diagnostics.event("cleanVad_disabled error=" + it.javaClass.simpleName) },
    )
    private val vad = com.akashrajeev.voicebeam.ml.NeuralVad(app.assets)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var dbgFrames = 0L

    @SuppressLint("MissingPermission")
    fun start(useSceneMic: Boolean) {
        if (running.getAndSet(true)) return
        Diagnostics.event("audio_start sceneMic=" + useSceneMic)
        capturedSamples = 0L; asrQueue.clear(); voiceQueue.clear(); vad.reset(); cleanVad.sample { it.reset() }
        thread = Thread({
            try { loop(useSceneMic) }
            catch (t: Throwable) {
                Log.e("VoiceBeamAudio", "audio loop failed", t)
                Diagnostics.event("audio_error=" + t.javaClass.simpleName)
                if (running.get()) onError(t)
            } finally {
                running.set(false)
                try { vad.release() } catch (_: Throwable) {}
                cleanVad.close()
            }
        }, "vb-audio").also { it.start() }
    }

    private fun rmsForCaption(samples: FloatArray): Float {
        var e = 0f; for (v in samples) e += v * v
        return sqrt(e / samples.size.coerceAtLeast(1))
    }

    fun stop() {
        Diagnostics.event("audio_stop")
        running.set(false)
        thread?.join(1500)
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun loop(useSceneMic: Boolean) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frameShift = models.denoiser.frameShift.takeIf { it > 0 } ?: 256
        val dbg = debugFeed
        val rec = if (dbg != null) null else {
            val minRec = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val source = if (useSceneMic) MediaRecorder.AudioSource.CAMCORDER else MediaRecorder.AudioSource.VOICE_RECOGNITION
            try {
                AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(minRec, frameShift * 8))
            } catch (t: Throwable) {
                AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(minRec, frameShift * 8))
            }
        }
        if (rec != null && Build.VERSION.SDK_INT >= 29) {
            try {
                rec.setPreferredMicrophoneDirection(
                    if (useSceneMic) MicrophoneDirection.MIC_DIRECTION_AWAY_FROM_USER else MicrophoneDirection.MIC_DIRECTION_UNSPECIFIED
                )
            } catch (_: Throwable) {}
        }
        val track = if (dbg != null) null else {
            val minPlay = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minPlay, frameShift * 4 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        }
        val audioManager = app.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        fun mediaHeadset(): AudioDeviceInfo? {
            val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            val type = MonitorRoute.chooseType(devices.map { it.type })
            return devices.firstOrNull { it.type == type }
        }
        // Media monitoring is A2DP/LE/wired/USB, never the first enumerated SCO device.
        var preferredOutputId: Int? = null
        val headset = mediaHeadset()
        if (headset != null) {
            val accepted = track?.setPreferredDevice(headset)
            Diagnostics.event("prefer_output=" + headset.type + " accepted=" + accepted)
            preferredOutputId = headset.id
        }
        // Listen to the scene through the phone, not the Bluetooth call microphone.
        val phoneMic = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        if (phoneMic != null) Diagnostics.event("prefer_input=" + phoneMic.type + " accepted=" + rec?.setPreferredDevice(phoneMic))
        track?.setVolume(1f)
        val input = FloatArray(frameShift)
        // Reused every frame. Only data handed to another thread is copied.
        val denoiseIn = FloatArray(frameShift)
        val envelope = com.akashrajeev.voicebeam.core.ListenEnvelope(SAMPLE_RATE)
        val alignment = DenoiseAlignment(maxOf(4096, frameShift * 8))
        var clean = FloatArray(frameShift)
        var gated = FloatArray(frameShift)
        var out = FloatArray(frameShift)
        val silence = FloatArray(frameShift)
        var lastRoute: Int? = null
        var routeLogged = false
        var lastDiagnosticMs = 0L
        try {
            check(rec == null || rec.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not initialize" }
            check(track == null || track.state == AudioTrack.STATE_INITIALIZED) { "Playback could not initialize" }
            rec?.startRecording()
            check(rec == null || rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
            // Prime with SILENCE only. A real route may not exist until the track consumes data.
            // Never use connected/preferred device as permission to send live microphone audio.
            track?.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
            track?.play()
            models.denoiser.reset()
            gate.reset()
            var fedFrames = 0L
            val feedStart = SystemClock.uptimeMillis()
            while (running.get()) {
                if (dbg != null) {
                    val f = dbg(frameShift)
                    System.arraycopy(f, 0, input, 0, minOf(f.size, frameShift))
                    fedFrames++
                    // Pace to real time so captions, lips and the gate line up.
                    val due = feedStart + fedFrames * frameShift * 1000L / SAMPLE_RATE
                    val wait = due - SystemClock.uptimeMillis()
                    if (wait > 0) Thread.sleep(wait)
                } else {
                    var read = 0
                    while (read < frameShift && running.get()) {
                        val n = rec!!.read(input, read, frameShift - read, AudioRecord.READ_BLOCKING)
                        if (n < 0) error("Microphone read failed: $n")
                        if (n == 0) break
                        read += n
                    }
                    if (read < frameShift) continue
                }
                capturedSamples += input.size
                rawWriter?.write(input)

                val denoiseStart = SystemClock.elapsedRealtimeNanos()
                System.arraycopy(input, 0, denoiseIn, 0, input.size)
                alignment.push(input)
                val denoised = try { models.denoiser.process(denoiseIn) } catch (t: Throwable) {
                    alignment.reset(); alignment.push(input); input
                }
                val n = minOf(denoised.size, input.size)
                val mix = denoiseMix
                if (n == 0) {
                    asrQueue.offer(CaptionBlock(input.copyOf(), capturedSamples * 1000 / SAMPLE_RATE,
                        gate.probability, rmsForCaption(input) > .005f))
                    if (enrollmentActive()) voiceQueue.offer(Pair(input.copyOf(), 1f))
                    continue
                }
                if (clean.size < n) { clean = FloatArray(n); gated = FloatArray(n); out = FloatArray(n) }
                // Streaming GTCRN has one warmup frame. Mix the matching buffered raw samples.
                alignment.mix(denoised, mix, clean, n)

                val vadStart = SystemClock.elapsedRealtimeNanos()
                // Identity/detection sees the same raw mic as enrollment, not hearing denoise.
                val voice = vad.isVoice(input)
                val cleanVadStart = SystemClock.elapsedRealtimeNanos()
                val cleanVadProbability = cleanVad.sample {
                    it.isVoice(clean.let { if (it.size == n) it else it.copyOf(n) })
                    it.probability
                }
                val cleanVadUs = (SystemClock.elapsedRealtimeNanos() - cleanVadStart) / 1000
                val rawRms = rmsForCaption(input)
                val gateStart = SystemClock.elapsedRealtimeNanos()
                val s = signals()
                gate.quietOthers = quietOthers
                val observation = com.akashrajeev.voicebeam.core.SpeechObservation.observe(
                    enrollmentActive(), voice, rawRms, s)
                val g = gate.process(observation.inputs)
                val requestedBoost = if (gate.boostAllowed) TargetGate.dbToLinear(boostDb) else 1f
                val boost = FrameDsp.safeBoost(clean, n, g, requestedBoost) // diagnostic estimate; envelope limits actual output
                val e = envelope.process(clean, n, g, requestedBoost, gated, out)
                cleanWriter?.write(gated, n)
                // Speaker playback of a boosted live microphone causes a runaway feedback loop.
                // The actual routed output, not merely a paired headset, must be safe.
                val playStart = SystemClock.elapsedRealtimeNanos()
                val routed = track?.routedDevice
                val headphoneRoute = MonitorRoute.isMediaHeadphone(routed?.type)
                // Keep feeding silence for unknown/speaker routes so routing can settle and recover
                // after headphones are connected. Only an ACTUAL headphone route receives speech.
                val writeSpeech = monitorEnabled && headphoneRoute
                val written = track?.write(MonitorOutput.samples(monitorEnabled, headphoneRoute, out, silence), 0, n, AudioTrack.WRITE_NON_BLOCKING)
                if (written != null && written < 0) error("Playback write failed: $written")
                if (!routeLogged || lastRoute != routed?.id) {
                    Log.i("VoiceBeamAudio", "route type=" + routed?.type + " monitor=" + writeSpeech + " write=" + written)
                    Diagnostics.event("route type=" + routed?.type + " monitor=" + writeSpeech + " write=" + written)
                    lastRoute = routed?.id; routeLogged = true
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastDiagnosticMs >= 1000L) {
                    val end = SystemClock.elapsedRealtimeNanos()
                    val wantedHeadset = mediaHeadset()
                    if (wantedHeadset?.id != preferredOutputId) {
                        val accepted = track?.setPreferredDevice(wantedHeadset)
                        Diagnostics.event("prefer_output=" + wantedHeadset?.type + " accepted=" + accepted)
                        preferredOutputId = wantedHeadset?.id
                    }
                    var outputEnergy = 0f
                    var rawEnergy = 0f
                    for (k in 0 until n) outputEnergy += out[k] * out[k]
                    for (sample in input) rawEnergy += sample * sample
                    Diagnostics.event("audio route=" + routed?.type + " monitor=" + writeSpeech +
                        " inputRoute=" + rec?.routedDevice?.type +
                        " mediaVolume=" + audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) +
                        "/" + audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) +
                        " mediaMuted=" + audioManager.isStreamMute(android.media.AudioManager.STREAM_MUSIC) +
                        " effectiveBoost=" + boost + " boostDb=" + boostDb + " rawRms=" + sqrt(rawEnergy / input.size) +
                        " outputRms=" + sqrt(outputEnergy / n) +
                        " micSamples=" + input.size + " level=" + sqrt(e / n) +
                        " track=ENH tseEnabled=false enrollment=" + enrollmentStatus() +
                        " quietOthers=" + quietOthers + " locked=" + s.hasLock + " visible=" + s.lockedVisible +
                        " lockedLips=" + s.lockedSpeaking + " otherLips=" + s.othersSpeaking +
                        " voiceMatch=" + s.voiceMatch + " wearerMatch=" + s.wearerMatch + " wearerVeto=" + s.wearerVetoEnabled + " boostAllowed=" + gate.boostAllowed +
                        " appliedBoost=" + boost +
                        " gate=" + gate.state + " gain=" + g + " probability=" + gate.probability +
                        " vad=" + voice + " rawVadProb=" + vad.probability + " cleanVadProb=" + cleanVadProbability + " cleanVadUs=" + cleanVadUs +
                        " queryFallback=" + (!voice && observation.queryWeight != null) +
                        " playbackUnderruns=" + track?.underrunCount +
                        " denoiseMix=" + denoiseMix + " visionAgeMs=" + s.visionAgeMs +
                        " voiceQueue=" + voiceQueue.size + " droppedVoiceBlocks=" + voiceQueue.dropped +
                        " audioProcessUptimeMs=" + SystemClock.uptimeMillis() + " written=" + written +
                        " denoiseUs=" + (vadStart - denoiseStart) / 1000 +
                        " vadUs=" + (gateStart - vadStart) / 1000 +
                        " gateUs=" + (playStart - gateStart) / 1000 +
                        " playbackUs=" + (end - playStart) / 1000 +
                        " droppedCaptionBlocks=" + asrQueue.dropped)
                    lastDiagnosticMs = now
                }
                asrQueue.offer(CaptionBlock(input.copyOf(), capturedSamples * 1000 / SAMPLE_RATE,
                    gate.probability, voice))
                // Recognition needs speech, not the gate's sometimes 80%-attenuated output.
                // Assign a caption to the target separately using the gate probability.
                com.akashrajeev.voicebeam.core.SpeechObservation.enqueue(
                    observation, input, voiceQueue)
                onFrame(FrameInfo(sqrt(e / n), g, gate.probability, voice,
                    routed?.productName?.toString().takeIf { headphoneRoute }))
                if (com.akashrajeev.voicebeam.BuildConfig.DEBUG && ++dbgFrames % 400 == 0L) {
                    var er = 0f; for (k in 0 until input.size) er += input[k] * input[k]
                    Log.i("VoiceBeamAudio", "audiodbg dbg=" + (dbg != null) + " in=" + kotlin.math.sqrt(er / input.size) + " clean=" + kotlin.math.sqrt(e / n) + " g=" + g + " route=" + routed?.type + " written=" + written + " monitor=" + writeSpeech)
                }
            }
        } finally {
            try { rec?.stop() } catch (_: Throwable) {}
            rec?.release()
            try { track?.stop() } catch (_: Throwable) {}
            track?.release()
        }
    }
}

/** About 1.5 s of 256-sample blocks at 16 kHz (the old bound was 400 blocks, about 6 s of stale audio). */
const val ASR_BACKLOG_BLOCKS = 96
const val VOICE_BACKLOG_BLOCKS = 32
