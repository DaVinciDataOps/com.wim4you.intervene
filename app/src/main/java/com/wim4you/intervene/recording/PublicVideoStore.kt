package com.wim4you.intervene.recording

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import com.wim4you.intervene.SecureLog
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stores patrol distress-stream recordings in public storage so they appear in My Files → Videos:
 * Movies/video/yyyyMMdd/username/recording_*.mp4
 */
object PublicVideoStore {
    private const val TAG = "PublicVideoStore"
    private const val ROOT_DIR = "video"
    const val PATH_PREFIX = "public/"

    fun recordingsRoot(): File {
        return File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            ROOT_DIR,
        )
    }

    fun sessionDirectory(sessionPath: String): File {
        return File(recordingsRoot(), sessionPath).apply { mkdirs() }
    }

    fun createSessionPath(username: String, startedAtMillis: Long = System.currentTimeMillis()): String {
        val dateFolder = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(startedAtMillis))
        return "$dateFolder/${RecordingLocalStore.sanitizeUsername(username)}"
    }

    fun createRecordingFileName(timestamp: Long = System.currentTimeMillis()): String {
        return "recording_$timestamp.mp4"
    }

    fun toPublicRelativePath(sessionPath: String, fileName: String): String {
        return "$PATH_PREFIX$sessionPath/$fileName"
    }

    fun fromPublicRelativePath(relativePath: String): String {
        return relativePath.removePrefix(PATH_PREFIX)
    }

    fun fileFor(relativePath: String): File {
        return File(recordingsRoot(), fromPublicRelativePath(relativePath))
    }

    fun isPublicPath(relativePath: String): Boolean {
        return relativePath.startsWith(PATH_PREFIX)
    }

    fun persistRecording(
        context: Context,
        sessionPath: String,
        fileName: String,
        sourceFile: File,
    ): File? {
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            sourceFile.delete()
            return null
        }

        val mediaRelativePath = "${Environment.DIRECTORY_MOVIES}/$ROOT_DIR/$sessionPath/"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, mediaRelativePath)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val uri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            values,
        )

        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    sourceFile.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IOException("Unable to open MediaStore output stream")
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                sourceFile.delete()
                val destination = File(sessionDirectory(sessionPath), fileName)
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(destination.absolutePath),
                    arrayOf("video/mp4"),
                    null,
                )
                SecureLog.i(TAG, "Saved patrol recording to ${destination.absolutePath}")
                return destination
            } catch (exception: Exception) {
                SecureLog.e(TAG, "MediaStore save failed for $fileName, falling back to direct copy", exception)
                context.contentResolver.delete(uri, null, null)
            }
        }

        return persistDirect(sessionPath, fileName, sourceFile)
    }

    fun listAll(): List<RecordingListItem.SingleRecording> {
        val root = recordingsRoot()
        root.mkdirs()
        if (!root.exists()) return emptyList()

        val items = mutableListOf<RecordingListItem.SingleRecording>()
        root.listFiles { file -> file.isDirectory && file.name.matches(Regex("\\d{8}")) }
            ?.forEach { dateDir ->
                dateDir.listFiles { file -> file.isDirectory }
                    ?.forEach { userDir ->
                        items += itemsFromUserDirectory(userDir, dateDir.name)
                    }
            }
        return items
    }

    fun delete(relativePath: String): Boolean {
        if (!isPublicPath(relativePath)) return false
        return fileFor(relativePath).delete()
    }

    private fun persistDirect(sessionPath: String, fileName: String, sourceFile: File): File? {
        return try {
            val destination = File(sessionDirectory(sessionPath), fileName)
            sourceFile.copyTo(destination, overwrite = true)
            sourceFile.delete()
            SecureLog.i(TAG, "Saved patrol recording via direct copy to ${destination.absolutePath}")
            destination
        } catch (exception: Exception) {
            SecureLog.e(TAG, "Direct patrol video save failed for $fileName", exception)
            null
        }
    }

    private fun itemsFromUserDirectory(userDir: File, dateFolder: String): List<RecordingListItem.SingleRecording> {
        val username = userDir.name
        val sessionPath = "$dateFolder/$username"
        return userDir.listFiles { file ->
            file.isFile && file.name.startsWith("recording_") && file.extension.equals("mp4", ignoreCase = true)
        }?.map { file ->
            RecordingListItem.SingleRecording(
                relativePath = toPublicRelativePath(sessionPath, file.name),
                username = username,
                createdAtMillis = file.lastModified(),
                durationMillis = readDurationMillis(file),
            )
        } ?: emptyList()
    }

    private fun readDurationMillis(file: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}
