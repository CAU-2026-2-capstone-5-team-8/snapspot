package com.capstone.snapspot.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Place(
    val id: String,
    val name: String,
    val address: String,
    val category: String,
    val note: String,
    /** YYYY-MM-DD. Pop-ups and exhibitions end; after this date we stop alerting. */
    val endDate: String?,
    val latitude: Double,
    val longitude: Double,
    /** Geofence radius in meters. */
    val radius: Int,
    val imageUri: String?,
    /** Set when the place was matched through Kakao Local search. */
    val kakaoId: String? = null,
    val placeUrl: String? = null,
    val createdAt: Long,
    val lastNotifiedAt: Long? = null,
)

/** A franchise coupon: alerts at whichever branch of `brand` the user walks past. */
@Serializable
data class Coupon(
    val id: String,
    /** As written on the coupon, e.g. "스타벅스". Used as the Kakao search query. */
    val brand: String,
    /** What the coupon gives, e.g. "아메리카노 1+1". */
    val benefit: String,
    /** YYYY-MM-DD. Expired coupons stop alerting. */
    val expiresAt: String?,
    val imageUri: String?,
    val createdAt: Long,
    /** Set when the user marks it used; used coupons stop alerting. */
    val usedAt: Long? = null,
    val lastNotifiedAt: Long? = null,
)

/** What Gemini read from a screenshot. */
@Serializable
data class Extracted(
    /** "place" or "coupon". */
    val kind: String,
    val brand: String,
    val benefit: String,
    val isPlace: Boolean,
    val name: String,
    val address: String,
    val category: String,
    val note: String,
    val endDate: String?,
)

/** A franchise branch found through Kakao. */
@Serializable
data class Branch(
    val kakaoId: String,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
)
