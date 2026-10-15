package com.kharlos.grabadorvoz

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs

/** Decodifica un audio y calcula los picos de volumen (uno cada ~20 ms) para dibujar las ondas. */
object WaveformLoader {

    private val cache = HashMap<String, FloatArray>()

    fun load(file: File): FloatArray? {
        val key = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        synchronized(cache) { cache[key]?.let { return it } }
        val peaks = decode(file) ?: return null
        synchronized(cache) { cache[key] = peaks }
        return peaks
    }

    private fun decode(file: File): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    extractor.selectTrack(i)
                    format = f
                    break
                }
            }
            if (format == null) return null

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val window = (sampleRate * channels / 50).coerceAtLeast(1)

            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)
            c.start()

            val info = MediaCodec.BufferInfo()
            val peaks = ArrayList<Float>()
            var current = 0
            var count = 0
            var inputDone = false
            var outputDone = false
            var idleLoops = 0

            while (!outputDone && idleLoops < 500) {
                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = c.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    idleLoops = 0
                    val out = c.getOutputBuffer(outIdx)
                    if (out != null && info.size > 0) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val samples = out.order(ByteOrder.nativeOrder()).asShortBuffer()
                        while (samples.hasRemaining()) {
                            val v = abs(samples.get().toInt())
                            if (v > current) current = v
                            count++
                            if (count >= window) {
                                peaks.add(current / 32768f)
                                current = 0
                                count = 0
                            }
                        }
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else {
                    idleLoops++
                }
            }
            if (count > 0) peaks.add(current / 32768f)
            return peaks.toFloatArray()
        } catch (e: Exception) {
            return null
        } finally {
            try { codec?.stop() } catch (e: Exception) { }
            try { codec?.release() } catch (e: Exception) { }
            extractor.release()
        }
    }
}
