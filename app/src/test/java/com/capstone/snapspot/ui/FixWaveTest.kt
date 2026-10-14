package com.capstone.snapspot.ui

import androidx.compose.ui.unit.dp
import com.capstone.snapspot.ui.home.collapsedListPadding
import com.capstone.snapspot.ui.status.kakaoSearchLabel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class FixWaveTest {
    @Test fun collapsedPaddingCoversHiddenPart() {
        assertEquals(430.dp, collapsedListPadding(880.dp, 450.dp, true))
        assertEquals(0.dp, collapsedListPadding(880.dp, 450.dp, false))
        assertEquals(0.dp, collapsedListPadding(400.dp, 450.dp, true))
    }

    @Test fun kakaoLabelIsNotACount() {
        assertEquals("저장해 둔 결과 사용", kakaoSearchLabel(0))
        assertEquals("새로 검색했어요", kakaoSearchLabel(3))
    }
}
