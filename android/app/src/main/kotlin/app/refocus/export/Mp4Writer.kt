package app.refocus.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor

/** Encodes ARGB frames into an H.264 MP4 with the hardware encoder. */
class Mp4Writer(fd: FileDescriptor, private val width: Int, private val height: Int, private val fps: Int) {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var started = false
    private var frame = 0L

    init {
        require(width % 2 == 0 && height % 2 == 0)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * fps * 0.3).toInt().coerceIn(2_000_000, 20_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun addFrame(argb: IntArray) {
        val index = dequeueInput()
        val image = codec.getInputImage(index) ?: error("Encoder has no input image")
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val yb = y.buffer; val ub = u.buffer; val vb = v.buffer
        for (row in 0 until height) {
            for (col in 0 until width) {
                val p = argb[row * width + col]
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                // BT.601 limited range
                val yy = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yb.put(row * y.rowStride + col * y.pixelStride, yy.toByte())
                if (row % 2 == 0 && col % 2 == 0) {
                    val uu = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val vv = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    ub.put((row / 2) * u.rowStride + (col / 2) * u.pixelStride, uu.coerceIn(0, 255).toByte())
                    vb.put((row / 2) * v.rowStride + (col / 2) * v.pixelStride, vv.coerceIn(0, 255).toByte())
                }
            }
        }
        codec.queueInputBuffer(index, 0, width * height * 3 / 2, frame * 1_000_000L / fps, 0)
        frame++
        drain(false)
    }

    private fun dequeueInput(): Int {
        while (true) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i >= 0) return i
            drain(false)
        }
    }

    fun finish() {
        val i = dequeueInput()
        codec.queueInputBuffer(i, 0, 0, frame * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
        codec.stop()
        codec.release()
        if (started) muxer.stop()
        muxer.release()
    }

    fun abort() {
        runCatching { codec.release() }
        runCatching { muxer.release() }
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val i = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                }
                i >= 0 -> {
                    val buf = codec.getOutputBuffer(i)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}
