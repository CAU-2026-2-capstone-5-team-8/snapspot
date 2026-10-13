package com.capstone.snapspot.core

import kotlinx.serialization.Serializable

/** Kakao Local search, as the core logic needs it. The app uses OkHttp, tests use a fake. */
interface KakaoApi {
    /** GET /keyword.json with these query parameters → documents. Throws [KakaoException] on HTTP errors. */
    suspend fun keyword(params: Map<String, String>): List<KakaoDoc>

    /** GET /address.json?query=…&size=1 → documents. */
    suspend fun address(query: String): List<AddressDoc>
}

class KakaoException(message: String) : Exception(message)

@Suppress("PropertyName")
@Serializable
data class AddressDoc(
    val address_name: String,
    val x: String,
    val y: String,
    val road_address: RoadAddress? = null,
) {
    @Suppress("PropertyName")
    @Serializable
    data class RoadAddress(val address_name: String)
}
