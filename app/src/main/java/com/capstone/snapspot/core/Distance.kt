package com.capstone.snapspot.core

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure geometry. No Android imports, so JVM tests and the simulator can use it. */
@Serializable
data class Coords(val latitude: Double, val longitude: Double)

/** Haversine distance in meters. */
fun distanceMeters(a: Coords, b: Coords): Double {
    val r = 6_371_000.0
    fun rad(d: Double) = Math.toRadians(d)
    val dLat = rad(b.latitude - a.latitude)
    val dLng = rad(b.longitude - a.longitude)
    val h = sin(dLat / 2).pow(2) + cos(rad(a.latitude)) * cos(rad(b.latitude)) * sin(dLng / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}

fun formatDistance(m: Double): String =
    if (m < 1000) "${m.roundToInt()}m" else String.format(Locale.US, "%.1fkm", m / 1000)
