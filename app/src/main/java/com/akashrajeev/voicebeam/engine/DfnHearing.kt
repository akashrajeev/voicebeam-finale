package com.akashrajeev.voicebeam.engine

import android.content.res.AssetManager
import com.rikorose.deepfilternet.NativeDeepFilterNet
import com.akashrajeev.voicebeam.core.DfnFrameAdapter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Per-session experiment. Native model runs on hearing path only; release on session end. */
class DfnHearing(assets: AssetManager, private val status: (String)->Unit = {}) {
    private val native=run {
        status("stage=asset_open path=models/dfn3-mobile.bin")
        val model=assets.open("models/dfn3-mobile.bin").use{it.readBytes()}
        require(model.size==7984565 && model[0]==0x1f.toByte() && model[1]==0x8b.toByte()) { "DFN gzip payload invalid bytes="+model.size }
        status("stage=native_load modelBytes="+model.size)
        NativeDeepFilterNet(model).also { status("stage=native_ready frameBytes="+it.frameLength) }
    }
    private val buffer=ByteBuffer.allocateDirect(480*2).order(ByteOrder.LITTLE_ENDIAN)
    init { if(native.frameLength!=960) { native.close();error("Expected960PCM16bytes") } }
    private val adapter=DfnFrameAdapter { samples ->
        buffer.clear();for(x in samples)buffer.putShort((x.coerceIn(-1f,1f)*32767f).toInt().toShort())
        val score=native.process(buffer);require(score.isFinite())
        buffer.clear();FloatArray(480){buffer.short/32768f}
    }
    fun process(input:FloatArray):FloatArray=adapter.process(input)
    fun close()=native.close()
}
