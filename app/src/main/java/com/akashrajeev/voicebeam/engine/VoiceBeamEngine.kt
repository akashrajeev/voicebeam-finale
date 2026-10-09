package com.akashrajeev.voicebeam.engine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.akashrajeev.voicebeam.BuildConfig
import com.akashrajeev.voicebeam.core.CaptionSegment
import com.akashrajeev.voicebeam.core.Captions
import com.akashrajeev.voicebeam.core.FaceObservation
import com.akashrajeev.voicebeam.core.FaceTracker
import com.akashrajeev.voicebeam.core.GateInputs
import com.akashrajeev.voicebeam.core.ListenLifecycle
import com.akashrajeev.voicebeam.core.WavWriter
import com.akashrajeev.voicebeam.ml.AudioModels
import com.akashrajeev.voicebeam.ml.SAMPLE_RATE
import com.akashrajeev.voicebeam.record.MediaExporter
import com.akashrajeev.voicebeam.record.SessionMeta
import com.akashrajeev.voicebeam.record.SessionStore
import com.akashrajeev.voicebeam.stage.StageServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns everything that runs while VoiceBeam is listening: models, the face
 * tracker, the audio pipeline, captions, voice learning, recording and the
 * stage-caption server. UI talks only to this class.
 */
class VoiceBeamEngine(private val app: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settingsStore = SettingsStore(app)
    val sessions = SessionStore(app)

    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private val _sessionList = MutableStateFlow<List<SessionMeta>>(emptyList())
    val sessionList: StateFlow<List<SessionMeta>> = _sessionList.asStateFlow()

    @Volatile private var models: AudioModels? = null
    private val tracker = FaceTracker()
    private var pipeline: AudioPipeline? = null
    private val lifecycle = ListenLifecycle()
    private val loadingModels = AtomicBoolean(false)
    @Volatile private var audioOnly = false
    private var learner: VoiceLearner? = null
    private var wearerLearner: VoiceLearner? = null
    @Volatile private var wearerVetoEnabled = false
    @Volatile private var latestWearerMatch: Float? = null
    private val assembler = CaptionAssembler(SAMPLE_RATE)
    private val workers = AtomicBoolean(false)
    private var captionThread: Thread? = null
    private var voiceThread: Thread? = null
    private var stage: StageServer? = null

    @Volatile private var latestProbability = 1f
    @Volatile private var latestVoiceMatch: Float? = null
    @Volatile private var lastVoiceMatchAtMs = 0L
    @Volatile private var visionClockMs = 0L

    /** Built fresh each time the camera is bound, so quality changes apply. */
    @Volatile var videoCapture: VideoCapture<Recorder>? = null
        private set

    fun buildVideoCapture(): VideoCapture<Recorder> {
        val q = if (_settings.value.hd1080) Quality.FHD else Quality.HD
        val vc = VideoCapture.withOutput(
            Recorder.Builder()
                .setQualitySelector(QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .build()
        )
        videoCapture = vc
        return vc
    }

    fun clearVideoCapture() { videoCapture = null }

    private var videoRecording: Recording? = null
    private var videoFinalized: CompletableDeferredFile? = null
    private var recStartCaptionMs = 0L
    private var recSegments = mutableListOf<CaptionSegment>()
    private var recDir: File? = null
    private var recId: String? = null

    init {
        refreshSessions()
        applyStage(_settings.value.stageEnabled)
    }

    // ---------- models ----------

    fun loadModels() {
        if (models != null || _state.value.modelError != null) return
        if (!loadingModels.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                val m = AudioModels.load(app.assets)
                models = m
                learner = VoiceLearner({ m.voicePrint.embed(it) }, SAMPLE_RATE)
                wearerLearner = VoiceLearner({ m.voicePrint.embed(it) }, SAMPLE_RATE)
                Diagnostics.event("models_ready")
                _state.update { it.copy(modelsReady = true) }
            } catch (t: Throwable) {
                Log.e(TAG, "model load failed", t)
                Diagnostics.event("models_error=" + t.javaClass.simpleName)
                _state.update { it.copy(modelError = t.message ?: t.javaClass.simpleName) }
            } finally {
                loadingModels.set(false)
            }
        }
    }

    // ---------- vision ----------

    fun onFaces(timeMs: Long, faces: List<FaceObservation>, w: Int, h: Int) {
        visionClockMs = timeMs
        val tracked = tracker.update(timeMs, faces)
        val locked = tracker.lockedId
        _state.update {
            it.copy(
                faces = tracked, lockedId = locked, imageWidth = w, imageHeight = h,
                lockedSpeaking = tracked.firstOrNull { f -> f.id == locked }?.speaking ?: 0f,
            )
        }
    }

    fun setMirrored(m: Boolean) = _state.update { it.copy(mirrored = m) }

    /** Tap in normalised image coordinates. Returns true when a face got locked. */
    fun lockAt(nx: Float, ny: Float): Boolean {
        val prev = tracker.lockedId
        val id = tracker.lockAt(nx, ny, SystemClock.uptimeMillis())
        if (id != prev) { learner?.reset(); latestVoiceMatch = null; latestWearerMatch = null; lastVoiceMatchAtMs = 0L }
        _state.update { it.copy(lockedId = id, voiceLearned = learner?.learned == true,
            voiceMatch = latestVoiceMatch, voiceEnrollmentActive = learner?.enrollmentEnabled == true,
            voiceEnrollmentProgress = learner?.progress ?: 0f) }
        return id != null
    }

    fun enrollmentMessage(): String = "Target: " + (learner?.enrollmentMessage ?: "Models not loaded") + " | Wearer: " + (wearerLearner?.enrollmentMessage ?: "Models not loaded")

    fun beginTargetEnrollment() {
        if (tracker.lockedId == null) return
        if (learner == null || !_state.value.listening) return
        if (wearerLearner?.enrollmentEnabled == true) wearerLearner?.reset()
        wearerVetoEnabled = false
        _state.update { it.copy(wearerEnrollmentActive = false, wearerVetoEnabled = false) }
        learner?.beginEnrollment()
        latestVoiceMatch = null; latestWearerMatch = null; lastVoiceMatchAtMs = 0L
        Diagnostics.event("target_enrollment_begin")
        _state.update { it.copy(voiceLearned = false, voiceMatch = null,
            voiceEnrollmentActive = true, voiceEnrollmentProgress = 0f) }
    }

    fun beginWearerEnrollment() {
        if (wearerLearner == null || !_state.value.listening) return
        if (learner?.enrollmentEnabled == true) {
            learner?.reset()
            _state.update { it.copy(voiceEnrollmentActive = false, voiceEnrollmentProgress = 0f, voiceLearned = false) }
        }
        wearerVetoEnabled = false; latestWearerMatch = null
        wearerLearner?.beginEnrollment()
        Diagnostics.event("wearer_enrollment_begin")
        _state.update { it.copy(wearerEnrollmentActive = true, wearerLearned = false,
            wearerEnrollmentProgress = 0f, wearerVetoEnabled = false) }
    }

    fun setWearerVeto(enabled: Boolean) {
        wearerVetoEnabled = enabled && wearerLearner?.learned == true
        _state.update { it.copy(wearerVetoEnabled = wearerVetoEnabled) }
        Diagnostics.event("wearer_veto=" + wearerVetoEnabled)
    }

    fun clearWearerVoice() {
        wearerVetoEnabled = false; latestWearerMatch = null; wearerLearner?.reset()
        _state.update { it.copy(wearerEnrollmentActive = false, wearerLearned = false,
            wearerEnrollmentProgress = 0f, wearerVetoEnabled = false) }
        Diagnostics.event("wearer_enrollment_clear")
    }

    fun unlock() {
        audioOnly = false; tracker.unlock(); learner?.reset(); latestVoiceMatch = null; latestWearerMatch = null; lastVoiceMatchAtMs = 0L
        _state.update { it.copy(lockedId = null, voiceLearned = false, voiceMatch = null, voiceEnrollmentActive = false, voiceEnrollmentProgress = 0f) }
    }

    private fun gateInputs(): GateInputs {
        val now = SystemClock.uptimeMillis()
        val faces = tracker.snapshot(now)
        val locked = tracker.lockedId
        val lockedFace = faces.firstOrNull { it.id == locked }
        val others = faces.filter { it.id != locked }.maxOfOrNull { it.speaking } ?: 0f
        return GateInputs(
            audioOnly = audioOnly,
            hasLock = locked != null,
            lockedSpeaking = if (audioOnly) 0f else lockedFace?.speaking ?: 0f,
            othersSpeaking = if (audioOnly) 0f else others,
            voiceMatch = latestVoiceMatch.takeIf { now - lastVoiceMatchAtMs < 2800L },
            wearerMatch = latestWearerMatch.takeIf { now - lastVoiceMatchAtMs < 1000L },
            wearerVetoEnabled = wearerVetoEnabled,
            voiceActive = false,
            voiceLearned = learner?.learned == true,
            visionAgeMs = lockedFace?.let { now - it.lastSeenMs } ?: -1,
            lockedVisible = !audioOnly && lockedFace != null &&
                com.akashrajeev.voicebeam.core.SpeechObservation.visionFresh(now, lockedFace.lastSeenMs),
        )
    }

    // ---------- listening ----------

    private var pipelineDebugFeed = false

    @Synchronized
    fun startListening() {
        val m = models ?: return
        val s = _settings.value
        val wantDebug = BuildConfig.DEBUG && s.debugFeed
        // The engine is app-scoped and activities come and go (and tests share
        // one engine), so a pipeline may already be running on the other audio
        // source. Restart it when the requested source differs.
        if (pipeline != null) {
            if (pipelineDebugFeed == wantDebug) return
            stopListening()
        }
        if (!lifecycle.beginStart()) return
        try {
        if (BuildConfig.DEBUG) Log.i("VoiceBeamEngine", "startListening source=" + (if (wantDebug) "debug wav" else "mic"))
        lateinit var p: AudioPipeline
        p = AudioPipeline(m, app, ::gateInputs, onError = { t ->
            // Stop/join from another thread, never join the failing audio thread from itself.
            scope.launch {
                synchronized(this@VoiceBeamEngine) {
                    if (pipeline === p) {
                        stopListening()
                        _state.update { it.copy(audioError = t.message ?: "Audio stopped") }
                    }
                }
            }
        }) { f ->
            latestProbability = f.probability
            _state.update { it.copy(inputLevel = f.level, gain = f.gain, targetProbability = f.probability, earphones = f.monitorRoute, overlapNow = f.overlap) }
        }
        p.enrollmentActive = { learner?.enrollmentEnabled == true || wearerLearner?.enrollmentEnabled == true }
        p.enrollmentStatus = { enrollmentMessage() }
        // Cue for the (currently disabled) extraction stage: frozen enrollment
        // fingerprint + locked-face lip activity. Copied so audio-thread use is safe.
        p.embeddingProvider = { try { learner?.centroid?.copyOf() } catch (_: Throwable) { null } }
        p.quietOthers = s.quietOthers; p.boostDb = s.boostDb; p.denoiseMix = s.denoise
        pipeline = p
        pipelineDebugFeed = wantDebug
        if (wantDebug) {
            val feed = DebugAudioFeed(app)
            p.debugFeed = feed::next
        }
        p.start(s.useSceneMic)
        startWorkers(p, m)
        _state.update { it.copy(listening = true, audioError = null, earphones = earphoneName()) }
        lifecycle.finishStart()
        } catch (t: Throwable) {
            Log.e(TAG, "startListening failed", t)
            lifecycle.abortStart()
            workers.set(false)
            pipeline?.stop(); pipeline = null
            throw t
        }
    }

    @Synchronized
    fun stopListening() {
        if (!lifecycle.beginStop()) return
        if (_state.value.recording.active) stopRecording()
        workers.set(false)
        audioOnly = false
        _state.update { it.copy(audioOnly = false) }
        pipeline?.stop(); pipeline = null
        captionThread?.join(1500); voiceThread?.join(1500)
        captionThread = null; voiceThread = null
        _state.update { it.copy(listening = false, partial = "", inputLevel = 0f, overlapNow = false) }
        lifecycle.finishStop()
    }

    /**
     * Frees the native models once. Safe to call repeatedly; loadModels() can load them again later.
     * Stops listening first so no worker is still using them.
     */
    @Synchronized
    fun releaseModels() {
        stopListening()
        val m = models ?: return
        models = null; learner = null; wearerLearner = null; clearWearerVoice()
        try { m.release() } catch (t: Throwable) { Log.w(TAG, "model release failed", t) }
        _state.update { it.copy(modelsReady = false) }
    }

    private var diagnosticAsrBlocks = 0
    private var diagnosticVoiceAt = 0L
    private var dbgBlocks = 0
    private var capWallStart = 0L

    private fun startWorkers(p: AudioPipeline, m: AudioModels) {
        workers.set(true)
        dbgBlocks = 0
        capWallStart = SystemClock.uptimeMillis()
        assembler.reset()
        m.asr.resetStream()
        captionThread = Thread({
            val block = FloatArray(1600)
            var fill = 0
            while (workers.get()) {
                val packet = p.asrQueue.poll(200) ?: continue
                val f = packet.samples
                var off = 0
                while (off < f.size) {
                    val n = minOf(f.size - off, block.size - fill)
                    System.arraycopy(f, off, block, fill, n); fill += n; off += n
                    if (fill == block.size) {
                        val speechy = packet.voiceActive
                        val asrStart = SystemClock.elapsedRealtimeNanos()
                        val (text, ended) = try { m.asr.accept(block.copyOf()) } catch (t: Throwable) {
                            Diagnostics.event("asr_error=" + t.javaClass.simpleName); Pair("", false)
                        }
                        if (++diagnosticAsrBlocks % 10 == 0) Diagnostics.event("asrUs=" + (SystemClock.elapsedRealtimeNanos() - asrStart) / 1000 + " queue=" + p.asrQueue.size)
                        assembler.advanceTo(maxOf(0L, packet.captureMs * SAMPLE_RATE / 1000 - (f.size - off) - block.size))
                        val seg = assembler.onBlock(block.size, text, ended, packet.probability, speechy)
                        fill = 0
                        publishCaption(seg)
                        if (BuildConfig.DEBUG) {
                            if (seg != null) Log.i("VoiceBeamEngine", "caption seg: '" + seg.text + "' target=" + seg.isTarget)
                            if (seg != null) Log.i("VoiceBeamPerf", "caplat ms=" + (SystemClock.uptimeMillis() - capWallStart - seg.endMs))
                            if (++dbgBlocks % 50 == 0) Log.i("VoiceBeamEngine", "capdbg partial='" + assembler.partial.take(60) + "' text='" + text.take(40) + "' prob=" + latestProbability + " learned=" + (learner?.learned == true) + " rms=" + rms(block))
                        }
                    }
                }
            }
        }, "vb-captions").also { it.start() }
        voiceThread = Thread({
            while (workers.get()) {
                val (samples, lip) = p.voiceQueue.poll(200) ?: continue
                val wearer = wearerLearner
                if (wearer?.enrollmentEnabled == true) {
                    // Deliberate wearer-only capture; do not contaminate target enrollment.
                    try { wearer.feed(samples, 1f) } catch (t: Throwable) {
                        Diagnostics.event("wearer_enrollment_error=" + t.javaClass.simpleName)
                        clearWearerVoice()
                    }
                    _state.update { it.copy(wearerEnrollmentActive = wearer.enrollmentEnabled,
                        wearerEnrollmentProgress = wearer.progress, wearerLearned = wearer.learned) }
                    continue
                }
                val l = learner ?: continue
                if (tracker.lockedId == null) continue
                val wasLearned = l.learned
                val speakerStart = SystemClock.elapsedRealtimeNanos()
                val score = try { l.feed(samples, lip) } catch (t: Throwable) {
                    Diagnostics.event("speaker_error=" + t.javaClass.simpleName); null
                }
                val speakerNow = SystemClock.elapsedRealtime()
                if (speakerNow - diagnosticVoiceAt >= 1000) {
                    Diagnostics.event("speakerUs=" + (SystemClock.elapsedRealtimeNanos() - speakerStart) / 1000 + " learned=" + l.learned + " enrollmentActive=" + l.enrollmentEnabled + " enrollmentProgress=" + l.progress + " completedPhrases=" + l.completedPhrases + " match=" + score + " querySamples=" + l.querySamplesBuffered + " scoreAgeMs=" + (if (lastVoiceMatchAtMs > 0) SystemClock.uptimeMillis() - lastVoiceMatchAtMs else -1))
                    diagnosticVoiceAt = speakerNow
                }
                if (score != null) {
                    latestVoiceMatch = score; lastVoiceMatchAtMs = SystemClock.uptimeMillis()
                    val query = l.lastQueryEmbedding
                    val wearerTemplate = wearer?.centroid
                    latestWearerMatch = if (query != null && wearerTemplate != null)
                        com.akashrajeev.voicebeam.core.VoiceMatch.score(com.akashrajeev.voicebeam.core.VoiceMatch.cosine(query, wearerTemplate)) else null
                }
                if (BuildConfig.DEBUG && (!wasLearned && l.learned || score != null)) Log.i("VoiceBeamEngine", "voice: learned=" + l.learned + " progress=" + l.progress + " score=" + score + " lip=" + lip)
                if (l.learned != _state.value.voiceLearned || score != null || l.progress != _state.value.voiceEnrollmentProgress || l.enrollmentEnabled != _state.value.voiceEnrollmentActive) {
                    _state.update { it.copy(voiceLearned = l.learned, voiceMatch = latestVoiceMatch, voiceEnrollmentActive = l.enrollmentEnabled, voiceEnrollmentProgress = l.progress) }
                }
            }
        }, "vb-voice").also { it.start() }
    }

    private fun publishCaption(seg: CaptionSegment?) {
        if (seg != null && _state.value.recording.active) {
            recSegments.add(seg.copy(startMs = maxOf(0, seg.startMs - recStartCaptionMs), endMs = maxOf(0, seg.endMs - recStartCaptionMs)))
        }
        _state.update {
            val segs = if (seg != null) (it.segments + seg).takeLast(200) else it.segments
            it.copy(segments = segs, partial = assembler.partial, partialIsTarget = assembler.partialIsTarget)
        }
    }

    fun clearCaptions() = _state.update { it.copy(segments = emptyList(), partial = "") }

    // ---------- settings ----------

    fun updateSettings(f: (Settings) -> Settings) {
        val old = _settings.value
        val s = f(old)
        _settings.value = s
        settingsStore.save(s)
        pipeline?.let { it.quietOthers = s.quietOthers; it.boostDb = s.boostDb; it.denoiseMix = s.denoise }
        if (s.stageEnabled != old.stageEnabled) applyStage(s.stageEnabled)
        if (s.useSceneMic != old.useSceneMic && pipeline != null) { stopListening(); startListening() }
        // Demo feed toggles swap the audio source too (recorded wav vs mic).
        if (BuildConfig.DEBUG && s.debugFeed != old.debugFeed && pipeline != null) { stopListening(); startListening() }
    }

    /** The camera may be released only after the tapped person's voice is learned. */
    fun enterAudioOnly(): Boolean {
        // Held-out different speakers scored the same as the target with the
        // current CAM++ calibration. Do not allow unsafe camera-free isolation.
        return false
    }

    fun exitAudioOnly() {
        audioOnly = false
        // Camera retargeting needs a fresh tap; do not silently attach to a new face.
        tracker.reset(); learner?.reset(); latestVoiceMatch = null; latestWearerMatch = null; lastVoiceMatchAtMs = 0L
        _state.update { it.copy(audioOnly = false, lockedId = null, voiceLearned = false, voiceMatch = null, voiceEnrollmentActive = false, voiceEnrollmentProgress = 0f) }
    }

    fun setMonitor(on: Boolean) { pipeline?.monitorEnabled = on }

    fun earphoneName(): String? {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val out = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val wanted = com.akashrajeev.voicebeam.core.MonitorRoute.chooseType(out.map { it.type })
        val d = out.firstOrNull { it.type == wanted } ?: return null
        return d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Earphones"
    }

    fun refreshEarphones() = _state.update { it.copy(earphones = earphoneName()) }

    // ---------- stage captions ----------

    private fun applyStage(on: Boolean) {
        stage?.stop(); stage = null
        if (!on) { _state.update { it.copy(stageUrl = null) }; return }
        try {
            val srv = StageServer(StageServer.PORT) {
                val st = _state.value
                val rec = st.recording
                val clock = if (rec.active) Captions.clock(SystemClock.elapsedRealtime() - rec.startedAtMs) else ""
                val lines = st.segments.takeLast(3).filter { _settings.value.showOthersCaptions || it.isTarget }.map { Pair(it.text, it.isTarget) }
                StageServer.state(lines, st.partial, rec.active, clock, st.lockedId != null, (_settings.value.quietOthers * 100).toInt())
            }
            srv.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            stage = srv
            val ip = StageServer.localIp()
            _state.update { it.copy(stageUrl = ip?.let { a -> "http://$a:${StageServer.PORT}" } ?: "http://<phone-ip>:${StageServer.PORT}") }
        } catch (t: Throwable) {
            Log.w(TAG, "stage server failed", t)
            _state.update { it.copy(stageUrl = null) }
        }
    }

    fun refreshStageUrl() { if (stage != null) applyStage(true) }

    // ---------- recording ----------

    @SuppressLint("MissingPermission")
    fun startRecording(mode: SaveMode, videoBound: Boolean) {
        val p = pipeline ?: return
        if (_state.value.recording.active) return
        val (id, dir) = sessions.newSessionDir()
        recId = id; recDir = dir
        recSegments = mutableListOf()
        recStartCaptionMs = assembler.nowMs
        val keepRaw = _settings.value.keepRawAudio
        if (mode != SaveMode.CAPTIONS) {
            p.cleanWriter = WavWriter(File(dir, "clean.wav"), SAMPLE_RATE)
            if (keepRaw) p.rawWriter = WavWriter(File(dir, "raw.wav"), SAMPLE_RATE)
        }
        val effectiveMode = if (mode == SaveMode.AUDIO_VIDEO && (!videoBound || videoCapture == null)) SaveMode.AUDIO else mode
        if (effectiveMode == SaveMode.AUDIO_VIDEO) {
            val done = CompletableDeferredFile()
            videoFinalized = done
            val file = File(dir, "camera.mp4")
            videoRecording = videoCapture!!.output
                .prepareRecording(app, FileOutputOptions.Builder(file).build())
                .start(ContextCompat.getMainExecutor(app)) { e ->
                    if (e is VideoRecordEvent.Finalize) done.complete(if (e.hasError() && e.error != VideoRecordEvent.Finalize.ERROR_NONE && !file.exists()) null else file)
                }
        }
        val note = if (effectiveMode != mode) "Camera not ready, saving audio only" else null
        _state.update { it.copy(recording = RecordingState(true, effectiveMode, SystemClock.elapsedRealtime(), id, false, note)) }
    }

    fun stopRecording() {
        val p = pipeline
        val rec = _state.value.recording
        if (!rec.active) return
        val dir = recDir ?: return
        val id = recId ?: return
        val durationMs = SystemClock.elapsedRealtime() - rec.startedAtMs
        val clean = p?.cleanWriter; val raw = p?.rawWriter
        p?.cleanWriter = null; p?.rawWriter = null
        clean?.close(); raw?.close()
        videoRecording?.stop(); videoRecording = null
        val videoDone = videoFinalized; videoFinalized = null
        // Include the caption still in progress.
        val tail = assembler.partial
        if (tail.isNotBlank()) {
            val now = maxOf(0, assembler.nowMs - recStartCaptionMs)
            recSegments.add(CaptionSegment(maxOf(0, now - 1500), now, tail, assembler.partialIsTarget))
        }
        val segs = recSegments.toList()
        val burn = _settings.value.captionBurn
        _state.update { it.copy(recording = rec.copy(active = false, exporting = true, lastMessage = "Saving...")) }
        scope.launch(Dispatchers.IO) {
            var message: String
            val capLabel = when (rec.mode) {
                SaveMode.AUDIO_VIDEO -> burn.name.lowercase()
                SaveMode.CAPTIONS -> "srt"
                SaveMode.AUDIO -> "srt"
            }
            val meta = SessionMeta(id, defaultTitle(), System.currentTimeMillis(), durationMs, rec.mode, capLabel, dir)
            try {
                when (rec.mode) {
                    SaveMode.AUDIO -> {
                        MediaExporter.wavToM4a(meta.cleanWav, meta.cleanAudio)
                        message = "Saved clean audio"
                    }
                    SaveMode.AUDIO_VIDEO -> {
                        val cam = videoDone?.await(15_000)
                        MediaExporter.wavToM4a(meta.cleanWav, meta.cleanAudio)
                        if (cam != null && cam.exists() && cam.length() > 0) {
                            val merged = File(dir, "merged.mp4")
                            MediaExporter.muxVideoWithWav(cam, meta.cleanWav, merged)
                            if (burn == CaptionBurn.BURNED && segs.isNotEmpty()) {
                                try {
                                    MediaExporter.burnCaptions(app, merged, meta.video, segs)
                                    merged.delete()
                                } catch (t: Throwable) {
                                    Log.w(TAG, "caption burn failed, keeping plain video + srt", t)
                                    merged.renameTo(meta.video)
                                }
                            } else merged.renameTo(meta.video)
                            cam.delete()
                            message = "Saved video with clean audio"
                        } else {
                            message = "Video failed, saved clean audio"
                        }
                    }
                    SaveMode.CAPTIONS -> message = "Saved captions"
                }
            } catch (t: Throwable) {
                Log.e(TAG, "export failed", t)
                message = "Saved with problems: ${t.message}"
            }
            sessions.writeMeta(meta, segs)
            refreshSessions()
            _state.update { it.copy(recording = it.recording.copy(exporting = false, lastMessage = message)) }
            // Save results are a short notice, not a permanent banner.
            kotlinx.coroutines.delay(4000)
            _state.update { st -> if (st.recording.lastMessage == message && !st.recording.exporting) st.copy(recording = st.recording.copy(lastMessage = null)) else st }
        }
    }

    fun dismissRecordingMessage() = _state.update { it.copy(recording = it.recording.copy(lastMessage = null)) }

    fun refreshSessions() { _sessionList.value = sessions.list() }
    fun deleteSession(m: SessionMeta) { sessions.delete(m); refreshSessions() }
    fun renameSession(m: SessionMeta, title: String) { sessions.rename(m, title); refreshSessions() }

    private fun defaultTitle(): String =
        java.text.SimpleDateFormat("EEE d MMM, h:mm a", java.util.Locale.getDefault()).format(java.util.Date())

    fun shutdown() { releaseModels(); stage?.stop() }

    companion object {
        private const val TAG = "VoiceBeamEngine"
        fun rms(a: FloatArray): Float { var e = 0f; for (v in a) e += v * v; return kotlin.math.sqrt(e / a.size) }
    }
}

/** Minimal blocking future for the camera file. */
class CompletableDeferredFile {
    private val latch = java.util.concurrent.CountDownLatch(1)
    @Volatile private var value: File? = null
    fun complete(f: File?) { value = f; latch.countDown() }
    fun await(timeoutMs: Long): File? { latch.await(timeoutMs, TimeUnit.MILLISECONDS); return value }
}
