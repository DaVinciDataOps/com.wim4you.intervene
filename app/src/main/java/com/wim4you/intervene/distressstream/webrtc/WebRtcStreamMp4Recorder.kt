package com.wim4you.intervene.distressstream.webrtc

import android.content.Context
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import com.wim4you.intervene.SecureLog
import org.webrtc.EglBase
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoFrameDrawer
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Records a WebRTC [VideoTrack] into a playable MP4 by feeding frames into [MediaRecorder].
 */
class WebRtcStreamMp4Recorder(
    private val context: Context,
    private val sharedEglContext: EglBase.Context,
    private val videoTrack: VideoTrack,
) : VideoSink {
    private val encoderThread = HandlerThread("WebRtcStreamRecorder")
    private val encoderHandler: Handler
    private val recordLock = Any()

    private var outputFile: File? = null
    private val videoWidth = WebRtcConfig.VIDEO_WIDTH
    private val videoHeight = WebRtcConfig.VIDEO_HEIGHT

    private var mediaRecorder: MediaRecorder? = null
    private var eglBase: EglBase? = null
    private val frameDrawer = VideoFrameDrawer()
    private val drawer = GlRectDrawer()

    private val started = AtomicBoolean(false)
    private val framesRendered = AtomicInteger(0)

    init {
        encoderThread.start()
        encoderHandler = Handler(encoderThread.looper)
    }

    fun start(outputFile: File) {
        if (!started.compareAndSet(false, true)) return
        this.outputFile = outputFile
        outputFile.parentFile?.mkdirs()

        synchronized(recordLock) {
            try {
                val recorder = MediaRecorder(context).apply {
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setVideoSize(videoWidth, videoHeight)
                    setVideoFrameRate(WebRtcConfig.VIDEO_FPS)
                    setVideoEncodingBitRate(BITRATE_BPS)
                    setOutputFile(outputFile.absolutePath)
                    prepare()
                }
                mediaRecorder = recorder

                val egl = EglBase.create(sharedEglContext, EglBase.CONFIG_RECORDABLE)
                eglBase = egl
                egl.createSurface(recorder.surface)
                egl.makeCurrent()

                recorder.start()
                videoTrack.addSink(this)
                SecureLog.i(TAG, "Stream recorder started for ${outputFile.absolutePath}")
            } catch (exception: Exception) {
                SecureLog.e(TAG, "Failed to start MediaRecorder", exception)
                cleanupLocked(deleteOutput = true)
                started.set(false)
                throw exception
            }
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        videoTrack.removeSink(this)

        val latch = CountDownLatch(1)
        encoderHandler.post {
            try {
                synchronized(recordLock) {
                    val hadFrames = framesRendered.get() > 0
                    cleanupLocked(deleteOutput = !hadFrames)
                    SecureLog.i(TAG, "Stream recorder stopped. frames=$framesRendered hadFrames=$hadFrames")
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
                    renderFrameLocked(frame)
                }
            } finally {
                frame.release()
            }
        }
    }

    private fun renderFrameLocked(frame: VideoFrame) {
        val egl = eglBase ?: return
        try {
            egl.makeCurrent()
            frameDrawer.drawFrame(frame, drawer, null, 0, 0, videoWidth, videoHeight)
            egl.swapBuffers(frame.timestampNs)
            framesRendered.incrementAndGet()
        } catch (exception: Exception) {
            SecureLog.e(TAG, "Failed to render stream frame", exception)
        }
    }

    private fun cleanupLocked(deleteOutput: Boolean) {
        var shouldDelete = deleteOutput
        val recorder = mediaRecorder
        if (recorder != null && framesRendered.get() > 0) {
            try {
                recorder.stop()
            } catch (exception: Exception) {
                SecureLog.e(TAG, "MediaRecorder stop failed", exception)
                shouldDelete = true
            }
        }
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null

        try {
            eglBase?.release()
        } catch (_: Exception) {
        }
        eglBase = null

        try {
            frameDrawer.release()
        } catch (_: Exception) {
        }
        try {
            drawer.release()
        } catch (_: Exception) {
        }

        if (shouldDelete) {
            outputFile?.delete()
        }
        outputFile = null
        encoderThread.quitSafely()
    }

    private companion object {
        const val TAG = "WebRtcStreamRecorder"
        const val BITRATE_BPS = 2_000_000
        const val STOP_TIMEOUT_SECONDS = 5L
    }
}
