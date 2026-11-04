package com.capstone.snapspot.core

/**
 * Remembers recent routes by (stops, start, chosen place order), so position updates that do not change a bundle's
 * start (the user is away from it) do not solve the same route again. Least recently used first out.
 */
class RouteMemo(private val capacity: Int) {
    private data class Key(val stops: List<Stop>, val start: Coords?, val orderedPlaces: List<Int>)
    private val entries = object : LinkedHashMap<Key, RouteResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, RouteResult>) = size > capacity
    }

    @Synchronized
    fun route(stops: List<Stop>, start: Coords?, solve: (List<Stop>, Coords?) -> RouteResult, firstStop: Int? = null): RouteResult =
        entries.getOrPut(Key(stops, start, listOfNotNull(firstStop))) { routeWithFirstPlace(stops, start, firstStop, solve) }

    @Synchronized
    fun routeOrdered(stops: List<Stop>, start: Coords?, orderedPlaces: List<Int>): RouteResult =
        entries.getOrPut(Key(stops, start, orderedPlaces.toList())) { routeWithPlaceOrder(stops, start, orderedPlaces) }
}
