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
import java.net.URLEncoder
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
    /**
     * Readable folder this device's backups belong in (`Galaxy S23+`). Used only against servers
     * that understand it — see [namedDeviceFolders].
     */
    private val folderId: String = "",
    /**
     * The random id used before folders were named after the device, created on first use. It is
     * what an older server still holds the backups under, so it is only asked for when needed.
     */
    private val legacyId: () -> String = { "" },
    /**
     * Folder ids this device used before, forwarded to the server so it can move an existing
     * backup to the folder the phone now asks for. Empty on a fresh install: nothing to move.
     */
    private val previousIds: List<String> = emptyList(),
) : Closeable {

    private val root = normalizeServerUrl(baseUrl)

    /**
     * Whether this server stores backups under the readable folder name (mk-backup-server 1.2.0).
     *
     * Negotiated once per client from the version the server reports, and never guessed: a server
     * that predates readable folders keeps being addressed by the original random id, exactly as
     * before, so installing this version alone cannot split an existing backup over two folders.
     * A failed probe is not swallowed — the call that needed it would fail the same way, and
     * silently picking the wrong id would be worse than an honest connection error.
     */
    private val namedDeviceFolders: Boolean by lazy {
        health().version.atLeast(NAMED_FOLDERS_SINCE)
    }

    /**
     * The folder id to use against this server: the readable name where it is supported, and the
     * random id it has always known everywhere else.
     */
    fun deviceId(): String =
        if (namedDeviceFolders) folderId.ifEmpty { legacyId() } else legacyId().ifEmpty { folderId }

    /** The previous ids as the server expects them in `?previous=` (newest first). */
    private fun previousQuery(): String = previousIds.filter { it.isNotEmpty() }.joinToString("|")

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
        get(pathUrl("/api/v1/health")).executeChecked().use { decode(it, HealthResponse.serializer()) }

    fun startSession(request: StartSessionRequest): StartSessionResponse =
        post(
            pathUrl("/api/v1/sessions"),
            json.encodeToString(StartSessionRequest.serializer(), request),
        ).executeChecked().use { decode(it, StartSessionResponse.serializer()) }

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
        val url = url("/api/v1/sessions/$sessionId/files") {
            // Same reason as in setsUrl: a `+` in a file name must not come back as a space.
            addEncodedQueryParameter("path", encodeSegment(path))
        } ?: throw BackupApiException("Invalid server URL")
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
            pathUrl("/api/v1/sessions/$sessionId/finish"),
            json.encodeToString(FinishRequest.serializer(), request),
        ).executeChecked().use { decode(it, FinishResponse.serializer()) }

    fun versions(setId: String): List<RemoteVersion> =
        get(setsUrl(setId, "versions")).executeChecked()
            .use { decode(it, VersionListResponse.serializer()) }
            .versions

    fun deleteVersion(setId: String, version: Int) {
        delete(setsUrl(setId, "versions", version.toString())).executeChecked().close()
    }

    /**
     * Manifest the server currently holds. Used as the diff base after a reinstall, when the local
     * manifest is gone but the server still knows exactly which files it has.
     */
    fun manifest(setId: String): Map<String, ManifestEntry> =
        get(setsUrl(setId, "manifest")).executeChecked()
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

    private fun get(url: HttpUrl): Request = authorized(url).get().build()

    private fun delete(url: HttpUrl): Request = authorized(url).delete().build()

    private fun post(url: HttpUrl, body: String): Request =
        authorized(url).post(body.toRequestBody(jsonMedia)).build()

    /** `/api/v1/…` addressed by path, for the calls whose segments the server generates. */
    private fun pathUrl(path: String): HttpUrl =
        url(path) ?: throw BackupApiException("Invalid server URL: $root$path")

    /**
     * `/api/v1/sets/{folder}/{set}/…`, plus any trailing segments.
     *
     * The ids are readable now (`Galaxy S23+`), so each one is percent-encoded exactly once and
     * handed to OkHttp as already encoded: a raw space must not reach the wire, and the `+` in a
     * product name must survive as a literal plus instead of being read as a space.
     */
    private fun setsUrl(setId: String, vararg tail: String): HttpUrl {
        val builder = "$root/api/v1/sets".toHttpUrlOrNull()?.newBuilder()
            ?: throw BackupApiException("Invalid server URL: $root")
        builder.addEncodedPathSegment(encodeSegment(deviceId()))
        builder.addEncodedPathSegment(encodeSegment(setId))
        tail.forEach { builder.addEncodedPathSegment(encodeSegment(it)) }
        // Only a server that understands folders has any use for the old ids. The query string is
        // parsed with `+` meaning space, hence the encoding: a plus left raw in `Galaxy S23+`
        // would come back as `Galaxy S23 ` and match nothing.
        if (namedDeviceFolders && previousQuery().isNotEmpty()) {
            builder.addEncodedQueryParameter("previous", encodeSegment(previousQuery()))
        }
        return builder.build()
    }

    /** Form encoding writes a space as `+`, which in a path segment means a literal plus. */
    private fun encodeSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

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

        /** First server version that stores backups under the readable device folder. */
        const val NAMED_FOLDERS_SINCE = "1.2.0"
    }
}

/**
 * `major.minor.patch >= required`, ignoring anything that is not a number.
 *
 * The version is the only signal that the server knows about readable folders, so an empty or
 * unexpected value must not be read as "new enough": the safe answer is the old behaviour.
 */
private fun String.atLeast(required: String): Boolean {
    val actual = split('.').map { it.trim().toIntOrNull() ?: 0 }
    val wanted = required.split('.').map { it.trim().toIntOrNull() ?: 0 }
    for (index in wanted.indices) {
        val have = actual.getOrNull(index) ?: 0
        if (have != wanted[index]) return have > wanted[index]
    }
    return true
}
