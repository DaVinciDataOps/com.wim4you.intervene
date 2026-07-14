package com.wim4you.intervene.distressstream.webrtc

import android.content.Context
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import com.wim4you.intervene.SecureLog
import com.wim4you.intervene.recording.PublicVideoStore
import com.wim4you.intervene.recording.RecordingLocalStore
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
 * Records a WebRTC [VideoTrack] into an MP4 file on a dedicated encoder thread, then persists
 * to public storage (with internal fallback).
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

    private var publicPersistTarget: PublicPersistTarget? = null
    private var tempOutputFile: File? = null
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

    fun start(publicPersistTarget: PublicPersistTarget) {
        if (!started.compareAndSet(false, true)) return
        this.publicPersistTarget = publicPersistTarget
        framesRendered.set(0)

        val latch = CountDownLatch(1)
        var startError: Exception? = null
        encoderHandler.post {
            try {
                synchronized(recordLock) {
                    val tempFile = File(
                        context.cacheDir,
                        "patrol_record_${System.currentTimeMillis()}.mp4",
                    )
                    tempOutputFile = tempFile
                    tempFile.parentFile?.mkdirs()
                    startRecorderLocked(tempFile)
                    videoTrack.addSink(this@WebRtcStreamMp4Recorder)
                    SecureLog.i(TAG, "Patrol stream recorder started for ${publicPersistTarget.sessionPath}")
                }
            } catch (exception: Exception) {
                startError = exception
                started.set(false)
                cleanupRecorderLocked(deleteTemp = true)
                SecureLog.e(TAG, "Failed to start patrol stream recorder", exception)
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            started.set(false)
            throw IllegalStateException("Patrol recorder start timed out")
        }
        startError?.let { throw it }
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
                        "Patrol stream recorder stopped. frames=$framesRendered saved=${savedFile?.absolutePath}",
                    )
                }
            } finally {
                latch.countDown()
            }
        }
        latch.await(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        encoderThread.quitSafely()
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

    private fun startRecorderLocked(outputFile: File) {
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
    }

    private fun stopRecorderLocked() {
        val recorder = mediaRecorder
        if (recorder != null && framesRendered.get() > 0) {
            try {
                recorder.stop()
            } catch (exception: Exception) {
                SecureLog.e(TAG, "MediaRecorder stop failed", exception)
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
    }

    private fun finalizeOutputLocked(hadFrames: Boolean): File? {
        stopRecorderLocked()

        val tempFile = tempOutputFile
        val target = publicPersistTarget
        tempOutputFile = null
        publicPersistTarget = null

        releaseDrawersLocked()

        if (!hadFrames || tempFile == null || target == null) {
            tempFile?.delete()
            return null
        }
        if (!tempFile.exists() || tempFile.length() == 0L) {
            tempFile.delete()
            return null
        }

        val appContext = context.applicationContext
        val publicSaved = PublicVideoStore.persistRecording(
            context = appContext,
            sessionPath = target.sessionPath,
            fileName = target.fileName,
            sourceFile = tempFile,
        )
        if (publicSaved != null) {
            tempFile.delete()
            return publicSaved
        }

        val username = target.sessionPath.substringAfter('/')
        val internalSaved = RecordingLocalStore.persistRecordingFile(
            context = appContext,
            username = username,
            fileName = target.fileName,
            sourceFile = tempFile,
        )
        tempFile.delete()
        return internalSaved
    }

    private fun cleanupRecorderLocked(deleteTemp: Boolean) {
        stopRecorderLocked()
        if (deleteTemp) {
            tempOutputFile?.delete()
        }
        tempOutputFile = null
        publicPersistTarget = null
        releaseDrawersLocked()
    }

    private fun releaseDrawersLocked() {
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
        const val START_TIMEOUT_SECONDS = 5L
        const val STOP_TIMEOUT_SECONDS = 15L
    }
}
