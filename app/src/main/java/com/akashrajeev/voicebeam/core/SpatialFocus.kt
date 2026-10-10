package com.akashrajeev.voicebeam.core

import kotlin.math.*

data class SpatialCue(val lagSamples: Float = 0f, val correlation: Float = 0f,
    val uniqueness: Float = 0f, val levelDifferenceDb: Float = 0f,
    val usable: Boolean = false, val reason: String = "no stereo")

/** Relative arrival time, not an angle. Channel order and phone orientation are device-specific. */
object StereoTiming {
    fun estimate(stereo: FloatArray, sampleRate: Int = 48000): SpatialCue {
        if (sampleRate != 48000 || stereo.size < 512 || stereo.size % 2 != 0)
            return SpatialCue(reason = "unsupported block")
        val n = stereo.size / 2
        var el = 0.0; var er = 0.0; var diff = 0.0
        for (k in 0 until n) {
            val l = stereo[2*k].toDouble(); val r = stereo[2*k+1].toDouble()
            if (!l.isFinite() || !r.isFinite()) return SpatialCue(reason = "nonfinite")
            el += l*l; er += r*r; diff += (l-r)*(l-r)
        }
        if (min(el, er) / n < 1e-6) return SpatialCue(reason = "too quiet")
        val db = (10 * log10(el / er)).toFloat()
        if (diff / (el + er) < .002) return SpatialCue(levelDifferenceDb = db, reason = "duplicate channels")
        // First differences remove DC/rumble and reduce broad autocorrelation peaks.
        val correlations = DoubleArray(41)
        for (lag in -20..20) {
            var cross = 0.0; var ll = 0.0; var rr = 0.0
            for (k in 21 until n-21) {
                val l = (stereo[2*k] - stereo[2*(k-1)]).toDouble()
                val j = k + lag
                val r = (stereo[2*j+1] - stereo[2*(j-1)+1]).toDouble()
                cross += l*r; ll += l*l; rr += r*r
            }
            correlations[lag+20] = cross / sqrt(ll*rr).coerceAtLeast(1e-12)
        }
        val peak = correlations.indices.maxByOrNull { correlations[it] }!!
        val second = correlations.indices.filter { abs(it-peak) > 2 }.maxOf { correlations[it] }
        val lag = (peak-20).toFloat(); val c = correlations[peak].toFloat()
        val uniqueness = (correlations[peak]-second).toFloat()
        val valid = c > .65f && uniqueness > .025f && abs(lag) >= 1 && abs(lag) < 20
        return SpatialCue(lag, c, uniqueness, db, valid,
            if (valid) "timing available" else "ambiguous timing")
    }
}

/** Learn a local timing signature ONLY from identity-confirmed solo target speech.
 * Never interprets the mic baseline as camera left/right. Never opens overlapping speech. */
class SpatialFocus {
    private var lock: Int? = null
    private var x = 0f; private var y = 0f
    private var lag = 0f; private var learnedMs = 0f; private var lastLearnAt = -1L
    var status: String = "not calibrated"; private set
    fun reset() { lock = null; learnedMs = 0f; lastLearnAt = -1L; status = "not calibrated" }
    fun update(c: SpatialCue, i: GateInputs, now: Long, frameMs: Float): Float? {
        val cx = i.targetX; val cy = i.targetY
        if (i.lockedFaceId != lock || cx == null || cy == null ||
            !i.lockedVisible || i.visionAgeMs !in 0..399 ||
            (learnedMs > 0 && (abs(cx-x) > .08f || abs(cy-y) > .08f)) ||
            (lastLearnAt >= 0 && now-lastLearnAt > 10000)) {
            reset(); lock = i.lockedFaceId
        }
        if (!i.hasLock || i.audioOnly || cx == null || cy == null || !i.lockedVisible ||
            i.visionAgeMs !in 0..399 || i.lockedFaceId == null) {
            status = "no fresh face lock"; return null
        }
        if (!c.usable) {
            if (learnedMs < 500f) learnedMs = 0f
            status = c.reason; return null
        }
        val freshVoice = i.voiceAgeMs in 0..600 && i.voiceLearned
        if (i.voiceActive && freshVoice && (i.voiceMatch ?: 0f) > .85f &&
            i.lockedSpeaking > .65f && i.othersSpeaking < .2f) {
            if (learnedMs == 0f || abs(c.lagSamples-lag) > 1.5f) {
                lag = c.lagSamples; learnedMs = 0f; x = cx; y = cy
            }
            lag += .1f * (c.lagSamples-lag)
            learnedMs += frameMs.coerceIn(0f, 32f); lastLearnAt = now
        }
        if (learnedMs < 500f && !(i.voiceActive && freshVoice && (i.voiceMatch ?: 0f) > .85f &&
            i.lockedSpeaking > .65f && i.othersSpeaking < .2f)) learnedMs = 0f
        if (learnedMs < 500f) { status = "learning solo target timing"; return null }
        if (!freshVoice || i.visibleFaceCount < 1 || !i.voiceActive) {
            status = "calibrated; waiting for fresh speech"; return null
        }
        if (i.othersSpeaking > .55f && i.lockedSpeaking > .55f) {
            status = "overlap: direction cannot separate"; return null
        }
        val delta = abs(c.lagSamples-lag)
        status = "lag=${c.lagSamples} ref=$lag delta=$delta"
        return when { delta <= 1f -> 1f; delta >= 3f -> -1f; else -> null }
    }
}

/** Stateful 48k -> 16k FIR decimator. Anti-aliases before discarding samples. */
class StereoDownsample {
    private val history = FloatArray(63)
    private var cursor = 0
    private val taps = FloatArray(63).also { t ->
        var sum = 0.0
        for (k in t.indices) {
            val d = k-31; val fc = .145
            val sinc = if (d == 0) 2*fc else sin(2*PI*fc*d)/(PI*d)
            val v = sinc*(.54-.46*cos(2*PI*k/62)); t[k] = v.toFloat(); sum += v
        }
        for (k in t.indices) t[k] = (t[k]/sum).toFloat()
    }
    fun process(stereo: FloatArray, mono: FloatArray) {
        require(stereo.size == mono.size*6)
        for (k in 0 until stereo.size/2) {
            history[cursor] = (stereo[k*2]+stereo[k*2+1])*.5f
            cursor = (cursor+1)%history.size
            if (k%3 == 2) {
                var v = 0f
                for (j in taps.indices) v += taps[j]*history[(cursor-1-j+history.size)%history.size]
                mono[k/3] = v
            }
        }
    }
}


/** Full noise removal only after independent identity + lips + timing agreement.
 * Changes hearing mix, not captions/enrollment. A scalar gain cannot isolate speech. */
object SoloTargetPolicy {
    fun confirmed(i: GateInputs, agreement: Float?): Boolean =
        agreement == 1f && i.hasLock && !i.audioOnly && i.lockedVisible &&
        i.visibleFaceCount == 1 && i.voiceLearned && i.lockedFaceId != null &&
        i.voiceActive && i.visionAgeMs in 0..399 && i.voiceAgeMs in 0..600 &&
        (i.voiceMatch ?: 0f) > .85f && i.lockedSpeaking > .65f && i.othersSpeaking < .2f
    fun mix(user: Float, confirmed: Boolean): Float =
        if (confirmed && user.isFinite() && user > 0f) 1f else user
}
