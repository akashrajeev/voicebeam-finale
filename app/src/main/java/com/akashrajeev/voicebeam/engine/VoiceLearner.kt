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
) {
    private val chunk = (sampleRate * chunkSeconds).toInt()
    private val enrollBuf = FloatArray(chunk)
    private var enrollFill = 0
    private val scoreBuf = FloatArray(chunk)
    private var scoreFill = 0
    private val queryHop = sampleRate.coerceAtLeast(1).coerceAtMost(chunk)
    private var querySinceScore = 0
    private var queryHasScore = false
    val querySamplesBuffered: Int get() = scoreFill
    private val prints = mutableListOf<FloatArray>()
    @Volatile var lastQueryEmbedding: FloatArray? = null
        private set
    @Volatile var enrollmentEnabled = false
        private set
    @Volatile var centroid: FloatArray? = null
        private set
    @Volatile var enrollmentMessage: String = "Voice not learned"
        private set
    val learned: Boolean get() = centroid != null
    val progress: Float get() = if (learned) 1f else (prints.size + enrollFill / chunk.toFloat()) / needed.toFloat()
    val completedPhrases: Int get() = prints.size

    /** Deliberate clean target enrollment; retain frozen template until explicitly restarted. */
    @Synchronized fun beginEnrollment() {
        reset(); enrollmentEnabled = true; enrollmentMessage="Learning: only this person speaks"
    }

    /** Feed voiced audio. [lipWeight] is how sure we are the locked person is the one talking. Returns a new match score when one is ready. */
    @Synchronized
    fun feed(samples: FloatArray, lipWeight: Float): Float? {
        if (!learned) {
            if (!enrollmentEnabled) return null
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
                        centroid = validEmbedding(VoiceMatch.average(prints))
                        enrollmentEnabled = centroid == null
                        enrollmentMessage = if (centroid != null) "LEARNED" else "Embedding failed: restart learning"
                    }
                }
            }
            return null
        }
        // Full 3-second context, refreshed per voiced second. Never extend score freshness
        // without computing a new embedding, and never modify the enrollment centroid.
        var latest: Float? = null
        var offset = 0
        while (offset < samples.size) {
            val untilDecode = if (queryHasScore) queryHop - querySinceScore else chunk - scoreFill
            val n = minOf(samples.size - offset, untilDecode)
            if (scoreFill + n > chunk) {
                val discard = scoreFill + n - chunk
                System.arraycopy(scoreBuf, discard, scoreBuf, 0, scoreFill - discard)
                scoreFill -= discard
            }
            System.arraycopy(samples, offset, scoreBuf, scoreFill, n)
            scoreFill += n; offset += n; querySinceScore += n
            if (scoreFill == chunk && (!queryHasScore || querySinceScore >= queryHop)) {
                querySinceScore = 0; queryHasScore = true
                val e = validEmbedding(embed(scoreBuf.copyOf()))
                val c = centroid
                if (e != null && c != null && e.size == c.size) {
                    lastQueryEmbedding = e
                    latest = VoiceMatch.score(VoiceMatch.cosine(e, c))
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

    @Synchronized
    fun reset() { enrollmentMessage="Voice not learned (cleared/interrupted)"; lastQueryEmbedding = null; enrollmentEnabled = false; prints.clear(); centroid = null; enrollFill = 0; scoreFill = 0; querySinceScore = 0; queryHasScore = false }
}
