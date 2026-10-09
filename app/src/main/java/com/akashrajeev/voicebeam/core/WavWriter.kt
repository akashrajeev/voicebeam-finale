package com.akashrajeev.voicebeam.core

import java.io.File
import java.io.RandomAccessFile

/** Streams 16-bit mono PCM to a .wav file and patches the header on close. */
class WavWriter(file: File, private val sampleRate: Int) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private val buf = ByteArray(16384)

    init {
        raf.setLength(0)
        raf.write(ByteArray(44))
    }

    @Synchronized
    fun write(samples: FloatArray, count: Int = samples.size) {
        var i = 0
        while (i < count) {
            val n = minOf(count - i, buf.size / 2)
            for (k in 0 until n) {
                val v = (samples[i + k].coerceIn(-1f, 1f) * 32767f).toInt()
                buf[2 * k] = (v and 0xff).toByte()
                buf[2 * k + 1] = ((v shr 8) and 0xff).toByte()
            }
            raf.write(buf, 0, n * 2)
            dataBytes += n * 2
            i += n
        }
    }

    val durationMs: Long get() = dataBytes * 1000 / (sampleRate * 2)

    @Synchronized
    override fun close() {
        raf.seek(0)
        raf.write(header(dataBytes, sampleRate))
        raf.close()
    }

    companion object {
        fun header(dataBytes: Long, sampleRate: Int): ByteArray {
            val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()); b.putInt((36 + dataBytes).toInt())
            b.put("WAVE".toByteArray()); b.put("fmt ".toByteArray())
            b.putInt(16); b.putShort(1); b.putShort(1)
            b.putInt(sampleRate); b.putInt(sampleRate * 2); b.putShort(2); b.putShort(16)
            b.put("data".toByteArray()); b.putInt(dataBytes.toInt())
            return b.array()
        }

        /** Reads a 16-bit mono wav into floats (used in tests and exports). */
        fun read(file: File): Pair<FloatArray, Int> {
            val bytes = file.readBytes()
            val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            var pos = 12
            var sr = 16000
            var channels = 1
            var dataStart = 44
            var dataLen = bytes.size - 44
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4)
                val len = bb.getInt(pos + 4)
                if (id == "fmt ") {
                    channels = bb.getShort(pos + 10).toInt()
                    sr = bb.getInt(pos + 12)
                } else if (id == "data") {
                    dataStart = pos + 8
                    dataLen = minOf(len, bytes.size - dataStart)
                    break
                }
                pos += 8 + len
            }
            val frames = dataLen / (2 * channels)
            val out = FloatArray(frames)
            for (i in 0 until frames) out[i] = bb.getShort(dataStart + i * 2 * channels) / 32768f
            return Pair(out, sr)
        }
    }
}
