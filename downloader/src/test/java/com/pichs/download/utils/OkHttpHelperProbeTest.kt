package com.pichs.download.utils

import com.pichs.download.config.DownloadConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * 用裸 Socket 搭一个可控 HTTP 服务，专门覆盖 HEAD/Content-Length/Content-Range 的边界。
 */
class OkHttpHelperProbeTest {

    private class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>
    )

    private class Response(
        val code: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray? = null,
        val contentLength: Int? = null,
        val chunked: Boolean = false
    )

    private lateinit var serverSocket: ServerSocket
    private lateinit var serverThread: Thread
    private lateinit var baseUrl: String
    private val routes = ConcurrentHashMap<String, (Request) -> Response>()
    private val requests = CopyOnWriteArrayList<Request>()

    @Before
    fun setUp() {
        routes.clear()
        requests.clear()
        serverSocket = ServerSocket(0)
        serverThread = thread(name = "probe-test-server", isDaemon = true) {
            while (!serverSocket.isClosed) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: IOException) {
                    break
                }
                handle(socket)
            }
        }
        baseUrl = "http://127.0.0.1:${serverSocket.localPort}"
        OkHttpHelper.rebuildClient(DownloadConfig())
    }

    @After
    fun tearDown() {
        serverSocket.close()
        serverThread.join(1000)
        routes.clear()
        requests.clear()
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val headers = LinkedHashMap<String, String>()
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase()] =
                            line.substring(idx + 1).trim()
                    }
                }
                val request = Request(parts[0].uppercase(), parts[1], headers)
                requests += request
                val response = routes[request.path]?.invoke(request) ?: Response(404)
                write(s, response)
            }
        } catch (_: IOException) {
            // 客户端提前断开时忽略
        }
    }

    private fun write(socket: Socket, response: Response) {
        val out = socket.getOutputStream()
        val builder = StringBuilder()
        builder.append("HTTP/1.1 ${response.code} ${reason(response.code)}\r\n")
        response.headers.forEach { (key, value) ->
            builder.append("$key: $value\r\n")
        }
        when {
            response.chunked -> builder.append("Transfer-Encoding: chunked\r\n")
            response.contentLength != null -> builder.append("Content-Length: ${response.contentLength}\r\n")
            response.body != null -> builder.append("Content-Length: ${response.body.size}\r\n")
        }
        builder.append("Connection: close\r\n\r\n")
        out.write(builder.toString().toByteArray(Charsets.ISO_8859_1))
        val body = response.body
        if (body != null) {
            if (response.chunked) {
                out.write("${Integer.toHexString(body.size)}\r\n".toByteArray())
                out.write(body)
                out.write("\r\n0\r\n\r\n".toByteArray())
            } else {
                out.write(body)
            }
        }
        out.flush()
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        206 -> "Partial Content"
        405 -> "Method Not Allowed"
        416 -> "Range Not Satisfiable"
        else -> "Error"
    }

    private fun route(path: String, handler: (Request) -> Response) {
        routes[path] = handler
    }

    @Test
    fun `200 without content length is treated as unknown total`() {
        route("/unknown") { request ->
            if (request.method == "HEAD") {
                Response(405)
            } else {
                Response(
                    200,
                    headers = mapOf("ETag" to "\"unknown-etag\""),
                    body = "hello".toByteArray(),
                    chunked = true
                )
            }
        }

        val data = OkHttpHelper.getFileTotalLengthFromUrl("$baseUrl/unknown")

        assertEquals(-1L, data.contentLength)
        assertEquals("none", data.acceptRanges)
        assertEquals("\"unknown-etag\"", data.eTag)
    }

    @Test
    fun `head without content length falls back to ranged get`() {
        route("/range") { request ->
            if (request.method == "HEAD") {
                Response(200, headers = mapOf("ETag" to "\"head-etag\""))
            } else {
                assertEquals("bytes=0-0", request.headers["range"])
                Response(
                    206,
                    headers = mapOf(
                        "Content-Range" to "bytes 0-0/12345",
                        "ETag" to "\"range-etag\""
                    ),
                    body = byteArrayOf(0)
                )
            }
        }

        val data = OkHttpHelper.getFileTotalLengthFromUrl("$baseUrl/range")

        assertEquals(12345L, data.contentLength)
        assertEquals("bytes", data.acceptRanges)
        assertEquals("\"range-etag\"", data.eTag)
        assertEquals(listOf("HEAD", "GET"), requests.map { it.method })
    }

    @Test
    fun `range get with wildcard total yields unknown total`() {
        route("/wildcard") { request ->
            if (request.method == "HEAD") {
                Response(405)
            } else {
                Response(
                    206,
                    headers = mapOf("Content-Range" to "bytes 0-0/*"),
                    body = byteArrayOf(0)
                )
            }
        }

        val data = OkHttpHelper.getFileTotalLengthFromUrl("$baseUrl/wildcard")

        assertEquals(-1L, data.contentLength)
        assertEquals("bytes", data.acceptRanges)
    }

    @Test
    fun `416 without content range fails instead of treating file as empty`() {
        route("/bad416") { request ->
            if (request.method == "HEAD") Response(405) else Response(416)
        }

        try {
            OkHttpHelper.getFileTotalLengthFromUrl("$baseUrl/bad416")
            fail("expected IOException")
        } catch (_: IOException) {
            // expected
        }
    }

    @Test
    fun `416 with content range exposes remote total`() {
        route("/empty") { request ->
            if (request.method == "HEAD") {
                Response(405)
            } else {
                Response(416, headers = mapOf("Content-Range" to "bytes */999"))
            }
        }

        val data = OkHttpHelper.getFileTotalLengthFromUrl("$baseUrl/empty")

        assertEquals(999L, data.contentLength)
        assertEquals("none", data.acceptRanges)
    }

    @Test
    fun `200 response to range request disables range support`() {
        route("/ignore-range") { request ->
            if (request.method == "HEAD") {
                Response(405)
            } else {
                Response(200, body = "hello".toByteArray())
            }
        }

        val data = OkHttpHelper.getFileTotalLengthFromUrl("$baseUrl/ignore-range")

        assertEquals(5L, data.contentLength)
        assertEquals("none", data.acceptRanges)
    }

    @Test
    fun `head success returns validators and avoids get fallback`() {
        route("/head-ok") { request ->
            if (request.method == "HEAD") {
                Response(
                    200,
                    headers = mapOf(
                        "ETag" to "\"head-etag\"",
                        "Last-Modified" to "Wed, 21 Oct 2015 07:28:00 GMT",
                        "Accept-Ranges" to "bytes"
                    ),
                    contentLength = 1024
                )
            } else {
                throw AssertionError("GET fallback should not be used")
            }
        }

        val data = OkHttpHelper.getFileTotalLengthFromUrl("$baseUrl/head-ok")

        assertEquals(1024L, data.contentLength)
        assertEquals("bytes", data.acceptRanges)
        assertEquals("\"head-etag\"", data.eTag)
        assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", data.lastModified)
        assertEquals(listOf("HEAD"), requests.map { it.method })
    }

    @Test
    fun `task headers are sent during probe`() {
        route("/auth") { request ->
            assertEquals("Bearer token", request.headers["authorization"])
            assertEquals("identity", request.headers["accept-encoding"])
            if (request.method == "HEAD") {
                Response(200)
            } else {
                Response(
                    206,
                    headers = mapOf("Content-Range" to "bytes 0-0/2048"),
                    body = byteArrayOf(0)
                )
            }
        }

        val data = OkHttpHelper.getFileTotalLengthFromUrl(
            "$baseUrl/auth",
            mapOf("Authorization" to "Bearer token")
        )

        assertEquals(2048L, data.contentLength)
        assertNull(data.contentType)
        assertTrue(data.supportsRange())
    }

    @Test
    fun `global headers apply and task headers win`() {
        val cfg = DownloadConfig()
        cfg.globalHeaders["X-Global"] = "global"
        cfg.globalHeaders["Authorization"] = "global-token"
        OkHttpHelper.rebuildClient(cfg)
        route("/global") { request ->
            if (request.method == "HEAD") {
                Response(200)
            } else {
                Response(
                    206,
                    headers = mapOf("Content-Range" to "bytes 0-0/16"),
                    body = byteArrayOf(0)
                )
            }
        }

        OkHttpHelper.getFileTotalLengthFromUrl(
            "$baseUrl/global",
            mapOf("Authorization" to "task-token")
        )

        val probe = requests.first()
        assertEquals("global", probe.headers["x-global"])
        assertEquals("task-token", probe.headers["authorization"])
    }

    @Test
    fun `custom interceptor runs for probe requests`() {
        val cfg = DownloadConfig()
        cfg.requestInterceptors.add(okhttp3.Interceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("X-Custom", "intercepted")
                    .build()
            )
        })
        OkHttpHelper.rebuildClient(cfg)
        route("/interceptor") { request ->
            if (request.method == "HEAD") {
                Response(200)
            } else {
                Response(
                    206,
                    headers = mapOf("Content-Range" to "bytes 0-0/8"),
                    body = byteArrayOf(0)
                )
            }
        }

        OkHttpHelper.getFileTotalLengthFromUrl("$baseUrl/interceptor")

        assertEquals("intercepted", requests.first().headers["x-custom"])
    }
}
