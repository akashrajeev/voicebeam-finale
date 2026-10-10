package com.akashrajeev.voicebeam.engine

import com.akashrajeev.voicebeam.core.VoiceMatch

/**
 * Learns a frozen voice template during explicit raw-mic capture,
 * then scores new speech against it. Pure logic; the embedding function is injected.
 */
class VoiceLearner(
    private val embed: (FloatArray) -> FloatArray?,
    private val sampleRate: Int = 16000,
    private val chunkSeconds: Float = 3f,
    private val needed: Int = 3,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val profile: com.akashrajeev.voicebeam.core.SpeakerProfile = com.akashrajeev.voicebeam.core.SpeakerProfile.DEFAULT,
    private val querySeconds: Float = chunkSeconds,
    private val queryHopSeconds: Float = 1f,
) {
    private val chunk = (sampleRate * chunkSeconds).toInt()
    private val enrollBuf = FloatArray(chunk)
    private var enrollFill = 0
    private val queryChunk = (sampleRate * querySeconds).toInt().coerceAtLeast(1)
    private val scoreBuf = FloatArray(queryChunk)
    private var scoreFill = 0
    private val queryHop = (sampleRate * queryHopSeconds).toInt().coerceIn(1,queryChunk)
    private var querySinceScore = 0
    private var queryHasScore = false
    var queryInputSamples = 0L
        private set
    var scoreQuerySamples = 0L
        private set
    val querySamplesBuffered: Int get() = scoreFill
    private val prints = mutableListOf<FloatArray>()
    private var enrollmentStartedMs = 0L
    private var failedRounds = 0
    private val consistencyCosine = .45f
    @Volatile var lastQueryEmbedding: FloatArray? = null
        private set
    @Volatile var enrollmentEnabled = false
        private set
    @Volatile var centroid: FloatArray? = null
        private set
    @Volatile var enrollmentMessage: String = "Voice not learned"
        private set
    val learned: Boolean get() = centroid != null
    val progress: Float get() = if (learned && !enrollmentEnabled) 1f else (prints.size + enrollFill / chunk.toFloat()) / needed.toFloat()
    val completedPhrases: Int get() = prints.size

    /** Deliberate clean target enrollment; retain frozen template until explicitly restarted. */
    @Synchronized fun beginEnrollment() {
        // Preserve the published profile until a complete, consistent replacement is ready.
        prints.clear(); enrollFill = 0; failedRounds = 0; enrollmentStartedMs = clockMs()
        enrollmentEnabled = true; enrollmentMessage="Learning: only this person speaks"
        clearQuery()
    }

    /** Feed voiced audio. [lipWeight] is how sure we are the locked person is the one talking. Returns a new match score when one is ready. */
    @Synchronized
    fun feed(samples: FloatArray, lipWeight: Float): Float? {
        if (enrollmentEnabled) {
            if (clockMs() - enrollmentStartedMs > 30_000) {
                cancelEnrollment("Enrollment timed out: previous profile kept"); return null
            }
            var off = 0
            while (off < samples.size && enrollmentEnabled) {
                val n = minOf(samples.size - off, chunk - enrollFill)
                System.arraycopy(samples, off, enrollBuf, enrollFill, n)
                off += n; enrollFill += n
                if (enrollFill == chunk) {
                    var energy = 0.0
                    for (v in enrollBuf) energy += v * v
                    if (kotlin.math.sqrt(energy / enrollBuf.size) >= .005) {
                        val embedding = validEmbedding(embed(enrollBuf.copyOf()))
                        if (embedding != null && (prints.isEmpty() || embedding.size == prints[0].size)) {
                            prints.add(embedding); enrollmentMessage = "Captured ${prints.size}/$needed phrases"
                        } else enrollmentMessage = "Embedding failed: try again"
                    } else enrollmentMessage = "Too quiet: move closer and speak alone"
                    enrollFill = 0
                    if (prints.size >= needed) {
                        val consistent = prints.indices.all { x ->
                            (x + 1 until prints.size).all { y -> VoiceMatch.cosine(prints[x], prints[y]) >= consistencyCosine }
                        }
                        val replacement = if (consistent) validEmbedding(VoiceMatch.average(prints)) else null
                        if (replacement != null) {
                            centroid = replacement // atomic published-template replacement
                            enrollmentEnabled = false; enrollmentMessage = "LEARNED"; clearQuery()
                        } else {
                            failedRounds++; prints.clear(); enrollFill = 0
                            if (failedRounds >= 2) cancelEnrollment("Inconsistent captures: previous profile kept")
                            else enrollmentMessage = "Inconsistent captures: speak alone and retry"
                        }
                    }
                }
            }
            return null
        }
        if (!learned) return null
        // Configurable query context/hop, independent of 3-second enrollment. Never extend score freshness
        // without computing a new embedding, and never modify the enrollment centroid.
        var latest: Float? = null
        var offset = 0
        while (offset < samples.size) {
            val untilDecode = if (queryHasScore) queryHop - querySinceScore else queryChunk - scoreFill
            val n = minOf(samples.size - offset, untilDecode)
            if (scoreFill + n > queryChunk) {
                val discard = scoreFill + n - queryChunk
                System.arraycopy(scoreBuf, discard, scoreBuf, 0, scoreFill - discard)
                scoreFill -= discard
            }
            System.arraycopy(samples, offset, scoreBuf, scoreFill, n)
            scoreFill += n; offset += n; querySinceScore += n; queryInputSamples += n
            if (scoreFill == queryChunk && (!queryHasScore || querySinceScore >= queryHop)) {
                querySinceScore = 0; queryHasScore = true
                val e = validEmbedding(embed(scoreBuf.copyOf()))
                val c = centroid
                if (e != null && c != null && e.size == c.size) {
                    scoreQuerySamples = queryInputSamples
                    lastQueryEmbedding = e
                    latest = profile.score(VoiceMatch.cosine(e, c))
                }
            }
        }
        return latest
    }

    private fun validEmbedding(e: FloatArray?): FloatArray? {
        if (e == null || e.isEmpty() || e.any { !it.isFinite() }) return null
        val norm = kotlin.math.sqrt(e.sumOf { it.toDouble() * it })
        if (norm <= 1e-8) return null
        return FloatArray(e.size) { (e[it] / norm).toFloat() }
    }

    /** Retapping preserves the frozen template, but discards the old face's query audio. */
    @Synchronized
    fun clearQuery() { lastQueryEmbedding = null; scoreFill = 0; querySinceScore = 0; queryHasScore = false; queryInputSamples = 0; scoreQuerySamples = 0 }

    @Synchronized fun cancelEnrollment(message: String = "Enrollment cancelled: previous profile kept") {
        enrollmentEnabled = false; prints.clear(); enrollFill = 0; enrollmentMessage = if (learned) message else "Voice not learned"
    }

    @Synchronized
    fun reset() { enrollmentMessage="Voice not learned (cleared/interrupted)"; lastQueryEmbedding = null; enrollmentEnabled = false; prints.clear(); centroid = null; enrollFill = 0; scoreFill = 0; querySinceScore = 0; queryHasScore = false; queryInputSamples = 0; scoreQuerySamples = 0 }
}
