/**
 * Shortest walking order through a neighborhood: a generalized traveling salesman problem on an
 * open path (start somewhere, no return). Every stop is visited once, at exactly one of its
 * options: a saved place has one option, a coupon brand has its nearby branches, and the route
 * picks the branch that keeps the walk shortest. With one option per stop this is the plain TSP.
 * Straight-line distances. g = stops, N = options in total.
 *
 * - [bruteForceRoute]: every stop order × every pick. Ground truth for tests and the benchmark only.
 * - [exactRoute]: Held-Karp DP over (visited stops bitmask, last option). O(2^g·N²) time, O(2^g·N) memory.
 * - [heuristicRoute]: nearest neighbor, then alternate (a) 2-opt / Or-opt on the stop order and
 *   (b) re-picking every stop's option for that order by a layered shortest path, until neither helps.
 * - [lowerBound]: MST over stops where two stops are as far apart as their closest options; no path
 *   through one option per stop can be shorter, so it bounds the heuristic's gap without the optimum.
 *
 * [EXACT_MAX] (picked by RouteBench, see docs/route.md) decides which one the app uses.
 */
package com.capstone.snapspot.core

import kotlin.math.ceil

/** Up to this many stops the app solves exactly; above it, the heuristic. */
const val EXACT_MAX = 11

/** Streets are not straight: walked distance ≈ straight distance × this. */
const val DETOUR_FACTOR = 1.3
const val WALK_M_PER_MIN = 70.0

/** The options for one stop: one for a saved place, several for a coupon brand's branches. */
typealias Stop = List<Coords>

/** `order[i]`: stop index; `picks[i]`: the option chosen in that stop. `meters` includes the leg from the start. */
data class RouteResult(val order: List<Int>, val picks: List<Int>, val meters: Double)

fun walkMinutes(meters: Double): Int = ceil(meters * DETOUR_FACTOR / WALK_M_PER_MIN).toInt()

fun stopsOf(points: List<Coords>): List<Stop> = points.map { listOf(it) }

/** Options flattened to node ids; node n is the start when there is one. */
private class Graph(stops: List<Stop>, start: Coords?) {
    val g = stops.size
    val offset = IntArray(g + 1).also { for (i in 0 until g) it[i + 1] = it[i] + stops[i].size }
    val n = offset[g]
    val stopOf = IntArray(n).also { for (s in 0 until g) for (k in offset[s] until offset[s + 1]) it[k] = s }
    private val all = stops.flatten() + listOfNotNull(start)
    private val d = Array(all.size) { i -> DoubleArray(all.size) { j -> distanceMeters(all[i], all[j]) } }
    val hasStart = start != null
    operator fun get(i: Int, j: Int) = d[i][j]
    fun lead(node: Int) = if (hasStart) d[n][node] else 0.0
    fun length(nodes: List<Int>): Double =
        if (nodes.isEmpty()) 0.0 else lead(nodes[0]) + nodes.zipWithNext().sumOf { (a, b) -> d[a][b] }
    fun result(nodes: List<Int>) = RouteResult(nodes.map { stopOf[it] }, nodes.map { it - offset[stopOf[it]] }, length(nodes))
    fun nodesOf(stop: Int) = offset[stop] until offset[stop + 1]
}

fun bruteForceRoute(stops: List<Stop>, start: Coords?): RouteResult {
    if (stops.isEmpty()) return RouteResult(emptyList(), emptyList(), 0.0)
    val gr = Graph(stops, start)
    var best: List<Int> = emptyList()
    var bestLen = Double.MAX_VALUE
    val path = mutableListOf<Int>()
    val used = BooleanArray(gr.g)
    fun go(len: Double) {
        if (len >= bestLen) return
        if (path.size == gr.g) {
            best = path.toList()
            bestLen = len
            return
        }
        for (s in 0 until gr.g) {
            if (used[s]) continue
            used[s] = true
            for (v in gr.nodesOf(s)) {
                val add = if (path.isEmpty()) gr.lead(v) else gr[path.last(), v]
                path += v
                go(len + add)
                path.removeAt(path.size - 1)
            }
            used[s] = false
        }
    }
    go(0.0)
    return gr.result(best)
}

fun exactRoute(stops: List<Stop>, start: Coords?): RouteResult {
    if (stops.isEmpty()) return RouteResult(emptyList(), emptyList(), 0.0)
    val gr = Graph(stops, start)
    val n = gr.n
    val full = (1 shl gr.g) - 1
    // cost[mask * n + v]: shortest path through exactly the stops in `mask`, ending at option v.
    val cost = DoubleArray((full + 1) * n) { Double.MAX_VALUE }
    val prev = IntArray((full + 1) * n) { -1 }
    for (v in 0 until n) cost[(1 shl gr.stopOf[v]) * n + v] = gr.lead(v)
    for (mask in 1..full) {
        for (v in 0 until n) {
            val here = cost[mask * n + v]
            if (here == Double.MAX_VALUE) continue
            for (u in 0 until n) {
                val bit = 1 shl gr.stopOf[u]
                if (mask and bit != 0) continue
                val m2 = mask or bit
                val c = here + gr[v, u]
                if (c < cost[m2 * n + u]) {
                    cost[m2 * n + u] = c
                    prev[m2 * n + u] = v
                }
            }
        }
    }
    var v = (0 until n).minBy { cost[full * n + it] }
    val nodes = ArrayDeque<Int>()
    var mask = full
    while (v >= 0) {
        nodes.addFirst(v)
        val p = prev[mask * n + v]
        mask = mask xor (1 shl gr.stopOf[v])
        v = p
    }
    return gr.result(nodes.toList())
}

fun heuristicRoute(stops: List<Stop>, start: Coords?, twoOpt: Boolean = true, orOpt: Boolean = true, repick: Boolean = true): RouteResult {
    if (stops.isEmpty()) return RouteResult(emptyList(), emptyList(), 0.0)
    val gr = Graph(stops, start)
    // With a start, one run from it; without, every option as the first one and keep the best.
    val firsts = if (start != null) listOf(-1) else (0 until gr.n).toList()
    var best: List<Int> = emptyList()
    var bestLen = Double.MAX_VALUE
    for (first in firsts) {
        val nodes = nearestNeighbor(gr, first)
        improve(gr, nodes, twoOpt, orOpt, repick)
        val len = gr.length(nodes)
        if (len < bestLen) {
            best = nodes.toList()
            bestLen = len
        }
    }
    return gr.result(best)
}

/** `first` = -1: from the start point. Next is always the nearest option of a stop not yet visited. */
private fun nearestNeighbor(gr: Graph, first: Int): MutableList<Int> {
    val visited = BooleanArray(gr.g)
    val nodes = mutableListOf<Int>()
    if (first >= 0) {
        nodes += first
        visited[gr.stopOf[first]] = true
    }
    while (nodes.size < gr.g) {
        val next = (0 until gr.n).filter { !visited[gr.stopOf[it]] }
            .minBy { if (nodes.isEmpty()) gr.lead(it) else gr[nodes.last(), it] }
        nodes += next
        visited[gr.stopOf[next]] = true
    }
    return nodes
}

/** Alternates order moves (2-opt, Or-opt) and option re-picking until nothing shortens the route. */
private fun improve(gr: Graph, nodes: MutableList<Int>, twoOpt: Boolean, orOpt: Boolean, repick: Boolean) {
    // Without a start point each run fixes its own first stop; with one, every position may move.
    val lo = if (gr.hasStart) 0 else 1
    var improved = true
    while (improved) {
        improved = false
        if (twoOpt) {
            for (i in lo until nodes.size - 1) for (k in i + 1 until nodes.size) {
                val before = gr.length(nodes)
                nodes.subList(i, k + 1).reverse()
                if (gr.length(nodes) < before - 1e-9) improved = true else nodes.subList(i, k + 1).reverse()
            }
        }
        if (orOpt) {
            for (len in 1..3) {
                var i = lo
                while (i + len <= nodes.size) {
                    val before = gr.length(nodes)
                    val seg = nodes.subList(i, i + len).toList()
                    repeat(len) { nodes.removeAt(i) }
                    var bestPos = -1
                    var bestLen = before - 1e-9
                    for (pos in lo..nodes.size) {
                        nodes.addAll(pos, seg)
                        val l = gr.length(nodes)
                        if (l < bestLen) {
                            bestLen = l
                            bestPos = pos
                        }
                        repeat(len) { nodes.removeAt(pos) }
                    }
                    if (bestPos >= 0) {
                        nodes.addAll(bestPos, seg)
                        improved = true
                    } else {
                        nodes.addAll(i, seg)
                    }
                    i++
                }
            }
        }
        if (repick && repickOptions(gr, nodes)) improved = true
    }
}

/**
 * For the current stop order, the best option in every stop at once: a shortest path through a
 * layered graph (layer i = the options of the i-th stop). O(Σ kᵢ·kᵢ₊₁). True if it shortened the route.
 */
private fun repickOptions(gr: Graph, nodes: MutableList<Int>): Boolean {
    if (nodes.isEmpty()) return false
    val before = gr.length(nodes)
    val layers = nodes.map { gr.nodesOf(gr.stopOf[it]).toList() }
    var cost: Map<Int, Double> = layers[0].associateWith { gr.lead(it) }
    val back = mutableListOf<Map<Int, Int>>()
    for (i in 1 until layers.size) {
        val next = HashMap<Int, Double>()
        val from = HashMap<Int, Int>()
        for (u in layers[i]) {
            val v = cost.keys.minBy { cost.getValue(it) + gr[it, u] }
            next[u] = cost.getValue(v) + gr[v, u]
            from[u] = v
        }
        cost = next
        back += from
    }
    var u = cost.keys.minBy { cost.getValue(it) }
    if (cost.getValue(u) >= before - 1e-9) return false
    val picked = ArrayDeque<Int>().apply { addFirst(u) }
    for (i in back.indices.reversed()) {
        u = back[i].getValue(u)
        picked.addFirst(u)
    }
    for (i in nodes.indices) nodes[i] = picked[i]
    return true
}

/** Prim's MST over stops (plus the start), two stops as far apart as their closest options. */
fun lowerBound(stops: List<Stop>, start: Coords?): Double {
    val all = stops + listOfNotNull(start).map { listOf(it) }
    if (all.size < 2) return 0.0
    fun gap(a: Stop, b: Stop) = a.minOf { p -> b.minOf { q -> distanceMeters(p, q) } }
    val inTree = BooleanArray(all.size)
    val best = DoubleArray(all.size) { Double.MAX_VALUE }
    best[0] = 0.0
    var total = 0.0
    repeat(all.size) {
        val u = all.indices.filter { !inTree[it] }.minBy { best[it] }
        inTree[u] = true
        total += best[u]
        for (v in all.indices) if (!inTree[v]) best[v] = minOf(best[v], gap(all[u], all[v]))
    }
    return total
}

fun shortestRoute(stops: List<Stop>, start: Coords?): RouteResult =
    if (stops.size <= EXACT_MAX) exactRoute(stops, start) else heuristicRoute(stops, start)
