package com.akashrajeev.voicebeam.recall

import kotlin.math.sqrt

/** Fixed 25 s windows, 1 s overlap. Final partial audio is always retained. */
class RecallChunker(private val rate: Int = 16000, private val seconds: Int = 25, private val overlap: Int = 1) {
    private val buffer = FloatArray(rate * seconds)
    private var size = 0
    private var offset = 0L
    private var fresh = 0
    init { require(rate > 0 && seconds in 2..30 && overlap in 0 until seconds) }
    fun add(samples: FloatArray, count: Int, emit: (Long, FloatArray) -> Unit) {
        require(count in 0..samples.size)
        var pos = 0
        while (pos < count) {
            val n = minOf(count - pos, buffer.size - size)
            samples.copyInto(buffer, size, pos, pos + n); size += n; fresh += n; pos += n
            if (size == buffer.size) {
                emit(offset * 1000 / rate, buffer.copyOf())
                val keep = overlap * rate
                buffer.copyInto(buffer, 0, buffer.size - keep, buffer.size)
                offset += buffer.size - keep; size = keep; fresh = 0
            }
        }
    }
    fun finish(emit: (Long, FloatArray) -> Unit) {
        if (fresh > 0) emit(offset * 1000 / rate, buffer.copyOf(size))
        size = 0; fresh = 0
    }
}

object RecallGrounding {
    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size && a.isNotEmpty())
        var dot = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
        return if (aa == 0.0 || bb == 0.0) 0f else (dot / sqrt(aa * bb)).toFloat()
    }
    /** Generated claims must be an exact extract from the stated source. */
    fun isExactQuote(quote: String, source: String) = quote.isNotBlank() && source.contains(quote)
}

data class RecallSegment(val id: Long, val session: Long, val start: Long, val duration: Long,
    val path: String, val text: String, val status: String, val speaker: String,
    val vector: FloatArray? = null, val notes: String = "[]", val processingMs: Long = 0L)
data class RecallSession(val id: Long, val start: Long, val title: String)

/** Byte budget is conservative for multilingual byte-fallback tokenization; no text is discarded. */
object RecallTextSlices {
    fun split(text: String, maxBytes: Int = 600): List<String> {
        require(maxBytes>=4)
        val out=mutableListOf<String>();val part=StringBuilder();var bytes=0;var pos=0
        while(pos<text.length) {
            val cp=text.codePointAt(pos);val unit=String(Character.toChars(cp));val n=unit.toByteArray(Charsets.UTF_8).size
            if(bytes+n>maxBytes) { out+=part.toString();part.setLength(0);bytes=0 }
            part.append(unit);bytes+=n;pos+=Character.charCount(cp)
        }
        if(part.isNotEmpty()) out+=part.toString()
        return out
    }
}

/** Conservative near-silence only. This is not speaker identity or a speech/noise classifier. */
object RecallAudioEnergy {
    fun nearSilent(samples: FloatArray): Boolean {
        if(samples.isEmpty()) return true
        var power=0.0;var peak=0f
        samples.forEach { v -> power+=v*v;peak=maxOf(peak,kotlin.math.abs(v)) }
        return kotlin.math.sqrt(power/samples.size)<0.0003 && peak<0.002f
    }
}
