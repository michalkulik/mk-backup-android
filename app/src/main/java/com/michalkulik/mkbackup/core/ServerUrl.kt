package com.michalkulik.mkbackup.core

/**
 * Makes the server address usable without the user having to type a scheme.
 *
 * A bare host such as `backup.example.com:8090` gets `http://` prepended, because a self-hosted
 * server usually starts out as plain HTTP. An explicit `http://` or `https://` is preserved, so
 * HTTPS can still be requested when the server is behind a TLS proxy.
 */
fun normalizeServerUrl(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty()) return ""
    val lower = trimmed.lowercase()
    return when {
        lower.startsWith("http://") || lower.startsWith("https://") -> trimmed
        // Tolerate a leading "//" (protocol relative URL).
        trimmed.startsWith("//") -> "http:$trimmed"
        else -> "http://$trimmed"
    }
}
