package com.wim4you.intervene.ui.common

import android.content.Context
import androidx.annotation.StringRes

sealed class UiMessage {
    data class Resource(
        @StringRes val resId: Int,
        val formatArgs: List<Any> = emptyList(),
    ) : UiMessage()

    fun resolve(context: Context): String {
        return when (this) {
            is Resource -> {
                val args = formatArgs.toTypedArray()
                if (args.isEmpty()) {
                    context.getString(resId)
                } else {
                    context.getString(resId, *args)
                }
            }
        }
    }
}
