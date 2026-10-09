package com.rikorose.deepfilternet

import java.nio.ByteBuffer

/** JNI signatures match Apache2 Kaleyra AndroidDeepFilterNet pinned native library. */
class NativeDeepFilterNet(model: ByteArray) {
    private var pointer = newNative(model, 32f).also { require(it != 0L) }
    val frameLength: Int get() = getFrameLengthNative(pointer).toInt()
    init { if(!setPostFilterBetaNative(pointer, 0f)) { close();error("DFN postfilter disable failed") } }
    fun process(buffer: ByteBuffer): Float {
        check(pointer!=0L) { "DFN released" }
        require(buffer.isDirect && buffer.capacity()==frameLength) { "DFN direct PCM16 capacity mismatch" }
        return processFrameNative(pointer, buffer)
    }
    fun close() { if (pointer != 0L) { freeNative(pointer); pointer=0L } }
    private external fun newNative(modelBytes: ByteArray, attenuationLimit: Float): Long
    private external fun getFrameLengthNative(statePtr: Long): Long
    private external fun processFrameNative(statePtr: Long, inputFrame: ByteBuffer): Float
    private external fun setPostFilterBetaNative(statePtr: Long, beta: Float): Boolean
    private external fun freeNative(statePtr: Long)
    companion object { init { System.loadLibrary("df") } }
}
