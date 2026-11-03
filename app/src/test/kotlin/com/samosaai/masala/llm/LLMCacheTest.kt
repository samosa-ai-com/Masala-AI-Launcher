package com.samosaai.masala.llm

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Memory + disk response cache: TTL, LRU and clear. Disk needs a Context, so JVM tests use memory only. */
class LLMCacheTest {
    private fun response(text: String) = ChatResponse(
        choices = listOf(Choice(ChatMessage("assistant", JsonPrimitive(text)), "stop")),
    )

    @Test fun `a stored response comes back`() {
        val cache = LLMCache(context = null)
        cache.put("k", response("hi"))
        assertEquals("hi", cache.get("k")?.choices?.single()?.message?.textContent)
    }

    @Test fun `entries expire after their TTL`() {
        val cache = LLMCache(context = null, defaultTtlMs = 40)
        cache.put("k", response("hi"))
        assertTrue(cache.get("k") != null)
        Thread.sleep(120)
        assertNull(cache.get("k"))
    }

    @Test fun `a custom TTL on one entry leaves the default alone`() {
        val cache = LLMCache(context = null, defaultTtlMs = 10_000)
        cache.put("short", response("s"), ttlMs = 40)
        cache.put("long", response("l"))
        Thread.sleep(120)
        assertNull(cache.get("short"))
        assertEquals("l", cache.get("long")?.choices?.single()?.message?.textContent)
    }

    @Test fun `the oldest entry goes past the size cap`() {
        val cache = LLMCache(context = null, maxSize = 2, defaultTtlMs = 10_000)
        cache.put("a", response("a"))
        cache.put("b", response("b"))
        cache.put("c", response("c"))
        assertNull(cache.get("a"))
        assertEquals("b", cache.get("b")?.choices?.single()?.message?.textContent)
        assertEquals("c", cache.get("c")?.choices?.single()?.message?.textContent)
    }

    @Test fun `a fresh read keeps its entry alive`() {
        val cache = LLMCache(context = null, maxSize = 2, defaultTtlMs = 10_000)
        cache.put("a", response("a"))
        cache.put("b", response("b"))
        cache.get("a")
        cache.put("c", response("c"))
        assertNull(cache.get("b"))
        assertEquals("a", cache.get("a")?.choices?.single()?.message?.textContent)
    }

    @Test fun `clear empties everything`() {
        val cache = LLMCache(context = null)
        cache.put("a", response("a"))
        cache.put("b", response("b"))
        cache.clear()
        assertNull(cache.get("a"))
        assertNull(cache.get("b"))
    }

    @Test fun `a miss returns null`() {
        assertNull(LLMCache(context = null).get("nope"))
    }
}
