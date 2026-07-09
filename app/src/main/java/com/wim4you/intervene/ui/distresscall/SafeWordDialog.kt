package com.wim4you.intervene.ui.distresscall

import android.content.Context
import android.view.LayoutInflater
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.wim4you.intervene.R

object SafeWordDialog {

    fun show(
        context: Context,
        title: String,
        message: String,
        onConfirm: (safeWord: String) -> Unit,
    ) {
        val inputLayout = LayoutInflater.from(context).inflate(
            R.layout.dialog_safe_word_input,
            null,
        ) as TextInputLayout
        val editText = inputLayout.findViewById<TextInputEditText>(R.id.safeWordInput)

        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setView(inputLayout)
            .setPositiveButton(R.string.safe_word_confirm) { _, _ ->
                onConfirm(editText.text?.toString().orEmpty())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
