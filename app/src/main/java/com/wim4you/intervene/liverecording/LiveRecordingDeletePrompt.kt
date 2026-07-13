package com.wim4you.intervene.liverecording

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wim4you.intervene.R

object LiveRecordingDeletePrompt {
    fun show(context: Context, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.live_recording_delete_title)
            .setMessage(R.string.live_recording_delete_message)
            .setPositiveButton(R.string.live_recording_delete_confirm) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
