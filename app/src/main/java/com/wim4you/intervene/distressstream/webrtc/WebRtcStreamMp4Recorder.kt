package com.wim4you.intervene.distressstream.webrtc

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import com.wim4you.intervene.SecureLog
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Records a WebRTC [VideoTrack] into an MP4 (H.264) file by encoding I420 frames.
 */
class WebRtcStreamMp4Recorder(
    private val videoTrack: VideoTrack,
) : VideoSink {
    private val encoderThread = HandlerThread("WebRtcStreamRecorder")
    private val encoderHandler: Handler
    private val recordLock = Any()

    private var outputFile: File? = null
    private var configuredWidth = 0
    private var configuredHeight = 0

    private var muxer: MediaMuxer? = null
    private var codec: MediaCodec? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var basePtsUs: Long? = null

    private val started = AtomicBoolean(false)
    private val framesEncoded = AtomicInteger(0)

    init {
        encoderThread.start()
        encoderHandler = Handler(encoderThread.looper)
    }

    fun start(outputFile: File) {
        if (!started.compareAndSet(false, true)) return
        this.outputFile = outputFile
        outputFile.parentFile?.mkdirs()
        videoTrack.addSink(this)
        SecureLog.i(TAG, "Stream recorder started for ${outputFile.absolutePath}")
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        videoTrack.removeSink(this)
        val latch = CountDownLatch(1)
        encoderHandler.post {
            try {
                synchronized(recordLock) {
                    val hadFrames = framesEncoded.get() > 0
                    cleanupLocked(deleteOutput = !hadFrames)
                    SecureLog.i(TAG, "Stream recorder stopped. frames=$framesEncoded hadFrames=$hadFrames")
                }
            } finally {
                latch.countDown()
            }
        }
        latch.await(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    override fun onFrame(frame: VideoFrame) {
        if (!started.get()) return
        frame.retain()
        encoderHandler.post {
            try {
                if (!started.get()) return@post
                synchronized(recordLock) {
                    encodeFrameLocked(frame)
                }
            } finally {
                frame.release()
            }
        }
    }

    private fun encodeFrameLocked(frame: VideoFrame) {
        val i420 = frame.buffer.toI420() ?: return
        try {
            if (!ensureEncoderConfiguredLocked(i420.width, i420.height)) return
            val encoder = codec ?: return
            val inputIndex = encoder.dequeueInputBuffer(DRAIN_TIMEOUT_US)
            if (inputIndex < 0) return

            val inputBuffer = encoder.getInputBuffer(inputIndex) ?: return
            inputBuffer.clear()
            val frameSize = packI420ToNv12(inputBuffer, i420)
            if (frameSize <= 0) return

            val ptsUs = normalizePts(frame.timestampNs / 1_000)
            encoder.queueInputBuffer(inputIndex, 0, frameSize, ptsUs, 0)
            framesEncoded.incrementAndGet()
            drainEncoderLocked(encoder, endOfStream = false)
        } catch (exception: Exception) {
            SecureLog.e(TAG, "Failed to encode stream frame", exception)
        } finally {
            i420.release()
        }
    }

    private fun ensureEncoderConfiguredLocked(width: Int, height: Int): Boolean {
        val evenWidth = width - (width % 2)
        val evenHeight = height - (height % 2)
        if (evenWidth <= 0 || evenHeight <= 0) return false
        if (codec != null &&
            configuredWidth == evenWidth &&
            configuredHeight == evenHeight
        ) {
            return true
        }

        cleanupEncoderLocked()
        configuredWidth = evenWidth
        configuredHeight = evenHeight

        val output = outputFile ?: return false
        return try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, evenWidth, evenHeight).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE_BPS)
                setInteger(MediaFormat.KEY_FRAME_RATE, WebRtcConfig.VIDEO_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL_S)
            }
            val encoder = MediaCodec.createEncoderByType(MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            codec = encoder
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            true
        } catch (exception: Exception) {
            SecureLog.e(TAG, "Failed to configure stream encoder", exception)
            cleanupEncoderLocked()
            false
        }
    }

    private fun packI420ToNv12(target: ByteBuffer, i420: VideoFrame.I420Buffer): Int {
        val width = configuredWidth
        val height = configuredHeight
        val ySize = width * height
        val uvSize = ySize / 2
        if (target.capacity() < ySize + uvSize) return -1

        copyPlane(i420.dataY, i420.strideY, target, width, height)
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            val uRowStart = row * i420.strideU
            val vRowStart = row * i420.strideV
            for (col in 0 until chromaWidth) {
                target.put(i420.dataU.get(uRowStart + col))
                target.put(i420.dataV.get(vRowStart + col))
            }
        }
        return ySize + uvSize
    }

    private fun copyPlane(
        source: ByteBuffer,
        stride: Int,
        target: ByteBuffer,
        width: Int,
        height: Int,
    ) {
        val row = ByteArray(width)
        for (y in 0 until height) {
            val rowStart = y * stride
            source.position(rowStart)
            source.get(row, 0, width)
            target.put(row)
        }
    }

    private fun cleanupLocked(deleteOutput: Boolean) {
        cleanupEncoderLocked()
        if (deleteOutput) {
            outputFile?.delete()
        }
        outputFile = null
        encoderThread.quitSafely()
    }

    private fun cleanupEncoderLocked() {
        val encoder = codec
        if (encoder != null) {
            try {
                drainEncoderLocked(encoder, endOfStream = true)
            } catch (exception: Exception) {
                SecureLog.e(TAG, "Failed to drain encoder", exception)
            }
            try {
                encoder.stop()
            } catch (_: Exception) {
            }
            try {
                encoder.release()
            } catch (_: Exception) {
            }
        }
        codec = null

        try {
            if (muxerStarted) {
                muxer?.stop()
            }
        } catch (exception: Exception) {
            SecureLog.e(TAG, "Failed to stop muxer", exception)
        }
        try {
            muxer?.release()
        } catch (_: Exception) {
        }
        muxer = null
        muxerStarted = false
        trackIndex = -1
        basePtsUs = null
        configuredWidth = 0
        configuredHeight = 0
    }

    private fun drainEncoderLocked(encoder: MediaCodec, endOfStream: Boolean) {
        val muxerInstance = muxer ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        if (endOfStream) {
            try {
                val inputIndex = encoder.dequeueInputBuffer(DRAIN_TIMEOUT_US)
                if (inputIndex >= 0) {
                    encoder.queueInputBuffer(
                        inputIndex,
                        0,
                        0,
                        normalizePts(System.nanoTime() / 1_000),
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                }
            } catch (_: Exception) {
            }
        }

        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, DRAIN_TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) return
                    trackIndex = muxerInstance.addTrack(encoder.outputFormat)
                    muxerInstance.start()
                    muxerStarted = true
                }
                outputIndex >= 0 -> {
                    val encodedData = encoder.getOutputBuffer(outputIndex) ?: run {
                        encoder.releaseOutputBuffer(outputIndex, false)
                        continue
                    }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        bufferInfo.presentationTimeUs = normalizePts(bufferInfo.presentationTimeUs)
                        muxerInstance.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun normalizePts(ptsUs: Long): Long {
        val base = basePtsUs
        return if (base == null) {
            basePtsUs = ptsUs
            0L
        } else {
            (ptsUs - base).coerceAtLeast(0L)
        }
    }

    private companion object {
        const val TAG = "WebRtcStreamRecorder"
        const val MIME_TYPE = "video/avc"
        const val BITRATE_BPS = 1_200_000
        const val IFRAME_INTERVAL_S = 2
        const val DRAIN_TIMEOUT_US = 10_000L
        const val STOP_TIMEOUT_SECONDS = 5L
    }
}
