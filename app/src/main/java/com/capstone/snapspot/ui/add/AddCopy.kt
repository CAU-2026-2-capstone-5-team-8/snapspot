package com.capstone.snapspot.ui.add

import com.capstone.snapspot.core.CONFIDENT
import com.capstone.snapspot.core.Candidate
import java.io.FileNotFoundException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException

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

/** What to tell the user when a screenshot could not be read; the raw exception goes to the log only. */
fun readErrorMessage(e: Throwable): String = when (e) {
    is SecurityException, is FileNotFoundException -> "이 이미지를 열 수 없어요. 갤러리에서 다시 골라 주세요."
    is UnknownHostException, is ConnectException -> "인터넷 연결을 확인해 주세요."
    is InterruptedIOException -> "응답이 너무 늦어요. 잠시 뒤 다시 해 보거나 직접 입력해 주세요."
    else -> "잠시 뒤 다시 해 보거나 직접 입력해 주세요."
}
