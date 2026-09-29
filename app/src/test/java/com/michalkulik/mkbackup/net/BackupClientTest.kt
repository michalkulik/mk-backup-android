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
 * string unchanged. The other half is the negotiation: a server too old for readable folders must
 * keep getting the random id it has always known, so installing the app alone can never leave a
 * backup split across two directories. Everything here is checked against the request exactly as
 * it goes out.
 */
class BackupClientTest {

    private lateinit var server: HttpServer
    private var baseUrl: String = ""

    /** Version the stub reports from `GET /api/v1/health`. */
    private var healthVersion: String = "1.2.0"

    /** When set, `GET /api/v1/health` answers with 500 instead of a version. */
    private var healthFails: Boolean = false

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

    // ------------------------------------------------------- readable folders

    @Test
    fun `device folder is percent encoded in the path`() {
        namedClient().use { it.versions("set-1") }

        assertEquals("GET", method)
        assertEquals("/api/v1/sets/Galaxy%20S23%2B/set-1/versions", rawPath)
        assertEquals("", rawQuery)
    }

    @Test
    fun `previous folder ids arrive decoded exactly as stored`() {
        namedClient(previous = listOf("old-uuid", "Galaxy S23+")).use { it.manifest("set-1") }

        assertEquals("/api/v1/sets/Galaxy%20S23%2B/set-1/manifest", rawPath)
        assertEquals("old-uuid|Galaxy S23+", decodeQuery("previous"))
    }

    @Test
    fun `deleting a version addresses the same folder`() {
        namedClient(previous = listOf("old-uuid")).use { it.deleteVersion("set-1", 7) }

        assertEquals("DELETE", method)
        assertEquals("/api/v1/sets/Galaxy%20S23%2B/set-1/versions/7", rawPath)
        assertEquals("old-uuid", decodeQuery("previous"))
    }

    @Test
    fun `session request carries the folder name and the previous ids`() {
        val request = StartSessionRequest(
            deviceId = "",
            deviceName = "Galaxy S23+",
            setId = "set-1",
            setName = "Zdjęcia",
            baseVersion = 3,
            keepVersions = 5,
            previousDeviceIds = listOf("old-uuid", "Galaxy S23+"),
        )

        namedClient(previous = listOf("old-uuid", "Galaxy S23+")).use { client ->
            // The engine fills the id in from the negotiated one, never by hand.
            val withId = request.copy(deviceId = client.deviceId())
            client.startSession(withId)
        }

        assertEquals("POST", method)
        assertEquals("/api/v1/sessions", rawPath)
        assertTrue(body, body.contains("\"deviceId\":\"Galaxy S23+\""))
        assertTrue(body, body.contains("\"previousDeviceIds\":[\"old-uuid\",\"Galaxy S23+\"]"))
    }

    @Test
    fun `no previous is sent when there is nothing to move`() {
        BackupClient(baseUrl, folderId = "device-1").use { it.versions("set-1") }

        assertEquals("/api/v1/sets/device-1/set-1/versions", rawPath)
        assertEquals("", rawQuery)
    }

    @Test
    fun `file path parameter keeps a plus sign intact`() {
        namedClient().use {
            it.uploadFile("session-1", "DCIM/a+b.jpg", "digest", "644", { byteArrayInputStream() })
        }

        assertEquals("PUT", method)
        assertEquals("DCIM/a+b.jpg", decodeQuery("path"))
    }

    // ------------------------------------------------------- older server

    @Test
    fun `older server is still addressed by the random id`() {
        healthVersion = "1.1.0"

        namedClient(previous = listOf("old-uuid")).use { client ->
            assertEquals("legacy-uuid", client.deviceId())
            client.versions("set-1")
        }

        assertEquals("/api/v1/sets/legacy-uuid/set-1/versions", rawPath)
        assertEquals("", rawQuery)
    }

    @Test
    fun `a two digit minor version counts as newer, not older`() {
        // A plain string comparison would read "1.10.0" < "1.2.0" and pick the wrong folder.
        healthVersion = "1.10.0"

        namedClient().use { client ->
            assertEquals("Galaxy S23+", client.deviceId())
        }
    }

    @Test
    fun `a server that does not report a version is treated as an old one`() {
        healthVersion = ""

        namedClient().use { client ->
            assertEquals("legacy-uuid", client.deviceId())
            client.versions("set-1")
        }

        assertEquals("/api/v1/sets/legacy-uuid/set-1/versions", rawPath)
    }

    @Test
    fun `unknown folder ids fall back rather than being sent empty`() {
        healthVersion = "1.1.0"

        BackupClient(baseUrl, folderId = "Galaxy S23+").use { client ->
            // No legacy id available: the readable one is the only thing to address.
            assertEquals("Galaxy S23+", client.deviceId())
        }
    }

    @Test
    fun `a broken health check surfaces instead of choosing the wrong folder`() {
        healthFails = true

        try {
            namedClient().use { it.versions("set-1") }
            throw AssertionError("expected the health probe to fail the call")
        } catch (expected: BackupApiException) {
            assertTrue(expected.message.orEmpty(), expected.message.orEmpty().contains("HTTP 500"))
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun namedClient(previous: List<String> = emptyList()) = BackupClient(
        baseUrl,
        folderId = "Galaxy S23+",
        legacyId = { "legacy-uuid" },
        previousIds = previous,
    )

    private fun handle(exchange: HttpExchange) {
        method = exchange.requestMethod
        rawPath = exchange.requestURI.rawPath
        rawQuery = exchange.requestURI.rawQuery.orEmpty()
        body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        // The upload call has a gzipped body; swallow it so the response can be written back.
        exchange.requestBody.readBytes()

        if (rawPath.endsWith("/health")) {
            respond(exchange, if (healthFails) 500 else 200, """{"status":"ok","version":"$healthVersion"}""")
            return
        }

        val payload = when {
            rawPath.endsWith("/versions") -> """{"versions":[]}"""
            rawPath.endsWith("/manifest") -> """{"version":1,"entries":[]}"""
            rawPath.endsWith("/sessions") -> """{"sessionId":"session-1","baseVersion":0}"""
            else -> "{}"
        }
        respond(exchange, 200, payload)
    }

    private fun respond(exchange: HttpExchange, status: Int, payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun decodeQuery(name: String): String {
        val pair = rawQuery.split("&").firstOrNull { it.startsWith("$name=") } ?: return ""
        return URLDecoder.decode(pair.substringAfter('='), "UTF-8")
    }

    private fun byteArrayInputStream() = ByteArray(16) { it.toByte() }.inputStream()
}
