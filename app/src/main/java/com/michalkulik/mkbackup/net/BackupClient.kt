package com.michalkulik.mkbackup.net

import com.michalkulik.mkbackup.core.ManifestEntry
import com.michalkulik.mkbackup.core.RemoteVersion
import com.michalkulik.mkbackup.core.normalizeServerUrl
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.gzip
import okio.source
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Raised for every non-successful response so the worker can surface a readable message. */
class BackupApiException(message: String) : Exception(message)

/**
 * Thin, synchronous client for the mk-backup server.
 *
 * Every call blocks on purpose: it is only ever invoked from a coroutine that already runs on
 * `Dispatchers.IO`.
 */
class BackupClient(
    baseUrl: String,
    private val token: String = "",
) : Closeable {

    private val root = normalizeServerUrl(baseUrl)

    /**
     * The call currently on the wire, if any. A blocking OkHttp call ignores coroutine
     * cancellation, so [cancel] is what actually interrupts an upload or a connection attempt
     * (for example to a mistyped URL) as soon as the user presses Stop.
     */
    private val activeCall = AtomicReference<Call?>(null)

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // Uploads can legitimately run for many minutes on a slow mobile link.
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ------------------------------------------------------------------- calls

    fun health(): HealthResponse =
        get("/api/v1/health").executeChecked().use { decode(it, HealthResponse.serializer()) }

    fun startSession(request: StartSessionRequest): StartSessionResponse =
        post("/api/v1/sessions", json.encodeToString(StartSessionRequest.serializer(), request))
            .executeChecked()
            .use { decode(it, StartSessionResponse.serializer()) }

    /**
     * Uploads one file. The content is streamed from [openStream] through a gzip encoder, so
     * neither the plain nor the compressed body is ever held in memory.
     */
    fun uploadFile(
        sessionId: String,
        path: String,
        sha256: String,
        mode: String,
        openStream: () -> InputStream,
        onBytes: (Long) -> Unit = {},
    ) {
        val url = url("/api/v1/sessions/$sessionId/files") { addQueryParameter("path", path) }
            ?: throw BackupApiException("Invalid server URL")
        val request = authorized(url)
            .header("X-File-Sha256", sha256)
            .header("X-File-Mode", mode)
            // The body is gzipped on the fly; the server streams it back out while hashing.
            .header("Content-Encoding", "gzip")
            .put(GzipStreamingBody(openStream, onBytes))
            .build()
        request.executeChecked().close()
    }

    fun finishSession(sessionId: String, request: FinishRequest): FinishResponse =
        post(
            "/api/v1/sessions/$sessionId/finish",
            json.encodeToString(FinishRequest.serializer(), request),
        ).executeChecked().use { decode(it, FinishResponse.serializer()) }

    fun versions(deviceId: String, setId: String): List<RemoteVersion> =
        get("/api/v1/sets/$deviceId/$setId/versions").executeChecked()
            .use { decode(it, VersionListResponse.serializer()) }
            .versions

    fun deleteVersion(deviceId: String, setId: String, version: Int) {
        delete("/api/v1/sets/$deviceId/$setId/versions/$version").executeChecked().close()
    }

    /**
     * Manifest the server currently holds. Used as the diff base after a reinstall, when the local
     * manifest is gone but the server still knows exactly which files it has.
     */
    fun manifest(deviceId: String, setId: String): Map<String, ManifestEntry> =
        get("/api/v1/sets/$deviceId/$setId/manifest").executeChecked()
            .use { decode(it, ManifestResponse.serializer()) }
            .entries
            .associate { entry ->
                entry.path to ManifestEntry(
                    path = entry.path,
                    // The server does not store timestamps; forcing -1 disables the metadata
                    // shortcut and makes the scanner hash the file, which is the safe choice.
                    size = entry.size,
                    modified = -1L,
                    sha256 = entry.sha256,
                )
            }

    /** Aborts the request in flight, if any. Safe to call from any thread. */
    fun cancel() {
        activeCall.get()?.cancel()
    }

    override fun close() {
        cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    // ---------------------------------------------------------------- plumbing

    private fun get(path: String): Request = authorized(resolve(path)).get().build()

    private fun delete(path: String): Request = authorized(resolve(path)).delete().build()

    private fun post(path: String, body: String): Request =
        authorized(resolve(path)).post(body.toRequestBody(jsonMedia)).build()

    private fun resolve(path: String): HttpUrl =
        url(path) ?: throw BackupApiException("Invalid server URL: $root$path")

    private fun authorized(url: HttpUrl): Request.Builder =
        Request.Builder().url(url).apply {
            if (token.isNotBlank()) header("Authorization", "Bearer $token")
        }

    private fun url(path: String, configure: HttpUrl.Builder.() -> Unit = {}): HttpUrl? =
        ("$root$path").toHttpUrlOrNull()?.newBuilder()?.apply(configure)?.build()

    private fun <T> decode(response: Response, serializer: KSerializer<T>): T {
        val body = response.body.string()
        return runCatching { json.decodeFromString(serializer, body) }
            .getOrElse { error ->
                throw BackupApiException("Unexpected server response (${response.code}): ${error.message}")
            }
    }

    private fun Request.executeChecked(): Response {
        val call = client.newCall(this)
        activeCall.set(call)
        val response = try {
            call.execute()
        } finally {
            activeCall.compareAndSet(call, null)
        }
        if (!response.isSuccessful) {
            val detail = runCatching { response.body.string() }.getOrNull().orEmpty()
            response.close()
            throw BackupApiException("HTTP ${response.code} for ${url.encodedPath}: ${detail.take(400)}")
        }
        return response
    }

    /**
     * Request body that pulls bytes from the content resolver and gzips them on the fly.
     *
     * The length is unknown up front (gzip), so the request is sent chunked. The server writes the
     * payload to disk and gunzips it, which is what makes a large photo library upload practical on
     * a mobile connection.
     */
    private class GzipStreamingBody(
        private val openStream: () -> InputStream,
        private val onBytes: (Long) -> Unit,
    ) : RequestBody() {

        override fun contentType() = "application/octet-stream".toMediaType()

        override fun contentLength() = -1L

        override fun writeTo(sink: BufferedSink) {
            val gzip = sink.gzip()
            val buffer = Buffer()
            openStream().use { input ->
                input.source().use { source ->
                    while (true) {
                        val read = source.read(buffer, CHUNK)
                        if (read == -1L) break
                        gzip.write(buffer, read)
                        onBytes(read)
                    }
                }
            }
            gzip.close()
        }

        private companion object {
            const val CHUNK = 64L * 1024L
        }
    }

    private companion object {
        /** Short enough that a mistyped host surfaces quickly instead of hanging the run. */
        const val CONNECT_TIMEOUT_SECONDS = 15L
    }
}
