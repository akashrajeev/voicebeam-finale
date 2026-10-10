package com.akashrajeev.voicebeam.footage

import kotlin.math.sqrt

data class FootageWindow(val start: Long, val end: Long, val group: Int, val level: Float, val similarity: Float)
data class FootageGroup(val id: Int, val name: String, val windows: List<FootageWindow>) {
    val speechMs get()=windows.sumOf { it.end-it.start }
    val level get()=if(windows.isEmpty()) 0f else windows.sumOf { it.level.toDouble() }.toFloat()/windows.size
}
data class FootageLine(val start: Long, val end: Long, val group: Int, val text: String, val status: String)
data class FootageClip(val id: String, val title: String, val video: String, val audio: String, val duration: Long,
    val status: String, val windows: List<FootageWindow> = emptyList(), val names: Map<Int,String> = emptyMap(), val selected: Int? = null,
    val lines: List<FootageLine> = emptyList()) {
    fun groups()=windows.groupBy { it.group }.map { (id,windows) -> FootageGroup(id,names[id]?:if(id<0) "Uncertain audio" else if(id<26) "Voice ${('A'.code+id).toChar()}" else "Audio group ${id+1}",windows) }.sortedByDescending { it.level }
}

/** Conservative audio similarity groups, not identified people or reliable overlap separation. */
class FootageClusterer(private val threshold: Float=0.8f,private val maxGroups: Int=6) {
    private data class Cluster(var center: FloatArray, var count: Int)
    private val clusters=mutableListOf<Cluster>()
    fun assign(vector: FloatArray?): Pair<Int,Float> {
        if(vector==null || vector.isEmpty() || vector.any { !it.isFinite() } || vector.all { it==0f }) return -1 to 0f
        val normalized=normalize(vector)
        val best=clusters.withIndex().filter { it.value.center.size==vector.size }.maxByOrNull { cosine(normalized,it.value.center) }
        val score=best?.let { cosine(normalized,it.value.center) }?:0f
        if(best!=null && score>=threshold) {
            val cluster=best.value
            cluster.center=normalize(FloatArray(vector.size) { (cluster.center[it]*cluster.count+normalized[it])/(cluster.count+1) })
            cluster.count++;return best.index to score
        }
        if(clusters.size>=maxGroups) return -1 to score
        clusters+=Cluster(normalized,1);return clusters.lastIndex to 1f
    }
    companion object {
        fun normalize(a: FloatArray): FloatArray { val norm=sqrt(a.sumOf { it.toDouble()*it });return if(norm<=0) a.copyOf() else FloatArray(a.size) { (a[it]/norm).toFloat() } }
        fun cosine(a: FloatArray,b: FloatArray): Float {
            if(a.size!=b.size || a.isEmpty()) return 0f
            val x=normalize(a);val y=normalize(b);return x.indices.sumOf { x[it].toDouble()*y[it] }.toFloat().coerceIn(-1f,1f)
        }
    }
}
/** Full-clip average-link clustering. Threshold is cosine SIMILARITY, not distance. */
object FootageOfflineGroups {
    fun assign(vectors: List<FloatArray?>,threshold: Float=0.3f,maxGroups: Int=6): List<Pair<Int,Float>> {
        require(threshold in -1f..1f && maxGroups>0)
        val valid=vectors.indices.filter { i -> vectors[i]?.let { it.isNotEmpty() && it.all { v -> v.isFinite() } && it.any { v -> v!=0f } }==true }
        val normalized=valid.associateWith { FootageClusterer.normalize(vectors[it]!!) }
        val clusters=valid.map { mutableListOf(it) }.toMutableList()
        fun score(a: List<Int>,b: List<Int>): Float = a.sumOf { x -> b.sumOf { y -> FootageClusterer.cosine(normalized.getValue(x),normalized.getValue(y)).toDouble() } }.toFloat()/(a.size*b.size)
        val distances=Array(clusters.size) { a -> FloatArray(clusters.size) { b -> if(a==b) -2f else score(clusters[a],clusters[b]) } }
        val active=BooleanArray(clusters.size) { true }
        while(true) {
            var best=-2f;var left=-1;var right=-1
            for(a in clusters.indices) if(active[a]) for(b in a+1 until clusters.size) if(active[b]) {
                if(distances[a][b]>best) { best=distances[a][b];left=a;right=b }
            }
            if(left<0 || best<threshold) break
            val na=clusters[left].size;val nb=clusters[right].size
            for(k in clusters.indices) if(active[k] && k!=left && k!=right) {
                val merged=(distances[left][k]*na+distances[right][k]*nb)/(na+nb)
                distances[left][k]=merged;distances[k][left]=merged
            }
            clusters[left].addAll(clusters[right]);active[right]=false
        }
        // Cap display groups by support, not early arrival; never force unlike audio together.
        val kept=clusters.filterIndexed { i,_ -> active[i] }.sortedWith(compareByDescending<MutableList<Int>> { it.size }.thenBy { it.minOrNull() }).take(maxGroups).sortedBy { it.minOrNull() }
        return vectors.indices.map { index ->
            val group=kept.indexOfFirst { index in it }
            if(group<0) -1 to 0f else {
                val others=kept[group].filter { it!=index }
                group to if(others.isEmpty()) 1f else score(listOf(index),others)
            }
        }
    }
}
object FootageTimeline {
    /** Join adjacent same-group slices. Keep original timeline, never stitch across a gap. */
    fun ranges(windows: List<FootageWindow>, group: Int): List<Pair<Long,Long>> {
        val out=mutableListOf<Pair<Long,Long>>()
        windows.filter { it.group==group }.sortedBy { it.start }.forEach { window ->
            val last=out.lastOrNull()
            if(last!=null && window.start<=last.second) out[out.lastIndex]=last.first to maxOf(last.second,window.end)
            else out+=window.start to window.end
        }
        return out.flatMap { (start,end) -> buildList { var pos=start;while(pos<end) { val next=minOf(end,pos+25000);add(pos to next);pos=next } } }
    }
    fun merge(clip: FootageClip,from: Int,into: Int): FootageClip {
        require(from!=into && clip.windows.any { it.group==from } && clip.windows.any { it.group==into })
        return clip.copy(windows=clip.windows.map { if(it.group==from) it.copy(group=into) else it },
            names=clip.names-from,selected=if(clip.selected==from) into else clip.selected,
            // Attribution boundaries changed. Regenerate instead of silently relabeling mixed text.
            lines=clip.lines.filter { it.group!=from && it.group!=into })
    }
}
/** Continuous-phase rate conversion for decoder blocks, mono input only. */
class FootageResampler(private val inputRate: Int,private val outputRate: Int=16000) {
    private var inputIndex=0L;private var next=0.0;private var previous=0f
    init { require(inputRate>0 && outputRate>0) }
    fun add(samples: FloatArray): FloatArray {
        val out=ArrayList<Float>((samples.size.toLong()*outputRate/inputRate+2).toInt())
        for(value in samples) {
            while(next<=inputIndex) {
                if(inputIndex==0L) out+=value else {
                    val fraction=(next-(inputIndex-1)).coerceIn(0.0,1.0).toFloat()
                    out+=previous+(value-previous)*fraction
                }
                next+=inputRate.toDouble()/outputRate
            }
            previous=value;inputIndex++
        }
        return out.toFloatArray()
    }
}
