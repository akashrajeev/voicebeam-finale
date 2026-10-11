package com.akashrajeev.voicebeam.core

/** Mirrors upstream decode_one_audio_AV_MossFormer2_TSE_16K (window 3s, stride 0.6*window, trim give_up on seams). */
object AvTseWindowing {
    const val SR = 16000
    const val WINDOW = SR * 3            // 48000
    const val WINDOW_V = 25 * 3          // 75 frames
    const val STRIDE = (WINDOW * 0.6).toInt()   // 28800
    const val GIVE_UP = (WINDOW - STRIDE) / 2   // 9600

    data class Win(val start: Int)

    /** Window start sample indices, exactly as upstream: loop while idx+W<t, then a final window at t-W. t<=W -> single window (caller pads). */
    fun starts(t: Int): List<Int> {
        if (t <= WINDOW) return listOf(0)
        val out = ArrayList<Int>()
        var idx = 0
        while (idx + WINDOW < t) { out.add(idx); idx += STRIDE }
        out.add(t - WINDOW)
        return out
    }

    /** Video frame start index for a given audio start, as upstream: int(idx/16000*25). */
    fun videoStart(audioStart: Int): Int = (audioStart.toDouble() / SR * 25).toInt()

    /** Copy a window's model output into the full output buffer using upstream's seam rules. */
    fun place(out: FloatArray, win: FloatArray, start: Int, isFirst: Boolean, isLast: Boolean) {
        val t = out.size
        if (t <= WINDOW) { System.arraycopy(win, 0, out, 0, minOf(win.size, t)); return }
        when {
            isLast -> System.arraycopy(win, GIVE_UP, out, t - WINDOW + GIVE_UP, WINDOW - GIVE_UP)
            isFirst -> System.arraycopy(win, 0, out, start, WINDOW - GIVE_UP)
            else -> System.arraycopy(win, GIVE_UP, out, start + GIVE_UP, WINDOW - 2 * GIVE_UP)
        }
    }
}
