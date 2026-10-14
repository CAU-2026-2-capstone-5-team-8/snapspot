package com.capstone.snapspot.ui.add

import com.capstone.snapspot.core.CONFIDENT
import com.capstone.snapspot.core.Candidate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AddCopyTest {
    private fun cand(score: Double) = Candidate(
        kakaoId = "1", name = "어니언 성수", address = "서울 성동구 아차산로9길 8", category = "카페",
        latitude = 37.54, longitude = 127.05, placeUrl = null, score = score, source = "keyword",
    )

    @Test
    fun `match label says how sure the pin is`() {
        assertEquals("위치를 못 찾았어요", matchLabel(cand(0.9), hasCoords = false))
        assertEquals("주소로 짐작한 위치예요", matchLabel(null, hasCoords = true))
        assertEquals("카카오맵에서 찾았어요", matchLabel(cand(CONFIDENT), hasCoords = true))
        assertEquals("비슷한 곳을 찾았어요", matchLabel(cand(0.5), hasCoords = true))
    }

    @Test
    fun `kicker names where the details came from`() {
        assertEquals("기프티콘에서 찾았어요", foundKicker("coupon", isPlace = false))
        assertEquals("스크린샷에서 찾았어요", foundKicker("place", isPlace = true))
        assertEquals("장소를 찾기 어려웠어요", foundKicker("place", isPlace = false))
    }
}
