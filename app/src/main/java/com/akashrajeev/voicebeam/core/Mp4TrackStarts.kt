package com.akashrajeev.voicebeam.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Only bounded MP4 metadata is read. Android may discard leading empty edits. */
object Mp4TrackStarts {
    data class Starts(val audioUs: Long = 0, val videoUs: Long = 0)
    private data class Box(val type: String, val body: Int, val end: Int)
    fun read(file: File): Starts {
        RandomAccessFile(file, "r").use { f ->
            var pos=0L;var boxes=0
            while(pos+8<=f.length()) {
                require(++boxes<=10000){"Unsupported MP4 metadata"}
                f.seek(pos);var size=f.readInt().toLong() and 0xffffffffL
                val type=ByteArray(4);f.readFully(type);var header=8L
                if(size==1L){size=f.readLong();header=16L}
                if(size==0L)size=f.length()-pos
                require(size>=header && size<=f.length()-pos){"Invalid MP4 metadata"}
                if(String(type,Charsets.US_ASCII)=="moov"){
                    require(size-header<=16*1024*1024){"Unsupported MP4 metadata"}
                    val bytes=ByteArray((size-header).toInt());f.readFully(bytes)
                    return parse(bytes)
                }
                pos+=size
            }
        }
        return Starts() // Non-MP4 containers retain extractor timestamps.
    }
    internal fun parse(bytes: ByteArray): Starts {
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        var nodes=0
        fun boxes(from:Int,to:Int):List<Box>{
            val result=ArrayList<Box>();var p=from
            while(p+8<=to){
                require(++nodes<=10000){"Unsupported MP4 metadata"}
                var n=b.getInt(p).toLong() and 0xffffffffL;var h=8
                val t=String(bytes,p+4,4,Charsets.US_ASCII)
                if(n==1L){require(p+16<=to){"Invalid MP4 metadata"};n=b.getLong(p+8);h=16}
                if(n==0L)n=(to-p).toLong()
                require(n>=h && n<=to-p){"Invalid MP4 metadata"}
                result.add(Box(t,p+h,p+n.toInt()));p+=n.toInt()
            }
            require(p==to){"Invalid MP4 metadata"};return result
        }
        val root=boxes(0,bytes.size)
        val mvhd=root.firstOrNull{it.type=="mvhd"}?:return Starts()
        fun version(box:Box):Int{require(box.end-box.body>=4){"Invalid MP4 metadata"};return bytes[box.body].toInt() and 255}
        val tv=version(mvhd);require(tv<=1){"Unsupported MP4 metadata"}
        val scaleAt=mvhd.body+if(tv==1)20 else 12
        require(scaleAt+4<=mvhd.end){"Invalid MP4 metadata"}
        val scale=b.getInt(scaleAt).toLong() and 0xffffffffL;require(scale>0){"Invalid MP4 timescale"}
        fun delay(track:Box):Pair<String,Long>?{
            val children=boxes(track.body,track.end)
            val mdia=children.firstOrNull{it.type=="mdia"}?:return null
            val handler=boxes(mdia.body,mdia.end).firstOrNull{it.type=="hdlr"}?:return null
            require(handler.body+12<=handler.end){"Invalid MP4 handler"}
            val kind=String(bytes,handler.body+8,4,Charsets.US_ASCII)
            if(kind!="soun" && kind!="vide")return null
            val edts=children.firstOrNull{it.type=="edts"}?:return kind to 0L
            val edit=boxes(edts.body,edts.end).firstOrNull{it.type=="elst"}?:return kind to 0L
            val v=version(edit);require(v<=1 && edit.body+8<=edit.end){"Unsupported MP4 edit list"}
            val count=b.getInt(edit.body+4);val width=if(v==1)20 else 12
            require(count in 1..16 && edit.body+8+count*width<=edit.end){"Unsupported MP4 edit list"}
            var empty=0L;var mediaSeen=false
            for(i in 0 until count){val p=edit.body+8+i*width
                val duration=if(v==1)b.getLong(p) else b.getInt(p).toLong() and 0xffffffffL
                val media=if(v==1)b.getLong(p+8) else b.getInt(p+4).toLong()
                val rate=p+if(v==1)16 else 8
                require(duration>=0 && b.getShort(rate).toInt()==1 && b.getShort(rate+2).toInt()==0){"Unsupported MP4 edit rate"}
                if(media== -1L){require(!mediaSeen && duration<=Long.MAX_VALUE-empty){"Unsupported MP4 edit list"};empty+=duration}
                else {require(media>=0 && !mediaSeen && i==count-1){"Unsupported MP4 edit list"};mediaSeen=true}
            }
            require(mediaSeen && empty<=Long.MAX_VALUE/1000000L){"Unsupported MP4 edit list"}
            return kind to (empty*1000000L/scale)
        }
        val tracks=root.filter{it.type=="trak"}.mapNotNull{delay(it)}
        return Starts(tracks.firstOrNull{it.first=="soun"}?.second?:0L,tracks.firstOrNull{it.first=="vide"}?.second?:0L)
    }
    /** Do not add the delay twice on Android versions that already expose it. */
    fun effective(extractorUs:Long,emptyEditUs:Long):Long = maxOf(extractorUs,emptyEditUs)
}
