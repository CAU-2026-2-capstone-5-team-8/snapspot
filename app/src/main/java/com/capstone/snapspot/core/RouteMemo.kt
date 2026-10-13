package com.capstone.snapspot.core

/**
 * Remembers recent routes by (stops, start), so position updates that do not change a bundle's
 * start (the user is away from it) do not solve the same route again. Least recently used first out.
 */
class RouteMemo(private val capacity: Int) {
    private val entries = object : LinkedHashMap<Pair<List<Stop>, Coords?>, RouteResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<List<Stop>, Coords?>, RouteResult>) = size > capacity
    }

    @Synchronized
    fun route(stops: List<Stop>, start: Coords?, solve: (List<Stop>, Coords?) -> RouteResult): RouteResult =
        entries.getOrPut(stops to start) { solve(stops, start) }
}
