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
data class RecallSession(val id: Long, val start: Long, val title: String, val duration: Long = 0L)

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

object RecallTranscriptQuality {
    fun repeatedLoop(text: String): Boolean {
        val words=text.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        // Only 3 consecutive repeats of a phrase >=5 words. Common Hindi function words remain safe.
        for(n in 5..minOf(24,words.size/3)) {
            for(start in 0..(words.size-3*n)) {
                val phrase=words.subList(start,start+n)
                if(phrase==words.subList(start+n,start+2*n) && phrase==words.subList(start+2*n,start+3*n)) return true
            }
        }
        return false
    }
}

/** Windows are replay anchors, never speaker boundaries. Raw ASR and audio remain unchanged. */
data class RecallTranscriptEntry(val source: RecallSegment, val text: String)
object RecallConversation {
    fun duration(clips: List<RecallSegment>) = clips.maxOfOrNull { it.start + it.duration } ?: 0L
    fun clock(ms: Long): String {
        val seconds=maxOf(0L,ms)/1000
        return if(seconds>=3600) "%d:%02d:%02d".format(seconds/3600,seconds/60%60,seconds%60)
            else "%d:%02d".format(seconds/60,seconds%60)
    }
    fun transcript(clips: List<RecallSegment>): List<RecallTranscriptEntry> {
        var previous: RecallSegment?=null
        return clips.sortedBy { it.start }.mapNotNull { clip ->
            if(clip.text.isBlank()) { previous=null;return@mapNotNull null }
            val before=previous;previous=clip
            val text=if(before!=null && before.session==clip.session && before.start+before.duration>clip.start)
                removeOverlap(before.text,clip.text) else clip.text.trim()
            text.takeIf { it.isNotBlank() }?.let { RecallTranscriptEntry(clip,it) }
        }
    }
    private fun removeOverlap(left: String, right: String): String {
        val a=Regex("\\S+").findAll(left).toList();val b=Regex("\\S+").findAll(right).toList()
        fun key(s: String)=s.lowercase().trim { !it.isLetterOrDigit() }
        // At least two words, capped to the likely one-second overlap. Don't erase whole clips.
        for(n in minOf(12,a.size,b.size-1) downTo 2) {
            if(a.takeLast(n).map { key(it.value) }==b.take(n).map { key(it.value) })
                return right.substring(b[n].range.first).trim()
        }
        return right.trim()
    }
    fun summaryQuestion(question: String): Boolean {
        val q=question.lowercase()
        return Regex("\\b(summar(?:y|ize|ise)|recap|overview|key points)\\b").containsMatchIn(q) ||
            Regex("\\bwhat\\b.*\\b(talk|discuss|do|did|happen|cover|learn|say|said|speak(?:ing)?|spoke)\\b").containsMatchIn(q)
    }
    /** Honest fallback key points: unchanged source excerpts, not invented actions. */
    fun keyPoints(text: String): List<String> = text.split(Regex("(?<=[.!?।])\\s+|\\n+"))
        .map { it.trim() }.filter { it.isNotBlank() }.distinct().take(3)
}

data class RecallCitation(val source: RecallSegment, val quote: String)
data class RecallAnswer(val text: String, val citations: List<RecallCitation>, val generated: Boolean, val fallbackReason: String = "")
