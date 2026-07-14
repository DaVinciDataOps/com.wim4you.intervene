package com.wim4you.intervene.distressstream.webrtc

import android.content.Context
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import com.wim4you.intervene.SecureLog
import com.wim4you.intervene.recording.PublicVideoStore
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
 * Patrol recordings are written to a cache file first, then persisted to public storage on stop.
 */
class WebRtcStreamMp4Recorder(
    private val context: Context,
    private val sharedEglContext: EglBase.Context,
    private val videoTrack: VideoTrack,
) : VideoSink {
    data class PublicPersistTarget(
        val sessionPath: String,
        val fileName: String,
    )

    private val encoderThread = HandlerThread("WebRtcStreamRecorder")
    private val encoderHandler: Handler
    private val recordLock = Any()

    private var tempOutputFile: File? = null
    private var publicPersistTarget: PublicPersistTarget? = null
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

    fun start(tempOutputFile: File, publicPersistTarget: PublicPersistTarget? = null) {
        if (!started.compareAndSet(false, true)) return
        this.tempOutputFile = tempOutputFile
        this.publicPersistTarget = publicPersistTarget
        tempOutputFile.parentFile?.mkdirs()

        synchronized(recordLock) {
            try {
                val recorder = MediaRecorder(context).apply {
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setVideoSize(videoWidth, videoHeight)
                    setVideoFrameRate(WebRtcConfig.VIDEO_FPS)
                    setVideoEncodingBitRate(BITRATE_BPS)
                    setOutputFile(tempOutputFile.absolutePath)
                    prepare()
                }
                mediaRecorder = recorder

                val egl = EglBase.create(sharedEglContext, EglBase.CONFIG_RECORDABLE)
                eglBase = egl
                egl.createSurface(recorder.surface)
                egl.makeCurrent()

                recorder.start()
                videoTrack.addSink(this)
                SecureLog.i(TAG, "Stream recorder started for ${tempOutputFile.absolutePath}")
            } catch (exception: Exception) {
                SecureLog.e(TAG, "Failed to start MediaRecorder", exception)
                cleanupLocked(deleteOutput = true)
                started.set(false)
                throw exception
            }
        }
    }

    fun stop(): File? {
        if (!started.compareAndSet(true, false)) return null
        videoTrack.removeSink(this)

        var savedFile: File? = null
        val latch = CountDownLatch(1)
        encoderHandler.post {
            try {
                synchronized(recordLock) {
                    val hadFrames = framesRendered.get() > 0
                    savedFile = finalizeOutputLocked(hadFrames)
                    SecureLog.i(
                        TAG,
                        "Stream recorder stopped. frames=$framesRendered hadFrames=$hadFrames saved=${savedFile?.absolutePath}",
                    )
                }
            } finally {
                latch.countDown()
            }
        }
        latch.await(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return savedFile
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

    private fun finalizeOutputLocked(hadFrames: Boolean): File? {
        val recorder = mediaRecorder
        if (recorder != null && hadFrames) {
            try {
                recorder.stop()
            } catch (exception: Exception) {
                SecureLog.e(TAG, "MediaRecorder stop failed", exception)
                cleanupLocked(deleteOutput = true)
                return null
            }
        }
        releaseRecorderResourcesLocked()

        val tempFile = tempOutputFile
        val target = publicPersistTarget
        tempOutputFile = null
        publicPersistTarget = null

        if (!hadFrames || tempFile == null) {
            tempFile?.delete()
            return null
        }

        if (target != null) {
            return PublicVideoStore.persistRecording(
                context = context.applicationContext,
                sessionPath = target.sessionPath,
                fileName = target.fileName,
                sourceFile = tempFile,
            )
        }

        return tempFile.takeIf { it.exists() && it.length() > 0L }
    }

    private fun cleanupLocked(deleteOutput: Boolean) {
        releaseRecorderResourcesLocked()
        if (deleteOutput) {
            tempOutputFile?.delete()
        }
        tempOutputFile = null
        publicPersistTarget = null
        encoderThread.quitSafely()
    }

    private fun releaseRecorderResourcesLocked() {
        try {
            mediaRecorder?.release()
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
    }

    private companion object {
        const val TAG = "WebRtcStreamRecorder"
        const val BITRATE_BPS = 2_000_000
        const val STOP_TIMEOUT_SECONDS = 10L
    }
}
