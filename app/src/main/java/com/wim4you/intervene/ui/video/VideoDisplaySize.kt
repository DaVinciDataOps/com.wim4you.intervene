package com.wim4you.intervene.ui.video

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File

object VideoDisplaySize {
    fun resolve(
        context: Context,
        uri: Uri,
        fallbackWidth: Int = 0,
        fallbackHeight: Int = 0,
        preferPortraitDisplay: Boolean = false,
    ): Pair<Int, Int> {
        if (fallbackWidth > 0 && fallbackHeight > 0 && fallbackHeight > fallbackWidth) {
            return fallbackWidth to fallbackHeight
        }

        val fromMetadata = readFromExtractor(context, uri, preferPortraitDisplay)
            ?: readFromRetriever(context, uri, preferPortraitDisplay)
        if (fromMetadata != null && fromMetadata.first > 0 && fromMetadata.second > 0) {
            return fromMetadata
        }

        return displayDimensions(fallbackWidth, fallbackHeight, 0, preferPortraitDisplay)
    }

    fun resolve(
        file: File,
        fallbackWidth: Int = 0,
        fallbackHeight: Int = 0,
        preferPortraitDisplay: Boolean = false,
    ): Pair<Int, Int> {
        if (fallbackWidth > 0 && fallbackHeight > 0 && fallbackHeight > fallbackWidth) {
            return fallbackWidth to fallbackHeight
        }

        val fromMetadata = readFromExtractor(file, preferPortraitDisplay)
            ?: readFromRetriever(file, preferPortraitDisplay)
        if (fromMetadata != null && fromMetadata.first > 0 && fromMetadata.second > 0) {
            return fromMetadata
        }

        return displayDimensions(fallbackWidth, fallbackHeight, 0, preferPortraitDisplay)
    }

    private fun readFromExtractor(context: Context, uri: Uri, preferPortraitDisplay: Boolean): Pair<Int, Int>? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            readVideoTrack(extractor, preferPortraitDisplay)
        } catch (_: Exception) {
            null
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun readFromExtractor(file: File, preferPortraitDisplay: Boolean): Pair<Int, Int>? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            readVideoTrack(extractor, preferPortraitDisplay)
        } catch (_: Exception) {
            null
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun readVideoTrack(extractor: MediaExtractor, preferPortraitDisplay: Boolean): Pair<Int, Int>? {
        for (trackIndex in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("video/")) continue

            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                format.getInteger(MediaFormat.KEY_ROTATION)
            } else {
                0
            }
            return displayDimensions(width, height, rotation, preferPortraitDisplay)
        }
        return null
    }

    private fun readFromRetriever(context: Context, uri: Uri, preferPortraitDisplay: Boolean): Pair<Int, Int>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            readRetrieverMetadata(retriever, preferPortraitDisplay)
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun readFromRetriever(file: File, preferPortraitDisplay: Boolean): Pair<Int, Int>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            readRetrieverMetadata(retriever, preferPortraitDisplay)
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun readRetrieverMetadata(
        retriever: MediaMetadataRetriever,
        preferPortraitDisplay: Boolean,
    ): Pair<Int, Int>? {
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull()
            ?: return null
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull()
            ?: return null
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull()
            ?: 0
        return displayDimensions(width, height, rotation, preferPortraitDisplay)
    }

    private fun displayDimensions(
        width: Int,
        height: Int,
        rotation: Int,
        preferPortraitDisplay: Boolean,
    ): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        return when {
            rotation == 90 || rotation == 270 -> height to width
            preferPortraitDisplay && width > height -> height to width
            else -> width to height
        }
    }
}
