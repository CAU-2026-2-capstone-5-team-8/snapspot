package com.capstone.snapspot.core

import kotlin.math.sqrt

/**
 * Taste: what the user's saved places have in common, as one embedding vector, and how close
 * a nearby candidate is to it. Measured in docs/taste.md (sim/TasteBench.kt).
 */

/**
 * The text embedded for a place: name and category. The memo goes in only when asked, since it
 * is the user's own words; addresses never go in (distance is worked out on the phone).
 */
fun tasteText(name: String, category: String, note: String = ""): String =
    listOf(name, category, note).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" | ")

/** Unit length; a zero vector stays as it is. */
fun normalize(v: FloatArray): FloatArray {
    val n = sqrt(v.sumOf { it.toDouble() * it })
    return if (n == 0.0) v.copyOf() else FloatArray(v.size) { (v[it] / n).toFloat() }
}

/** Cosine similarity in [-1, 1]; 0 when either side is a zero vector. */
fun cosine(a: FloatArray, b: FloatArray): Double {
    require(a.size == b.size) { "벡터 길이가 달라요: ${a.size} vs ${b.size}" }
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in a.indices) {
        dot += a[i].toDouble() * b[i]
        na += a[i].toDouble() * a[i]
        nb += b[i].toDouble() * b[i]
    }
    return if (na == 0.0 || nb == 0.0) 0.0 else dot / sqrt(na * nb)
}

/** Mean of the normalized vectors, normalized again; every saved place counts the same. Null when there are none. */
fun tasteVector(saved: List<FloatArray>): FloatArray? {
    if (saved.isEmpty()) return null
    val dim = saved.first().size
    val sum = DoubleArray(dim)
    for (v in saved) {
        require(v.size == dim) { "벡터 길이가 달라요: ${v.size} vs $dim" }
        val u = normalize(v)
        for (i in 0 until dim) sum[i] += u[i]
    }
    return normalize(FloatArray(dim) { (sum[it] / saved.size).toFloat() })
}

/**
 * [similarity]: cosine to the taste. [lift]: how many standard deviations above the candidates'
 * average that is. Embedding similarities sit in a narrow band (unrelated places still score
 * around 0.6), so a fixed cutoff on [similarity] does not work; [lift] does.
 */
data class Scored<T>(val item: T, val similarity: Double, val lift: Double)

/** Every candidate, most similar to the mean taste first. */
fun <T> rankByTaste(taste: FloatArray, candidates: List<Pair<T, FloatArray>>): List<Scored<T>> =
    ranked(candidates) { cosine(taste, it) }

/**
 * Every candidate, scored by the mean similarity to its [k] closest saved places. Someone who
 * saves both dessert cafés and bars has a mean taste somewhere in between, which matches
 * neither; this keeps both. With k = 1 a single odd save (a mart) pulls in every mart, so k > 1.
 */
fun <T> rankByNearest(saved: List<FloatArray>, candidates: List<Pair<T, FloatArray>>, k: Int = 1): List<Scored<T>> {
    if (saved.isEmpty()) return emptyList()
    val unit = saved.map { normalize(it) }
    val take = k.coerceIn(1, unit.size)
    return ranked(candidates) { c -> unit.map { cosine(it, c) }.sortedDescending().take(take).average() }
}

private fun <T> ranked(candidates: List<Pair<T, FloatArray>>, score: (FloatArray) -> Double): List<Scored<T>> {
    val sims = candidates.map { score(it.second) }
    val mean = sims.average()
    val sd = sqrt(sims.sumOf { (it - mean) * (it - mean) } / sims.size)
    return candidates.indices
        .map { i -> Scored(candidates[i].first, sims[i], if (sd < 1e-9) 0.0 else (sims[i] - mean) / sd) }
        .sortedByDescending { it.similarity }
}

/**
 * Closest saves averaged per candidate. Best overall in docs/taste.md: close to the mean taste
 * for one-theme users, far better for mixed tastes, and one odd save does not take over.
 */
const val TASTE_NEAREST_K = 3

/** The top [limit] candidates that stand out by at least [minLift], scored by [rankByNearest]. */
fun <T> recommend(
    saved: List<FloatArray>,
    candidates: List<Pair<T, FloatArray>>,
    limit: Int = 3,
    minLift: Double = 0.5,
    k: Int = TASTE_NEAREST_K,
): List<Scored<T>> = rankByNearest(saved, candidates, k).filter { it.lift >= minLift }.take(limit)
