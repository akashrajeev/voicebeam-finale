package com.akashrajeev.voicebeam.core
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
class Mp4TrackStartsTest {
    private fun payload(write:DataOutputStream.()->Unit):ByteArray=ByteArrayOutputStream().also{DataOutputStream(it).use(write)}.toByteArray()
    private fun box(type:String,data:ByteArray)=payload{writeInt(data.size+8);writeBytes(type);write(data)}
    private fun movie(delay:Int,rate:Int=1,version:Int=0):ByteArray{
        val mvhd=box("mvhd",payload{writeInt(version shl 24);if(version==1){writeLong(0);writeLong(0)}else{writeInt(0);writeInt(0)};writeInt(1000)})
        val hdlr=box("hdlr",payload{writeLong(0);writeBytes("soun")})
        val elst=box("elst",payload{writeInt(version shl 24);writeInt(2);if(version==1){writeLong(delay.toLong());writeLong(-1)}else{writeInt(delay);writeInt(-1)};writeShort(rate);writeShort(0);if(version==1){writeLong(5000);writeLong(0)}else{writeInt(5000);writeInt(0)};writeShort(1);writeShort(0)})
        return mvhd+box("trak",box("mdia",hdlr)+box("edts",elst))
    }
    @Test fun delayedStartVersionZero(){assertEquals(236000L,Mp4TrackStarts.parse(movie(236)).audioUs)}
    @Test fun delayedStartVersionOne(){assertEquals(2236000L,Mp4TrackStarts.parse(movie(2236,version=1)).audioUs)}
    @Test fun neverDoubleAddAlreadyExposedDelay(){assertEquals(236000L,Mp4TrackStarts.effective(236000,236000));assertEquals(236000L,Mp4TrackStarts.effective(0,236000))}
    @Test fun truncatedMetadataRejected(){try{Mp4TrackStarts.parse(movie(236).dropLast(1).toByteArray());fail()}catch(_:IllegalArgumentException){}}
    @Test fun unsupportedRateRejected(){try{Mp4TrackStarts.parse(movie(236,rate=2));fail()}catch(_:IllegalArgumentException){}}
    @Test fun overCapRecoveredThenRejected(){try{AudioAlignment.leadingSamples(Mp4TrackStarts.parse(movie(2236)).audioUs,16000);fail()}catch(_:IllegalArgumentException){}}
}
