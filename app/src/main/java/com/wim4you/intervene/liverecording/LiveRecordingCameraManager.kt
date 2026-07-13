package com.wim4you.intervene.liverecording

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.wim4you.intervene.distressstream.DistressStreamConstants
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages CameraX preview and video capture for the live recording screen.
 */
class LiveRecordingCameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val listener: Listener,
    private val streamSegments: Boolean = false,
    private val onSegmentFinalized: ((File) -> Unit)? = null,
) {
    interface Listener {
        fun onRecordingStarted()
        fun onRecordingStopped()
        fun onError(message: String)
    }

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val segmentHandler = Handler(Looper.getMainLooper())
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var shouldKeepRecording = false
    private var pendingCameraSwitch = false
    private var pendingSegmentRotation = false
    private var lastOutputFile: File? = null
    private var sessionOutputFile: File? = null
    private val sessionPartFiles = mutableListOf<File>()
    private var sessionStartedAtMs = 0L
    private val isStopping = AtomicBoolean(false)

    private val segmentRotationRunnable = Runnable {
        if (shouldKeepRecording && streamSegments && !pendingCameraSwitch && activeRecording != null) {
            pendingSegmentRotation = true
            activeRecording?.stop()
        }
        scheduleSegmentRotation()
    }

    private val maxDurationRunnable = Runnable {
        if (shouldKeepRecording) {
            stop()
        }
    }

    fun start() {
        isStopping.set(false)
        shouldKeepRecording = true
        sessionStartedAtMs = System.currentTimeMillis()
        sessionOutputFile = LiveRecordingLocalStore.createRecordingFile(context)
        sessionPartFiles.clear()
        bindCamera(startRecording = true)
        scheduleSegmentRotation()
        scheduleMaxDuration()
    }

    fun stop() {
        if (!isStopping.compareAndSet(false, true)) return
        shouldKeepRecording = false
        pendingCameraSwitch = false
        pendingSegmentRotation = false
        segmentHandler.removeCallbacks(segmentRotationRunnable)
        segmentHandler.removeCallbacks(maxDurationRunnable)
        val recording = activeRecording
        if (recording != null) {
            recording.stop()
        } else {
            completeStop()
        }
    }

    fun switchCamera() {
        if (!shouldKeepRecording || pendingCameraSwitch || pendingSegmentRotation) return
        pendingCameraSwitch = true
        activeRecording?.stop()
    }

    fun release() {
        stop()
    }

    private fun scheduleSegmentRotation() {
        segmentHandler.removeCallbacks(segmentRotationRunnable)
        if (!shouldKeepRecording || !streamSegments) return
        segmentHandler.postDelayed(
            segmentRotationRunnable,
            DistressStreamConstants.SEGMENT_DURATION_MS,
        )
    }

    private fun scheduleMaxDuration() {
        segmentHandler.removeCallbacks(maxDurationRunnable)
        if (!shouldKeepRecording) return
        val remaining = LiveRecordingConstants.MAX_RECORDING_DURATION_MS -
            (System.currentTimeMillis() - sessionStartedAtMs)
        if (remaining <= 0L) {
            maxDurationRunnable.run()
            return
        }
        segmentHandler.postDelayed(maxDurationRunnable, remaining)
    }

    private fun bindCamera(startRecording: Boolean) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also { useCase ->
                    useCase.surfaceProvider = previewView.surfaceProvider
                }
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.SD))
                    .build()
                val capture = VideoCapture.withOutput(recorder)
                videoCapture = capture

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    capture,
                )

                if (startRecording && shouldKeepRecording) {
                    startRecordingToFile(capture)
                }
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to bind camera", exception)
                listener.onError(exception.message ?: "Camera error")
            }
        }, mainExecutor)
    }

    private fun startRecordingToFile(capture: VideoCapture<Recorder>) {
        val outputFile = createPartFile()
        lastOutputFile = outputFile
        sessionPartFiles.add(outputFile)
        val outputOptions = FileOutputOptions.Builder(outputFile).build()
        activeRecording = capture.output
            .prepareRecording(context, outputOptions)
            .withAudioEnabled()
            .start(cameraExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        if (sessionPartFiles.size == 1) {
                            mainExecutor.execute { listener.onRecordingStarted() }
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        val file = lastOutputFile
                        if (!event.hasError()) {
                            Log.i(TAG, "Recording part saved to ${file?.absolutePath}")
                            if (streamSegments && file != null && file.exists()) {
                                mainExecutor.execute { onSegmentFinalized?.invoke(file) }
                            }
                        } else {
                            Log.e(TAG, "Recording failed", event.cause)
                            file?.delete()
                            sessionPartFiles.remove(file)
                            if (!pendingSegmentRotation && !pendingCameraSwitch && !isStopping.get()) {
                                mainExecutor.execute {
                                    listener.onError(event.cause?.message ?: "Recording failed")
                                }
                            }
                        }
                        mainExecutor.execute { handleRecordingFinalized() }
                    }
                }
            }
    }

    private fun handleRecordingFinalized() {
        activeRecording = null
        if (pendingCameraSwitch && shouldKeepRecording) {
            pendingCameraSwitch = false
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            bindCamera(startRecording = true)
            return
        }
        if (pendingSegmentRotation && shouldKeepRecording) {
            pendingSegmentRotation = false
            bindCamera(startRecording = true)
            scheduleMaxDuration()
            return
        }
        if (isStopping.get() || !shouldKeepRecording) {
            completeStop()
        }
    }

    private fun completeStop() {
        cameraExecutor.execute {
            finalizeSession()
            mainExecutor.execute {
                releaseCamera()
                cameraExecutor.shutdown()
                listener.onRecordingStopped()
            }
        }
    }

    private fun releaseCamera() {
        try {
            cameraProvider?.unbindAll()
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to unbind camera", exception)
        }
        cameraProvider = null
        videoCapture = null
    }

    private fun createPartFile(): File {
        val partsDir = File(context.cacheDir, "live_recording_parts").apply { mkdirs() }
        return File(partsDir, "part_${System.currentTimeMillis()}.mp4")
    }

    private fun finalizeSession() {
        val output = sessionOutputFile
        val parts = sessionPartFiles.filter { it.exists() && it.length() > 0L }
        if (output == null) {
            cleanupPartFiles()
            return
        }
        if (parts.isEmpty()) {
            cleanupPartFiles()
            output.delete()
            return
        }
        val merged = RecordingMerger.mergeMp4Files(parts, output)
        cleanupPartFiles()
        if (!merged) {
            Log.e(TAG, "Failed to finalize recording session")
            output.delete()
        }
    }

    private fun cleanupPartFiles() {
        sessionPartFiles.forEach { it.delete() }
        sessionPartFiles.clear()
        File(context.cacheDir, "live_recording_parts").deleteRecursively()
    }

    private companion object {
        const val TAG = "LiveRecordingCamera"
    }
}
