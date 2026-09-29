package com.michalkulik.mkbackup.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder

/**
 * Pins the wire format of the calls that carry the device folder name.
 *
 * The name is human readable (`Galaxy S23+`), so the interesting part is not the JSON but the
 * encoding: the space must reach the server as `%20`, the `+` must not be readable as a space in
 * either the path or the query, and the previous ids must survive a round trip through the query
 * string unchanged. Everything here is checked against the request exactly as it goes out.
 */
class BackupClientTest {

    private lateinit var server: HttpServer
    private var baseUrl: String = ""

    private var method: String = ""
    private var rawPath: String = ""
    private var rawQuery: String = ""
    private var body: String = ""

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `device folder is percent encoded in the path`() {
        BackupClient(baseUrl).use { client ->
            client.versions("Galaxy S23+", "set-1")
        }

        assertEquals("GET", method)
        assertEquals("/api/v1/sets/Galaxy%20S23%2B/set-1/versions", rawPath)
        assertEquals("", rawQuery)
    }

    @Test
    fun `previous folder ids arrive decoded exactly as stored`() {
        BackupClient(baseUrl, previousDeviceIds = listOf("old-uuid", "Galaxy S23+")).use { client ->
            client.manifest("Galaxy S23+", "set-1")
        }

        assertEquals("/api/v1/sets/Galaxy%20S23%2B/set-1/manifest", rawPath)
        assertEquals("old-uuid|Galaxy S23+", decodeQuery("previous"))
    }

    @Test
    fun `deleting a version addresses the same folder`() {
        BackupClient(baseUrl, previousDeviceIds = listOf("old-uuid")).use { client ->
            client.deleteVersion("Galaxy S23+", "set-1", 7)
        }

        assertEquals("DELETE", method)
        assertEquals("/api/v1/sets/Galaxy%20S23%2B/set-1/versions/7", rawPath)
        assertEquals("old-uuid", decodeQuery("previous"))
    }

    @Test
    fun `session request carries the previous folder ids`() {
        val request = StartSessionRequest(
            deviceId = "Galaxy S23+",
            deviceName = "Galaxy S23+",
            setId = "set-1",
            setName = "Zdjęcia",
            baseVersion = 3,
            keepVersions = 5,
            previousDeviceIds = listOf("old-uuid", "Galaxy S23+"),
        )

        BackupClient(baseUrl, previousDeviceIds = listOf("old-uuid", "Galaxy S23+")).use { client ->
            client.startSession(request)
        }

        assertEquals("POST", method)
        assertEquals("/api/v1/sessions", rawPath)
        assertTrue(body, body.contains("\"deviceId\":\"Galaxy S23+\""))
        assertTrue(body, body.contains("\"previousDeviceIds\":[\"old-uuid\",\"Galaxy S23+\"]"))
    }

    @Test
    fun `no previous is sent when there is nothing to move`() {
        BackupClient(baseUrl).use { client ->
            client.versions("device-1", "set-1")
        }

        assertEquals("/api/v1/sets/device-1/set-1/versions", rawPath)
        assertEquals("", rawQuery)
    }

    @Test
    fun `file path parameter keeps a plus sign intact`() {
        BackupClient(baseUrl).use { client ->
            client.uploadFile("session-1", "DCIM/a+b.jpg", "digest", "644", { byteArrayInputStream() })
        }

        assertEquals("PUT", method)
        assertEquals("DCIM/a+b.jpg", decodeQuery("path"))
    }

    // ------------------------------------------------------------------ helpers

    private fun handle(exchange: HttpExchange) {
        method = exchange.requestMethod
        rawPath = exchange.requestURI.rawPath
        rawQuery = exchange.requestURI.rawQuery.orEmpty()
        body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)

        val payload = when {
            rawPath.endsWith("/versions") -> """{"versions":[]}"""
            rawPath.endsWith("/manifest") -> """{"version":1,"entries":[]}"""
            rawPath.endsWith("/sessions") -> """{"sessionId":"session-1","baseVersion":0}"""
            rawPath.endsWith("/health") -> """{"status":"ok","version":"1.2.0"}"""
            else -> "{}"
        }
        // The upload call has a gzipped body; swallow it so the response can be written back.
        exchange.requestBody.readBytes()
        val bytes = payload.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun decodeQuery(name: String): String {
        val pair = rawQuery.split("&").firstOrNull { it.startsWith("$name=") } ?: return ""
        return URLDecoder.decode(pair.substringAfter('='), "UTF-8")
    }

    private fun byteArrayInputStream() = ByteArray(16) { it.toByte() }.inputStream()
}
