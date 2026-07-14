package com.wim4you.intervene.recording

import android.content.ContentUris
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

    fun findContentUri(context: Context, relativePath: String): android.net.Uri? {
        if (!isPublicPath(relativePath)) return null
        val path = fromPublicRelativePath(relativePath)
        val fileName = path.substringAfterLast('/')
        val sessionPath = path.substringBeforeLast('/')
        val relativeDirectory = "${Environment.DIRECTORY_MOVIES}/$ROOT_DIR/$sessionPath/"
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.DISPLAY_NAME} = ?",
            arrayOf("%$relativeDirectory%", fileName),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(0),
                )
            }
        }
        return null
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

    fun listAll(context: Context): List<RecordingListItem.SingleRecording> {
        val items = linkedMapOf<String, RecordingListItem.SingleRecording>()
        listFromMediaStore(context).forEach { item ->
            items[item.relativePath] = item
        }
        listFromFileSystem().forEach { item ->
            items.putIfAbsent(item.relativePath, item)
        }
        return items.values.sortedByDescending { it.sortKey }
    }

    fun delete(context: Context, relativePath: String): Boolean {
        if (!isPublicPath(relativePath)) return false
        val file = fileFor(relativePath)
        var deleted = file.delete()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.DISPLAY_NAME} = ?"
        val path = fromPublicRelativePath(relativePath)
        val fileName = path.substringAfterLast('/')
        val sessionPath = path.substringBeforeLast('/')
        val relativeDirectory = "${Environment.DIRECTORY_MOVIES}/$ROOT_DIR/$sessionPath/"
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Video.Media._ID),
            selection,
            arrayOf("%$relativeDirectory%", fileName),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val uri = ContentUris.withAppendedId(collection, id)
                deleted = context.contentResolver.delete(uri, null, null) > 0 || deleted
            }
        }
        return deleted
    }

    private fun listFromMediaStore(context: Context): List<RecordingListItem.SingleRecording> {
        val items = mutableListOf<RecordingListItem.SingleRecording>()
        val prefix = "${Environment.DIRECTORY_MOVIES}/$ROOT_DIR/"
        val projection = arrayOf(
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DURATION,
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("$prefix%", "recording_%")

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Video.Media.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)

            while (cursor.moveToNext()) {
                val relativePath = cursor.getString(pathCol) ?: continue
                val normalized = relativePath.replace('\\', '/').trimEnd('/')
                val marker = "/$ROOT_DIR/"
                val markerIndex = normalized.indexOf(marker)
                if (markerIndex < 0) continue
                val sessionPath = normalized.substring(markerIndex + marker.length)
                if (!sessionPath.matches(Regex("\\d{8}/[^/]+"))) continue

                val fileName = cursor.getString(nameCol) ?: continue
                val username = sessionPath.substringAfter('/')
                val modifiedSeconds = cursor.getLong(modifiedCol)
                val durationMs = cursor.getLong(durationCol).takeIf { it > 0L }

                items += RecordingListItem.SingleRecording(
                    relativePath = toPublicRelativePath(sessionPath, fileName),
                    username = username,
                    createdAtMillis = modifiedSeconds * 1000L,
                    durationMillis = durationMs,
                )
            }
        }
        return items
    }

    private fun listFromFileSystem(): List<RecordingListItem.SingleRecording> {
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
