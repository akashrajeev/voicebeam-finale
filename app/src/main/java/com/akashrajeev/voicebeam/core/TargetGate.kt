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
    val voiceLearned: Boolean = true, // complete frozen enrollment, supplied by engine

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
    private val holdMs: Float = 300f,
) {
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
        state = when {
            !i.hasLock -> TargetState.UNLOCKED
            !i.voiceLearned -> TargetState.UNCERTAIN
            !i.audioOnly && i.lockedVisible && i.othersSpeaking > 0.55f && i.lockedSpeaking < 0.3f -> TargetState.OTHER
            !i.voiceActive -> TargetState.UNCERTAIN
            wearerVeto(i) -> TargetState.OTHER
            // A 3-second query can contain several turns. Only a strong negative vetoes lips.
            i.audioOnly -> if ((i.voiceMatch ?: 0f) > 0.8f) TargetState.TARGET else if (i.voiceMatch != null && i.voiceMatch < 0.2f) TargetState.OTHER else TargetState.UNCERTAIN
            i.voiceMatch != null && i.voiceMatch < 0.2f -> if (i.lockedVisible && i.lockedSpeaking > 0.55f) TargetState.UNCERTAIN else TargetState.OTHER
            i.lockedSpeaking > 0.55f && i.othersSpeaking > 0.55f -> TargetState.OVERLAP
            i.othersSpeaking > 0.55f && i.lockedSpeaking < 0.3f -> TargetState.OTHER
            i.lockedVisible && i.lockedSpeaking > 0.55f -> TargetState.TARGET
            !i.lockedVisible -> TargetState.UNCERTAIN
            (i.voiceMatch ?: 0f) > 0.8f && i.lockedSpeaking > 0.3f -> TargetState.TARGET
            else -> TargetState.UNCERTAIN
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
        } else if (i.voiceActive) {
            probability = p
            holdLeft = if (state == TargetState.TARGET) holdMs else 0f
        } else {
            holdLeft = (holdLeft - frameMs).coerceAtLeast(0f)
            // A short gap may hold the last target turn. It must expire, not retain .95 forever.
            if (holdLeft <= 0f) probability = 0f
        }
        val confirmed = !i.hasLock ||
            (i.voiceLearned && i.voiceActive && state == TargetState.TARGET) || (i.voiceLearned && !i.voiceActive && holdLeft > 0f)
        boostAllowed = confirmed
        val strength = quietOthers.coerceIn(0f, 1f)
        // Squared residual gives useful suppression despite proximity to the phone mic.
        // At80% residual is4%; max attenuation keeps2% to avoid completely lost speech.
        val residual = maxOf(0.02f, (1f - strength) * (1f - strength))
        val wanted = when {
            !i.hasLock -> 1f
            confirmed -> 1f - strength * (1f - probability)
            state == TargetState.UNCERTAIN -> 1f // safe unboosted enhancement passthrough
            state == TargetState.OVERLAP && i.voiceActive -> 1f // cannot separate, preserve speech
            else -> residual
        }
        val tau = if (wanted > gain) attackMs else releaseMs
        val alpha = 1f - exp(-frameMs / tau)
        gain += (wanted - gain) * alpha
        return gain
    }

    fun reset() {
        state = TargetState.UNLOCKED; probability = 1f; gain = 1f; holdLeft = 0f
        hadLock = false; boostAllowed = true
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
