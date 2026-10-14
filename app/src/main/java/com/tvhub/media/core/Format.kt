package com.tvhub.media.core

import java.text.DateFormat
import java.util.Date
import java.util.Locale

object Format {
    fun duration(ms: Long): String {
        if (ms <= 0) return ""
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun size(bytes: Long): String {
        if (bytes <= 0) return ""
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var i = 0
        while (value >= 1024 && i < units.size - 1) {
            value /= 1024
            i++
        }
        return if (i == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[i])
    }

    fun date(millis: Long): String =
        if (millis <= 0) "" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
}
