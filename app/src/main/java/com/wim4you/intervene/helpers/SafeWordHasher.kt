package com.wim4you.intervene.helpers

import java.security.MessageDigest

object SafeWordHasher {

    private const val SALT_PREFIX = "intervene:v1:"

    fun hash(safeWord: String): String {
        return digest(SALT_PREFIX + normalize(safeWord))
    }

    fun matches(safeWord: String, storedHash: String?): Boolean {
        if (storedHash.isNullOrBlank()) return false
        if (hash(safeWord) == storedHash) return true
        return legacyHash(safeWord) == storedHash
    }

    private fun legacyHash(safeWord: String): String {
        return digest(normalize(safeWord))
    }

    private fun normalize(safeWord: String): String = safeWord.trim().lowercase()

    private fun digest(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
