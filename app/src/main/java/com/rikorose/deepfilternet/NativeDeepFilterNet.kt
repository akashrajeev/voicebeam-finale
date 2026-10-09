package com.rikorose.deepfilternet

import java.nio.ByteBuffer

/** JNI signatures match Apache2 Kaleyra AndroidDeepFilterNet pinned native library. */
class NativeDeepFilterNet(model: ByteArray) {
    private var pointer = newNative(model, 50f).also { require(it != 0L) }
    val frameLength: Int get() = getFrameLengthNative(pointer).toInt()
    fun process(buffer: ByteBuffer): Float = processFrameNative(pointer, buffer)
    fun close() { if (pointer != 0L) { freeNative(pointer); pointer=0L } }
    private external fun newNative(modelBytes: ByteArray, attenuationLimit: Float): Long
    private external fun getFrameLengthNative(statePtr: Long): Long
    private external fun processFrameNative(statePtr: Long, inputFrame: ByteBuffer): Float
    private external fun freeNative(statePtr: Long)
    companion object { init { System.loadLibrary("df") } }
}
