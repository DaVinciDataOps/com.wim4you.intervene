package com.wim4you.intervene.helpers

import java.security.MessageDigest

object SafeWordHasher {

    fun hash(safeWord: String): String {
        val normalized = safeWord.trim().lowercase()
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(normalized.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun matches(safeWord: String, storedHash: String?): Boolean {
        if (storedHash.isNullOrBlank()) return false
        return hash(safeWord) == storedHash
    }
}
