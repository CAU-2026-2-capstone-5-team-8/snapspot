/**
 * Taste benchmark: does the taste vector (core/Taste.kt) find places the user would save?
 *
 * Six made-up users save eight places each. Leave one out: the other seven make the taste, and
 * the held-out place is hidden among every other user's places plus everyday places
 * (convenience stores, banks...). A good taste ranks it near the top.
 *
 * Saved places are written the way the app stores them (Gemini's short category, e.g. "카페");
 * candidates the way Kakao returns them ("음식점 > 카페 > 디저트카페"), as in the real app.
 *
 * Calls the real Gemini embedding API (about 160 texts, 2 requests).
 *
 *   ./gradlew :app:testDebugUnitTest -PtasteBench                # prints the report
 *   ./gradlew :app:testDebugUnitTest -PtasteBench -PtasteWrite   # also writes docs/taste.md
 */
package com.capstone.snapspot.sim

import com.capstone.snapspot.core.Scored
import com.capstone.snapspot.core.rankByNearest
import com.capstone.snapspot.core.rankByTaste
import com.capstone.snapspot.core.tasteText
import com.capstone.snapspot.core.tasteVector
import com.capstone.snapspot.net.GEMINI_EMBED_MODEL
import com.capstone.snapspot.net.GeminiClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

/** name, the app's short category, the memo Gemini wrote, Kakao's category_name. */
private class P(val name: String, val cat: String, val note: String, val kakao: String) {
    val kakaoText get() = "$name | $kakao"
}

private class Persona(val label: String, val places: List<P>)

private val PERSONAS = listOf(
    Persona(
        "디저트·베이커리",
        listOf(
            P("어니언 성수", "카페", "소금빵 맛집", "음식점 > 카페 > 베이커리"),
            P("누데이크 하우스 도산", "디저트", "독특한 모양 케이크", "음식점 > 카페 > 디저트카페"),
            P("레이어드 연남", "디저트", "스콘이 유명한 곳", "음식점 > 카페 > 디저트카페"),
            P("노티드 청담", "디저트", "우유 크림 도넛", "음식점 > 간식 > 도넛"),
            P("런던베이글뮤지엄 안국", "카페", "베이글 웨이팅 맛집", "음식점 > 카페 > 베이커리"),
            P("아우프글렛 한남", "카페", "디저트가 예쁜 카페", "음식점 > 카페"),
            P("태극당 본점", "디저트", "옛날 모나카 아이스크림", "음식점 > 간식 > 제과,베이커리"),
            P("밀토스트 익선", "카페", "우유식빵 토스트", "음식점 > 카페 > 베이커리"),
        ),
    ),
    Persona(
        "전시·미술관",
        listOf(
            P("국립현대미술관 서울", "전시", "현대미술 기획전", "문화,예술 > 문화시설 > 미술관"),
            P("리움미술관", "전시", "고미술과 현대미술", "문화,예술 > 문화시설 > 미술관"),
            P("대림미술관", "전시", "사진전 보러 가기", "문화,예술 > 문화시설 > 미술관"),
            P("아라리오뮤지엄 인 스페이스", "전시", "건축이 멋진 미술관", "문화,예술 > 문화시설 > 미술관"),
            P("서울시립미술관", "전시", "무료 전시", "문화,예술 > 문화시설 > 미술관"),
            P("피크닉", "전시", "남산 아래 전시 공간", "문화,예술 > 문화시설 > 전시관"),
            P("그라운드시소 성수", "전시", "미디어아트 전시", "문화,예술 > 문화시설 > 전시관"),
            P("송은", "전시", "건물이 특이한 무료 전시", "문화,예술 > 문화시설 > 미술관"),
        ),
    ),
    Persona(
        "국밥·노포",
        listOf(
            P("옥동식", "식당", "맑은 돼지국밥", "음식점 > 한식 > 국밥"),
            P("농민백암순대 본점", "식당", "순댓국 맛집", "음식점 > 한식 > 순대,순댓국"),
            P("하동관 명동", "식당", "오래된 곰탕집", "음식점 > 한식 > 곰탕"),
            P("우래옥", "식당", "평양냉면 노포", "음식점 > 한식 > 냉면"),
            P("이문설농탕", "식당", "100년 된 설렁탕집", "음식점 > 한식 > 설렁탕"),
            P("청진옥", "식당", "해장국 노포", "음식점 > 한식 > 해장국"),
            P("진주회관", "식당", "여름 콩국수", "음식점 > 한식 > 국수"),
            P("부촌육회", "식당", "광장시장 육회", "음식점 > 한식 > 육류,고기"),
        ),
    ),
    Persona(
        "술집·바",
        listOf(
            P("을지로 만선호프", "술집", "노가리 골목", "음식점 > 술집 > 호프,요리주점"),
            P("찰스 H", "술집", "호텔 칵테일 바", "음식점 > 술집 > 칵테일바"),
            P("르챔버", "술집", "청담 스피크이지 바", "음식점 > 술집 > 칵테일바"),
            P("앨리스 청담", "술집", "꽃집 뒤에 숨은 바", "음식점 > 술집 > 칵테일바"),
            P("문샤인", "술집", "막걸리 바", "음식점 > 술집 > 전통주점"),
            P("한남 내추럴와인바", "술집", "내추럴 와인", "음식점 > 술집 > 와인바"),
            P("서촌 계단집", "술집", "해산물 포차", "음식점 > 술집 > 실내포장마차"),
            P("성수 이자카야 하루", "술집", "사케 한잔", "음식점 > 술집 > 일본식주점"),
        ),
    ),
    Persona(
        "산책·자연",
        listOf(
            P("서울숲", "관광", "주말 산책", "여행 > 공원 > 도시근린공원"),
            P("북서울꿈의숲", "관광", "단풍 명소", "여행 > 공원 > 도시근린공원"),
            P("남산서울타워", "관광", "야경 보기", "여행 > 관광,명소 > 전망대"),
            P("선유도공원", "관광", "한강 섬 공원 산책", "여행 > 공원 > 도시근린공원"),
            P("낙산공원", "관광", "성곽길 야경", "여행 > 공원 > 도시근린공원"),
            P("북한산 우이령길", "관광", "가벼운 등산", "여행 > 관광,명소 > 산"),
            P("창덕궁 후원", "관광", "가을 궁궐 산책", "여행 > 관광,명소 > 고궁"),
            P("반포한강공원", "관광", "무지개분수", "여행 > 공원 > 한강공원"),
        ),
    ),
    Persona(
        "쇼핑·팝업",
        listOf(
            P("더현대 서울 캐릭터 팝업", "팝업", "캐릭터 팝업스토어", "쇼핑 > 백화점"),
            P("성수 디올", "팝업", "플래그십 스토어", "쇼핑 > 패션 > 명품"),
            P("무신사 스탠다드 성수", "쇼핑", "옷 구경", "쇼핑 > 패션 > 의류판매"),
            P("젠틀몬스터 하우스 도산", "쇼핑", "안경 플래그십", "쇼핑 > 패션 > 안경"),
            P("탬버린즈 성수", "쇼핑", "향수 쇼룸", "쇼핑 > 화장품"),
            P("포인트오브뷰 성수", "쇼핑", "문구 편집숍", "쇼핑 > 문구,팬시"),
            P("아더에러 성수", "쇼핑", "공간이 예쁜 매장", "쇼핑 > 패션 > 의류판매"),
            P("띵굴스토어", "팝업", "생활용품 팝업", "쇼핑 > 생활용품"),
        ),
    ),
)

/** Everyday places nobody saves on purpose; each with the short category it would get. */
private val EVERYDAY = listOf(
    P("다이소 성수점", "쇼핑", "", "쇼핑 > 생활용품 > 다이소"),
    P("CU 성수역점", "기타", "", "쇼핑 > 편의점 > CU"),
    P("GS25 연남점", "기타", "", "쇼핑 > 편의점 > GS25"),
    P("신한은행 성수동지점", "기타", "", "금융,보험 > 금융서비스 > 은행 > 신한은행"),
    P("올리브영 성수역점", "쇼핑", "", "쇼핑 > 화장품 > 올리브영"),
    P("스타벅스 성수역점", "카페", "", "음식점 > 카페 > 커피전문점 > 스타벅스"),
    P("맥도날드 성수점", "식당", "", "음식점 > 패스트푸드 > 맥도날드"),
    P("이마트 성수점", "쇼핑", "", "쇼핑 > 대형마트 > 이마트"),
    P("메가MGC커피 성수점", "카페", "", "음식점 > 카페 > 커피전문점 > 메가MGC커피"),
    P("롯데시네마 건대입구", "기타", "", "문화,예술 > 영화,영상 > 영화관 > 롯데시네마"),
    P("성수연세내과의원", "기타", "", "의료,건강 > 병원 > 내과"),
    P("SK에너지 성수주유소", "기타", "", "교통,수송 > 자동차 > 주유소"),
)

/**
 * Look like a theme but are not what that user saves: chains and near-misses with a close
 * Kakao category (a franchise bakery for the dessert lover, a chain pub for the bar lover).
 */
private val NEAR_MISS = listOf(
    P("파리바게뜨 성수역점", "디저트", "", "음식점 > 간식 > 제과,베이커리 > 파리바게뜨"),
    P("배스킨라빈스 성수점", "디저트", "", "음식점 > 간식 > 아이스크림 > 배스킨라빈스"),
    P("던킨 건대점", "디저트", "", "음식점 > 간식 > 도넛 > 던킨"),
    P("이디야커피 성수점", "카페", "", "음식점 > 카페 > 커피전문점 > 이디야커피"),
    P("CGV 용산아이파크몰", "기타", "", "문화,예술 > 영화,영상 > 영화관 > CGV"),
    P("교보문고 광화문점", "쇼핑", "", "문화,예술 > 도서 > 서점 > 교보문고"),
    P("코엑스 아쿠아리움", "관광", "", "여행 > 관광,명소 > 아쿠아리움"),
    P("김밥천국 성수점", "식당", "", "음식점 > 분식 > 김밥천국"),
    P("본죽 성수역점", "식당", "", "음식점 > 한식 > 죽 > 본죽"),
    P("한솥도시락 성수점", "식당", "", "음식점 > 도시락 > 한솥도시락"),
    P("명륜진사갈비 성수점", "식당", "", "음식점 > 한식 > 육류,고기 > 명륜진사갈비"),
    P("생활맥주 성수점", "술집", "", "음식점 > 술집 > 호프,요리주점 > 생활맥주"),
    P("투다리 성수점", "술집", "", "음식점 > 술집 > 실내포장마차 > 투다리"),
    P("락휴 코인노래방 건대점", "기타", "", "가정,생활 > 여가시설 > 노래방"),
    P("롯데월드 어드벤처", "관광", "", "여행 > 테마파크 > 롯데월드"),
    P("스파렉스 동대문점", "기타", "", "가정,생활 > 목욕,찜질 > 찜질방"),
    P("골프존파크 성수점", "기타", "", "스포츠,레저 > 골프 > 스크린골프장"),
    P("유니클로 성수점", "쇼핑", "", "쇼핑 > 패션 > 의류판매 > 유니클로"),
    P("ABC마트 건대점", "쇼핑", "", "쇼핑 > 신발 > ABC마트"),
    P("홈플러스 성수점", "쇼핑", "", "쇼핑 > 대형마트 > 홈플러스"),
)

private val KS = listOf(1, 3, 5)

/** Rank of the target per query (1 = top), possibly fractional for ties (expected rank). */
private class Tally(val label: String) {
    private val hits = KS.associateWith { 0.0 }.toMutableMap()
    private var rr = 0.0
    var n = 0
        private set

    /** [better]: candidates strictly ahead of the target; [tied]: candidates level with it, target included. */
    fun add(better: Int, tied: Int) {
        n++
        for (k in KS) hits[k] = hits.getValue(k) + ((k - better).toDouble() / tied).coerceIn(0.0, 1.0)
        rr += (1..tied).sumOf { 1.0 / (better + it) } / tied
    }

    fun hit(k: Int) = pct(hits.getValue(k) / n)
    fun mrr() = "%.3f".format(Locale.US, rr / n)
}

private fun pct(x: Double) = "%.1f%%".format(Locale.US, 100 * x)

/**
 * One test user. [saved]: what they saved; [own]: every place of their themes, saved or not,
 * kept out of the candidates (an unsaved place of their own theme is not a wrong answer);
 * [noise]: a save that is not their taste, never held out.
 */
private class TestUser(val saved: List<P>, val own: Set<P>, val noise: P? = null)

private class Scenario(val label: String, val about: String, val users: List<TestUser>, val pool: List<P>)

private fun personaPlaces(i: Int) = PERSONAS[i % PERSONAS.size].places

/** One user per persona i, saving split[k] places of the persona at i + offsets[k]. */
private fun mixed(offsets: List<Int>, split: List<Int>): List<TestUser> = PERSONAS.indices.map { i ->
    var from = 0
    val saved = offsets.zip(split).flatMap { (o, n) -> personaPlaces(i + o).subList(from, from + n).also { from += n } }
    TestUser(saved, offsets.flatMap { personaPlaces(i + it) }.toSet())
}

private val ALL = PERSONAS.flatMap { it.places }

private val SCENARIOS = listOf(
    Scenario("기본", "한 주제 8곳, 오답은 다른 사람 장소와 일상 장소", PERSONAS.map { TestUser(it.places, it.places.toSet()) }, ALL + EVERYDAY),
    Scenario("헷갈리는 오답", "한 주제 8곳, 프랜차이즈 빵집·체인 술집 같은 비슷한 오답 추가", PERSONAS.map { TestUser(it.places, it.places.toSet()) }, ALL + EVERYDAY + NEAR_MISS),
    Scenario("두 취향", "두 주제 4곳씩, 비슷한 오답 포함", mixed(listOf(0, 3), listOf(4, 4)), ALL + EVERYDAY + NEAR_MISS),
    Scenario("세 취향", "세 주제 3·3·2곳, 비슷한 오답 포함", mixed(listOf(0, 2, 4), listOf(3, 3, 2)), ALL + EVERYDAY + NEAR_MISS),
    Scenario(
        "두 취향 + 잡음",
        "두 주제 4곳씩에 상관없는 곳(편의점 등) 1곳을 더 저장, 비슷한 오답 포함",
        mixed(listOf(0, 3), listOf(4, 4)).mapIndexed { i, u ->
            val n = EVERYDAY[i * 2 % EVERYDAY.size]
            TestUser(u.saved + n, u.own, n)
        },
        ALL + EVERYDAY + NEAR_MISS,
    ),
)

private class Outcome(val scenario: Scenario, val tallies: List<Tally>, val candidates: Int, val picks: Map<String, Double>)

private const val MEAN = "평균 취향 (이름+분류)"
private const val MEAN_NOTE = "평균 취향 (이름+분류+메모)"
private const val NEAREST = "최근접 1곳 (이름+분류)"
private const val NEAREST2 = "최근접 2곳 평균 (이름+분류)"
private const val NEAREST3 = "최근접 3곳 평균 (이름+분류)"
private const val CATEGORY = "같은 분류 개수 (기준선)"
private const val RANDOM = "무작위 (기준선)"

private class Vectors(val short: Map<P, FloatArray>, val note: Map<P, FloatArray>, val kakao: Map<P, FloatArray>, val dim: Int)

private fun embedAll(gemini: GeminiClient): Vectors {
    val saveable = ALL + EVERYDAY // noise saves come from EVERYDAY
    val cands = ALL + EVERYDAY + NEAR_MISS
    val texts = saveable.map { tasteText(it.name, it.cat) } + saveable.map { tasteText(it.name, it.cat, it.note) } + cands.map { it.kakaoText }
    val v = runBlocking { gemini.embed(texts) }
    val s = saveable.size
    return Vectors(
        saveable.indices.associate { saveable[it] to v[it] },
        saveable.indices.associate { saveable[it] to v[s + it] },
        cands.indices.associate { cands[it] to v[2 * s + it] },
        v.first().size,
    )
}

private fun measure(sc: Scenario, vx: Vectors): Outcome {
    val t = listOf(MEAN, MEAN_NOTE, NEAREST, NEAREST2, NEAREST3, CATEGORY, RANDOM).associateWith { Tally(it) }
    val picks = mutableMapOf(MEAN to 0, NEAREST to 0, NEAREST2 to 0, NEAREST3 to 0)
    var candidates = 0
    for (u in sc.users) for (target in u.saved) {
        if (target === u.noise) continue
        val rest = u.saved - target
        val cands = listOf(target) + sc.pool.filter { it !in u.own && it !== u.noise }
        candidates = cands.size
        val pairs = cands.map { it to vx.kakao.getValue(it) }
        fun record(label: String, ranked: List<Scored<P>>) {
            val hit = ranked.first { it.item === target }
            t.getValue(label).add(ranked.count { it.similarity > hit.similarity }, ranked.count { it.similarity == hit.similarity })
            if (label in picks && ranked.filter { it.lift >= 0.5 }.take(3).any { it.item === target }) picks[label] = picks.getValue(label) + 1
        }
        record(MEAN, rankByTaste(tasteVector(rest.map { vx.short.getValue(it) })!!, pairs))
        record(MEAN_NOTE, rankByTaste(tasteVector(rest.map { vx.note.getValue(it) })!!, pairs))
        record(NEAREST, rankByNearest(rest.map { vx.short.getValue(it) }, pairs, k = 1))
        record(NEAREST2, rankByNearest(rest.map { vx.short.getValue(it) }, pairs, k = 2))
        record(NEAREST3, rankByNearest(rest.map { vx.short.getValue(it) }, pairs, k = 3))
        val count = rest.groupingBy { it.cat }.eachCount()
        val score = cands.associateWith { count[it.cat] ?: 0 }
        val ts = score.getValue(target)
        t.getValue(CATEGORY).add(cands.count { score.getValue(it) > ts }, cands.count { score.getValue(it) == ts })
        t.getValue(RANDOM).add(0, cands.size)
    }
    val n = t.getValue(MEAN).n.toDouble()
    return Outcome(sc, t.values.toList(), candidates, picks.mapValues { it.value / n })
}

private fun report(outs: List<Outcome>, dim: Int): String = buildString {
    appendLine("## 취향 추천 정확도 (가상 사용자)")
    appendLine()
    appendLine("가상 사용자(${PERSONAS.joinToString(", ") { it.label }} 주제를 조합)가 저장한 장소로 `core/Taste.kt`를 재요.")
    appendLine("저장한 곳 하나를 빼고 나머지로 취향을 만든 다음, 뺀 곳을 오답 후보들 사이에 섞어 몇 등에 오는지 봐요. 저장한 곳마다 한 번씩 시험해요.")
    appendLine()
    appendLine("- 저장 장소는 앱이 저장하는 대로 짧은 분류(\"카페\")로, 후보는 카카오가 주는 대로 긴 분류(\"음식점 > 카페 > 디저트카페\")로 넣었어요.")
    appendLine("- 모델: `$GEMINI_EMBED_MODEL` (${dim}차원). 주소와 좌표는 보내지 않아요.")
    appendLine("- 평균 취향: 저장한 곳들의 평균 벡터와 비교. 최근접 k곳: 저장한 곳 중 가장 비슷한 k곳과의 유사도 평균.")
    appendLine("- hit@k: 뺀 곳이 k등 안에 든 비율. MRR: 1/등수의 평균(1이면 늘 1등). 동점은 기대 등수로 계산했어요.")
    appendLine()
    appendLine("### 한눈에 (MRR)")
    appendLine()
    appendLine("| 방법 | ${outs.joinToString(" | ") { it.scenario.label }} |")
    appendLine("|---|${outs.joinToString("") { "---:|" }}")
    for (label in outs.first().tallies.map { it.label }) {
        appendLine("| $label | ${outs.joinToString(" | ") { o -> o.tallies.first { it.label == label }.mrr() }} |")
    }
    appendLine("| 추천 3곳에 든 비율: 평균 취향 | ${outs.joinToString(" | ") { pct(it.picks.getValue(MEAN)) }} |")
    for (m in listOf(NEAREST, NEAREST2, NEAREST3)) appendLine("| 추천 3곳에 든 비율: ${m.substringBefore(" (")} | ${outs.joinToString(" | ") { pct(it.picks.getValue(m)) }} |")
    appendLine()
    appendLine("추천 3곳: 앱의 규칙대로 lift 0.5 이상 중 상위 3곳을 골랐을 때 뺀 곳이 들어간 비율이에요.")
    for (o in outs) {
        appendLine()
        appendLine("### ${o.scenario.label}")
        appendLine()
        appendLine("${o.scenario.about}. 후보 ${o.candidates}곳, 시험 ${o.tallies.first().n}번.")
        appendLine()
        appendLine("| 방법 | hit@1 | hit@3 | hit@5 | MRR |")
        appendLine("|---|---:|---:|---:|---:|")
        for (t in o.tallies) appendLine("| ${t.label} | ${t.hit(1)} | ${t.hit(3)} | ${t.hit(5)} | ${t.mrr()} |")
    }
    appendLine()
    appendLine("### 한계")
    appendLine()
    appendLine("- 사용자와 장소는 직접 만든 가짜예요. 실제 저장 데이터로 다시 재야 해요.")
    appendLine("- \"같은 분류 개수\" 기준선은 후보의 짧은 분류를 정답으로 알고 있다고 가정해서 실제보다 유리해요. 앱에서는 카카오 분류를 짧은 분류로 바꾸는 단계가 따로 필요해요.")
    appendLine("- 같은 사람이 다시 저장할 곳을 맞히는지 보는 대리 지표예요. 실제로 마음에 들어 했는지는 사용자 테스트로 따로 봐야 해요.")
}

class TasteBench {
    @Test
    fun run() {
        assumeTrue(System.getProperty("taste.run") == "true")
        val key = System.getProperty("taste.key").orEmpty()
        assumeTrue(key.isNotEmpty(), "GEMINI_API_KEY가 없어서 건너뛰어요.")
        val vx = embedAll(GeminiClient(key, "unused", OkHttpClient(), timeoutMs = 60_000))
        val md = report(SCENARIOS.map { measure(it, vx) }, vx.dim)
        println(md)
        if (System.getProperty("taste.write") == "true") {
            File(requireNotNull(System.getProperty("taste.out"))).writeText(md)
        }
    }
}
