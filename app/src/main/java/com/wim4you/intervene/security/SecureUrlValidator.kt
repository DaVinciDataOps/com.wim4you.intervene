package com.wim4you.intervene.security

import android.net.Uri
import java.util.Locale

/**
 * Validates remote URLs before opening network connections.
 */
object SecureUrlValidator {

    private val ALLOWED_DOWNLOAD_HOSTS = setOf(
        "firebasestorage.googleapis.com",
        "storage.googleapis.com",
    )

    private val ALLOWED_IMAGE_HOST_SUFFIXES = listOf(
        ".googleusercontent.com",
        ".firebasestorage.app",
    )

    fun isAllowedHttpsDownloadUrl(url: String): Boolean {
        val host = httpsHost(url) ?: return false
        return host in ALLOWED_DOWNLOAD_HOSTS
    }

    fun isAllowedRemoteImageUrl(url: String): Boolean {
        val host = httpsHost(url) ?: return false
        if (host in ALLOWED_DOWNLOAD_HOSTS) return true
        return ALLOWED_IMAGE_HOST_SUFFIXES.any { suffix -> host.endsWith(suffix) }
    }

    fun isSafeStorageChildPath(path: String, expectedRoot: String): Boolean {
        if (path.isBlank() || path.contains("..")) return false
        val normalized = path.trim('/')
        val root = expectedRoot.trim('/')
        return normalized == root || normalized.startsWith("$root/")
    }

    private fun httpsHost(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        return uri.host?.lowercase(Locale.US)
    }
}
