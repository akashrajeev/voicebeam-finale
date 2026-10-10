package com.akashrajeev.voicebeam.core

enum class ConsentPhase { IDLE, ASKING, GRANTED, DECLINED, WITHDRAWN }

/** One hand from the gesture model: label ("Thumb_Up", "Thumb_Down", ...), score, hand centre in normalised image coords. */
data class HandObservation(val label: String, val score: Float, val cx: Float, val cy: Float)

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
    private val holdMs: Long = 1_000,
    private val gapMs: Long = 300,
    private val minGestureScore: Float = 0.6f,
    private val maxHandDistFaceWidths: Float = 1.5f,
    private val bothWindowMs: Long = 5_000,
) {
    /** When true, consent needs BOTH a held thumbs-up and "I agree" within bothWindowMs. */
    @Volatile var requireBoth = false
    /** 0..1 fill of the on-screen ring while a thumbs-up (or thumbs-down) is held. */
    var gestureProgress = 0f
        private set
    private var holdStart = -1L
    private var lastHoldFrame = 0L
    private var holdKind = ""
    private var speechOkAt = -1L
    private var gestureOkAt = -1L
    var phase = ConsentPhase.IDLE
        private set
    var faceId: Int? = null
        private set
    var lastMessage: String = ""
        private set
    private var askedAt = 0L
    private val samples = HashMap<Int, ArrayDeque<Pair<Long, Float>>>()

    @Synchronized fun request(id: Int, nowMs: Long) {
        resetHold(); speechOkAt = -1; gestureOkAt = -1
        phase = ConsentPhase.ASKING; faceId = id; askedAt = nowMs
        lastMessage = "Hold a thumbs-up next to your face for 1 s, or say \"I agree\""
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
                if (requireBoth && !(gestureOkAt >= 0 && nowMs - gestureOkAt <= bothWindowMs)) {
                    speechOkAt = nowMs
                    lastMessage = "Heard you. Now hold a thumbs-up next to your face"
                    return ConsentResult.NeedsRetry("waiting for thumbs-up")
                }
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
        resetHold(); phase = ConsentPhase.WITHDRAWN; faceId = null
        lastMessage = "Permission withdrawn. Unlocked and voice forgotten"
        return if (was == ConsentPhase.GRANTED || was == ConsentPhase.ASKING) ConsentResult.Withdrawn(via) else ConsentResult.Ignored
    }

    /** Lock was lost for another reason (face gone for good). */
    @Synchronized fun lockLost() {
        if (phase == ConsentPhase.GRANTED) { phase = ConsentPhase.IDLE; faceId = null; lastMessage = "Lock ended. Ask again to lock" }
    }

    @Synchronized fun cancel() { resetHold(); phase = ConsentPhase.IDLE; faceId = null; lastMessage = "" }

    private fun resetHold() { holdStart = -1; holdKind = ""; gestureProgress = 0f }

    /**
     * Feed every frame where the gesture model ran. [faces] are the currently visible faces (id, box).
     * Thumb_Up held 1 s next to the asking face grants (or, with requireBoth, half-grants).
     * Thumb_Down held 1 s next to the locked face withdraws. Exactly one hand, nearest to that face.
     * Geometry only: it does not prove whose hand it is.
     */
    @Synchronized fun onHands(nowMs: Long, hands: List<HandObservation>, faces: List<Pair<Int, Box>>): ConsentResult {
        val target = faceId
        val wantLabel = when (phase) { ConsentPhase.ASKING -> "Thumb_Up"; ConsentPhase.GRANTED -> "Thumb_Down"; else -> { resetHold(); return ConsentResult.Ignored } }
        val box = faces.firstOrNull { it.first == target }?.second
        val h = hands.singleOrNull()
        val ok = box != null && h != null && h.label == wantLabel && h.score >= minGestureScore && run {
            val d = Math.hypot((h.cx - box.cx).toDouble(), (h.cy - box.cy).toDouble())
            val near = d <= maxHandDistFaceWidths * box.width
            val nearest = faces.none { it.first != target && Math.hypot((h.cx - it.second.cx).toDouble(), (h.cy - it.second.cy).toDouble()) < d }
            near && nearest
        }
        if (!ok) {
            if (holdStart >= 0 && nowMs - lastHoldFrame > gapMs) resetHold()
            if (hands.size > 1 && phase == ConsentPhase.ASKING) lastMessage = "Only one hand please"
            return ConsentResult.Ignored
        }
        if (holdStart < 0 || holdKind != wantLabel || nowMs - lastHoldFrame > gapMs) { holdStart = nowMs; holdKind = wantLabel }
        lastHoldFrame = nowMs
        gestureProgress = ((nowMs - holdStart).toFloat() / holdMs).coerceIn(0f, 1f)
        if (nowMs - holdStart < holdMs) return ConsentResult.Ignored
        resetHold()
        if (phase == ConsentPhase.GRANTED) return withdraw("thumbs_down")
        // Thumbs-up completed.
        if (requireBoth && !(speechOkAt >= 0 && nowMs - speechOkAt <= bothWindowMs)) {
            gestureOkAt = nowMs
            lastMessage = "Thumbs-up seen. Now say \"I agree\""
            return ConsentResult.NeedsRetry("waiting for speech")
        }
        phase = ConsentPhase.GRANTED
        lastMessage = "Locked with permission"
        return ConsentResult.Granted(target!!, 1f)
    }
}
