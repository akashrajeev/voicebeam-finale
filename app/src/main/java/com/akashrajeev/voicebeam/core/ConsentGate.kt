package com.akashrajeev.voicebeam.core

enum class ConsentPhase { IDLE, ASKING, GRANTED, DECLINED, WITHDRAWN }

sealed class ConsentResult {
    object Ignored : ConsentResult()
    data class Granted(val faceId: Int, val lipActive: Float) : ConsentResult()
    data class Refused(val reason: String) : ConsentResult()
    data class NeedsRetry(val reason: String) : ConsentResult()
    data class Withdrawn(val via: String) : ConsentResult()
}

/** What was said, reduced to the few things consent cares about. */
object ConsentPhrases {
    private val agreeWords = setOf("agree", "consent")
    private val negations = setOf("no", "not", "dont", "never", "nope", "cannot", "cant", "wont", "disagree", "refuse", "stop")
    private val withdrawPhrases = listOf(
        "i withdraw", "withdraw consent", "withdraw my consent", "i revoke", "revoke consent",
        "i do not agree", "i dont agree", "i disagree", "stop listening to me", "unlock me",
    )

    fun tokens(text: String): List<String> =
        text.lowercase().replace("'", "").replace("\u2019", "").replace(Regex("[^a-z ]"), " ")
            .split(' ').filter { it.isNotBlank() }

    fun isAgree(text: String): Boolean {
        val t = tokens(text)
        if (t.size > 8) return false              // a long sentence is not a consent phrase
        if (t.any { it in negations }) return false
        return t.zipWithNext().any { (a, b) -> a == "i" && b in agreeWords }
    }

    fun isRefusal(text: String): Boolean {
        val t = tokens(text)
        return t.size <= 8 && t.any { it in negations }
    }

    fun isWithdraw(text: String): Boolean {
        val t = tokens(text)
        if (t.isEmpty() || t.size > 8) return false
        val s = t.joinToString(" ")
        return withdrawPhrases.any { s.contains(it) }
    }
}

/**
 * Permission before lock. Pure logic, all times passed in.
 *
 * VERIFIED by construction: nothing locks without a final ASR result that
 * says "I agree" while a tapped face is asking.
 * NOT VERIFIED: that the voice belongs to that face. We only check that the
 * face's mouth moved during the sentence and no other visible face moved
 * comparably. A lookalike mouth movement or an off-camera speaker can fool it.
 */
class ConsentGate(
    private val askTimeoutMs: Long = 20_000,
    private val windowMs: Long = 3_000,
    private val activeScore: Float = 0.35f,
    private val minActiveFrac: Float = 0.30f,
    private val rivalRatio: Float = 0.6f,
    private val minSamples: Int = 5,
) {
    var phase = ConsentPhase.IDLE
        private set
    var faceId: Int? = null
        private set
    var lastMessage: String = ""
        private set
    private var askedAt = 0L
    private val samples = HashMap<Int, ArrayDeque<Pair<Long, Float>>>()

    @Synchronized fun request(id: Int, nowMs: Long) {
        phase = ConsentPhase.ASKING; faceId = id; askedAt = nowMs
        lastMessage = "Say \"I agree\" on camera to be locked"
    }

    /** Feed every vision frame: face id -> lip score. Faces not in the map are not visible. */
    @Synchronized fun onFaces(nowMs: Long, scores: Map<Int, Float>) {
        for ((id, s) in scores) {
            val q = samples.getOrPut(id) { ArrayDeque() }
            q.addLast(nowMs to s)
        }
        for (q in samples.values) while (q.isNotEmpty() && nowMs - q.first().first > windowMs) q.removeFirst()
        samples.values.removeAll { it.isEmpty() }
        if (phase == ConsentPhase.ASKING && nowMs - askedAt > askTimeoutMs) {
            phase = ConsentPhase.DECLINED; faceId = null
            lastMessage = "Not locked: no permission given"
        }
    }

    private fun frac(id: Int): Pair<Int, Float> {
        val q = samples[id] ?: return 0 to 0f
        if (q.isEmpty()) return 0 to 0f
        return q.size to q.count { it.second >= activeScore }.toFloat() / q.size
    }

    @Synchronized fun onSpeech(nowMs: Long, text: String): ConsentResult {
        when (phase) {
            ConsentPhase.ASKING -> {
                val id = faceId ?: return ConsentResult.Ignored
                if (ConsentPhrases.isRefusal(text)) {
                    phase = ConsentPhase.DECLINED; faceId = null
                    lastMessage = "Not locked: permission refused"
                    return ConsentResult.Refused("refused")
                }
                if (!ConsentPhrases.isAgree(text)) return ConsentResult.Ignored
                val (n, mine) = frac(id)
                if (n < minSamples) { lastMessage = "Face not visible enough, try again"; return ConsentResult.NeedsRetry("face not visible") }
                if (mine < minActiveFrac) { lastMessage = "Could not see your mouth move. Say it again, facing the camera"; return ConsentResult.NeedsRetry("no lip movement") }
                val rival = samples.keys.filter { it != id }.maxOfOrNull { frac(it).second } ?: 0f
                if (rival >= mine * rivalRatio) { lastMessage = "Another face moved too. Only one person should speak"; return ConsentResult.NeedsRetry("ambiguous") }
                phase = ConsentPhase.GRANTED
                lastMessage = "Locked with permission"
                return ConsentResult.Granted(id, mine)
            }
            ConsentPhase.GRANTED -> {
                if (ConsentPhrases.isWithdraw(text)) return withdraw("spoken")
                return ConsentResult.Ignored
            }
            else -> return ConsentResult.Ignored
        }
    }

    @Synchronized fun withdraw(via: String): ConsentResult {
        val was = phase
        phase = ConsentPhase.WITHDRAWN; faceId = null
        lastMessage = "Permission withdrawn. Unlocked and voice forgotten"
        return if (was == ConsentPhase.GRANTED || was == ConsentPhase.ASKING) ConsentResult.Withdrawn(via) else ConsentResult.Ignored
    }

    /** Lock was lost for another reason (face gone for good). */
    @Synchronized fun lockLost() {
        if (phase == ConsentPhase.GRANTED) { phase = ConsentPhase.IDLE; faceId = null; lastMessage = "Lock ended. Ask again to lock" }
    }

    @Synchronized fun cancel() { phase = ConsentPhase.IDLE; faceId = null; lastMessage = "" }
}
