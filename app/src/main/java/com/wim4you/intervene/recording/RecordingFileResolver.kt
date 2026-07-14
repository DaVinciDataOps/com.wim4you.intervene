package com.wim4you.intervene.recording

import android.content.Context
import java.io.File

object RecordingFileResolver {
    fun resolve(context: Context, relativePath: String): File? {
        return if (PublicVideoStore.isPublicPath(relativePath)) {
            PublicVideoStore.fileFor(relativePath).takeIf { it.exists() }
        } else {
            RecordingLocalStore.fileFor(context, relativePath).takeIf { it.exists() }
        }
    }

    fun delete(context: Context, item: RecordingListItem.SingleRecording): Boolean {
        return if (PublicVideoStore.isPublicPath(item.relativePath)) {
            PublicVideoStore.delete(item.relativePath)
        } else {
            RecordingLocalStore.deleteItem(context, item)
        }
    }
}
