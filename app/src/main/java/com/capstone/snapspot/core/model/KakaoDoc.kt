package com.capstone.snapspot.core.model

import kotlinx.serialization.Serializable

/** The fields of a Kakao Local keyword-search document that we use (names as in the API). */
@Suppress("PropertyName")
@Serializable
data class KakaoDoc(
    val id: String,
    val place_name: String,
    val category_name: String = "",
    val category_group_code: String = "",
    val address_name: String = "",
    val road_address_name: String = "",
    val x: String,
    val y: String,
    val place_url: String = "",
)
