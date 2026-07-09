package com.wim4you.intervene.security

import com.wim4you.intervene.BuildConfig
import com.wim4you.intervene.SecureLog

/**
 * Guards against accidental exposure of API keys in logs or crash reports.
 */
object ApiKeyGuard {

    fun redact(value: String?): String {
        if (value.isNullOrBlank()) return "[missing]"
        return "[redacted:${value.length}]"
    }

    fun redactUrlContainingKey(url: String): String {
        val key = BuildConfig.GOOGLE_DIRECTIONS_API_KEY
        return if (key.isNotBlank() && url.contains(key)) {
            url.replace(key, "[redacted-api-key]")
        } else {
            url
        }
    }

    fun logDirectionsFailure(message: String, url: String, throwable: Throwable? = null) {
        SecureLog.e(
            "DirectionsApiClient",
            "$message url=${redactUrlContainingKey(url)}",
            throwable,
        )
    }
}
