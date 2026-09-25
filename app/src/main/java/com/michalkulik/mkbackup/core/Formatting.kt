package com.michalkulik.mkbackup.core

/** Locale-independent byte formatting, shared by the notifications and the UI. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return "%.1f %s".format(value, units[index])
}

/** Locale-aware date + time for the run history. */
fun formatTimestamp(millis: Long): String =
    if (millis <= 0) {
        "—"
    } else {
        java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.MEDIUM,
            java.text.DateFormat.SHORT,
        ).format(java.util.Date(millis))
    }
