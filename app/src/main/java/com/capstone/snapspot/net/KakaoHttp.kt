package com.capstone.snapspot.net

import com.capstone.snapspot.core.AddressDoc
import com.capstone.snapspot.core.KakaoApi
import com.capstone.snapspot.core.KakaoDoc
import com.capstone.snapspot.core.KakaoException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Kakao Local search over HTTP with the REST API key. */
class KakaoHttp(
    private val key: String,
    private val client: OkHttpClient,
    private val base: String = "https://dapi.kakao.com/v2/local/search",
) : KakaoApi {

    @Serializable
    private data class Page<T>(val documents: List<T> = emptyList())

    override suspend fun keyword(params: Map<String, String>): List<KakaoDoc> =
        get("keyword", params, KakaoDoc.serializer())

    override suspend fun address(query: String): List<AddressDoc> =
        get("address", mapOf("query" to query, "size" to "1"), AddressDoc.serializer())

    private suspend fun <T> get(path: String, params: Map<String, String>, item: KSerializer<T>): List<T> {
        if (key.isEmpty()) throw KakaoException("카카오 REST API 키가 없어요. local.properties의 KAKAO_REST_KEY를 설정하세요.")
        val url = "$base/$path.json".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val req = Request.Builder().url(url).header("Authorization", "KakaoAK $key").build()
        client.newCall(req).await().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw KakaoException("카카오 검색 실패 (${res.code}): ${body.take(200)}")
            return json.decodeFromString(Page.serializer(item), body).documents
        }
    }
}
