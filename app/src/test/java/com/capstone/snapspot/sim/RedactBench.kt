/**
 * Redaction benchmark on synthetic screenshots: fake personal information (phone numbers, emails,
 * resident numbers, card numbers, barcode/PIN/order numbers) is put into lines that look like
 * gift cards, posts, chats, receipts and posters, together with things the app must keep (dates,
 * prices, times, street numbers). Measures how much is hidden and how much is hidden by mistake,
 * on clean text and on text with OCR-like errors.
 *
 * All values are made up from a fixed seed; no real data is used.
 *
 *   ./gradlew :app:testDebugUnitTest -PredactBench                 # prints the report
 *   ./gradlew :app:testDebugUnitTest -PredactBench -PredactWrite   # also writes docs/redaction.md
 */
package com.capstone.snapspot.sim

import com.capstone.snapspot.core.Box
import com.capstone.snapspot.core.OcrLine
import com.capstone.snapspot.core.OcrPage
import com.capstone.snapspot.core.OcrWord
import com.capstone.snapspot.core.findPii
import com.capstone.snapspot.core.maskBoxes
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale
import kotlin.random.Random

private const val SCREENSHOTS = 400
private const val SEED = 20261104

/** What a planted piece of text is. PII kinds must be hidden; KEEP kinds must not be. */
private enum class Kind(val label: String, val pii: Boolean) {
    PHONE("전화번호", true),
    EMAIL("이메일", true),
    RRN("주민등록번호", true),
    CARD("카드번호", true),
    BARCODE("바코드 번호", true),
    PIN("핀 번호", true),
    ORDER("주문번호", true),
    DATE("날짜", false),
    PRICE("가격", false),
    TIME("영업시간", false),
    STREET("주소 번지", false),
    SHORT("짧은 숫자(D-day·층·수량)", false),
}

/** A planted value and where it sits in its line's text. */
private class Span(val kind: Kind, val range: IntRange)

private class Line(val text: String, val spans: List<Span>)

/** Builds one line from literal text and planted values, remembering where each value went. */
private class LineBuilder {
    private val sb = StringBuilder()
    private val spans = mutableListOf<Span>()
    fun t(s: String) = apply { sb.append(s) }
    fun v(kind: Kind, s: String) = apply {
        val start = sb.length
        sb.append(s)
        spans += Span(kind, start until sb.length)
    }
    fun build() = Line(sb.toString(), spans)
}

private fun line(f: LineBuilder.() -> Unit) = LineBuilder().apply(f).build()

private class Fake(val r: Random) {
    fun digits(n: Int) = (1..n).joinToString("") { r.nextInt(10).toString() }
    fun pick(vararg s: String) = s[r.nextInt(s.size)]

    fun phone(): String {
        val sep = pick("-", "-", " ", ".", "")
        return when (r.nextInt(4)) {
            0, 1 -> "010$sep${digits(4)}$sep${digits(4)}"
            2 -> "02$sep${digits(r.nextInt(3, 5))}$sep${digits(4)}"
            else -> "${pick("031", "032", "051", "070")}$sep${digits(r.nextInt(3, 5))}$sep${digits(4)}"
        }
    }

    fun email(): String {
        val user = pick("jimin", "cafe.lover", "seoul_kim", "hello", "snap.spot", "me") + digits(r.nextInt(0, 3))
        return "$user@${pick("cau.ac.kr", "gmail.com", "naver.com", "daum.net", "kakao.com")}"
    }

    fun rrn(): String {
        val yy = "%02d".format(r.nextInt(100))
        val mm = "%02d".format(r.nextInt(1, 13))
        val dd = "%02d".format(r.nextInt(1, 29))
        return "$yy$mm$dd${pick("-", "-", " - ")}${r.nextInt(1, 5)}${digits(6)}"
    }

    fun card(): String {
        val sep = pick("-", " ")
        return List(4) { digits(4) }.joinToString(sep)
    }

    fun barcode(): String = when (r.nextInt(3)) {
        0 -> List(3) { digits(4) }.joinToString(" ")
        1 -> List(3) { digits(4) }.joinToString("-")
        else -> digits(12)
    }

    fun pin(): String = List(4) { digits(4) }.joinToString(pick("-", " ")).let { if (r.nextBoolean()) it else it.replace(Regex("[ -]"), "") }

    fun order(): String = digits(r.nextInt(10, 15))

    fun date(): String {
        val y = 2026 + r.nextInt(2)
        val m = r.nextInt(1, 13)
        val d = r.nextInt(1, 29)
        return when (r.nextInt(3)) {
            0 -> "%d.%02d.%02d".format(y, m, d)
            1 -> "%d-%02d-%02d".format(y, m, d)
            else -> "${y}년 ${m}월 ${d}일"
        }
    }

    fun price(): String {
        val won = (r.nextInt(1, 60) * 100 + r.nextInt(2) * 50) * if (r.nextBoolean()) 10 else 1
        return if (r.nextBoolean()) "%,d원".format(Locale.US, won) else "${won}원"
    }

    fun time(): String = "%02d:%02d - %02d:%02d".format(r.nextInt(7, 12), pick("00", "30").toInt(), r.nextInt(18, 24), pick("00", "30").toInt())

    fun street(): String = "${pick("여의대로", "아차산로9길", "성수이로", "연무장길", "테헤란로", "흑석로")} ${r.nextInt(1, 400)}"

    fun short(): String = pick("D-${r.nextInt(1, 120)}", "${r.nextInt(1, 10)}층", "1+1", "${r.nextInt(1, 5)}잔", "No.${r.nextInt(1, 999)}")
}

/** Five kinds of screenshot, each mixing personal information with what the app needs. */
private fun screenshot(f: Fake): List<Line> = when (f.r.nextInt(5)) {
    0 -> listOf( // gift card
        line { t("받은 선물") },
        line { t(f.pick("스타벅스", "메가MGC커피", "투썸플레이스", "CU")) },
        line { t("카페 아메리카노 Tall ").v(Kind.SHORT, f.short()) },
        line { v(Kind.BARCODE, f.barcode()) },
        line { t("유효기간 ").v(Kind.DATE, f.date()).t(" 까지 ").v(Kind.SHORT, f.short()) },
        line { t("주문번호 ").v(Kind.ORDER, f.order()) },
        line { t("PIN ").v(Kind.PIN, f.pin()) },
        line { t("보낸 사람 연락처 ").v(Kind.PHONE, f.phone()) },
    )
    1 -> listOf( // place post
        line { t("성수 어니언 다녀왔어요 ").v(Kind.SHORT, f.short()) },
        line { t("서울 성동구 ").v(Kind.STREET, f.street()) },
        line { t("영업 ").v(Kind.TIME, f.time()) },
        line { t("예약 문의 ").v(Kind.PHONE, f.phone()) },
        line { t("협찬 문의 ").v(Kind.EMAIL, f.email()) },
        line { t("아메리카노 ").v(Kind.PRICE, f.price()) },
    )
    2 -> listOf( // chat
        line { t("오늘 여기 가자 ").v(Kind.DATE, f.date()) },
        line { t("내 번호 ").v(Kind.PHONE, f.phone()).t(" 로 연락해") },
        line { t("서류에 주민번호 ").v(Kind.RRN, f.rrn()).t(" 적었어") },
        line { t("메일은 ").v(Kind.EMAIL, f.email()) },
        line { t("만원 넘으면 ").v(Kind.PRICE, f.price()).t(" 할인") },
    )
    3 -> listOf( // receipt / order
        line { t("결제 완료 ").v(Kind.DATE, f.date()) },
        line { t("카드 ").v(Kind.CARD, f.card()) },
        line { t("주문번호 ").v(Kind.ORDER, f.order()) },
        line { t("합계 ").v(Kind.PRICE, f.price()) },
        line { t("매장 ").v(Kind.STREET, f.street()).t(" ").v(Kind.SHORT, f.short()) },
        line { t("고객센터 ").v(Kind.PHONE, f.phone()) },
    )
    else -> listOf( // poster / exhibition
        line { t("팝업스토어 ").v(Kind.DATE, f.date()).t(" ~ ").v(Kind.DATE, f.date()) },
        line { t("운영 ").v(Kind.TIME, f.time()) },
        line { t("장소 ").v(Kind.STREET, f.street()).t(" ").v(Kind.SHORT, f.short()) },
        line { t("입장료 ").v(Kind.PRICE, f.price()) },
        line { t("문의 ").v(Kind.PHONE, f.phone()).t(" / ").v(Kind.EMAIL, f.email()) },
    )
}

/**
 * OCR-like errors on one line, keeping the spans pointing at the same values: a separator read
 * as another (- as ~ or .), a space dropped or added next to a separator, a 0 read as O.
 */
private fun noisy(l: Line, r: Random): Line {
    val out = StringBuilder()
    // For each original index, where it went (-1 when dropped).
    val at = IntArray(l.text.length) { -1 }
    for ((i, c) in l.text.withIndex()) {
        val inPii = l.spans.any { it.kind.pii && i in it.range }
        when {
            c == '-' && inPii && r.nextDouble() < 0.15 -> { at[i] = out.length; out.append(if (r.nextBoolean()) '~' else '.') }
            c == ' ' && inPii && r.nextDouble() < 0.15 -> Unit // dropped
            c == '0' && inPii && r.nextDouble() < 0.05 -> { at[i] = out.length; out.append('O') }
            else -> { at[i] = out.length; out.append(c) }
        }
        if (inPii && c.isDigit() && i + 1 < l.text.length && l.text[i + 1].isDigit() && r.nextDouble() < 0.02) out.append(' ')
    }
    val spans = l.spans.map { s ->
        val first = s.range.map { at[it] }.first { it >= 0 }
        val last = s.range.map { at[it] }.last { it >= 0 }
        Span(s.kind, first..last)
    }
    return Line(out.toString(), spans)
}

/** Lays a line out as words 10px per character, like RedactTest's ocrLine. */
private fun ocr(l: Line, top: Int): OcrLine {
    var x = 0
    return OcrLine(l.text.split(" ").map { w -> OcrWord(w, Box(x, top, x + w.length * 10, top + 20)).also { x += (w.length + 1) * 10 } })
}

/** The words of `l` that overlap `range`, as indexes. */
private fun wordsOf(l: Line, range: IntRange): List<Int> {
    var start = 0
    val out = mutableListOf<Int>()
    for ((i, w) in l.text.split(" ").withIndex()) {
        val end = start + w.length - 1
        if (w.isNotEmpty() && start <= range.last && range.first <= end) out += i
        start = end + 2
    }
    return out
}

private class Tally {
    var n = 0
    var text = 0 // fully inside a text hit (redactText leaves nothing of it)
    var image = 0 // every word it spans is painted over
    fun rate(k: Int) = if (n == 0) "-" else "%.1f%%".format(Locale.US, 100.0 * k / n)
}

private class Result(val byKind: Map<Kind, Tally>)

/** The same screenshots both times (one seed); the noise has its own seed. */
private fun measure(withNoise: Boolean): Result {
    val f = Fake(Random(SEED))
    val noise = Random(SEED + 1)
    val tallies = Kind.entries.associateWith { Tally() }
    repeat(SCREENSHOTS) {
        val lines = screenshot(f).map { if (withNoise) noisy(it, noise) else it }
        val page = OcrPage(1080, lines.size * 40, lines.mapIndexed { i, l -> ocr(l, i * 40) })
        val masked = maskBoxes(page, pad = 0).toSet()
        for ((li, l) in lines.withIndex()) {
            val hits = findPii(l.text)
            val words = page.lines[li].words
            for (s in l.spans) {
                val t = tallies.getValue(s.kind)
                t.n++
                val inText = if (s.kind.pii) hits.any { s.range.first >= it.range.first && s.range.last <= it.range.last }
                else hits.any { it.range.first <= s.range.last && s.range.first <= it.range.last }
                if (inText) t.text++
                val ws = wordsOf(l, s.range)
                val painted = if (s.kind.pii) ws.all { words[it].box in masked } else ws.any { words[it].box in masked }
                if (painted) t.image++
            }
        }
    }
    return Result(tallies)
}

private fun report(clean: Result, noise: Result): String = buildString {
    appendLine("## 개인정보 가림률 (합성 스크린샷)")
    appendLine()
    appendLine("가짜 개인정보를 넣은 합성 스크린샷 ${SCREENSHOTS}장(기프티콘·게시물·대화·영수증·포스터)으로 `core/Redact.kt`를 재요.")
    appendLine("값은 모두 고정 시드($SEED)로 만든 가짜예요. `흔들림`은 OCR 오류를 흉내 낸 것(구분자 바뀜, 띄어쓰기 빠짐·추가, 0→O)이에요.")
    appendLine()
    appendLine("- 깨끗한 글자는 정규식이 아는 형식만 만들었으니 100%가 정상이에요. 실제 차이는 `흔들림` 열에서 봐요.")
    appendLine("- 글자: 가린 글자(`[전화번호]` 등)에 값이 통째로 들어간 비율")
    appendLine("- 이미지: 값이 걸친 단어가 모두 검게 칠해진 비율")
    appendLine()
    appendLine("### 가려야 하는 것 (높을수록 좋음)")
    appendLine()
    appendLine("| 종류 | 개수 | 글자 | 이미지 | 흔들림 글자 | 흔들림 이미지 |")
    appendLine("|---|---:|---:|---:|---:|---:|")
    for (k in Kind.entries.filter { it.pii }) {
        val c = clean.byKind.getValue(k)
        val z = noise.byKind.getValue(k)
        appendLine("| ${k.label} | ${c.n} | ${c.rate(c.text)} | ${c.rate(c.image)} | ${z.rate(z.text)} | ${z.rate(z.image)} |")
    }
    appendLine()
    appendLine("### 남겨야 하는 것: 잘못 가린 비율 (낮을수록 좋음)")
    appendLine()
    appendLine("| 종류 | 개수 | 글자 | 이미지 |")
    appendLine("|---|---:|---:|---:|")
    for (k in Kind.entries.filter { !it.pii }) {
        val c = clean.byKind.getValue(k)
        appendLine("| ${k.label} | ${c.n} | ${c.rate(c.text)} | ${c.rate(c.image)} |")
    }
    appendLine()
    appendLine("### 한계")
    appendLine()
    appendLine("- 합성 데이터라 실제 스크린샷의 OCR 오류(작은 글씨, 기울어짐, 배경)는 다 담지 못해요. 실제 스크린샷 측정과 함께 봐야 해요.")
    appendLine("- 이름·아이디처럼 정규식에 없는 개인정보는 여기서 재지 않아요.")
}

class RedactBench {
    @Test
    fun run() {
        assumeTrue(System.getProperty("redact.run") == "true")
        val md = report(measure(withNoise = false), measure(withNoise = true))
        println(md)
        if (System.getProperty("redact.write") == "true") {
            File(requireNotNull(System.getProperty("redact.out"))).writeText(md)
        }
    }
}
