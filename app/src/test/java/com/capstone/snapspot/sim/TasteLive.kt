/**
 * Taste suggestions, live: the app's own code path (core/TasteNearby.kt) against real Kakao and Gemini,
 * for made-up users with different tastes standing in different Seoul neighborhoods.
 *
 * Each user has one theme, decided before running and judged only from Kakao's category_name
 * (a rule per theme, below). Metrics:
 *  - theme precision: of the places shown, how many fit the user's theme. Compared with showing the
 *    nearest 3 candidates and with 3 random ones (expected value = the theme's share of the pool).
 *  - missed: the pool had a fitting place but none was shown.
 *  - overlap: in one neighborhood, how much two single-theme users' suggestions share (Jaccard).
 *    Low means the suggestions follow the user, not the neighborhood.
 *  - time: one full suggestion (Kakao calls + embeddings), cold and with the embedding cache warm.
 *
 * Runs twice: candidates from the category groups only ("before"), and with the keyword searches for
 * the user's most saved categories that the app does ("app"). Saved places are found on Kakao first,
 * as the app does when saving, so they carry real ids and are not suggested back.
 *
 *   ./gradlew :app:testDebugUnitTest -PtasteLive                   # prints the report
 *   ./gradlew :app:testDebugUnitTest -PtasteLive -PtasteLiveWrite  # also writes docs/taste-live.md
 */
package com.capstone.snapspot.sim

import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.Embedder
import com.capstone.snapspot.core.KakaoApi
import com.capstone.snapspot.core.KakaoDoc
import com.capstone.snapspot.core.Suggestion
import com.capstone.snapspot.core.distanceMeters
import com.capstone.snapspot.core.model.Place
import com.capstone.snapspot.core.suggestNearby
import com.capstone.snapspot.core.tasteCandidates
import com.capstone.snapspot.net.GeminiClient
import com.capstone.snapspot.net.KakaoHttp
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate
import java.util.Locale

private class LiveUser(val label: String, val saves: List<Pair<String, String>>, val fits: (String) -> Boolean)

private fun has(cat: String, vararg words: String) = words.any { it in cat }

private val DESSERT = { c: String -> has(c, "베이커리", "디저트", "제과", "도넛", "케이크", "아이스크림", "떡,한과") }
private val BAR = { c: String -> has(c, "술집") }

private val USERS = listOf(
    LiveUser(
        "디저트·베이커리",
        listOf("어니언 성수" to "카페", "누데이크 하우스 도산" to "디저트", "레이어드 연남" to "디저트", "태극당 본점" to "디저트", "런던베이글뮤지엄 안국" to "카페"),
        DESSERT,
    ),
    LiveUser(
        "전시·미술관",
        listOf("국립현대미술관 서울" to "전시", "리움미술관" to "전시", "대림미술관" to "전시", "아라리오뮤지엄 인 스페이스" to "전시", "서울시립미술관" to "전시"),
    ) { c -> has(c, "미술관", "갤러리", "전시관", "박물관", "화랑", "기념관") },
    LiveUser(
        "국밥·노포 한식",
        listOf("하동관 명동본점" to "한식", "농민백암순대 본점" to "한식", "우래옥" to "한식", "을지면옥" to "한식", "이문설농탕" to "한식"),
    ) { c -> has(c, "한식") },
    LiveUser(
        "술집·바",
        listOf("찰스H" to "술집", "르챔버" to "술집", "앨리스 청담" to "술집", "만선호프" to "술집", "바 참" to "술집"),
        BAR,
    ),
    LiveUser(
        "산책·공원",
        listOf("서울숲" to "공원", "올림픽공원" to "공원", "북서울꿈의숲" to "공원", "선유도공원" to "공원", "경의선숲길" to "공원"),
    ) { c -> has(c, "공원", "하천", "호수", "도보여행", "둘레길", "숲") },
    LiveUser(
        "섞인 취향 (디저트 2 + 술집 2)",
        listOf("어니언 성수" to "카페", "레이어드 연남" to "디저트", "찰스H" to "술집", "만선호프" to "술집"),
    ) { c -> DESSERT(c) || BAR(c) },
)

private val SPOTS = listOf(
    "성수동" to Coords(37.5447, 127.0582),
    "을지로3가" to Coords(37.5662, 126.9910),
    "홍대입구" to Coords(37.5563, 126.9236),
    "안국·삼청" to Coords(37.5794, 126.9850),
    "석촌호수" to Coords(37.5096, 127.1027),
)

/** Embeds through Gemini, remembering vectors like the app's EmbedCache (in memory). */
private class MemoEmbedder(private val gemini: GeminiClient) : Embedder {
    val memo = HashMap<String, FloatArray>()
    override suspend fun embed(texts: List<String>): List<FloatArray> {
        val missing = texts.distinct().filter { it !in memo }
        if (missing.isNotEmpty()) missing.zip(gemini.embed(missing)).forEach { (t, v) -> memo[t] = v }
        return texts.map { memo.getValue(it) }
    }
}

private class Run(
    val app: Boolean,
    val user: LiveUser,
    val spot: String,
    val pool: List<KakaoDoc>,
    val shown: List<Suggestion>,
    val coldMs: Long,
    val warmMs: Long,
) {
    val byId = pool.associateBy { it.id }
    fun fits(s: Suggestion) = user.fits(byId[s.kakaoId]?.category_name.orEmpty())
    val hits get() = shown.count(::fits)
    val poolFits get() = pool.count { user.fits(it.category_name) }
    val nearest3 get() = pool.sortedBy { it.distance() }.take(3)
    private fun KakaoDoc.distance() = distanceMeters(SPOTS.first { it.first == spot }.second, Coords(y.toDouble(), x.toDouble()))
}

private fun pct(n: Int, d: Int) = if (d == 0) "-" else String.format(Locale.ROOT, "%.0f%%", 100.0 * n / d)
private fun pct(x: Double) = String.format(Locale.ROOT, "%.0f%%", 100.0 * x)

class TasteLive {
    @Test
    fun live() = runBlocking {
        assumeTrue(System.getProperty("taste.live.run") == "true")
        val gKey = System.getProperty("taste.key").orEmpty()
        val kKey = System.getProperty("taste.kakao").orEmpty()
        assumeTrue(gKey.isNotEmpty() && kKey.isNotEmpty(), "GEMINI_API_KEY와 KAKAO_REST_KEY가 있어야 해요.")
        val http = OkHttpClient()
        val kakao: KakaoApi = KakaoHttp(kKey, http)
        val gemini = GeminiClient(gKey, "unused", http, timeoutMs = 60_000)

        val savedOf = USERS.associateWith { user ->
            user.saves.mapIndexed { i, (name, cat) ->
                val d = kakao.keyword(mapOf("query" to name, "size" to "1")).firstOrNull()
                Place("p$i", name, d?.road_address_name.orEmpty(), cat, "", null, d?.y?.toDouble() ?: 0.0, d?.x?.toDouble() ?: 0.0, 200, null, kakaoId = d?.id, createdAt = 0)
            }
        }
        val runs = mutableListOf<Run>()
        for (app in listOf(false, true)) for ((spot, at) in SPOTS) for (user in USERS) {
            val saved = savedOf.getValue(user)
            val embedder = MemoEmbedder(gemini)
            val t0 = System.nanoTime()
            val shown = suggestNearby(at, saved, kakao, embedder, byTaste = app)
            val cold = (System.nanoTime() - t0) / 1_000_000
            val t1 = System.nanoTime()
            suggestNearby(at, saved, kakao, embedder, byTaste = app)
            val warm = (System.nanoTime() - t1) / 1_000_000
            val pool = tasteCandidates(at, saved, kakao, byTaste = app)
            runs += Run(app, user, spot, pool, shown, cold, warm)
        }
        val unresolved = savedOf.values.flatten().filter { it.kakaoId == null }.map { it.name }
        val report = report(runs, unresolved)
        println(report)
        if (System.getProperty("taste.live.write") == "true") File(System.getProperty("taste.live.out")).writeText(report)
    }

    private class Summary(rs: List<Run>) {
        val shown = rs.sumOf { it.shown.size }
        val hits = rs.sumOf { it.hits }
        val nearHits = rs.sumOf { r -> r.nearest3.count { r.user.fits(it.category_name) } }
        val nearShown = rs.sumOf { it.nearest3.size }
        val randomExp = rs.filter { it.pool.isNotEmpty() }.map { it.poolFits.toDouble() / it.pool.size }.average()
        val missed = rs.count { it.poolFits > 0 && it.hits == 0 }
        val withFit = rs.count { it.poolFits > 0 }
        val noFit = rs.count { it.poolFits == 0 }
        val poolFits = rs.map { it.poolFits }.average()
        val poolSize = rs.map { it.pool.size }.average()
        val perRun = shown.toDouble() / rs.size
        val overlap: Double = run {
            val single = USERS.dropLast(1).toSet()
            SPOTS.flatMap { (spot, _) ->
                val sets = rs.filter { it.spot == spot && it.user in single }.map { r -> r.shown.map { it.kakaoId }.toSet() }
                sets.indices.flatMap { i -> (i + 1 until sets.size).map { j -> sets[i] to sets[j] } }
                    .filter { (a, b) -> a.isNotEmpty() || b.isNotEmpty() }
                    .map { (a, b) -> (a intersect b).size.toDouble() / (a union b).size }
            }.average()
        }
        val cold = rs.map { it.coldMs }.sorted()
        val warm = rs.map { it.warmMs }.sorted()
        val n = rs.size
    }

    private fun f1(x: Double) = String.format(Locale.ROOT, "%.1f", x)

    private fun report(runs: List<Run>, unresolved: List<String>): String = buildString {
        val b = Summary(runs.filter { !it.app })
        val a = Summary(runs.filter { it.app })

        appendLine("## 취향 추천 실측 (실제 카카오·Gemini)")
        appendLine()
        appendLine("측정일 ${LocalDate.now()}. 앱과 같은 코드(`core/TasteNearby.kt`의 `suggestNearby`)로 가상 사용자 ${USERS.size}명 × 동네 ${SPOTS.size}곳 = ${a.n}번 추천했어요.")
        appendLine("사용자마다 취향을 미리 정해 두고, 추천된 곳이 그 취향에 맞는지는 카카오 분류(`category_name`)만 보고 정해진 규칙으로 판정했어요(`sim/TasteLive.kt`). 저장 장소는 앱처럼 카카오에서 찾아 실제 ID와 좌표를 넣었어요.")
        appendLine()
        appendLine("후보를 모으는 방식 두 가지를 비교했어요.")
        appendLine("- 분류만: 카페·음식점·문화시설·관광명소마다 가까운 15곳")
        appendLine("- 앱(분류 + 취향 검색): 위에 더해, 저장한 장소에 많은 분류(최대 3개, 예: '술집')로 1km 안을 카카오 키워드 검색")
        appendLine()
        appendLine("### 한눈에")
        appendLine()
        appendLine("| 지표 | 분류만 | **앱 (분류 + 취향 검색)** |")
        appendLine("|---|---:|---:|")
        appendLine("| 취향 적중률: 취향 추천 | ${pct(b.hits, b.shown)} (${b.hits}/${b.shown}) | **${pct(a.hits, a.shown)}** (${a.hits}/${a.shown}) |")
        appendLine("| 취향 적중률: 가까운 순 3곳 (비교) | ${pct(b.nearHits, b.nearShown)} | ${pct(a.nearHits, a.nearShown)} |")
        appendLine("| 취향 적중률: 무작위 3곳 (비교, 기댓값) | ${pct(b.randomExp)} | ${pct(a.randomExp)} |")
        appendLine("| 후보 수 / 그중 취향에 맞는 곳 (평균) | ${f1(b.poolSize)} / ${f1(b.poolFits)}곳 | ${f1(a.poolSize)} / ${f1(a.poolFits)}곳 |")
        appendLine("| 후보 안에 취향에 맞는 곳이 아예 없던 경우 | ${b.noFit}/${b.n}번 | ${a.noFit}/${a.n}번 |")
        appendLine("| 맞는 후보가 있었는데 하나도 못 보여 준 경우 | ${b.missed}/${b.withFit}번 | ${a.missed}/${a.withFit}번 |")
        appendLine("| 한 번에 보여 준 개수 (평균) | ${f1(b.perRun)}곳 | ${f1(a.perRun)}곳 |")
        appendLine("| 같은 동네, 다른 취향끼리 추천 겹침 (평균 Jaccard) | ${pct(b.overlap)} | ${pct(a.overlap)} |")
        appendLine("| 걸린 시간: 처음 (보통 / 최대) | ${b.cold[b.n / 2]} / ${b.cold.last()}ms | ${a.cold[a.n / 2]} / ${a.cold.last()}ms |")
        appendLine("| 걸린 시간: 임베딩 캐시 있을 때 (보통) | ${b.warm[b.n / 2]}ms | ${a.warm[a.n / 2]}ms |")
        appendLine()
        appendLine("시간은 PC 유선 인터넷 기준이에요. 카카오 요청은 분류만 4번, 앱 방식은 최대 7번이에요.")
        if (unresolved.isNotEmpty()) appendLine("카카오에서 못 찾은 저장 장소: ${unresolved.joinToString(", ")} (이름·분류만으로 들어갔어요)")
        appendLine()
        appendLine("### 취향별 (앱 방식)")
        appendLine()
        appendLine("| 취향 | 적중률 | 분류만일 때 | 가까운 순 3곳 | 무작위 기댓값 | 후보 중 맞는 곳 (평균) | 못 보여 준 경우 |")
        appendLine("|---|---:|---:|---:|---:|---:|---:|")
        for (u in USERS) {
            val s = Summary(runs.filter { it.app && it.user === u })
            val sb = Summary(runs.filter { !it.app && it.user === u })
            appendLine("| ${u.label} | **${pct(s.hits, s.shown)}** (${s.hits}/${s.shown}) | ${pct(sb.hits, sb.shown)} | ${pct(s.nearHits, s.nearShown)} | ${pct(s.randomExp)} | ${f1(s.poolFits)}/${f1(s.poolSize)}곳 | ${s.missed}/${s.withFit} |")
        }
        appendLine()
        appendLine("### 실제로 뜬 추천")
        appendLine()
        appendLine("앱 방식 기준. O = 취향에 맞음, X = 아님. 괄호는 카카오 분류의 마지막 칸이에요.")
        for ((spot, _) in SPOTS) {
            appendLine()
            appendLine("**$spot**")
            appendLine()
            appendLine("| 취향 | 추천 |")
            appendLine("|---|---|")
            for (r in runs.filter { it.app && it.spot == spot }) {
                val list = r.shown.joinToString(", ") { s -> "${if (r.fits(s)) "O" else "X"} ${s.name} (${s.category})" }.ifEmpty { "(없음)" }
                appendLine("| ${r.user.label} | $list |")
            }
        }
        appendLine()
        appendLine("### 판정 규칙과 한계")
        appendLine()
        appendLine("- 취향 판정: 디저트 = 분류에 베이커리·디저트·제과·도넛·케이크·아이스크림·떡,한과. 전시 = 미술관·갤러리·전시관·박물관·화랑·기념관. 국밥·노포 = 한식. 술집 = 술집. 산책 = 공원·하천·호수·도보여행·둘레길·숲. 섞인 취향 = 디저트 또는 술집.")
        appendLine("- 모델도 분류 글자를 보기 때문에 분류로 판정하면 유리한 면이 있어요. 대신 비교 기준(가까운 순·무작위)도 같은 후보와 같은 규칙으로 쟀어요.")
        appendLine("- 취향 검색은 저장 장소의 분류 글자로 검색해서 판정 규칙과 겹치는 면이 있어요. 그래서 비교 기준(가까운 순·무작위)도 앱 방식의 같은 후보로 함께 쟀어요.")
        appendLine("- 동네에 그 취향의 가게가 실제로 없으면 후보에도 없어요(위 '아예 없던 경우'). 이때도 상대 점수로 3곳을 고르기 때문에 취향과 먼 곳이 뜰 수 있어요.")
        appendLine("- 가상 사용자예요. 실제로 마음에 들어 했는지는 사용자 테스트로 따로 봐야 해요.")
    }
}
