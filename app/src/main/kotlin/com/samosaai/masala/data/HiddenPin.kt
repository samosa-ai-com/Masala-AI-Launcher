package com.samosaai.masala.data

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The curtain in front of the Hidden apps page: a 4–8 digit PIN, stored as `salt:sha256(salt + pin)`.
 * It keeps the list out of casual view; it is not encryption — the apps are still installed and the
 * system launcher, Settings and adb can see them.
 */
object HiddenPin {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 8

    fun isValid(pin: String): Boolean = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it.isDigit() }

    /** What is stored for [pin]: a fresh salt and its hash, separated by a colon. */
    fun store(pin: String): String? {
        if (!isValid(pin)) return null
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        return "$salt:${hash(salt, pin)}"
    }

    /** Whether [pin] opens [stored]; false when nothing is stored. */
    fun matches(stored: String?, pin: String): Boolean {
        val salt = stored?.substringBefore(':') ?: return false
        val expected = stored.substringAfter(':', "")
        if (salt.isEmpty() || expected.isEmpty()) return false
        return MessageDigest.isEqual(expected.toByteArray(), hash(salt, pin).toByteArray())
    }

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray()).joinToString("") { "%02x".format(it) }
}
