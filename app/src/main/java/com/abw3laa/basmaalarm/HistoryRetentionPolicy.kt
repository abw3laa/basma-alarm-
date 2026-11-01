package com.abw3laa.basmaalarm

/** Keeps a bounded, chronologically ordered event window without depending on Android storage APIs. */
internal object HistoryRetentionPolicy {
    fun <T> keepNewest(items: List<T>, maximum: Int, timestamp: (T) -> Long): List<T> {
        if (maximum <= 0 || items.isEmpty()) return emptyList()
        return items.sortedBy(timestamp).takeLast(maximum)
    }
}
