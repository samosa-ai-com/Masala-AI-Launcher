package com.samosaai.masala.core

import com.samosaai.masala.core.layout.ResolveCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ResolveCacheTest {
    private val doc = Fixtures.layout("workday")
    private val focus = Fixtures.mode("mode-focus")

    @Test
    fun `the same version modes and snapshot resolve once`() {
        val cache = ResolveCache()
        val a = cache.resolve(1, doc, emptyList(), Fixtures.at("09:00"))
        val b = cache.resolve(1, doc, emptyList(), Fixtures.at("09:00"))
        assertSame(a, b)
        assertEquals(1, cache.misses)
    }

    @Test
    fun `a new version snapshot or mode set resolves again`() {
        val cache = ResolveCache()
        cache.resolve(1, doc, emptyList(), Fixtures.at("09:00"))
        cache.resolve(2, doc, emptyList(), Fixtures.at("09:00"))
        cache.resolve(2, doc, emptyList(), Fixtures.at("09:01"))
        cache.resolve(2, doc, listOf(focus), Fixtures.at("09:01"))
        assertEquals(4, cache.misses)
    }

    @Test
    fun `only the newest few results are kept`() {
        val cache = ResolveCache(capacity = 2)
        repeat(3) { cache.resolve(it.toLong(), doc, emptyList(), Fixtures.at("09:00")) }
        cache.resolve(0, doc, emptyList(), Fixtures.at("09:00"))
        assertEquals(4, cache.misses)
    }
}
