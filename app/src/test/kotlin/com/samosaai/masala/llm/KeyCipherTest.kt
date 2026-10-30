package com.samosaai.masala.llm

import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyCipherTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = KeyCipher { key }

    @Test
    fun `a key survives sealing and opening, and the stored text doesn't contain it`() {
        val secret = "sk-test-0123456789abcdef"
        val stored = cipher.encrypt(secret)
        assertTrue(secret !in stored)
        assertEquals(secret, cipher.decrypt(stored))
    }

    @Test
    fun `each seal uses a fresh IV`() {
        assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"))
    }

    @Test
    fun `tampered text, another key or garbage opens to nothing`() {
        val stored = cipher.encrypt("secret")
        val bytes = java.util.Base64.getDecoder().decode(stored)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        assertNull(cipher.decrypt(java.util.Base64.getEncoder().encodeToString(bytes)))
        val other = KeyCipher { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        assertNull(other.decrypt(stored))
        assertNull(cipher.decrypt("not base64 at all!"))
    }

    @Test
    fun `unicode keys round-trip`() {
        assertEquals("ключ-🔑", cipher.decrypt(cipher.encrypt("ключ-🔑")))
    }
}
