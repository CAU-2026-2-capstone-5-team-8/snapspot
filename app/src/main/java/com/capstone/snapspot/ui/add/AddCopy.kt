package com.capstone.snapspot.ui.add

import com.capstone.snapspot.core.CONFIDENT
import com.capstone.snapspot.core.Candidate

/** The chip over the screenshot: how the pin was found. Scores are not probabilities, so no percentages. */
fun matchLabel(matched: Candidate?, hasCoords: Boolean): String = when {
    !hasCoords -> "위치를 못 찾았어요"
    matched == null -> "주소로 짐작한 위치예요"
    matched.score >= CONFIDENT -> "카카오맵에서 찾았어요"
    else -> "비슷한 곳을 찾았어요"
}

/** The small line above the found name. */
fun foundKicker(kind: String, isPlace: Boolean): String = when {
    kind == "coupon" -> "기프티콘에서 찾았어요"
    isPlace -> "스크린샷에서 찾았어요"
    else -> "장소를 찾기 어려웠어요"
}
