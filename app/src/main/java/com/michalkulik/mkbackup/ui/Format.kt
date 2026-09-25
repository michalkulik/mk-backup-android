package com.michalkulik.mkbackup.ui

import java.text.DateFormat
import java.util.Date

/** Locale-aware date + time for the run history. */
fun formatTimestamp(millis: Long): String =
    if (millis <= 0) "—" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

/** Human readable byte sizes, moved here so the UI never reaches into the worker package. */
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
