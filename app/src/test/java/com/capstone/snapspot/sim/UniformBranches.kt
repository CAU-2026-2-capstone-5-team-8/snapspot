package com.capstone.snapspot.sim

import com.capstone.snapspot.core.BRANCHES_PER_BRAND
import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.MAX_REGIONS
import com.capstone.snapspot.core.REFRESH_ID
import com.capstone.snapspot.core.Spot
import com.capstone.snapspot.core.Tier
import com.capstone.snapspot.core.planRegions
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A controlled version of the refresh-radius question: one brand, N branches spread evenly over a disk
 * (only coordinates, no coupons or Kakao), people walking around inside it. How much does the refresh radius
 * change what gets caught?
 *
 * ./gradlew :app:testDebugUnitTest -PuniformSim
 */
class UniformBranches {
    private val origin = Coords(37.5447, 127.0560)
    private val mLat = 111_320.0
    private val mLng = 111_320.0 * cos(Math.toRadians(origin.latitude))
    private fun coords(x: Double, y: Double) = Coords(origin.latitude + y / mLat, origin.longitude + x / mLng)

    private val stepS = 10
    private val walkMps = 1.4
    private val alertM = 200.0

    private class Rng(seed: Int) {
        private var s = seed
        fun next(): Double {
            s += 0x6d2b79f5
            var t = s
            t = (t xor (t ushr 15)) * (t or 1)
            t = t xor (t + (t xor (t ushr 7)) * (t or 61))
            return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL) / 4294967296.0
        }
    }

    /** Uniform over a disk of radius r (sqrt keeps the density even). */
    private fun inDisk(rng: Rng, r: Double): Pair<Double, Double> {
        val d = r * sqrt(rng.next())
        val a = 2 * PI * rng.next()
        return d * cos(a) to d * sin(a)
    }

    private class Stats(var passes: Int = 0, var caught: Int = 0, var walks: Int = 0, var alerted: Int = 0, var firstCaught: Int = 0, var replans: Int = 0, var km: Double = 0.0)

    private fun run(spread: Double, count: Int, refreshM: Double, delayS: Int, people: Int, seed: Int): Stats {
        val rng = Rng(seed)
        val pts = List(count) { inDisk(rng, spread) }
        val spots = pts.mapIndexed { i, (x, y) -> coords(x, y).let { Spot("b$i", "brand", Tier.NORMAL, it.latitude, it.longitude, alertM) } }
        val st = Stats()
        repeat(people) {
            // A stroll: start anywhere, then 5 legs of up to 1.2km, staying inside the disk.
            val stops = mutableListOf(inDisk(rng, spread))
            while (stops.size < 6) {
                val (lx, ly) = stops.last()
                val (dx, dy) = inDisk(rng, 1200.0)
                if (hypot(lx + dx, ly + dy) <= spread) stops += (lx + dx) to (ly + dy)
            }
            val path = mutableListOf<Pair<Double, Double>>()
            for (w in 0 until stops.size - 1) {
                val (ax, ay) = stops[w]
                val (bx, by) = stops[w + 1]
                val len = hypot(bx - ax, by - ay)
                st.km += len / 1000
                val n = max(1, (len / (walkMps * stepS)).roundToInt())
                for (i in 0 until n) path += (ax + (bx - ax) * i / n) to (ay + (by - ay) * i / n)
            }
            path += stops.last()

            fun plan(x: Double, y: Double): Set<String> =
                planRegions(coords(x, y), spots, MAX_REGIONS, refreshM, BRANCHES_PER_BRAND).regions.map { it.identifier }.filter { it != REFRESH_ID }.toSet()

            var reg = plan(path[0].first, path[0].second)
            var at = path[0]
            st.replans++
            var pendingAt = -1
            val registered = ArrayList<Set<String>>(path.size)
            for (t in path.indices) {
                val (x, y) = path[t]
                if (pendingAt in 0..t) {
                    reg = plan(x, y); at = path[t]; st.replans++; pendingAt = -1
                }
                registered += reg
                if (pendingAt < 0 && hypot(x - at.first, y - at.second) > refreshM) pendingAt = t + ceil(delayS.toDouble() / stepS).toInt()
            }

            // Visits: stretches of the path within 200m of a branch.
            class V(val id: String, val from: Int, val to: Int)
            val visits = mutableListOf<V>()
            pts.forEachIndexed { i, (bx, by) ->
                var start = -1
                for (t in path.indices) {
                    val inside = hypot(path[t].first - bx, path[t].second - by) <= alertM
                    if (inside && start < 0) start = t
                    if (!inside && start >= 0) { visits += V("b$i", start, t - 1); start = -1 }
                }
                if (start >= 0) visits += V("b$i", start, path.size - 1)
            }
            if (visits.isEmpty()) return@repeat
            st.walks++
            val hits = visits.map { v -> (v.from..v.to).any { registered[it].contains(v.id) } }
            st.passes += visits.size
            st.caught += hits.count { it }
            if (hits.any { it }) st.alerted++
            if (hits[visits.indices.minBy { visits[it].from }]) st.firstCaught++
        }
        return st
    }

    @Test
    fun report() {
        assumeTrue(System.getProperty("uniform.run") == "true", "run with ./gradlew :app:testDebugUnitTest -PuniformSim")
        val f1 = { v: Double -> String.format(Locale.US, "%.1f", v) }
        val f2 = { v: Double -> String.format(Locale.US, "%.2f", v) }
        val seeds = listOf(1, 2, 3)
        val out = StringBuilder()
        out.appendLine("지점 50곳을 원 안에 고르게 · 사람 500명 × 시드 3개 · 걷기만 · 90칸 · 브랜드당 ${BRANCHES_PER_BRAND}곳 등록")
        for (spread in listOf(1000.0, 3000.0, 5000.0)) {
            val density = 50 / (PI * (spread / 1000) * (spread / 1000))
            out.appendLine()
            out.appendLine("### 반경 ${(spread / 1000).toInt()}km 원 (km²당 ${f1(density)}곳)")
            out.appendLine("| 갱신 반경 | 지연 | 지나간 지점을 감시 중 | 처음 지난 지점에서 알림 | 알림이 한 번이라도 | km당 갱신 |")
            out.appendLine("|---|---|---|---|---|---|")
            for (delay in listOf(0, 120)) for (r in listOf(250.0, 500.0, 1000.0, 1500.0, 2000.0, 3000.0)) {
                val s = seeds.map { run(spread, 50, r, delay, 500, 7919 * it) }
                val sum = { f: (Stats) -> Int -> s.sumOf(f) }
                val km = s.sumOf { it.km }
                out.appendLine(
                    "| ${r.toInt()}m | ${if (delay == 0) "0초" else "2분"} | ${f1(100.0 * sum { it.caught } / sum { it.passes })}% (${sum { it.caught }}/${sum { it.passes }}) | " +
                        "${f1(100.0 * sum { it.firstCaught } / sum { it.walks })}% | ${f1(100.0 * sum { it.alerted } / sum { it.walks })}% (${sum { it.alerted }}/${sum { it.walks }}) | ${f2(sum { it.replans } / km)} |",
                )
            }
        }
        println(out)
    }
}
