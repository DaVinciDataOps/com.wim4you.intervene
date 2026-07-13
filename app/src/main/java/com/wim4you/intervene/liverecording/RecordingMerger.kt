package com.wim4you.intervene.liverecording

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

object RecordingMerger {
    private const val TAG = "RecordingMerger"

    fun mergeMp4Files(sources: List<File>, output: File): Boolean {
        val validSources = sources.filter { it.exists() && it.length() > 0L }
        if (validSources.isEmpty()) return false

        output.parentFile?.mkdirs()
        if (validSources.size == 1) {
            validSources[0].copyTo(output, overwrite = true)
            return true
        }

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerVideoTrack = -1
        var muxerAudioTrack = -1

        try {
            val setupExtractor = MediaExtractor()
            setupExtractor.setDataSource(validSources[0].absolutePath)
            for (trackIndex in 0 until setupExtractor.trackCount) {
                val format = setupExtractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("video/") && muxerVideoTrack == -1 ->
                        muxerVideoTrack = muxer.addTrack(format)
                    mime.startsWith("audio/") && muxerAudioTrack == -1 ->
                        muxerAudioTrack = muxer.addTrack(format)
                }
            }
            setupExtractor.release()

            if (muxerVideoTrack == -1 && muxerAudioTrack == -1) return false
            muxer.start()

            var timeOffsetUs = 0L
            for (source in validSources) {
                val segmentDurationUs = appendSource(
                    muxer = muxer,
                    source = source,
                    muxerVideoTrack = muxerVideoTrack,
                    muxerAudioTrack = muxerAudioTrack,
                    timeOffsetUs = timeOffsetUs,
                )
                timeOffsetUs += segmentDurationUs
            }

            muxer.stop()
            return true
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to merge recordings", exception)
            output.delete()
            return false
        } finally {
            try {
                muxer.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun appendSource(
        muxer: MediaMuxer,
        source: File,
        muxerVideoTrack: Int,
        muxerAudioTrack: Int,
        timeOffsetUs: Long,
    ): Long {
        val extractor = MediaExtractor()
        extractor.setDataSource(source.absolutePath)

        var sourceVideoTrack = -1
        var sourceAudioTrack = -1
        for (trackIndex in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            when {
                mime.startsWith("video/") -> sourceVideoTrack = trackIndex
                mime.startsWith("audio/") -> sourceAudioTrack = trackIndex
            }
        }

        var maxEndUs = 0L
        val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()

        listOf(
            sourceVideoTrack to muxerVideoTrack,
            sourceAudioTrack to muxerAudioTrack,
        ).forEach { (sourceTrack, destinationTrack) ->
            if (sourceTrack < 0 || destinationTrack < 0) return@forEach
            extractor.selectTrack(sourceTrack)
            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break
                bufferInfo.presentationTimeUs = extractor.sampleTime + timeOffsetUs
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(destinationTrack, buffer, bufferInfo)
                maxEndUs = maxOf(maxEndUs, bufferInfo.presentationTimeUs)
                extractor.advance()
            }
            extractor.unselectTrack(sourceTrack)
        }

        extractor.release()
        return if (maxEndUs > timeOffsetUs) maxEndUs - timeOffsetUs else 0L
    }
}
