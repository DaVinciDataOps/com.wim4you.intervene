package com.wim4you.intervene.recording

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RecordingLocalStore {
    private const val DIRECTORY = "recordings"
    private const val LEGACY_LIVE_DIR = "live_recordings"
    private const val LEGACY_DISTRESS_DIR = "distress_recordings"
    private const val MIGRATION_FLAG = "recordings_migrated_v1"

    fun recordingsRoot(context: Context): File {
        return File(context.filesDir, DIRECTORY).apply { mkdirs() }
    }

    fun sessionDirectory(context: Context, sessionPath: String): File {
        return File(recordingsRoot(context), sessionPath).apply { mkdirs() }
    }

    fun createSessionPath(username: String, startedAtMillis: Long = System.currentTimeMillis()): String {
        val dateFolder = formatDateFolder(startedAtMillis)
        return "$dateFolder/${sanitizeUsername(username)}"
    }

    fun createRecordingFile(context: Context, username: String): File {
        migrateIfNeeded(context)
        val timestamp = System.currentTimeMillis()
        val sessionPath = createSessionPath(username, timestamp)
        val directory = sessionDirectory(context, sessionPath)
        return File(directory, "recording_$timestamp.mp4")
    }

    fun fileFor(context: Context, relativePath: String): File {
        return File(recordingsRoot(context), relativePath)
    }

    fun segmentFile(context: Context, sessionPath: String, segmentId: String): File {
        return File(sessionDirectory(context, sessionPath), "segment_$segmentId.mp4")
    }

    fun saveSessionMetadata(
        context: Context,
        sessionPath: String,
        distressId: String,
        distressAlias: String?,
        startedAtMillis: Long,
    ) {
        val metaFile = File(sessionDirectory(context, sessionPath), "session.meta")
        metaFile.writeText(
            listOf(
                distressId,
                distressAlias.orEmpty(),
                startedAtMillis.toString(),
            ).joinToString("\n"),
        )
    }

    fun listAll(context: Context): List<RecordingListItem> {
        migrateIfNeeded(context)
        val root = recordingsRoot(context)
        if (!root.exists()) return emptyList()

        val items = mutableListOf<RecordingListItem>()
        root.listFiles { file -> file.isDirectory && file.name.matches(Regex("\\d{8}")) }
            ?.forEach { dateDir ->
                dateDir.listFiles { file -> file.isDirectory }
                    ?.forEach { userDir ->
                        items += itemsFromUserDirectory(userDir, dateDir.name)
                    }
            }
        return items.sortedByDescending { it.sortKey }
    }

    fun listSegments(context: Context, sessionPath: String): List<File> {
        val dir = sessionDirectory(context, sessionPath)
        return dir.listFiles { file ->
            file.isFile && file.name.startsWith("segment_") && file.extension.equals("mp4", ignoreCase = true)
        }?.sortedBy { it.name }
            ?: emptyList()
    }

    fun deleteItem(context: Context, item: RecordingListItem): Boolean {
        return when (item) {
            is RecordingListItem.SingleRecording -> fileFor(context, item.relativePath).delete()
            is RecordingListItem.DistressSession -> sessionDirectory(context, item.sessionPath).deleteRecursively()
        }
    }

    fun formatTimestamp(millis: Long): String {
        val formatter = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        return formatter.format(Date(millis))
    }

    fun formatDuration(durationMillis: Long?): String {
        if (durationMillis == null || durationMillis <= 0L) return "--:--"
        val totalSeconds = durationMillis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    fun sanitizeUsername(name: String): String {
        return name.trim()
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .ifBlank { "unknown" }
    }

    private fun formatDateFolder(millis: Long): String {
        return SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(millis))
    }

    private fun itemsFromUserDirectory(userDir: File, dateFolder: String): List<RecordingListItem> {
        val username = userDir.name
        val sessionPath = "$dateFolder/$username"
        val items = mutableListOf<RecordingListItem>()

        val recordingFiles = userDir.listFiles { file ->
            file.isFile && file.name.startsWith("recording_") && file.extension.equals("mp4", ignoreCase = true)
        } ?: emptyArray()

        recordingFiles.forEach { file ->
            items += RecordingListItem.SingleRecording(
                relativePath = "$sessionPath/${file.name}",
                username = username,
                createdAtMillis = file.lastModified(),
                durationMillis = readDurationMillis(file),
            )
        }

        val segmentFiles = userDir.listFiles { file ->
            file.isFile && file.name.startsWith("segment_") && file.extension.equals("mp4", ignoreCase = true)
        } ?: emptyArray()

        if (segmentFiles.isNotEmpty()) {
            val metaFile = File(userDir, "session.meta")
            val startedAt = if (metaFile.exists()) {
                metaFile.readLines().getOrNull(2)?.toLongOrNull() ?: userDir.lastModified()
            } else {
                segmentFiles.minOf { it.lastModified() }
            }
            items += RecordingListItem.DistressSession(
                sessionPath = sessionPath,
                username = username,
                startedAtMillis = startedAt,
                segmentCount = segmentFiles.size,
            )
        }

        return items
    }

    private fun migrateIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences("recording_store", Context.MODE_PRIVATE)
        if (prefs.getBoolean(MIGRATION_FLAG, false)) return

        migrateLegacyLiveRecordings(context)
        migrateLegacyDistressRecordings(context)

        prefs.edit().putBoolean(MIGRATION_FLAG, true).apply()
    }

    private fun migrateLegacyLiveRecordings(context: Context) {
        val legacyDir = File(context.filesDir, LEGACY_LIVE_DIR)
        legacyDir.listFiles { file -> file.isFile && file.extension.equals("mp4", ignoreCase = true) }
            ?.forEach { file ->
                val timestamp = file.nameWithoutExtension.removePrefix("recording_").toLongOrNull()
                    ?: file.lastModified()
                val sessionPath = createSessionPath("unknown", timestamp)
                val target = File(sessionDirectory(context, sessionPath), file.name)
                file.copyTo(target, overwrite = true)
                file.delete()
            }
    }

    private fun migrateLegacyDistressRecordings(context: Context) {
        val legacyRoot = File(context.filesDir, LEGACY_DISTRESS_DIR)
        legacyRoot.listFiles { file -> file.isDirectory }
            ?.forEach { sessionDir ->
                val metaFile = File(sessionDir, "session.meta")
                val alias: String
                val startedAt: Long
                if (metaFile.exists()) {
                    val lines = metaFile.readLines()
                    alias = lines.getOrNull(1)?.ifBlank { "unknown" } ?: "unknown"
                    startedAt = lines.getOrNull(2)?.toLongOrNull() ?: sessionDir.lastModified()
                } else {
                    alias = "unknown"
                    startedAt = sessionDir.name.split("_").getOrNull(1)?.toLongOrNull()
                        ?: sessionDir.lastModified()
                }
                val sessionPath = createSessionPath(alias, startedAt)
                val targetDir = sessionDirectory(context, sessionPath)
                sessionDir.listFiles()?.forEach { file ->
                    file.copyTo(File(targetDir, file.name), overwrite = true)
                }
                sessionDir.deleteRecursively()
            }
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
