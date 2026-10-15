package com.kharlos.grabadorvoz

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Exporta un audio editado.
 * - Solo recorte: copia el audio tal cual (sin pérdida, casi instantáneo).
 * - Con velocidad o volumen: decodifica, procesa y vuelve a codificar en AAC.
 */
object AudioProcessor {

    fun export(
        src: File, out: File, startMs: Long, endMs: Long,
        speed: Float, gain: Float, progress: (Float) -> Unit
    ): Boolean {
        return try {
            val ok = if (abs(speed - 1f) < 0.001f && abs(gain - 1f) < 0.001f) {
                trim(src, out, startMs, endMs)
            } else {
                render(src, out, startMs, endMs, speed, gain, progress)
            }
            ok && out.exists() && out.length() > 0
        } catch (e: Exception) {
            out.delete()
            false
        }
    }

    private fun audioTrack(ex: MediaExtractor): Int {
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return i
        }
        return -1
    }

    // ---------- Recorte sin pérdida ----------

    private fun trim(src: File, out: File, startMs: Long, endMs: Long): Boolean {
        val ex = MediaExtractor()
        ex.setDataSource(src.absolutePath)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val t = audioTrack(ex)
            if (t < 0) return false
            ex.selectTrack(t)
            val fmt = ex.getTrackFormat(t)
            val outTrack = muxer.addTrack(fmt)
            muxer.start()
            val maxSize = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            val buf = ByteBuffer.allocate(max(maxSize, 256 * 1024))
            val info = MediaCodec.BufferInfo()
            val startUs = startMs * 1000L
            val endUs = endMs * 1000L
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var first = -1L
            while (true) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val time = ex.sampleTime
                if (time > endUs) break
                if (time >= startUs) {
                    if (first < 0) first = time
                    info.set(0, size, time - first, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(outTrack, buf, info)
                }
                ex.advance()
            }
            if (first < 0) return false
            muxer.stop()
            return true
        } finally {
            try {
                muxer.release()
            } catch (e: Exception) {
            }
            ex.release()
        }
    }

    // ---------- Procesado completo ----------

    private fun render(
        src: File, out: File, startMs: Long, endMs: Long,
        speed: Float, gain: Float, progress: (Float) -> Unit
    ): Boolean {
        val ex = MediaExtractor()
        ex.setDataSource(src.absolutePath)
        var decoder: MediaCodec? = null
        var writer: AacWriter? = null
        try {
            val t = audioTrack(ex)
            if (t < 0) return false
            ex.selectTrack(t)
            val inFmt = ex.getTrackFormat(t)
            val srcBitrate = if (inFmt.containsKey(MediaFormat.KEY_BIT_RATE)) inFmt.getInteger(MediaFormat.KEY_BIT_RATE) else 0

            val dec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
            decoder = dec
            dec.configure(inFmt, null, null, 0)
            dec.start()

            val startUs = startMs * 1000L
            val endUs = endMs * 1000L
            val spanUs = max(1L, endUs - startUs)
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            var rate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var stretcher: TimeStretcher? = null
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            var idle = 0
            var scratch = ShortArray(0)

            while (!outDone && idle < 2000) {
                if (!inDone) {
                    val i = dec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val b = dec.getInputBuffer(i)!!
                        val size = ex.readSampleData(b, 0)
                        if (size < 0 || ex.sampleTime > endUs + 200_000) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }

                val o = dec.dequeueOutputBuffer(info, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = dec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    idle = 0
                    if (writer == null) {
                        writer = AacWriter(out, rate, ch, chooseBitrate(srcBitrate, ch))
                        if (abs(speed - 1f) >= 0.001f) stretcher = TimeStretcher(ch, rate, speed)
                    }
                    val w = writer
                    val ob = dec.getOutputBuffer(o)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val total = sb.remaining()
                        if (scratch.size < total) scratch = ShortArray(total)
                        sb.get(scratch, 0, total)

                        // Solo las muestras dentro de [inicio, fin]
                        val frames = total / ch
                        val t0 = info.presentationTimeUs
                        var from = 0
                        var to = frames
                        if (t0 < startUs) from = ((startUs - t0) * rate / 1_000_000L).toInt().coerceIn(0, frames)
                        val tEnd = t0 + frames * 1_000_000L / rate
                        if (tEnd > endUs) to = ((endUs - t0) * rate / 1_000_000L).toInt().coerceIn(0, frames)

                        if (to > from) {
                            val chunk = scratch.copyOfRange(from * ch, to * ch)
                            applyGain(chunk, gain)
                            val st = stretcher
                            if (st != null) st.push(chunk, chunk.size) { d, n -> w.write(d, n) }
                            else w.write(chunk, chunk.size)
                        }
                        progress(((t0 - startUs).toFloat() / spanUs).coerceIn(0f, 1f))
                        if (t0 >= endUs) outDone = true
                    }
                    dec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                } else {
                    idle++
                }
            }

            val w = writer ?: return false
            stretcher?.flush { d, n -> w.write(d, n) }
            w.finish()
            progress(1f)
            return true
        } finally {
            try { decoder?.stop() } catch (e: Exception) { }
            try { decoder?.release() } catch (e: Exception) { }
            writer?.release()
            ex.release()
        }
    }

    private fun chooseBitrate(src: Int, ch: Int): Int =
        if (src > 0) src.coerceIn(64_000, 320_000) else if (ch >= 2) 192_000 else 128_000

    /** Ganancia con limitador suave para que al subir el volumen no se distorsione. */
    private fun applyGain(s: ShortArray, g: Float) {
        if (abs(g - 1f) < 0.001f) return
        val knee = 0.8f
        for (i in s.indices) {
            var x = s[i] / 32768f * g
            val a = abs(x)
            if (a > knee) {
                val y = knee + (1f - knee) * tanh((a - knee) / (1f - knee))
                x = if (x < 0) -y else y
            }
            s[i] = (x * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}

/** Codifica PCM de 16 bits a AAC dentro de un .m4a. */
class AacWriter(out: File, private val rate: Int, private val ch: Int, bitrate: Int) {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var started = false
    private var framesWritten = 0L
    private val info = MediaCodec.BufferInfo()

    init {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch)
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun write(data: ShortArray, len: Int) {
        var pos = 0
        while (pos < len) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = codec.getInputBuffer(idx)!!
                buf.clear()
                var n = min(len - pos, buf.remaining() / 2)
                if (n >= ch) n = n / ch * ch
                buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(data, pos, n)
                val pts = framesWritten * 1_000_000L / rate
                codec.queueInputBuffer(idx, 0, n * 2, pts, 0)
                framesWritten += n / ch
                pos += n
            }
            drain(false)
        }
    }

    fun finish() {
        var queued = false
        var tries = 0
        while (!queued && tries < 500) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                codec.queueInputBuffer(idx, 0, 0, framesWritten * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                queued = true
            } else {
                drain(false)
                tries++
            }
        }
        drain(true)
        if (started) muxer.stop()
    }

    private fun drain(endOfStream: Boolean) {
        var tries = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream || ++tries > 300) return
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(codec.outputFormat)
                muxer.start()
                started = true
            } else if (idx >= 0) {
                val buf = codec.getOutputBuffer(idx)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && started && buf != null) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    muxer.writeSampleData(track, buf, info)
                }
                codec.releaseOutputBuffer(idx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
    }

    fun release() {
        try { codec.stop() } catch (e: Exception) { }
        try { codec.release() } catch (e: Exception) { }
        try { muxer.release() } catch (e: Exception) { }
    }
}

/**
 * Cambia la velocidad sin cambiar el tono (WSOLA): toma trozos cortos del audio,
 * busca el punto donde mejor empalman y los superpone suavemente.
 */
class TimeStretcher(private val ch: Int, rate: Int, private val speed: Float) {

    private val n = ((rate * 0.03f).toInt() / 2) * 2 // trozo de 30 ms
    private val hs = n / 2
    private val tol = (rate * 0.012f).toInt()
    private val win = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / n)).toFloat() }

    private var buf = FloatArray(ch * (n * 8 + 4 * tol))
    private var bufFrames = 0
    private var bufStart = 0L
    private var k = 0L
    private var prevPos = 0L
    private val tail = FloatArray(hs * ch)
    private val outScratch = ShortArray(hs * ch)

    fun push(data: ShortArray, len: Int, out: (ShortArray, Int) -> Unit) {
        val frames = len / ch
        val need = (bufFrames + frames) * ch
        if (need > buf.size) buf = buf.copyOf(need * 2)
        val base = bufFrames * ch
        for (i in 0 until frames * ch) buf[base + i] = data[i] / 32768f
        bufFrames += frames
        process(out)
    }

    fun flush(out: (ShortArray, Int) -> Unit) {
        for (i in 0 until hs * ch) outScratch[i] = toShort(tail[i])
        out(outScratch, hs * ch)
    }

    private fun sample(abs: Long, c: Int): Float = buf[((abs - bufStart) * ch).toInt() + c]

    private fun mono(abs: Long): Float {
        if (ch == 1) return sample(abs, 0)
        var s = 0f
        for (c in 0 until ch) s += sample(abs, c)
        return s / ch
    }

    private fun process(out: (ShortArray, Int) -> Unit) {
        while (true) {
            val target = (k * hs * speed.toDouble()).roundToLong()
            val lo = if (k == 0L) target else max(target - tol, bufStart)
            val hi = if (k == 0L) target else target + tol
            if (hi + n > bufStart + bufFrames) return // falta audio de entrada

            val chosen = if (k == 0L) target else bestMatch(lo, hi, prevPos + hs)

            for (j in 0 until hs) for (c in 0 until ch) {
                val v = tail[j * ch + c] + sample(chosen + j, c) * win[j]
                outScratch[j * ch + c] = toShort(v)
            }
            out(outScratch, hs * ch)
            for (j in 0 until hs) for (c in 0 until ch) {
                tail[j * ch + c] = sample(chosen + hs + j, c) * win[hs + j]
            }
            prevPos = chosen
            k++

            // Descarta la entrada que ya no se necesita
            val nextTarget = (k * hs * speed.toDouble()).roundToLong()
            val keepFrom = min(prevPos + hs, nextTarget - tol)
            val drop = (keepFrom - bufStart).toInt()
            if (drop > 0) {
                System.arraycopy(buf, drop * ch, buf, 0, (bufFrames - drop) * ch)
                bufFrames -= drop
                bufStart += drop
            }
        }
    }

    /** Busca el desplazamiento cuyo inicio se parece más a la continuación natural del trozo anterior. */
    private fun bestMatch(lo: Long, hi: Long, ref: Long): Long {
        var best = lo
        var bestScore = -Float.MAX_VALUE
        var c = lo
        while (c <= hi) {
            var dot = 0f
            var energy = 0f
            var j = 0
            while (j < hs) {
                val a = mono(c + j)
                dot += a * mono(ref + j)
                energy += a * a
                j += 4
            }
            val score = dot / sqrt(energy + 1e-6f)
            if (score > bestScore) {
                bestScore = score
                best = c
            }
            c += 2
        }
        return best
    }

    private fun toShort(v: Float): Short = (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
}
