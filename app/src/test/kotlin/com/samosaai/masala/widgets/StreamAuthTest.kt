package com.samosaai.masala.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Review fix B1: credentials leave the URL and arrive as a Basic header. */
class StreamAuthTest {
    @Test
    fun `userinfo becomes a basic header and leaves the url`() {
        val (clean, auth) = StreamAuth.split("http://cam:secret@192.168.1.20/mjpeg")
        assertEquals("http://192.168.1.20/mjpeg", clean)
        assertEquals("Basic Y2FtOnNlY3JldA==", auth)
    }

    @Test
    fun `anonymous urls pass through`() {
        val (clean, auth) = StreamAuth.split("http://192.168.1.20/snap.jpg")
        assertEquals("http://192.168.1.20/snap.jpg", clean)
        assertNull(auth)
    }

    @Test
    fun `bare username gets the colon`() {
        val (clean, auth) = StreamAuth.split("https://admin@[2001:db8::1]:8443/x")
        assertEquals("https://[2001:db8::1]:8443/x", clean)
        assertEquals("Basic YWRtaW46", auth)
    }

    @Test
    fun `garbage urls pass through`() {
        val (clean, auth) = StreamAuth.split("not a url")
        assertEquals("not a url", clean)
        assertNull(auth)
    }
}
