package com.capstone.snapspot.net

import com.capstone.snapspot.core.isDate
import com.capstone.snapspot.core.model.Extracted
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val PROMPT = """너는 스마트폰 스크린샷에서 "나중에 가 보고 싶은 장소"나 "프랜차이즈 쿠폰"을 뽑아내는 도우미야.
이미지(인스타그램 게시물, 지도 앱, 블로그, 팝업스토어·전시 포스터, 기프티콘·모바일 쿠폰 등)를 보고 아래 JSON만 출력해.
- kind: 여러 지점이 있는 브랜드(스타벅스, 올리브영, 메가커피 등)의 쿠폰·기프티콘·교환권이면 "coupon", 그 밖에는 "place"
- brand: coupon일 때 브랜드 이름만 (예: "스타벅스", "올리브영"). 지점명은 빼. place면 빈 문자열
- benefit: coupon일 때 받을 수 있는 것 (예: "아메리카노 Tall", "5,000원 할인"). place면 빈 문자열
- isPlace: 특정 오프라인 장소(가게, 카페, 식당, 전시, 팝업, 관광지 등)가 보이면 true
- name: 가게/장소 이름 (지점명 포함, 모르면 빈 문자열)
- address: 이미지에 보이는 주소나 위치 단서 (예: "서울 성동구 연무장길 12", "성수동"). 없으면 빈 문자열
- category: 카페, 식당, 술집, 디저트, 전시, 팝업, 쇼핑, 관광, 기타 중 하나
- note: 사용자가 왜 저장했을지 한 문장 (예: "소금빵 맛집", "10월까지 하는 사진전")
- endDate: 운영 종료일이나 쿠폰 유효기간 마지막 날이 보이면 YYYY-MM-DD, 아니면 null
추측으로 주소를 지어내지 마."""

private val SCHEMA = buildJsonObject {
    put("type", "OBJECT")
    putJsonObject("properties") {
        putJsonObject("kind") { put("type", "STRING"); putJsonArray("enum") { add("place"); add("coupon") } }
        for (f in listOf("brand", "benefit", "name", "address", "category", "note")) putJsonObject(f) { put("type", "STRING") }
        putJsonObject("isPlace") { put("type", "BOOLEAN") }
        putJsonObject("endDate") { put("type", "STRING"); put("nullable", true) }
    }
    putJsonArray("required") {
        for (f in listOf("kind", "brand", "benefit", "isPlace", "name", "address", "category", "note")) add(f)
    }
}

/** Gemini's JSON text → [Extracted], with every field defaulted and cleaned. */
fun parseExtracted(text: String): Extracted {
    val o = json.parseToJsonElement(text).jsonObject
    fun str(k: String) = o[k]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.trim().orEmpty()
    val endDate = str("endDate").takeIf { isDate(it) }
    return Extracted(
        kind = if (str("kind") == "coupon") "coupon" else "place",
        brand = str("brand"),
        benefit = str("benefit"),
        isPlace = o["isPlace"]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } ?: false,
        name = str("name"),
        address = str("address"),
        category = str("category").ifEmpty { "기타" },
        note = str("note"),
        endDate = endDate,
    )
}

/** Reads a screenshot with Gemini (structured JSON output). */
class GeminiClient(
    private val key: String,
    private val model: String,
    private val client: OkHttpClient,
    private val base: String = "https://generativelanguage.googleapis.com/v1beta",
) {
    suspend fun extract(base64Jpeg: String): Extracted {
        check(key.isNotEmpty()) { "Gemini API 키가 없어요. local.properties의 GEMINI_API_KEY를 설정하세요." }
        val body = buildJsonObject {
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", PROMPT) })
                            add(buildJsonObject { putJsonObject("inline_data") { put("mime_type", "image/jpeg"); put("data", base64Jpeg) } })
                        }
                    },
                )
            }
            putJsonObject("generationConfig") {
                put("temperature", 0)
                put("responseMimeType", "application/json")
                put("responseSchema", SCHEMA)
            }
        }
        val req = Request.Builder()
            .url("$base/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = client.newCall(req).await().use { res ->
            val raw = res.body?.string().orEmpty()
            check(res.isSuccessful) { "Gemini 요청 실패 (${res.code}): ${raw.take(200)}" }
            val root = json.parseToJsonElement(raw).jsonObject
            root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("text")?.jsonPrimitive?.contentOrNull
        }
        check(!text.isNullOrBlank()) { "Gemini 응답이 비어 있어요." }
        return parseExtracted(text)
    }
}
