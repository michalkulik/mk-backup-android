package com.michalkulik.mkbackup.core

/**
 * The folder on the server that holds this phone's backups.
 *
 * The name is read from the phone settings (for a Galaxy S23+ that is `Galaxy S23+`), so the
 * server directory says which device the copies came from instead of hiding behind a random id.
 * Because it is used verbatim as a directory and as a URL path segment, everything that could
 * break either one is replaced by a space: path separators, the usual punctuation that is not
 * allowed in file names, and control characters. Any run of whitespace collapses to a single
 * space, and the result is checked against `.` / `..`, which would walk out of `sets/`.
 *
 * Returns an empty string when nothing usable is left; the caller then falls back to the plain
 * device name, and finally to whatever folder it already had.
 */
fun sanitizeDeviceFolder(raw: String): String {
    val cleaned = buildString(raw.length) {
        raw.forEach { ch -> append(if (ch.isIllegalInFolder()) ' ' else ch) }
    }
    val candidate = cleaned
        .replace(WHITESPACE_RUN, " ")
        .trim()
        // The server refuses anything longer than 128 characters; cutting here keeps the two in
        // agreement instead of letting the request fail.
        .take(MAX_FOLDER_LENGTH)
        .trim()
    return if (candidate.isEmpty() || candidate == "." || candidate == "..") "" else candidate
}

/** Matches the server's `safe_component`, which guards everything joined onto `sets/`. */
const val MAX_FOLDER_LENGTH = 128

private const val ILLEGAL_PUNCTUATION = "\\/:*?\"<>|"

private val WHITESPACE_RUN = Regex("\\s+")

private fun Char.isIllegalInFolder(): Boolean =
    this in ILLEGAL_PUNCTUATION || code < 0x20 || code == 0x7F
