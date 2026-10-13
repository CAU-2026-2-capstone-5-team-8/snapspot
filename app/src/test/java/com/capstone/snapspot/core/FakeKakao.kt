package com.capstone.snapspot.core

/** Fake Kakao: keyword answers by exact query text, records what was asked. */
class FakeKakao(
    private val byQuery: Map<String, List<KakaoDoc>> = emptyMap(),
    private val any: List<KakaoDoc>? = null,
) : KakaoApi {
    val asked = mutableListOf<String>()

    override suspend fun keyword(params: Map<String, String>): List<KakaoDoc> {
        val q = params.getValue("query")
        asked += "kw:$q"
        return any ?: byQuery[q].orEmpty()
    }

    override suspend fun address(query: String): List<AddressDoc> {
        asked += "addr:$query"
        return emptyList()
    }
}
