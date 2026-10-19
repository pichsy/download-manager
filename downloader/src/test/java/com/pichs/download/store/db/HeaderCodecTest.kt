package com.pichs.download.store.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderCodecTest {

    @Test
    fun `round trips arbitrary header characters`() {
        val headers = linkedMapOf(
            "Cookie" to "sid=abc; theme=dark",
            "Authorization" to "Bearer a:b:c",
            "X-Custom-中文" to "值: with separator",
            "X-Empty" to ""
        )

        val decoded = HeaderCodec.decode(HeaderCodec.encode(headers))

        assertEquals(headers, decoded)
    }

    @Test
    fun `empty headers encode to empty and decode to empty`() {
        assertEquals("", HeaderCodec.encode(emptyMap()))
        assertTrue(HeaderCodec.decode("").isEmpty())
    }

    @Test
    fun `malformed lines are ignored without throwing`() {
        val raw = listOf(
            "no-separator",
            ":missing-key",
            HeaderCodecLine("X-Good", "ok")
        ).joinToString("\n")

        val decoded = HeaderCodec.decode(raw)

        assertEquals(mapOf("X-Good" to "ok"), decoded)
    }

    private fun HeaderCodecLine(key: String, value: String): String {
        // 复用编码器格式构造一条合法行
        return HeaderCodec.encode(mapOf(key to value))
    }
}
