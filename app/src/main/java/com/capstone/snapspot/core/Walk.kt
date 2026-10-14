package com.capstone.snapspot.core

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The streets a route actually walks. The stop order still comes from straight-line distances
 * ([shortestRoute]); this only replaces the drawn line and the minutes once the order is known.
 */
data class WalkPath(val line: List<Coords>, val meters: Int, val seconds: Int) {
    val minutes: Int get() = ceil(seconds / 60.0).toInt()
}

/** A walking-directions service. One call takes at most [WALK_MAX_POINTS] points (start, waypoints, end). */
fun interface WalkApi {
    suspend fun walk(points: List<Coords>): WalkPath
}

/** TMAP pedestrian routes take start + end + up to 5 waypoints. */
const val WALK_MAX_POINTS = 7

/** Splits a long route into calls of at most [max] points; consecutive chunks share their joint. */
fun walkChunks(points: List<Coords>, max: Int = WALK_MAX_POINTS): List<List<Coords>> {
    require(max >= 2)
    if (points.size < 2) return emptyList()
    val out = mutableListOf<List<Coords>>()
    var i = 0
    while (i < points.size - 1) {
        val end = minOf(i + max - 1, points.size - 1)
        out += points.subList(i, end + 1)
        i = end
    }
    return out
}

/** Walks [points] in order, one call per chunk, joined into one line. */
suspend fun walkPath(api: WalkApi, points: List<Coords>): WalkPath {
    val parts = walkChunks(points).map { api.walk(it) }
    val line = parts.flatMapIndexed { i, p -> if (i == 0) p.line else p.line.drop(1) }
    return WalkPath(line, parts.sumOf { it.meters }, parts.sumOf { it.seconds })
}

/**
 * Cache key for a walk. The start (the user) is snapped to about 100m so that walking around
 * the neighborhood does not ask again on every position update; stops are kept exact.
 */
fun walkKey(start: Coords?, stops: List<Coords>): String {
    fun snap(d: Double) = (d * 1000).roundToInt()
    val s = start?.let { "${snap(it.latitude)},${snap(it.longitude)}" } ?: "-"
    return s + "|" + stops.joinToString(";") { "${it.latitude},${it.longitude}" }
}
