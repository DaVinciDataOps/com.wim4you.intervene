package com.wim4you.intervene.liverecording

import android.content.Context
import com.wim4you.intervene.AppModeController
import com.wim4you.intervene.recording.RecordingLocalStore
import java.io.File

object LiveRecordingLocalStore {
    fun createRecordingFile(context: Context): File {
        val alias = AppModeController.person?.alias?.ifBlank { null } ?: "unknown"
        return RecordingLocalStore.createRecordingFile(context, alias)
    }

    fun fileFor(context: Context, relativePath: String): File {
        return RecordingLocalStore.fileFor(context, relativePath)
    }

    fun listRecordings(context: Context): List<LiveRecordingEntry> {
        return RecordingLocalStore.listAll(context)
            .filterIsInstance<com.wim4you.intervene.recording.RecordingListItem.SingleRecording>()
            .map { item ->
                LiveRecordingEntry(
                    id = item.relativePath,
                    filename = item.relativePath,
                    createdAtMillis = item.createdAtMillis,
                    durationMillis = item.durationMillis,
                )
            }
    }

    fun deleteRecording(context: Context, entry: LiveRecordingEntry): Boolean {
        return RecordingLocalStore.deleteItem(
            context,
            com.wim4you.intervene.recording.RecordingListItem.SingleRecording(
                relativePath = entry.filename,
                username = "",
                createdAtMillis = entry.createdAtMillis,
                durationMillis = entry.durationMillis,
            ),
        )
    }

    fun formatTimestamp(millis: Long) = RecordingLocalStore.formatTimestamp(millis)

    fun formatDuration(durationMillis: Long?) = RecordingLocalStore.formatDuration(durationMillis)
}
