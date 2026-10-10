package com.akashrajeev.voicebeam.core

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/** Everything the gate knows at one audio frame. */
data class GateInputs(
    val hasLock: Boolean,
    val lockedSpeaking: Float,   // 0..1 from lip movement of the tapped face
    val othersSpeaking: Float,   // 0..1, max over other faces
    val voiceMatch: Float?,      // 0..1 from the voice fingerprint, null when not learned yet
    val voiceActive: Boolean,    // is there speech energy in this frame
    val lockedVisible: Boolean = true, // is the locked face in view right now
    val audioOnly: Boolean = false, // use speaker embedding only; no camera fallback
    val wearerMatch: Float? = null, // fresh score of deliberately enrolled wearer, optional
    val wearerVetoEnabled: Boolean = false,
    val visionAgeMs: Long = -1, // source-frame age; -1 when absent
    val voiceLearned: Boolean = true,
    val voiceQuerySamples: Long = 0,
    val voiceScoreAgeMs: Long = Long.MAX_VALUE,
    val voiceScoreSequence: Long = 0, // increments only for successful fresh query embeddings // complete frozen enrollment, supplied by engine

)

/**
 * Decides, frame by frame, how likely the sound is to be the locked person,
 * and turns that into a smooth gain. Others are turned down by [quietOthers]
 * (0 = leave them, 1 = maximum attenuation (safety floor)). Unknown turns pass enhanced audio without boost.
 * This is turn gating, not separation of overlapping speakers.
 */
enum class TargetState { UNLOCKED, TARGET, OTHER, UNCERTAIN, OVERLAP }

class TargetGate(
    private val frameMs: Float = 10f,
    private val attackMs: Float = 25f,
    private val releaseMs: Float = 60f,
    private val holdMs: Float = 700f,
) {
    @Volatile var tuning = GateTuning()
    private var strictHoldLeft = 0f
        private var lastScoreSequence = 0L
    private var voiceConfirmations = 0
    private var firstHighQuerySamples = 0L
    var state: TargetState = TargetState.UNLOCKED
        private set
    var quietOthers: Float = 0.8f
    var probability: Float = 1f
        private set
    var gain: Float = 1f
        private set
    private var holdLeft = 0f
    private var hadLock = false
    var boostAllowed: Boolean = true
        private set

    fun targetProbability(i: GateInputs): Float {
        val rawState = when {
            !i.hasLock -> TargetState.UNLOCKED
            !i.voiceLearned -> TargetState.UNCERTAIN
            !i.audioOnly && !i.lockedVisible -> TargetState.UNCERTAIN
            !i.audioOnly && i.lockedVisible && i.othersSpeaking > 0.55f && i.lockedSpeaking < 0.3f -> TargetState.OTHER
            !i.voiceActive -> TargetState.UNCERTAIN
            wearerVeto(i) -> TargetState.OTHER
            // A 3-second query can contain several turns. Only a strong negative vetoes lips.
            i.audioOnly -> if ((i.voiceMatch ?: 0f) > tuning.targetThreshold) TargetState.TARGET else if (i.voiceMatch != null && i.voiceMatch < 0.2f) TargetState.OTHER else TargetState.UNCERTAIN
            i.voiceMatch != null && i.voiceMatch < 0.2f -> if (i.lockedVisible && i.lockedSpeaking > 0.55f) TargetState.UNCERTAIN else TargetState.OTHER
            i.lockedSpeaking > 0.55f && i.othersSpeaking > 0.55f -> TargetState.OVERLAP
            i.othersSpeaking > 0.55f && i.lockedSpeaking < 0.3f -> TargetState.OTHER
            i.lockedVisible && i.lockedSpeaking > 0.55f -> TargetState.TARGET
            !i.lockedVisible -> TargetState.UNCERTAIN
            (i.voiceMatch ?: 0f) > tuning.targetThreshold && i.lockedSpeaking > 0.1f -> TargetState.TARGET
            else -> TargetState.UNCERTAIN
        }
        if (tuning.conversationCandidate) {
        val fresh = i.voiceScoreSequence > 0 && i.voiceScoreAgeMs in 0..1000 &&
            i.voiceMatch?.isFinite() == true && i.voiceQuerySamples >= 16000
        val overlapping = i.lockedSpeaking > .55f && i.othersSpeaking > .55f
        val qualified = i.hasLock && i.voiceLearned && i.lockedVisible && !i.audioOnly &&
            !i.wearerVetoEnabled && !wearerVeto(i) && !overlapping
        state = when {
            // Unknown, stale and overlap are not evidence for attenuation or target boost.
            i.hasLock && i.voiceLearned && overlapping -> TargetState.OVERLAP
            i.hasLock && i.voiceLearned && !fresh -> TargetState.UNCERTAIN
            qualified && i.othersSpeaking <= .3f && (i.voiceMatch ?: 0f) >= tuning.targetThreshold -> TargetState.TARGET
            qualified && i.voiceActive && i.lockedSpeaking < .3f && (i.voiceMatch ?: 1f) <= .35f -> TargetState.OTHER
            i.hasLock && i.voiceLearned && rawState == TargetState.TARGET &&
                (i.wearerVetoEnabled || i.othersSpeaking > .3f) -> TargetState.UNCERTAIN
            qualified && rawState == TargetState.TARGET && (i.voiceMatch ?: 0f) < tuning.targetThreshold -> TargetState.UNCERTAIN
            else -> rawState
        }
        } else {
        if (!i.hasLock || !i.voiceLearned || !i.lockedVisible || wearerVeto(i) ||
            i.wearerVetoEnabled || rawState == TargetState.OTHER || i.othersSpeaking > .3f) {
            voiceConfirmations = 0
        }
        if (i.voiceScoreSequence > 0 && i.voiceScoreSequence != lastScoreSequence) {
            lastScoreSequence = i.voiceScoreSequence
            val high = i.hasLock && i.voiceLearned && i.lockedVisible &&
                !i.wearerVetoEnabled && !wearerVeto(i) && i.voiceScoreAgeMs <= 1000 && rawState != TargetState.OTHER &&
                i.othersSpeaking <= .3f && (i.voiceMatch ?: 0f) >= tuning.targetThreshold
            if (high) {
                if (voiceConfirmations == 0) firstHighQuerySamples = i.voiceQuerySamples
                voiceConfirmations = (voiceConfirmations + 1).coerceAtMost(2)
            } else voiceConfirmations = 0
        }
        state = when {
            i.hasLock && i.voiceLearned && i.lockedVisible && i.wearerVetoEnabled ->
                if (rawState == TargetState.TARGET) TargetState.UNCERTAIN else rawState
            i.hasLock && i.voiceLearned && i.lockedVisible && i.othersSpeaking > .3f &&
                rawState == TargetState.TARGET -> TargetState.UNCERTAIN
            rawState == TargetState.UNCERTAIN && voiceConfirmations >= 2 && i.voiceQuerySamples - firstHighQuerySamples >= 24000 && i.voiceScoreAgeMs <= 1000 &&
                (i.voiceMatch ?: 0f) >= tuning.targetThreshold -> TargetState.TARGET
            else -> rawState
        }
        }
        return when (state) {
            TargetState.UNLOCKED -> 1f
            TargetState.TARGET -> if (i.audioOnly) i.voiceMatch ?: 0.95f else 0.95f
            TargetState.OTHER -> if (i.audioOnly) i.voiceMatch ?: 0f else 0f
            TargetState.OVERLAP -> 0.5f // A scalar gate cannot remove an overlapping voice.
            TargetState.UNCERTAIN -> 0f // not target-attributed; listening passes without boost
        }
    }

    private fun wearerVeto(i: GateInputs): Boolean {
        val wearer = i.wearerMatch ?: return false
        val target = i.voiceMatch ?: return false // never block from unconfirmed wearer similarity alone
        return i.wearerVetoEnabled && wearer > 0.9f && wearer - target > 0.15f
    }

    fun process(i: GateInputs): Float {
        // A new lock must not inherit the open, boosted UNLOCKED monitor.
        if (i.hasLock && !hadLock) { gain = 1f; probability = 0f; holdLeft = 0f }
        hadLock = i.hasLock
        val p = targetProbability(i)
        if (!i.hasLock) {
            probability = 1f; holdLeft = 0f
        } else if (i.voiceActive || state == TargetState.TARGET) {
            probability = p
            holdLeft = if (state == TargetState.TARGET) holdMs else 0f
        } else {
            holdLeft = (holdLeft - frameMs).coerceAtLeast(0f)
            // A short gap may hold the last target turn. It must expire, not retain .95 forever.
            if (holdLeft <= 0f) probability = 0f
        }
        val strict = tuning
        val contradictory = state == TargetState.OTHER || state == TargetState.OVERLAP ||
            !i.voiceLearned || (!i.audioOnly && !i.lockedVisible) || !i.hasLock
        strictHoldLeft = when {
            contradictory -> 0f
            state == TargetState.TARGET -> strict.hangoverMs
            else -> (strictHoldLeft - frameMs).coerceAtLeast(0f)
        }
        val fullStrict = strict.strictEnabled && strict.strictFull && i.hasLock && i.voiceLearned
        val fullResidual = fullStrict && state != TargetState.TARGET &&
            state != TargetState.OTHER && strictHoldLeft <= 0f
        val strictResidual = strict.strictEnabled && i.hasLock && i.voiceLearned &&
            i.voiceActive && state == TargetState.UNCERTAIN && strictHoldLeft <= 0f
        val confirmed = !i.hasLock || ((i.audioOnly || i.lockedVisible) &&
            ((i.voiceLearned && state == TargetState.TARGET) ||
                (i.voiceLearned && !i.voiceActive && (if (fullStrict) strictHoldLeft > 0f else holdLeft > 0f))))
        // Provisional general monitor for a visible, not-yet-learned lock.
        // This does not attribute speech to the target or lower the gate gain.
        val provisional = i.hasLock && !i.voiceLearned && !i.audioOnly &&
            i.lockedVisible && state == TargetState.UNCERTAIN
        boostAllowed = confirmed || provisional
        val strength = quietOthers.coerceIn(0f, 1f)
        // Squared residual gives useful suppression despite proximity to the phone mic.
        // Misfire safety floor15% amplitude prevents erasing target on a bad OTHER decision.
        val residual = maxOf(0.15f, (1f - strength) * (1f - strength))
        val fresh = i.voiceScoreSequence > 0 && i.voiceScoreAgeMs in 0..1000 &&
            i.voiceMatch?.isFinite() == true && i.voiceQuerySamples >= 16000
        val safeUnknown = i.hasLock && i.voiceLearned &&
            (!fresh || state == TargetState.UNCERTAIN || state == TargetState.OVERLAP || !i.lockedVisible)
        val wanted = when {
            !i.hasLock -> 1f
            tuning.conversationCandidate && safeUnknown -> 1f
            fullStrict && state == TargetState.OTHER -> residual // preserve explicit OTHER policy
            fullResidual -> strict.residualGain // includes quiet, music, overlap and face loss
            fullStrict && strictHoldLeft > 0f && state != TargetState.TARGET -> 1f
            confirmed -> 1f - strength * (1f - probability)
            strictResidual -> strict.residualGain
            state == TargetState.UNCERTAIN -> 1f // safe unboosted enhancement passthrough
            state == TargetState.OVERLAP && i.voiceActive -> 1f // cannot separate, preserve speech
            else -> residual
        }
        val tau = if (wanted > gain) attackMs else if (strictResidual || fullResidual) strict.releaseMs else releaseMs
        val alpha = 1f - exp(-frameMs / tau)
        gain += (wanted - gain) * alpha
        return gain
    }

    fun reset() {
        state = TargetState.UNLOCKED; probability = 1f; gain = 1f; holdLeft = 0f
        hadLock = false; boostAllowed = true; strictHoldLeft = 0f; lastScoreSequence = 0L; voiceConfirmations = 0
    }

    companion object {
        fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)
        fun linearToDb(v: Float): Float = 20f * ln(v.coerceAtLeast(1e-9f)) / ln(10f)
    }
}

/** Tracks the noise floor and says whether a frame holds speech-level energy. */
class EnergyVad(private val marginDb: Float = 9f) {
    private var floorDb = -60f
    fun isVoice(frame: FloatArray): Boolean {
        var e = 0.0
        for (s in frame) e += (s * s).toDouble()
        val rms = kotlin.math.sqrt(e / frame.size.coerceAtLeast(1)).toFloat()
        val db = TargetGate.linearToDb(rms)
        // Floor follows quiet frames quickly and loud frames very slowly.
        floorDb += if (db < floorDb) (db - floorDb) * 0.2f else (db - floorDb) * 0.002f
        floorDb = floorDb.coerceIn(-90f, -20f)
        return db > floorDb + marginDb && db > -55f
    }
}
