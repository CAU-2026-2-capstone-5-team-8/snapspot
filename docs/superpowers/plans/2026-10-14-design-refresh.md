# 디자인 개편 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SnapSpot 앱의 화면을 "지도 중심 + 카드형, 잉크 + 라임, 모서리 10dp, Pretendard"로 바꿔요. 기능과 데이터는 그대로 둬요.

**Architecture:**
- 디자인 값(색, 글꼴, 모서리, 간격)은 `ui/theme/Theme.kt` 한곳에만 둬요. 공통 부품은 `ui/components/Components.kt`에 둬요.
- 화면에 들어가는 문구와 판단(머리말 문장, 근처 판정, 메타 줄, 핀 모양)은 순수 함수로 빼서 단위 테스트로 고정해요. Compose 화면은 이 함수들을 그리기만 해요.
- 화면을 한 파일씩 옮기는 동안 `StyleGuardTest`가 아직 옮기지 않은 파일 목록(legacy)을 관리해요. 각 작업은 자기 파일을 그 목록에서 지우는 것(빨강)으로 시작해서 옮기기를 마치는 것(초록)으로 끝나요.

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2025.10.00, Material3 `BottomSheetScaffold`), Kakao Map SDK 2.15.2, JUnit 5

**Spec:** `docs/superpowers/specs/2026-10-14-design-refresh-design.md`

## Global Constraints

- 저장소: `C:\Users\hasan\dev\snapspot`, 브랜치 `design/refresh`. 명령은 Git Bash로 저장소 루트에서 실행해요.
- 색 값: ink `#191F28`, ink2 `#4E5968`, muted `#6B7684`, line `#E5E8EB`, bg `#F2F4F6`, surface `#FFFFFF`, lime `#D9F99D`, limeDeep `#65A30D`, limeInk `#1A2E05`, me `#3B82F6`, danger `#C9252D`, urgent `#C2410C`
- 라임은 "지금 근처"와 동선 번호(그리고 + 버튼의 "+")에만 써요. 다른 곳에 장식으로 쓰지 않아요.
- 모서리: 카드, 버튼, 입력 칸은 10dp, 칩과 썸네일은 8dp, 바텀시트 위쪽은 16dp, 사각 핀은 4dp예요.
- 간격: 화면 좌우 16dp, 카드 안 14dp, 카드 사이 8dp
- 글꼴은 Pretendard 하나(Regular, SemiBold, Bold, ExtraBold)로 쓰고, 글자 간격은 `-0.3sp`로 좁혀요.
- 색, 글꼴, 모서리 값은 `Theme.kt` 토큰으로만 써요. 화면 파일에 `Color(0x…)`를 쓰지 않아요.
- 뷰 모델 로직, 내비게이션, 저장 데이터 형태는 바꾸지 않아요. 예외는 Task 3의 `hereName` 흐름 하나이고, 저장 데이터 형태는 그대로예요.
- 커밋 메시지에 Claude 표기(`Co-Authored-By: Claude…`, "Generated with Claude Code")를 넣지 않아요.
- 카카오 키와 Gemini 키는 `local.properties`와 `.env`에만 있어요. 커밋하지 않아요.
- 전체 테스트: `./gradlew :app:testDebugUnitTest --console=plain -q` / 빌드: `./gradlew :app:assembleDebug --console=plain -q`

## Review Focus

1. **큰 글씨 설정(글꼴 배율 1.3)과 긴 장소 이름**: 목록 행과 지도 위 칩은 말줄임으로 끝나야 하고, 위쪽 칩 줄이 화면 밖으로 밀리면 안 돼요. Task 5에서 `maxLines`와 `weight(1f, fill = false)`로 막고, Task 9에서 배율 1.3 화면을 찍어 확인해요.
2. **바텀시트가 덮은 지도에서 핀 누르기**: 고른 핀이 시트에 가려지지 않고 보이는 지도 영역 가운데로 와야 해요. Task 4의 `padBottom`(카카오 `setPadding`)이 맡고, Task 5에서 직접 확인해요.
3. **권한 안내나 묶음 카드가 있을 때 핀 누르기**: 목록이 그 장소 행으로 정확히 스크롤돼야 해요. Task 3의 `listHeader` 테스트로 고정해요.
4. **묶음을 연 채 그 묶음의 장소를 지워서 묶음이 사라짐**: 일반 홈 목록으로 돌아오고, 왼쪽 위 칩도 "SnapSpot"으로 돌아와야 해요. Task 5의 `open == null` 분기가 맡고, Task 5에서 직접 확인해요.
5. **끝난 팝업 근처에 있음**: "지금 근처"로 세지도, 라임으로 칠하지도 않아요. Task 3의 `nearPlaces` 테스트와 Task 4의 핀 테스트로 고정해요.

---

### Task 1: 디자인 토큰, Pretendard 글꼴, 스타일 감시 테스트

**Files:**
- Modify: `app/src/main/java/com/capstone/snapspot/ui/theme/Theme.kt` (전체 교체)
- Create: `app/src/main/res/font/pretendard_regular.ttf`, `pretendard_semibold.ttf`, `pretendard_bold.ttf`, `pretendard_extrabold.ttf`
- Create: `docs/licenses/pretendard-OFL.txt`
- Delete: `app/src/main/res/font/plex_kr_regular.ttf`, `plex_kr_medium.ttf`, `plex_kr_bold.ttf`
- Test: `app/src/test/java/com/capstone/snapspot/ui/theme/ThemeTest.kt`
- Test: `app/src/test/java/com/capstone/snapspot/ui/StyleGuardTest.kt`
- Commit with: `docs/superpowers/specs/2026-10-14-design-refresh-design.md` (이미 고친 값: muted, danger, urgent, 글꼴 4개, 칩 문구, 갱신 구역 원)

**Interfaces:**
- Produces:
  - `SnapColors.{ink, ink2, muted, line, bg, surface, lime, limeDeep, limeInk, me, danger, urgent}: Color`
  - 옛 토큰 `SnapColors.{paper, green, greenSoft, highlight, amber, amberSoft}`은 Task 9까지 남겨 둬요.
  - `SnapFonts.sans: FontFamily`. 옛 이름 `SnapFonts.display`(Black Han Sans)는 Task 9까지 남기고, `SnapFonts.body`는 `sans`를 가리키게 해요.
  - `SnapType.{display, logo, title, body, label, meta, button}: TextStyle`. `button`에는 색을 넣지 않아요. 옛 스타일 `SnapType.{hero, distance}`는 Task 9까지 남겨요.
  - `SnapShapes.{card, small, sheet}: RoundedCornerShape`, `SnapShapes.PIN_CORNER_DP = 4f`
  - `SnapSpace.{gutter = 16.dp, card = 14.dp, gap = 8.dp}`
  - `StyleGuardTest.legacy: Set<String>`: 아직 옮기지 않은 파일 목록이에요. 각 작업이 자기 파일을 이 목록에서 지워요.

- [ ] **Step 1: 대비 테스트를 먼저 써요**

`app/src/test/java/com/capstone/snapspot/ui/theme/ThemeTest.kt`:

```kotlin
package com.capstone.snapspot.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.pow

/** WCAG 2 relative luminance of an sRGB color. */
private fun luminance(c: Color): Double {
    fun ch(v: Float): Double = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
}

private fun contrast(a: Color, b: Color): Double {
    val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
    return (hi + 0.05) / (lo + 0.05)
}

class ThemeTest {
    private fun assertContrast(fg: Color, bg: Color, min: Double, what: String) {
        val r = contrast(fg, bg)
        assertTrue(r >= min, "$what: ${"%.2f".format(r)}:1 < $min:1")
    }

    @Test
    fun `text colors are readable on cards`() {
        assertContrast(SnapColors.ink, SnapColors.surface, 4.5, "ink")
        assertContrast(SnapColors.ink2, SnapColors.surface, 4.5, "ink2")
        assertContrast(SnapColors.muted, SnapColors.surface, 4.5, "muted")
        assertContrast(SnapColors.danger, SnapColors.surface, 4.5, "danger")
        assertContrast(SnapColors.urgent, SnapColors.surface, 4.5, "urgent")
    }

    @Test
    fun `text colors are readable on the sheet background`() {
        assertContrast(SnapColors.ink, SnapColors.bg, 4.5, "ink on bg")
        assertContrast(SnapColors.ink2, SnapColors.bg, 4.5, "ink2 on bg")
        assertContrast(SnapColors.ink2, SnapColors.line, 4.5, "ink2 on the segmented track")
    }

    @Test
    fun `lime pairs are readable`() {
        assertContrast(SnapColors.lime, SnapColors.ink, 4.5, "lime on ink")
        assertContrast(SnapColors.limeInk, SnapColors.lime, 4.5, "limeInk on lime")
        assertContrast(SnapColors.surface, SnapColors.ink, 4.5, "white on ink")
    }

    @Test
    fun `map marks stand out from white`() {
        assertContrast(SnapColors.limeDeep, SnapColors.surface, 3.0, "limeDeep")
        assertContrast(SnapColors.me, SnapColors.surface, 3.0, "me")
    }
}
```

- [ ] **Step 2: 실패하는지 확인해요**

Run: `./gradlew :app:testDebugUnitTest --tests "com.capstone.snapspot.ui.theme.ThemeTest" --console=plain -q`
Expected: 컴파일 실패, `Unresolved reference 'ink2'` (그리고 `bg`, `lime` 등)

- [ ] **Step 3: 글꼴을 받고 옛 본문 글꼴을 지워요**

```bash
for w in Regular SemiBold Bold ExtraBold; do
  f=$(echo "$w" | tr 'A-Z' 'a-z')
  curl -sfL "https://cdn.jsdelivr.net/npm/pretendard@1.3.9/dist/public/static/alternative/Pretendard-$w.ttf" -o "app/src/main/res/font/pretendard_$f.ttf"
done
mkdir -p docs/licenses
curl -sfL https://raw.githubusercontent.com/orioncactus/pretendard/v1.3.9/LICENSE -o docs/licenses/pretendard-OFL.txt
git rm -q app/src/main/res/font/plex_kr_regular.ttf app/src/main/res/font/plex_kr_medium.ttf app/src/main/res/font/plex_kr_bold.ttf
ls -la app/src/main/res/font docs/licenses
```
Expected: `pretendard_*.ttf` 4개(각 2.5MB 안팎), `black_han_sans.ttf`, `pretendard-OFL.txt`(첫 줄에 "Copyright")

- [ ] **Step 4: `Theme.kt`를 통째로 바꿔요**

```kotlin
package com.capstone.snapspot.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.capstone.snapspot.R

/**
 * Ink + lime: ink carries text, actions and routes; lime means one thing only, "near you now"
 * (and the numbers of the route you are about to walk).
 */
object SnapColors {
    val ink = Color(0xFF191F28)
    val ink2 = Color(0xFF4E5968)
    /** Meta text: 4.6:1 on white. */
    val muted = Color(0xFF6B7684)
    val line = Color(0xFFE5E8EB)
    val bg = Color(0xFFF2F4F6)
    val surface = Color(0xFFFFFFFF)
    val lime = Color(0xFFD9F99D)
    val limeDeep = Color(0xFF65A30D)
    val limeInk = Color(0xFF1A2E05)
    /** The user's dot on the map, nowhere else. */
    val me = Color(0xFF3B82F6)
    val danger = Color(0xFFC9252D)
    /** Coupons that end within 3 days. */
    val urgent = Color(0xFFC2410C)

    // Pre-refresh colors, for screens not moved over yet (StyleGuardTest.legacy). Removed in the last task.
    val paper = Color(0xFFF2F3EE)
    val green = Color(0xFF2D7A57)
    val greenSoft = Color(0xFFE3EFE7)
    val highlight = Color(0xFFFFE34D)
    val amber = Color(0xFF8A5A0A)
    val amberSoft = Color(0xFFFBF0D4)
}

object SnapFonts {
    val sans = FontFamily(
        Font(R.font.pretendard_regular, FontWeight.Normal),
        Font(R.font.pretendard_semibold, FontWeight.SemiBold),
        Font(R.font.pretendard_bold, FontWeight.Bold),
        Font(R.font.pretendard_extrabold, FontWeight.ExtraBold),
    )

    // Pre-refresh names. Removed in the last task.
    val display = FontFamily(Font(R.font.black_han_sans))
    val body = sans
}

private val tight = (-0.3).sp

object SnapType {
    /** The big sentence on top of a screen or sheet. */
    val display = TextStyle(fontFamily = SnapFonts.sans, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = tight, color = SnapColors.ink)
    val logo = TextStyle(fontFamily = SnapFonts.sans, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = tight, color = SnapColors.ink)
    val title = TextStyle(fontFamily = SnapFonts.sans, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = tight, color = SnapColors.ink)
    val body = TextStyle(fontFamily = SnapFonts.sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = tight, color = SnapColors.ink)
    val label = TextStyle(fontFamily = SnapFonts.sans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = tight, color = SnapColors.ink2)
    val meta = TextStyle(fontFamily = SnapFonts.sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = tight, color = SnapColors.muted)
    /** Button text: no color, the button decides. */
    val button = TextStyle(fontFamily = SnapFonts.sans, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = tight)

    // Pre-refresh styles. Removed in the last task.
    val hero = TextStyle(fontFamily = SnapFonts.display, fontSize = 40.sp, lineHeight = 46.sp, color = SnapColors.ink)
    val distance = TextStyle(fontFamily = SnapFonts.display, fontSize = 18.sp, lineHeight = 22.sp, color = SnapColors.ink)
}

object SnapShapes {
    val card = RoundedCornerShape(10.dp)
    val small = RoundedCornerShape(8.dp)
    val sheet = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    /** Corner of the square map pins (coupon branches, coupon stops), in dp. */
    const val PIN_CORNER_DP = 4f
}

object SnapSpace {
    /** Screen side padding. */
    val gutter = 16.dp
    /** Padding inside a card. */
    val card = 14.dp
    /** Between cards. */
    val gap = 8.dp
}

@Composable
fun SnapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = SnapColors.ink,
            onPrimary = SnapColors.surface,
            background = SnapColors.bg,
            onBackground = SnapColors.ink,
            surface = SnapColors.surface,
            onSurface = SnapColors.ink,
            onSurfaceVariant = SnapColors.ink2,
            outline = SnapColors.line,
            error = SnapColors.danger,
        ),
        typography = Typography(
            bodyLarge = SnapType.body.copy(color = Color.Unspecified),
            bodyMedium = SnapType.body.copy(color = Color.Unspecified),
            titleLarge = SnapType.title.copy(color = Color.Unspecified),
            labelLarge = SnapType.button,
        ),
        content = content,
    )
}
```

- [ ] **Step 5: 대비 테스트와 빌드가 통과하는지 확인해요**

Run: `./gradlew :app:testDebugUnitTest --tests "com.capstone.snapspot.ui.theme.ThemeTest" --console=plain -q && ./gradlew :app:assembleDebug --console=plain -q`
Expected: 테스트 4개 통과, 빌드 성공 (옛 화면은 옛 토큰을 그대로 써서 컴파일돼요)

- [ ] **Step 6: 스타일 감시 테스트를 써요**

이 테스트는 처음에는 바로 통과해요. 모든 화면이 아직 `legacy`에 들어 있기 때문이에요. 다음 작업들이 자기 파일을 목록에서 지우면 빨강이 돼요.

`app/src/test/java/com/capstone/snapspot/ui/StyleGuardTest.kt`:

```kotlin
package com.capstone.snapspot.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** The design refresh, enforced: every source file outside ui/theme uses only the refreshed tokens. */
class StyleGuardTest {
    private val root = File("src/main/java/com/capstone/snapspot")

    /** Files not moved to the refreshed design yet. Each task of the refresh removes its own; empty when done. */
    private val legacy = setOf(
        "MainActivity.kt",
        "ui/Nav.kt",
        "ui/components/Components.kt",
        "ui/home/HomeScreen.kt",
        "ui/map/SpotMap.kt",
        "ui/add/AddScreen.kt",
        "ui/coupon/CouponScreen.kt",
        "ui/status/StatusScreen.kt",
    )

    private val banned = listOf(
        Regex("""Color\(0x""") to "raw color (use SnapColors)",
        Regex("""SnapColors\.(paper|green|greenSoft|highlight|amber|amberSoft)\b""") to "pre-refresh color",
        Regex("""SnapType\.(hero|distance)\b""") to "pre-refresh text style",
        Regex("""SnapFonts\.(display|body)\b""") to "pre-refresh font",
        Regex("""\bNearBand\(|\bFact\(""") to "pre-refresh component",
    )

    @Test
    fun `source uses only refreshed design tokens`() {
        val problems = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).invariantSeparatorsPath to it }
            .filter { (rel, _) -> !rel.startsWith("ui/theme/") && rel !in legacy }
            .flatMap { (rel, f) ->
                f.readLines().withIndex().flatMap { (i, line) ->
                    banned.filter { it.first.containsMatchIn(line) }.map { "$rel:${i + 1} ${it.second}: ${line.trim()}" }
                }
            }
            .toList()
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `legacy list names only files that exist`() {
        legacy.forEach { assertTrue(File(root, it).isFile, it) }
    }
}
```

- [ ] **Step 7: 전체 테스트를 돌려요**

Run: `./gradlew :app:testDebugUnitTest --console=plain -q`
Expected: 모두 통과. 기존 약 132개에 `ThemeTest` 4개와 `StyleGuardTest` 2개가 더해지고, 옵션 실행용 3개는 건너뛰어요.

- [ ] **Step 8: 커밋해요**

브레인스토밍 목업 폴더 `.superpowers/`가 올라가지 않도록 먼저 막아요.

```bash
grep -qx '.superpowers/' .gitignore || echo '.superpowers/' >> .gitignore
git add .gitignore app/src/main/java/com/capstone/snapspot/ui/theme/Theme.kt app/src/main/res/font docs/licenses \
  app/src/test/java/com/capstone/snapspot/ui/theme/ThemeTest.kt app/src/test/java/com/capstone/snapspot/ui/StyleGuardTest.kt \
  docs/superpowers/specs/2026-10-14-design-refresh-design.md docs/superpowers/plans/2026-10-14-design-refresh.md
git commit -q -m "design: ink + lime tokens, Pretendard, contrast and style guard tests"
```

---

### Task 2: 공통 부품

**Files:**
- Modify: `app/src/main/java/com/capstone/snapspot/ui/components/Components.kt` (전체 교체)
- Modify: `app/src/main/java/com/capstone/snapspot/ui/home/HomeScreen.kt`: 지금 쓰는 `NearBand`와 `Fact`를 이 파일 아래쪽에 private으로 옮겨요. Task 5에서 지워요.
- Modify: `app/src/test/java/com/capstone/snapspot/ui/StyleGuardTest.kt`: `legacy`에서 `"ui/components/Components.kt"`를 지워요.

**Interfaces:**
- Consumes: Task 1의 `SnapColors`, `SnapType`, `SnapShapes`, `SnapSpace`
- Produces (모두 `com.capstone.snapspot.ui.components`):
  - 기존 시그니처 유지:
    - `Shot(uri: String?, width: Dp, height: Dp, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, shape: Shape = SnapShapes.small)`
    - `LargeShot(uri: String?, modifier: Modifier = Modifier)`
    - `PrimaryButton(label, onClick, modifier, enabled, busy)`, `SecondaryButton(label, onClick, modifier, busy)`
    - `QuietButton(label, onClick, color: Color = SnapColors.ink2)`
    - `Field(label, value, onChange, modifier, placeholder, large)`
    - `Notice(text: String, action: Pair<String, () -> Unit>? = null, modifier: Modifier = Modifier)`
    - `Segmented(options, value, onChange, format, modifier)`
    - `Card(modifier: Modifier = Modifier, border: BorderStroke? = null, content)`
    - `MapUnavailable(reason)`, `SlotMeter(total, parts: List<Triple<String, Int, Color>>)`, `Dot(color, size)`, `RowScope.Grow()`, `Gap(w)`
  - 새로 만듦:
    - `IconTap(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier)`
    - `NearChip(text: String = "지금 근처", modifier: Modifier = Modifier)`
    - `FloatingChip(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit)`
    - `SheetHandle()`
    - `BigTitle(kicker: String?, title: String, modifier: Modifier = Modifier)`, `BigTitle(kicker: String?, title: AnnotatedString, modifier: Modifier = Modifier)`
    - `ConfirmDelete(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit)`
  - 지움: `NearBand`, `Fact` (`HomeScreen.kt`에 private으로 옮김)

- [ ] **Step 1: 감시 목록에서 이 파일을 빼요**

`StyleGuardTest.kt`의 `legacy`에서 `"ui/components/Components.kt",` 줄을 지워요.

- [ ] **Step 2: 실패하는지 확인해요**

Run: `./gradlew :app:testDebugUnitTest --tests "com.capstone.snapspot.ui.StyleGuardTest" --console=plain -q`
Expected: FAIL. `ui/components/Components.kt:67 pre-refresh color: ...SnapColors.greenSoft...`처럼 이 파일의 옛 토큰 줄이 나열돼요.

- [ ] **Step 3: `NearBand`와 `Fact`를 홈 화면으로 옮겨요**

`HomeScreen.kt` 맨 아래에 다음 코드를 붙이고, `import com.capstone.snapspot.ui.components.NearBand`와 `import com.capstone.snapspot.ui.components.Fact` 두 줄을 지워요. `SnapFonts`와 `RoundedCornerShape` import는 이미 있어요.

```kotlin
/** Pre-refresh pieces, kept here until this screen moves over (Task 5). */
@Composable
private fun NearBand(text: String) {
    Text(
        text,
        fontFamily = SnapFonts.display,
        color = SnapColors.ink,
        modifier = Modifier.fillMaxWidth().background(SnapColors.highlight).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun Fact(text: String) {
    Text(
        text,
        style = SnapType.meta,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(SnapColors.paper).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
```

- [ ] **Step 4: `Components.kt`를 통째로 바꿔요**

```kotlin
package com.capstone.snapspot.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.capstone.snapspot.ui.theme.SnapColors
import com.capstone.snapspot.ui.theme.SnapShapes
import com.capstone.snapspot.ui.theme.SnapSpace
import com.capstone.snapspot.ui.theme.SnapType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A saved screenshot, decoded small off the main thread. */
@Composable
fun Shot(
    uri: String?,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    shape: Shape = SnapShapes.small,
) {
    val ctx = LocalContext.current
    val image by produceState<ImageBitmap?>(null, uri) {
        value = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                ctx.contentResolver.openInputStream(Uri.parse(uri)).use { BitmapFactory.decodeStream(it, null, opts) }?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.size(width, height).clip(shape).background(SnapColors.line), contentAlignment = Alignment.Center) {
        image?.let { Image(it, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize()) }
    }
}

/** The screenshot at full width (coupon screen): keeps the barcode readable. */
@Composable
fun LargeShot(uri: String?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val image by produceState<ImageBitmap?>(null, uri) {
        value = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.openInputStream(Uri.parse(uri)).use { BitmapFactory.decodeStream(it) }?.asImageBitmap() }.getOrNull()
        }
    }
    Box(modifier.fillMaxWidth().heightIn(min = 200.dp), contentAlignment = Alignment.Center) {
        val img = image
        if (img != null) Image(img, contentDescription = "쿠폰 이미지", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
        else Text(if (uri == null) "쿠폰 이미지가 없어요" else "", style = SnapType.meta)
    }
}

@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier.heightIn(min = 52.dp),
        shape = SnapShapes.card,
        colors = ButtonDefaults.buttonColors(
            containerColor = SnapColors.ink,
            contentColor = SnapColors.surface,
            disabledContainerColor = SnapColors.line,
            disabledContentColor = SnapColors.muted,
        ),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = SnapColors.ink, strokeWidth = 2.dp)
        else Text(label, style = SnapType.button, maxLines = 1)
    }
}

@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, busy: Boolean = false) {
    OutlinedButton(
        onClick = onClick,
        enabled = !busy,
        modifier = modifier.heightIn(min = 52.dp),
        shape = SnapShapes.card,
        border = BorderStroke(1.dp, SnapColors.line),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = SnapColors.surface, contentColor = SnapColors.ink),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = SnapColors.ink, strokeWidth = 2.dp)
        else Text(label, style = SnapType.button, maxLines = 1)
    }
}

@Composable
fun QuietButton(label: String, onClick: () -> Unit, color: Color = SnapColors.ink2) {
    TextButton(onClick = onClick) { Text(label, style = SnapType.button, color = color) }
}

/** A 44dp tap target with one icon (back, close, menu). */
@Composable
fun IconTap(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = description, tint = SnapColors.ink)
    }
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String? = null, large: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = SnapType.meta)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = if (large) SnapType.title else SnapType.body,
            placeholder = placeholder?.let { { Text(it, style = SnapType.meta) } },
            singleLine = true,
            shape = SnapShapes.card,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SnapColors.ink,
                unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = SnapColors.bg,
                unfocusedContainerColor = SnapColors.bg,
                cursorColor = SnapColors.ink,
            ),
        )
    }
}

/** Something the user should fix: a white card with an ink bar on the left and an optional text action. */
@Composable
fun Notice(text: String, action: Pair<String, () -> Unit>? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(SnapShapes.card).background(SnapColors.surface)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(SnapColors.ink))
        Column(Modifier.padding(SnapSpace.card), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, style = SnapType.body)
            action?.let { (label, onClick) ->
                Text(label, style = SnapType.button.copy(color = SnapColors.ink), modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
fun <T> Segmented(options: List<T>, value: T, onChange: (T) -> Unit, format: (T) -> String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(SnapShapes.card).background(SnapColors.line).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (o in options) {
            val on = o == value
            Box(
                Modifier.weight(1f).clip(SnapShapes.small).background(if (on) SnapColors.surface else Color.Transparent)
                    .clickable { onChange(o) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(format(o), style = SnapType.label.copy(color = if (on) SnapColors.ink else SnapColors.ink2, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold))
            }
        }
    }
}

/** White card on the grey background: no border or shadow unless asked. */
@Composable
fun Card(modifier: Modifier = Modifier, border: BorderStroke? = null, content: @Composable () -> Unit) {
    Box(
        modifier.fillMaxWidth().clip(SnapShapes.card).background(SnapColors.surface)
            .then(if (border != null) Modifier.border(border, SnapShapes.card) else Modifier),
    ) { content() }
}

/** Lime chip: "you are near this right now". The one meaning lime has. */
@Composable
fun NearChip(text: String = "지금 근처", modifier: Modifier = Modifier) {
    Text(
        text,
        style = SnapType.meta.copy(fontWeight = FontWeight.Bold, color = SnapColors.limeInk),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(SnapShapes.small).background(SnapColors.lime).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** A white chip floating on the map (logo, "12곳 지켜보는 중", back to the list). */
@Composable
fun FloatingChip(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.shadow(4.dp, SnapShapes.card).clip(SnapShapes.card).background(SnapColors.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 40.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun SheetHandle() {
    Box(Modifier.padding(vertical = 8.dp).size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(SnapColors.muted.copy(alpha = 0.35f)))
}

/** A small line, then the screen's sentence in display type. */
@Composable
fun BigTitle(kicker: String?, title: String, modifier: Modifier = Modifier) = BigTitle(kicker, AnnotatedString(title), modifier)

@Composable
fun BigTitle(kicker: String?, title: AnnotatedString, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        kicker?.let { Text(it, style = SnapType.label) }
        Text(title, style = SnapType.display)
    }
}

@Composable
fun ConfirmDelete(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = SnapType.title) },
        text = { Text(body, style = SnapType.body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("삭제", style = SnapType.button, color = SnapColors.danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소", style = SnapType.button, color = SnapColors.ink2) } },
        containerColor = SnapColors.surface,
        shape = SnapShapes.card,
    )
}

@Composable
fun MapUnavailable(reason: String) {
    Column(
        Modifier.fillMaxSize().background(SnapColors.bg).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("지도를 불러오지 못했어요", style = SnapType.title)
        Spacer(Modifier.height(4.dp))
        Text("카카오 개발자 콘솔에 이 앱의 키 해시가 등록됐는지 확인해 주세요.\n$reason", style = SnapType.meta, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

/** How the OS geofence slots are shared right now: one segment per group. */
@Composable
fun SlotMeter(total: Int, parts: List<Triple<String, Int, Color>>) {
    val used = parts.sumOf { it.second }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            parts.filter { it.second > 0 }.forEach { (_, n, tone) -> Box(Modifier.weight(n.toFloat()).fillMaxSize().background(tone)) }
            if (total - used > 0) Box(Modifier.weight((total - used).toFloat()).fillMaxSize().background(SnapColors.line))
        }
        parts.forEach { (label, n, tone) -> MeterRow(label, "${n}칸", tone) }
        MeterRow("비어 있음", "${maxOf(total - used, 0)}칸", SnapColors.line, muted = true)
    }
}

@Composable
private fun MeterRow(label: String, count: String, tone: Color, muted: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(tone))
        Text(label, style = SnapType.body.copy(color = if (muted) SnapColors.muted else SnapColors.ink), modifier = Modifier.weight(1f))
        Text(count, style = SnapType.label.copy(color = if (muted) SnapColors.muted else SnapColors.ink, fontWeight = FontWeight.Bold))
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

@Composable
fun RowScope.Grow() = Spacer(Modifier.weight(1f))

@Composable
fun Gap(w: Dp) = Spacer(Modifier.width(w))
```

- [ ] **Step 5: 감시 테스트, 전체 테스트, 빌드를 확인해요**

Run: `./gradlew :app:testDebugUnitTest --console=plain -q && ./gradlew :app:assembleDebug --console=plain -q`
Expected:
- 모든 테스트가 통과해요.
- 빌드가 성공해요. 홈 화면은 private으로 옮긴 `NearBand`/`Fact`를 쓰고, `Card(border = …)` 호출도 그대로 컴파일돼요.

- [ ] **Step 6: 커밋해요**

```bash
git add app/src/main/java/com/capstone/snapspot/ui/components/Components.kt app/src/main/java/com/capstone/snapspot/ui/home/HomeScreen.kt app/src/test/java/com/capstone/snapspot/ui/StyleGuardTest.kt
git commit -q -m "design: shared components in ink + lime (chips, sheet handle, big title)"
```

---

### Task 3: 홈 문구 함수와 현재 동네 이름

**Files:**
- Create: `app/src/main/java/com/capstone/snapspot/ui/home/HomeCopy.kt`
- Modify: `app/src/main/java/com/capstone/snapspot/ui/home/HomeViewModel.kt` (`hereName` 흐름, 현재 위치 칸의 동네 이름 조회)
- Test: `app/src/test/java/com/capstone/snapspot/ui/home/HomeCopyTest.kt`

**Interfaces:**
- Consumes:
  - `core`: `place()`, `coupon()`, `NOW` (테스트 도우미, `com.capstone.snapspot.core` 패키지)
  - `core`: `formatDistance`, `untilLabel`, `placeEnded`, `distanceMeters`, `cellOf`
- Produces (`com.capstone.snapspot.ui.home`):
  - `copula(word: String): String`
  - `data class Headline(val kicker: String?, val before: String, val count: String? = null, val after: String? = null)`, 계산 속성 `text`
  - `homeHeadline(region: String?, near: Int, saved: Int, alertsOn: Boolean): Headline`
  - `nearPlaces(places: List<Place>, here: Coords?, now: Long): Set<String>`
  - `placeMeta(p: Place, ended: Boolean): String`
  - `couponMeta(item: CouponItem): String`
  - `bundleTitle(name: String): String`
  - `listHeader(alertsOn: Boolean, bundles: Boolean): Int`
  - `HomeViewModel.hereName: StateFlow<String?>`

- [ ] **Step 1: 테스트를 먼저 써요**

`app/src/test/java/com/capstone/snapspot/ui/home/HomeCopyTest.kt`:

```kotlin
package com.capstone.snapspot.ui.home

import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.NOW
import com.capstone.snapspot.core.coupon
import com.capstone.snapspot.core.formatDistance
import com.capstone.snapspot.core.place
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HomeCopyTest {
    @Test
    fun `copula follows the last syllable`() {
        assertEquals("성수동이에요", copula("성수동"))
        assertEquals("을지로3가예요", copula("을지로3가"))
        assertEquals("성수예요", copula("성수"))
        assertEquals("명동1이에요", copula("명동1"))
        assertEquals("강남2예요", copula("강남2"))
        assertEquals("", copula(""))
    }

    @Test
    fun `places nearby are counted and the count is highlighted`() {
        val h = homeHeadline("성수동", near = 2, saved = 5, alertsOn = true)
        assertEquals("지금 성수동이에요", h.kicker)
        assertEquals("2곳", h.count)
        assertEquals("근처에 가 볼 곳이 2곳 있어요", h.text)
    }

    @Test
    fun `nothing nearby says what is being watched`() {
        val h = homeHeadline(null, near = 0, saved = 5, alertsOn = true)
        assertNull(h.kicker)
        assertNull(h.count)
        assertEquals("저장한 곳 5곳을 지켜보고 있어요", h.text)
    }

    @Test
    fun `alerts off is said in the headline`() {
        assertEquals("저장한 곳 5곳, 알림은 꺼져 있어요", homeHeadline(null, near = 0, saved = 5, alertsOn = false).text)
    }

    @Test
    fun `no saved places invites the first screenshot`() {
        assertEquals("가 보고 싶은 곳을 저장해 보세요", homeHeadline("성수동", near = 0, saved = 0, alertsOn = true).text)
    }

    @Test
    fun `near means inside the radius of a place that has not ended`() {
        val inside = place(id = "in")
        val far = place(id = "far", latitude = 37.5547)
        val ended = place(id = "ended", endDate = "2026-10-09")
        val here = Coords(37.5447, 127.0582)
        assertEquals(setOf("in"), nearPlaces(listOf(inside, far, ended), here, NOW))
        assertEquals(emptySet<String>(), nearPlaces(listOf(inside), null, NOW))
    }

    @Test
    fun `place meta joins category, end date and note`() {
        assertEquals("카페 · 10월 31일까지 · 창가 자리", placeMeta(place(endDate = "2026-10-31", note = "창가 자리"), ended = false))
        assertEquals("팝업 · 운영 종료", placeMeta(place(category = "팝업", endDate = "2026-10-09"), ended = true))
        assertEquals("카페", placeMeta(place(), ended = false))
    }

    @Test
    fun `coupon meta prefers the nearest branch`() {
        val c = coupon(benefit = "아메리카노 1+1")
        assertEquals("아메리카노 1+1 · 성수역점 ${formatDistance(320.0)}", couponMeta(CouponItem(c, active = true, urgent = false, nearest = "성수역점" to 320.0)))
        assertEquals("사용한 쿠폰이에요", couponMeta(CouponItem(coupon(usedAt = 1L), active = false, urgent = false, nearest = null)))
        assertEquals("기간이 지났어요", couponMeta(CouponItem(c, active = false, urgent = false, nearest = null)))
        assertEquals("아메리카노 1+1 · 12월 31일까지", couponMeta(CouponItem(coupon(benefit = "아메리카노 1+1", expiresAt = "2026-12-31"), active = true, urgent = false, nearest = null)))
    }

    @Test
    fun `bundle titles read as a course unless already a near-name`() {
        assertEquals("성수동 코스", bundleTitle("성수동"))
        assertEquals("어니언 성수 근처", bundleTitle("어니언 성수 근처"))
    }

    @Test
    fun `list header counts the rows above the first place`() {
        assertEquals(2, listHeader(alertsOn = true, bundles = false))
        assertEquals(3, listHeader(alertsOn = true, bundles = true))
        assertEquals(4, listHeader(alertsOn = false, bundles = true))
    }
}
```

- [ ] **Step 2: 실패하는지 확인해요**

Run: `./gradlew :app:testDebugUnitTest --tests "com.capstone.snapspot.ui.home.HomeCopyTest" --console=plain -q`
Expected: 컴파일 실패, `Unresolved reference 'copula'`

- [ ] **Step 3: `HomeCopy.kt`를 써요**

```kotlin
package com.capstone.snapspot.ui.home

import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.distanceMeters
import com.capstone.snapspot.core.formatDistance
import com.capstone.snapspot.core.model.Place
import com.capstone.snapspot.core.placeEnded
import com.capstone.snapspot.core.untilLabel

/** Digits read with a final consonant: 영 일 삼 육 칠 팔. */
private const val DIGITS_WITH_BATCHIM = "013678"

/** "성수동" → "성수동이에요", "을지로3가" → "을지로3가예요". */
fun copula(word: String): String {
    val last = word.lastOrNull() ?: return word
    val batchim = when (last) {
        in '가'..'힣' -> (last - '가') % 28 != 0
        in '0'..'9' -> last in DIGITS_WITH_BATCHIM
        else -> false
    }
    return word + if (batchim) "이에요" else "예요"
}

/** The sentence on top of the home sheet. `count`, when set, is drawn on lime. */
data class Headline(val kicker: String?, val before: String, val count: String? = null, val after: String? = null) {
    val text: String get() = listOfNotNull(before, count, after).joinToString(" ")
}

fun homeHeadline(region: String?, near: Int, saved: Int, alertsOn: Boolean): Headline {
    val kicker = region?.let { "지금 ${copula(it)}" }
    return when {
        saved == 0 -> Headline(kicker, "가 보고 싶은 곳을 저장해 보세요")
        near > 0 -> Headline(kicker, "근처에 가 볼 곳이", "${near}곳", "있어요")
        alertsOn -> Headline(kicker, "저장한 곳 ${saved}곳을 지켜보고 있어요")
        else -> Headline(kicker, "저장한 곳 ${saved}곳, 알림은 꺼져 있어요")
    }
}

/** Places the user is inside the alert radius of right now. Ended pop-ups never count. */
fun nearPlaces(places: List<Place>, here: Coords?, now: Long): Set<String> {
    val at = here ?: return emptySet()
    return places.filter { !placeEnded(it, now) && distanceMeters(at, Coords(it.latitude, it.longitude)) <= it.radius }.map { it.id }.toSet()
}

/** "카페 · 10월 31일까지 · 창가 자리". */
fun placeMeta(p: Place, ended: Boolean): String =
    listOfNotNull(p.category.ifBlank { null }, if (ended) "운영 종료" else untilLabel(p.endDate), p.note.ifBlank { null }).joinToString(" · ")

/** The coupon row's second line: where to use it, or why it no longer alerts. */
fun couponMeta(item: CouponItem): String {
    val c = item.coupon
    return when {
        c.usedAt != null -> "사용한 쿠폰이에요"
        !item.active -> "기간이 지났어요"
        item.nearest != null -> "${c.benefit} · ${item.nearest.first} ${formatDistance(item.nearest.second)}"
        else -> listOfNotNull(c.benefit.ifBlank { null }, untilLabel(c.expiresAt)).joinToString(" · ").ifBlank { "유효기간 없음" }
    }
}

/** "성수동" → "성수동 코스"; the fallback "어니언 성수 근처" already reads as a name. */
fun bundleTitle(name: String): String = if (name.endsWith("근처")) name else "$name 코스"

/** Rows above the first place in the home list: headline, permission notice, bundle cards, tabs. */
fun listHeader(alertsOn: Boolean, bundles: Boolean): Int = 2 + (if (alertsOn) 0 else 1) + (if (bundles) 1 else 0)
```

- [ ] **Step 4: 통과하는지 확인해요**

Run: `./gradlew :app:testDebugUnitTest --tests "com.capstone.snapspot.ui.home.HomeCopyTest" --console=plain -q`
Expected: 10개 통과

- [ ] **Step 5: 뷰 모델에 현재 동네 이름을 붙여요**

`HomeViewModel.kt`에서 다음 두 가지를 바꿔요.

(1) `val watching …` 선언 바로 위에 추가해요.

```kotlin
    /** Neighborhood name of where the user is, once looked up (cached per ~1km cell, region-names.json). */
    val hereName: StateFlow<String?> = combine(here, c.regionNames.names) { at, _ -> at?.let { c.regionNames.cached(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
```

(2) `init` 블록의 "Name bundles the first time they show up" 실행문 바로 뒤에 추가해요.

```kotlin
        // Name the cell the user is in, once per cell per session (the home headline says "지금 성수동이에요").
        viewModelScope.launch {
            here.filterNotNull().distinctUntilChangedBy { cellOf(it).key }.collect { at ->
                if (c.regionNames.cached(at) == null && namesTried.add(cellOf(at).key)) c.regionNames.resolve(at) { c.kakao.regionName(it) }
            }
        }
```

import에 `kotlinx.coroutines.flow.distinctUntilChangedBy`를 더해요. `filterNotNull`, `combine`, `stateIn`, `cellOf`는 이미 import돼 있어요. 컴파일 오류가 나면 없는 것만 더해요.

- [ ] **Step 6: 전체 테스트와 빌드를 확인해요**

Run: `./gradlew :app:testDebugUnitTest --console=plain -q && ./gradlew :app:assembleDebug --console=plain -q`
Expected: 모두 통과, 빌드 성공

- [ ] **Step 7: 커밋해요**

```bash
git add app/src/main/java/com/capstone/snapspot/ui/home/HomeCopy.kt app/src/main/java/com/capstone/snapspot/ui/home/HomeViewModel.kt app/src/test/java/com/capstone/snapspot/ui/home/HomeCopyTest.kt
git commit -q -m "home: headline, near and meta copy as tested functions; name the user's neighborhood"
```

---

### Task 4: 지도 핀과 지도 틀

**Files:**
- Create: `app/src/main/java/com/capstone/snapspot/ui/map/Pins.kt`
- Modify: `app/src/main/java/com/capstone/snapspot/ui/map/SpotMap.kt` (전체 교체)
- Modify: `app/src/test/java/com/capstone/snapspot/ui/StyleGuardTest.kt`: `legacy`에서 `"ui/map/SpotMap.kt"`를 지워요.
- Test: `app/src/test/java/com/capstone/snapspot/ui/map/PinsTest.kt`

**Interfaces:**
- Consumes: `SnapColors`, `SnapShapes.PIN_CORNER_DP`, `R.font.pretendard_bold`
- Produces:
  - `MapPlace`에 필드 두 개를 더해요: `MapPlace(id, at, radiusM, initial, ended, selected, number: Int? = null, near: Boolean = false, couponStop: Boolean = false)`
  - `enum class PinShape { CIRCLE, SQUARE }`
  - `data class PinStyle(text, fill, ring, textColor, sizeDp: Int, ringDp: Float, shape: PinShape)`
  - `data class RadiusStyle(fill: Color, stroke: Color)`
  - `placePin(p: MapPlace): PinStyle`, `branchPin(b: MapBranch): PinStyle`, `ME_PIN: PinStyle`, `radiusStyle(p: MapPlace): RadiusStyle`, `REFRESH_AREA: RadiusStyle`
  - `SpotMap(...)` 매개변수:
    - `cornerRadius: Dp = 0.dp`: 0이면 가림막을 그리지 않아요.
    - `maskColor: Color = SnapColors.bg`
    - `padBottom: Dp = 0.dp`: 새로 추가하고, 카카오 `setPadding`의 아래 여백으로 써요.
    - 나머지는 그대로예요.

- [ ] **Step 1: 핀 테스트를 먼저 써요**

`app/src/test/java/com/capstone/snapspot/ui/map/PinsTest.kt`:

```kotlin
package com.capstone.snapspot.ui.map

import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.ui.theme.SnapColors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PinsTest {
    private fun place(near: Boolean = false, ended: Boolean = false, selected: Boolean = false, number: Int? = null, couponStop: Boolean = false) =
        MapPlace("p", Coords(37.5, 127.0), 200.0, "어", ended, selected, number, near, couponStop)

    @Test
    fun `a saved place is an ink circle with its initial`() {
        val s = placePin(place())
        assertEquals("어", s.text)
        assertEquals(SnapColors.ink, s.fill)
        assertEquals(SnapColors.surface, s.ring)
        assertEquals(PinShape.CIRCLE, s.shape)
    }

    @Test
    fun `a place you are near gets a lime ring`() {
        assertEquals(SnapColors.lime, placePin(place(near = true)).ring)
    }

    @Test
    fun `an ended place is grey even when near`() {
        val s = placePin(place(ended = true, near = true))
        assertEquals(SnapColors.muted, s.fill)
        assertEquals(SnapColors.surface, s.ring)
    }

    @Test
    fun `route stops show their number in lime on ink`() {
        val s = placePin(place(number = 2))
        assertEquals("2", s.text)
        assertEquals(SnapColors.ink, s.fill)
        assertEquals(SnapColors.lime, s.textColor)
    }

    @Test
    fun `coupon stops are white squares with an ink number`() {
        val s = placePin(place(number = 3, couponStop = true))
        assertEquals(PinShape.SQUARE, s.shape)
        assertEquals(SnapColors.surface, s.fill)
        assertEquals(SnapColors.ink, s.textColor)
        assertEquals("3", s.text)
    }

    @Test
    fun `branches are squares, lime when near, orange-ringed when urgent`() {
        val b = MapBranch("b", Coords(37.5, 127.0), "스", urgent = true, near = true)
        val s = branchPin(b)
        assertEquals(PinShape.SQUARE, s.shape)
        assertEquals(SnapColors.lime, s.fill)
        assertEquals(SnapColors.urgent, s.ring)
        assertEquals(SnapColors.surface, branchPin(b.copy(near = false)).fill)
        assertEquals(SnapColors.ink, branchPin(b.copy(urgent = false)).ring)
    }

    @Test
    fun `radius is ink, lime-deep when near, grey when ended`() {
        assertEquals(SnapColors.ink, radiusStyle(place()).stroke.copy(alpha = 1f))
        assertEquals(SnapColors.limeDeep, radiusStyle(place(near = true)).stroke.copy(alpha = 1f))
        assertEquals(SnapColors.muted, radiusStyle(place(ended = true, near = true)).stroke.copy(alpha = 1f))
    }
}
```

- [ ] **Step 2: 실패하는지 확인해요**

Run: `./gradlew :app:testDebugUnitTest --tests "com.capstone.snapspot.ui.map.PinsTest" --console=plain -q`
Expected: 컴파일 실패. `Unresolved reference 'placePin'`이 나오고, `MapPlace` 생성자 인자 수도 맞지 않아요.

- [ ] **Step 3: `Pins.kt`를 쓰고 `MapPlace`에 필드를 더해요**

`app/src/main/java/com/capstone/snapspot/ui/map/Pins.kt`:

```kotlin
package com.capstone.snapspot.ui.map

import androidx.compose.ui.graphics.Color
import com.capstone.snapspot.ui.theme.SnapColors

enum class PinShape { CIRCLE, SQUARE }

/** How one map label is drawn: a circle or a 4dp-rounded square, a ring, and a short text. */
data class PinStyle(val text: String, val fill: Color, val ring: Color, val textColor: Color, val sizeDp: Int, val ringDp: Float, val shape: PinShape)

/** An alert radius circle around a saved place. */
data class RadiusStyle(val fill: Color, val stroke: Color)

/** Saved places are ink circles; route stops carry their number; coupon stops are white squares. */
fun placePin(p: MapPlace): PinStyle = when {
    p.number != null && p.couponStop -> PinStyle("${p.number}", SnapColors.surface, SnapColors.ink, SnapColors.ink, 24, 2f, PinShape.SQUARE)
    p.number != null -> PinStyle("${p.number}", SnapColors.ink, SnapColors.surface, SnapColors.lime, 28, 2f, PinShape.CIRCLE)
    p.ended -> PinStyle(p.initial, SnapColors.muted, SnapColors.surface, SnapColors.surface, if (p.selected) 34 else 28, 2f, PinShape.CIRCLE)
    else -> PinStyle(
        p.initial, SnapColors.ink, if (p.near) SnapColors.lime else SnapColors.surface, SnapColors.surface,
        if (p.selected) 34 else 28, if (p.near) 3f else 2f, PinShape.CIRCLE,
    )
}

/** Franchise branches are small white squares: lime when near, ringed in urgent orange when the coupon ends soon. */
fun branchPin(b: MapBranch): PinStyle = PinStyle(
    b.initial, if (b.near) SnapColors.lime else SnapColors.surface, if (b.urgent) SnapColors.urgent else SnapColors.ink,
    SnapColors.ink, 20, if (b.urgent) 2.5f else 1.5f, PinShape.SQUARE,
)

val ME_PIN = PinStyle("", SnapColors.me, SnapColors.surface, SnapColors.surface, 16, 3f, PinShape.CIRCLE)

fun radiusStyle(p: MapPlace): RadiusStyle {
    val tone = when {
        p.ended -> SnapColors.muted
        p.near -> SnapColors.limeDeep
        else -> SnapColors.ink
    }
    val fill = when {
        p.near && !p.ended -> 0.16f
        p.selected -> 0.10f
        else -> 0.05f
    }
    return RadiusStyle(tone.copy(alpha = fill), tone.copy(alpha = if (p.near && !p.ended) 0.9f else 0.35f))
}

/** The status screen's refresh area: a thin ink ring (Kakao polygons have no dashes). */
val REFRESH_AREA = RadiusStyle(SnapColors.ink.copy(alpha = 0.04f), SnapColors.ink.copy(alpha = 0.5f))
```

- [ ] **Step 4: 감시 목록에서 `SpotMap.kt`를 빼고 실패하는지 확인해요**

`StyleGuardTest.kt`의 `legacy`에서 `"ui/map/SpotMap.kt",` 줄을 지워요.

Run: `./gradlew :app:testDebugUnitTest --tests "com.capstone.snapspot.ui.StyleGuardTest" --console=plain -q`
Expected: FAIL. `ui/map/SpotMap.kt:… pre-refresh color` 줄들이 나와요(`paper`, `highlight`, `amber`, `green`).

- [ ] **Step 5: `SpotMap.kt`를 통째로 바꿔요**

```kotlin
package com.capstone.snapspot.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.capstone.snapspot.R
import com.capstone.snapspot.SnapSpotApp
import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.ui.components.MapUnavailable
import com.capstone.snapspot.ui.theme.SnapColors
import com.capstone.snapspot.ui.theme.SnapShapes
import com.kakao.vectormap.GestureType
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraAnimation
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles
import com.kakao.vectormap.shape.MapPoints
import com.kakao.vectormap.shape.PolygonOptions
import com.kakao.vectormap.shape.PolygonStyles
import com.kakao.vectormap.shape.PolylineOptions
import kotlin.math.cos
import kotlin.math.sin

/**
 * A saved place: pin plus its alert radius (none when 0). `number`: position in an open bundle's route,
 * shown instead of the initial. `near`: the user is inside the radius. `couponStop`: a coupon branch on the route.
 */
data class MapPlace(
    val id: String,
    val at: Coords,
    val radiusM: Double,
    val initial: String,
    val ended: Boolean,
    val selected: Boolean,
    val number: Int? = null,
    val near: Boolean = false,
    val couponStop: Boolean = false,
)

/** A registered franchise branch: a small square, no radius. */
data class MapBranch(val id: String, val at: Coords, val initial: String, val urgent: Boolean, val near: Boolean)

/** Where the camera should go; `seq` changes for every request so the same spot can be re-focused. */
data class MapFocus(val at: Coords, val seq: Long, val zoom: Int = 16)

private val SEOUL = Coords(37.5665, 126.9780)

/**
 * Kakao map with saved places (pin + alert radius), registered branches and the user. Full-bleed by default;
 * with `cornerRadius` > 0 it sits in a rounded card, and since the map renders on a SurfaceView that ignores
 * Compose clipping, the corners are masked with `maskColor`. `padBottom` keeps the camera's center above a
 * bottom sheet covering the map.
 */
@Composable
fun SpotMap(
    places: List<MapPlace>,
    branches: List<MapBranch>,
    here: Coords?,
    focus: MapFocus?,
    modifier: Modifier = Modifier,
    refresh: Pair<Coords, Double>? = null,
    cornerRadius: Dp = 0.dp,
    maskColor: Color = SnapColors.bg,
    interactive: Boolean = true,
    /** An open bundle's walking route, drawn as a line through these points in order. */
    route: List<Coords> = emptyList(),
    padBottom: Dp = 0.dp,
    onPinClick: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    if (!(ctx.applicationContext as SnapSpotApp).mapReady) {
        Box(modifier) { MapUnavailable("지도 SDK를 시작하지 못했어요 (네이티브 앱 키 또는 이 기기의 CPU 지원)") }
        return
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var map by remember { mutableStateOf<KakaoMap?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    val onPin by rememberUpdatedState(onPinClick)
    val startAt = remember { focus?.at ?: here ?: places.firstOrNull()?.at ?: SEOUL }
    val density = LocalDensity.current.density
    val face = remember { ResourcesCompat.getFont(ctx, R.font.pretendard_bold) ?: Typeface.DEFAULT_BOLD }

    val view = remember {
        MapView(ctx).apply {
            start(
                object : MapLifeCycleCallback() {
                    override fun onMapDestroy() {
                        map = null
                    }

                    override fun onMapError(error: Exception) {
                        failed = error.message ?: error.javaClass.simpleName
                    }
                },
                object : KakaoMapReadyCallback() {
                    override fun onMapReady(kakaoMap: KakaoMap) {
                        kakaoMap.setOnLabelClickListener { _, _, label ->
                            (label.tag as? String)?.let { onPin(it) }
                            true
                        }
                        map = kakaoMap
                    }

                    override fun getPosition(): LatLng = LatLng.from(startAt.latitude, startAt.longitude)
                    override fun getZoomLevel(): Int = 15
                },
            )
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> view.resume()
                Lifecycle.Event.ON_PAUSE -> view.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.resume()
        onDispose {
            lifecycle.removeObserver(observer)
            view.finish()
        }
    }

    LaunchedEffect(map, places, branches, here, refresh, route) {
        val m = map ?: return@LaunchedEffect
        draw(m, places, branches, here, refresh, route, density, face)
    }
    LaunchedEffect(map, interactive) {
        val m = map ?: return@LaunchedEffect
        if (!interactive) {
            listOf(
                GestureType.Pan, GestureType.Zoom, GestureType.Rotate, GestureType.Tilt, GestureType.OneFingerDoubleTap,
                GestureType.TwoFingerSingleTap, GestureType.OneFingerZoom, GestureType.RotateZoom,
            ).forEach { m.setGestureEnable(it, false) }
        }
    }
    LaunchedEffect(map, padBottom) {
        val m = map ?: return@LaunchedEffect
        m.setPadding(0, 0, 0, (padBottom.value * density).toInt())
    }
    LaunchedEffect(map, focus) {
        val m = map ?: return@LaunchedEffect
        val f = focus ?: return@LaunchedEffect
        m.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(f.at.latitude, f.at.longitude), f.zoom), CameraAnimation.from(350))
    }

    Box(
        modifier.then(
            if (cornerRadius <= 0.dp) Modifier
            else Modifier.drawWithContent {
                drawContent()
                val r = cornerRadius.toPx()
                val mask = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                    addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r)))
                }
                drawPath(mask, maskColor)
            },
        ),
    ) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        if (failed != null) MapUnavailable(failed!!)
    }
}

private fun latLng(c: Coords) = LatLng.from(c.latitude, c.longitude)

/** A 64-gon around `center` with real-world radius in meters. */
private fun circle(center: Coords, radiusM: Double): MapPoints {
    val dLat = radiusM / 111_320.0
    val dLng = radiusM / (111_320.0 * cos(Math.toRadians(center.latitude)))
    val pts = (0 until 64).map { i ->
        val a = 2 * Math.PI * i / 64
        LatLng.from(center.latitude + dLat * sin(a), center.longitude + dLng * cos(a))
    }
    return MapPoints.fromLatLng(pts)
}

private fun draw(
    m: KakaoMap, places: List<MapPlace>, branches: List<MapBranch>, here: Coords?, refresh: Pair<Coords, Double>?,
    route: List<Coords>, density: Float, face: Typeface,
) {
    val shapes = m.shapeManager?.layer ?: return
    val labels = m.labelManager?.layer ?: return
    shapes.removeAll()
    labels.removeAll()

    refresh?.let { (c, r) ->
        shapes.addPolygon(PolygonOptions.from(circle(c, r), PolygonStyles.from(REFRESH_AREA.fill.toArgb(), 1.5f * density, REFRESH_AREA.stroke.toArgb())))
    }
    for (p in places) {
        if (p.radiusM <= 0) continue
        val s = radiusStyle(p)
        shapes.addPolygon(PolygonOptions.from(circle(p.at, p.radiusM), PolygonStyles.from(s.fill.toArgb(), 1.5f * density, s.stroke.toArgb())))
    }
    if (route.size >= 2) {
        shapes.addPolyline(PolylineOptions.from(MapPoints.fromLatLng(route.map { latLng(it) }), 3f * density, SnapColors.ink.toArgb()))
    }
    for (b in branches) {
        val bmp = badge(branchPin(b), density, face)
        labels.addLabel(LabelOptions.from(b.id, latLng(b.at)).setStyles(LabelStyles.from(LabelStyle.from(bmp).setAnchorPoint(0.5f, 0.5f))).setTag(b.id).setClickable(true))
    }
    for (p in places) {
        val bmp = badge(placePin(p), density, face)
        labels.addLabel(
            LabelOptions.from(p.id, latLng(p.at)).setStyles(LabelStyles.from(LabelStyle.from(bmp).setAnchorPoint(0.5f, 0.5f)))
                // Numbered route pins sit above the user's dot (rank 20), so standing on stop 1 does not hide its number.
                .setTag(p.id).setClickable(true).setRank(if (p.number != null) 30 else if (p.selected) 10 else 5),
        )
    }
    here?.let {
        val bmp = badge(ME_PIN, density, face)
        labels.addLabel(LabelOptions.from("me", latLng(it)).setStyles(LabelStyles.from(LabelStyle.from(bmp).setAnchorPoint(0.5f, 0.5f))).setRank(20))
    }
}

/** Draws a [PinStyle]: ring, then fill inset by the ring width, then the text centered. */
private fun badge(s: PinStyle, density: Float, face: Typeface): Bitmap {
    val size = (s.sizeDp * density).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val half = size / 2f
    fun shape(inset: Float) {
        if (s.shape == PinShape.CIRCLE) c.drawCircle(half, half, half - inset, p)
        else {
            val r = (SnapShapes.PIN_CORNER_DP * density - inset).coerceAtLeast(0f)
            c.drawRoundRect(RectF(inset, inset, size - inset, size - inset), r, r, p)
        }
    }
    p.color = s.ring.toArgb()
    shape(0f)
    p.color = s.fill.toArgb()
    shape(s.ringDp * density)
    if (s.text.isNotEmpty()) {
        p.color = s.textColor.toArgb()
        p.textAlign = Paint.Align.CENTER
        p.typeface = face
        p.textSize = size * 0.46f
        c.drawText(s.text, half, half - (p.descent() + p.ascent()) / 2, p)
    }
    return bmp
}
```

- [ ] **Step 6: 전체 테스트와 빌드를 확인해요**

Run: `./gradlew :app:testDebugUnitTest --console=plain -q && ./gradlew :app:assembleDebug --console=plain -q`
Expected:
- 모두 통과해요. `PinsTest` 7개가 포함되고, `StyleGuardTest`가 초록으로 돌아와요.
- 빌드가 성공해요. 홈, 추가, 현황 화면의 `SpotMap` 호출은 이름 붙인 인자를 써서 그대로 컴파일돼요.

- [ ] **Step 7: 커밋해요**

```bash
git add app/src/main/java/com/capstone/snapspot/ui/map app/src/test/java/com/capstone/snapspot/ui/map/PinsTest.kt app/src/test/java/com/capstone/snapspot/ui/StyleGuardTest.kt
git commit -q -m "map: ink and lime pins as tested styles, full-bleed map with bottom padding"
```

---

### Task 5: 홈 화면 (지도 전체 + 바텀시트 + 묶음 동선)

**Files:**
- Modify: `app/src/main/java/com/capstone/snapspot/ui/home/HomeScreen.kt` (전체 교체)
- Modify: `app/src/main/java/com/capstone/snapspot/MainActivity.kt`: 상태 표시줄을 투명하게 하고 내비게이션 바를 `bg`로 칠해요.
- Modify: `app/src/test/java/com/capstone/snapspot/ui/StyleGuardTest.kt`: `legacy`에서 `"ui/home/HomeScreen.kt"`와 `"MainActivity.kt"`를 지워요.

**Interfaces:**
- Consumes:
  - Task 2: `BigTitle`, `NearChip`, `FloatingChip`, `SheetHandle`, `Notice`, `Segmented`, `Shot`, `Dot`, `Gap`, `ConfirmDelete`
  - Task 3: `homeHeadline`, `nearPlaces`, `placeMeta`, `couponMeta`, `bundleTitle`, `listHeader`, `HomeViewModel.hereName`
  - Task 4: `SpotMap(padBottom = …)`, `MapPlace(near = …, couponStop = …)`
- Produces: 화면만 바뀌고 `HomeScreen(vm, onAdd, onStatus, onCoupon, onAskPermissions)` 시그니처는 그대로예요.

- [ ] **Step 1: 감시 목록에서 두 파일을 빼고 실패하는지 확인해요**

`StyleGuardTest.kt`의 `legacy`에서 `"MainActivity.kt",`와 `"ui/home/HomeScreen.kt",` 두 줄을 지워요.

Run: `./gradlew :app:testDebugUnitTest --tests "com.capstone.snapspot.ui.StyleGuardTest" --console=plain -q`
Expected: FAIL. `MainActivity.kt:27 pre-refresh color`와 `ui/home/HomeScreen.kt:… pre-refresh …` 줄들이 나와요.

- [ ] **Step 2: `MainActivity.kt`의 시스템 바 색을 바꿔요**

`enableEdgeToEdge(...)` 호출을 다음으로 바꿔요. 지도가 상태 표시줄 밑까지 보여요. 다른 화면은 `statusBarsPadding()`과 `bg` 바탕을 써서 같은 색으로 보여요.

```kotlin
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(SnapColors.bg.toArgb(), SnapColors.bg.toArgb()),
        )
```

- [ ] **Step 3: `HomeScreen.kt`를 통째로 바꿔요**

```kotlin
package com.capstone.snapspot.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.capstone.snapspot.core.BRANCH_RADIUS_M
import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.dDay
import com.capstone.snapspot.core.daysLeft
import com.capstone.snapspot.core.distanceMeters
import com.capstone.snapspot.core.formatDistance
import com.capstone.snapspot.core.model.Coupon
import com.capstone.snapspot.core.model.Place
import com.capstone.snapspot.core.placeEnded
import com.capstone.snapspot.ui.components.BigTitle
import com.capstone.snapspot.ui.components.ConfirmDelete
import com.capstone.snapspot.ui.components.Dot
import com.capstone.snapspot.ui.components.FloatingChip
import com.capstone.snapspot.ui.components.Gap
import com.capstone.snapspot.ui.components.NearChip
import com.capstone.snapspot.ui.components.Notice
import com.capstone.snapspot.ui.components.Segmented
import com.capstone.snapspot.ui.components.SheetHandle
import com.capstone.snapspot.ui.components.Shot
import com.capstone.snapspot.ui.map.MapBranch
import com.capstone.snapspot.ui.map.MapPlace
import com.capstone.snapspot.ui.map.SpotMap
import com.capstone.snapspot.ui.theme.SnapColors
import com.capstone.snapspot.ui.theme.SnapShapes
import com.capstone.snapspot.ui.theme.SnapSpace
import com.capstone.snapspot.ui.theme.SnapType

private enum class Tab { PLACES, COUPONS }

private fun initial(s: String) = s.trim().firstOrNull()?.toString() ?: "?"

/** Rows of one list share a card: only the first and last rows round their outer corners. */
private fun groupShape(i: Int, n: Int): Shape {
    val r = 10.dp
    return when {
        n == 1 -> SnapShapes.card
        i == 0 -> RoundedCornerShape(topStart = r, topEnd = r)
        i == n - 1 -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
        else -> RoundedCornerShape(0.dp)
    }
}

/**
 * The map fills the screen; the list lives in a bottom sheet (45% when collapsed). An open bundle swaps the sheet
 * for its walking route and the top-left chip for a way back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onAdd: () -> Unit,
    onStatus: () -> Unit,
    onCoupon: (String) -> Unit,
    onAskPermissions: () -> Unit,
) {
    val places by vm.places.collectAsState()
    val coupons by vm.coupons.collectAsState()
    val here by vm.here.collectAsState()
    val hereName by vm.hereName.collectAsState()
    val win by vm.window.collectAsState()
    val perms by vm.perms.collectAsState()
    val watching by vm.watching.collectAsState()
    val focus by vm.focus.collectAsState()
    val selected by vm.selected.collectAsState()
    val bundleItems by vm.bundles.collectAsState()
    val openBundle by vm.openBundle.collectAsState()
    val couponsOn by vm.withCoupons.collectAsState()
    val open = bundleItems.firstOrNull { it.bundle.id == openBundle }
    var tab by rememberSaveable { mutableStateOf(Tab.PLACES) }
    var deletingPlace by remember { mutableStateOf<Place?>(null) }
    var deletingCoupon by remember { mutableStateOf<Coupon?>(null) }
    val listState = rememberLazyListState()
    val sheet = rememberBottomSheetScaffoldState()
    val now = System.currentTimeMillis()
    val near = nearPlaces(places, here, now)
    val header = listHeader(perms.alertsOn, bundleItems.isNotEmpty())
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Tapping a pin scrolls the list to that row.
    LaunchedEffect(selected, tab, open, header) {
        val id = selected ?: return@LaunchedEffect
        val i = places.indexOfFirst { it.id == id }
        if (open == null && tab == Tab.PLACES && i >= 0) listState.animateScrollToItem(header + i)
    }

    // An open bundle numbers its stops in walking order; coupon stops are the branch the route picked.
    val stops = open?.let { routeStops(it) }.orEmpty()
    val numberOf = stops.mapIndexedNotNull { i, st -> st.placeId?.let { it to i + 1 } }.toMap()
    val mapPlaces = places.map {
        MapPlace(
            it.id, Coords(it.latitude, it.longitude), it.radius.toDouble(), initial(it.name), placeEnded(it, now),
            it.id == selected, numberOf[it.id], near = it.id in near,
        )
    } + stops.mapIndexedNotNull { i, st ->
        if (st.placeId != null) null
        else MapPlace("route-$i", st.at, 0.0, initial(st.name), ended = false, selected = false, number = i + 1, couponStop = true)
    }
    val routeLine = if (open == null) emptyList() else listOfNotNull(open.start) + stops.map { it.at }
    val mapBranches = win?.branches.orEmpty().map { (id, b) ->
        val at = Coords(b.latitude, b.longitude)
        MapBranch(id, at, initial(b.name), b.urgent, here?.let { distanceMeters(it, at) <= BRANCH_RADIUS_M } == true)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(SnapColors.bg)) {
        val peek = maxHeight * 0.45f
        val sheetHeight = maxHeight * 0.88f
        BottomSheetScaffold(
            scaffoldState = sheet,
            sheetPeekHeight = peek,
            sheetShape = SnapShapes.sheet,
            sheetContainerColor = SnapColors.bg,
            sheetShadowElevation = 8.dp,
            sheetDragHandle = { SheetHandle() },
            containerColor = SnapColors.bg,
            sheetContent = {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().height(sheetHeight),
                    contentPadding = PaddingValues(start = SnapSpace.gutter, end = SnapSpace.gutter, bottom = 96.dp + navBottom),
                ) {
                    if (open == null) {
                        // Header rows: keep in step with listHeader().
                        item(key = "headline") {
                            val h = homeHeadline(hereName, near.size, places.size, perms.alertsOn)
                            BigTitle(
                                h.kicker,
                                buildAnnotatedString {
                                    append(h.before)
                                    h.count?.let {
                                        append(" ")
                                        withStyle(SpanStyle(background = SnapColors.lime, color = SnapColors.limeInk)) { append(it) }
                                    }
                                    h.after?.let { append(" "); append(it) }
                                },
                                Modifier.padding(start = 4.dp, top = 4.dp, bottom = 12.dp),
                            )
                        }
                        if (!perms.alertsOn) item(key = "notice") {
                            Notice(
                                if (!perms.net && perms.bg) "휴대폰의 \"Google 위치 정확도\"가 꺼져 있어서 앱을 닫으면 알림이 오지 않아요."
                                else "앱을 닫아도 알림을 받으려면 알림 권한과 위치 \"항상 허용\"이 필요해요.",
                                "권한 설정하기" to onAskPermissions,
                                Modifier.padding(bottom = SnapSpace.gap),
                            )
                        }
                        if (bundleItems.isNotEmpty()) item(key = "bundles") {
                            LazyRow(Modifier.padding(bottom = SnapSpace.gap), horizontalArrangement = Arrangement.spacedBy(SnapSpace.gap)) {
                                items(bundleItems, key = { it.bundle.id }) { b ->
                                    BundleCard(b, Modifier.fillParentMaxWidth(if (bundleItems.size == 1) 1f else 0.86f)) { vm.toggleBundle(b.bundle.id) }
                                }
                            }
                        }
                        item(key = "tabs") {
                            Segmented(
                                options = Tab.entries,
                                value = tab,
                                onChange = { tab = it },
                                format = { if (it == Tab.PLACES) "장소 ${places.size}" else "쿠폰 ${coupons.count { c -> c.active }}" },
                                modifier = Modifier.padding(bottom = SnapSpace.gap),
                            )
                        }
                        if (tab == Tab.PLACES) {
                            if (places.isEmpty()) item(key = "empty") {
                                if (coupons.isEmpty()) Empty("스크린샷 한 장으로 시작해 보세요", "가 보고 싶은 곳의 스크린샷을 넣으면 근처를 지날 때 알려 드려요.")
                                else Empty("저장한 장소가 없어요", "가 보고 싶은 곳의 스크린샷을 넣으면 근처를 지날 때 알려 드려요. 쿠폰은 쿠폰 탭에 있어요.")
                            }
                            itemsIndexed(places, key = { _, p -> p.id }) { i, p ->
                                PlaceRow(
                                    p, here?.let { distanceMeters(it, Coords(p.latitude, p.longitude)) }, near = p.id in near,
                                    ended = placeEnded(p, now), selected = p.id == selected, shape = groupShape(i, places.size),
                                    onClick = { vm.focusOn(p.id) }, onLongClick = { deletingPlace = p },
                                )
                            }
                        } else {
                            if (coupons.isEmpty()) item(key = "empty") { Empty("저장한 쿠폰이 없어요", "기프티콘 스크린샷을 넣으면 그 브랜드 지점 근처에서 알려 드려요.") }
                            itemsIndexed(coupons, key = { _, c -> c.coupon.id }) { i, item ->
                                TicketRow(item, now, groupShape(i, coupons.size), onClick = { onCoupon(item.coupon.id) }, onLongClick = { deletingCoupon = item.coupon })
                            }
                        }
                    } else {
                        item(key = "bundle-head") {
                            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
                                BigTitle("가 볼 곳 ${open.bundle.places.size}곳", "걸어서 ${open.minutes}분", Modifier.weight(1f))
                                Text("쿠폰 포함", style = SnapType.label)
                                Gap(6.dp)
                                Switch(
                                    checked = open.bundle.id in couponsOn,
                                    onCheckedChange = { vm.toggleCoupons(open.bundle.id) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = SnapColors.lime, checkedTrackColor = SnapColors.ink, checkedBorderColor = SnapColors.ink,
                                        uncheckedThumbColor = SnapColors.surface, uncheckedTrackColor = SnapColors.line, uncheckedBorderColor = SnapColors.line,
                                    ),
                                )
                            }
                        }
                        if (open.couponsFailed) item(key = "bundle-failed") {
                            Notice(
                                if (open.coupons.isEmpty()) "쿠폰 지점을 불러오지 못했어요" else "일부 쿠폰 지점을 불러오지 못했어요",
                                modifier = Modifier.padding(bottom = SnapSpace.gap),
                            )
                        }
                        itemsIndexed(stops, key = { i, _ -> "stop-$i" }) { i, st ->
                            val p = places.firstOrNull { it.id == st.placeId }
                            StopRow(
                                i + 1, st, p, near = p != null && p.id in near, shape = groupShape(i, stops.size),
                                onClick = p?.let { { vm.focusOn(it.id) } },
                            )
                        }
                        item(key = "bundle-preview") {
                            Text(
                                "알림 미리보기",
                                style = SnapType.button.copy(color = SnapColors.ink2),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = SnapSpace.gap).clip(SnapShapes.card)
                                    .clickable { vm.previewBundle(open.bundle.id) }.padding(vertical = 14.dp),
                            )
                        }
                    }
                }
            },
        ) { _ ->
            Box(Modifier.fillMaxSize()) {
                SpotMap(
                    places = mapPlaces,
                    branches = mapBranches,
                    here = here,
                    focus = focus,
                    modifier = Modifier.fillMaxSize(),
                    route = routeLine,
                    padBottom = peek,
                    onPinClick = { id ->
                        if (places.any { it.id == id }) tab = Tab.PLACES
                        vm.focusOn(id)
                    },
                )
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (open == null) {
                        FloatingChip(Modifier.weight(1f, fill = false)) { Text("SnapSpot", style = SnapType.logo) }
                    } else {
                        FloatingChip(Modifier.weight(1f, fill = false), onClick = { vm.toggleBundle(open.bundle.id) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "묶음 닫기", tint = SnapColors.ink, modifier = Modifier.size(18.dp))
                            Gap(6.dp)
                            Text(bundleTitle(open.name), style = SnapType.title.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Gap(8.dp)
                    WatchChip(perms.alertsOn, watching, onStatus)
                }
                Box(
                    Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 60.dp, end = 12.dp).size(44.dp)
                        .shadow(4.dp, SnapShapes.card).clip(SnapShapes.card).background(SnapColors.surface).clickable { vm.focusHere() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.MyLocation, contentDescription = "내 위치로", tint = SnapColors.ink, modifier = Modifier.size(20.dp)) }
            }
        }
        if (open == null) {
            Box(
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp).size(56.dp)
                    .shadow(6.dp, SnapShapes.card).clip(SnapShapes.card).background(SnapColors.ink).clickable(onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Add, contentDescription = "스크린샷 추가", tint = SnapColors.lime, modifier = Modifier.size(28.dp)) }
        }
    }

    deletingPlace?.let { p ->
        ConfirmDelete("${p.name} 삭제", "이 장소의 근처 알림도 함께 꺼져요.", onConfirm = { vm.deletePlace(p); deletingPlace = null }, onDismiss = { deletingPlace = null })
    }
    deletingCoupon?.let { cp ->
        ConfirmDelete("${cp.brand} 쿠폰 삭제", "이 쿠폰의 지점 알림도 함께 꺼져요.", onConfirm = { vm.deleteCoupon(cp); deletingCoupon = null }, onDismiss = { deletingCoupon = null })
    }
}

@Composable
private fun WatchChip(on: Boolean, watching: Int, onClick: () -> Unit) {
    FloatingChip(onClick = onClick) {
        Dot(if (on) SnapColors.limeDeep else SnapColors.urgent)
        Gap(6.dp)
        Text(if (on) "${watching}곳 지켜보는 중" else "알림 꺼짐", style = SnapType.label.copy(color = SnapColors.ink), maxLines = 1)
    }
}

@Composable
private fun Empty(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().clip(SnapShapes.card).background(SnapColors.surface).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = SnapType.title)
        Text(body, style = SnapType.meta)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaceRow(
    p: Place, distance: Double?, near: Boolean, ended: Boolean, selected: Boolean, shape: Shape,
    onClick: () -> Unit, onLongClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (selected) SnapColors.line else SnapColors.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = SnapSpace.card, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Shot(p.imageUri, 40.dp, 40.dp, modifier = if (ended) Modifier.alpha(0.5f) else Modifier)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(p.name, style = SnapType.title.copy(fontSize = 15.sp, color = if (ended) SnapColors.muted else SnapColors.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(placeMeta(p, ended), style = SnapType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            near -> NearChip()
            distance != null -> Text(formatDistance(distance), style = SnapType.label)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TicketRow(item: CouponItem, now: Long, shape: Shape, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = item.coupon
    val near = item.active && item.nearest != null && item.nearest.second <= BRANCH_RADIUS_M
    Row(
        Modifier.fillMaxWidth().clip(shape).background(SnapColors.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = SnapSpace.card, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Shot(c.imageUri, 40.dp, 40.dp, modifier = if (item.active) Modifier else Modifier.alpha(0.5f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(c.brand, style = SnapType.title.copy(fontSize = 15.sp, color = if (item.active) SnapColors.ink else SnapColors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(couponMeta(item), style = SnapType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            near -> NearChip()
            item.active -> dDay(daysLeft(c, now))?.let {
                Text(it, style = SnapType.label.copy(fontWeight = FontWeight.Bold, color = if (item.urgent) SnapColors.urgent else SnapColors.ink2))
            }
        }
    }
}

/** One stop of an open bundle's route: a saved place, or the branch picked for a coupon brand. */
private class RouteStop(val placeId: String?, val name: String, val at: Coords, val brand: String? = null)

private fun routeStops(b: BundleItem): List<RouteStop> = b.route.order.indices.map { i ->
    val s = b.route.order[i]
    val k = b.route.picks[i]
    if (s < b.bundle.places.size) {
        val p = b.bundle.places[s]
        RouteStop(p.id, p.name, Coords(p.latitude, p.longitude))
    } else {
        val cs = b.coupons[s - b.bundle.places.size]
        val br = cs.branches[k]
        RouteStop(null, br.name, Coords(br.latitude, br.longitude), cs.brand)
    }
}

@Composable
private fun StopRow(number: Int, stop: RouteStop, place: Place?, near: Boolean, shape: Shape, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clip(shape).background(SnapColors.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = SnapSpace.card, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StopNumber(number, coupon = place == null)
        if (place != null) Shot(place.imageUri, 40.dp, 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stop.name, style = SnapType.title.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (place != null) place.category else "쿠폰 · ${stop.brand.orEmpty()}", style = SnapType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (near) NearChip()
    }
}

/** Route numbers match the map: ink circle with a lime digit for places, white rounded square for coupon branches. */
@Composable
private fun StopNumber(n: Int, coupon: Boolean) {
    val shape = if (coupon) RoundedCornerShape(4.dp) else CircleShape
    Box(
        Modifier.size(24.dp).clip(shape).background(if (coupon) SnapColors.surface else SnapColors.ink)
            .then(if (coupon) Modifier.border(2.dp, SnapColors.ink, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text("$n", style = SnapType.meta.copy(fontWeight = FontWeight.Bold, color = if (coupon) SnapColors.ink else SnapColors.lime))
    }
}

@Composable
private fun BundleCard(b: BundleItem, modifier: Modifier, onOpen: () -> Unit) {
    Row(
        modifier.clip(SnapShapes.card).background(SnapColors.surface).clickable(onClick = onOpen).padding(SnapSpace.card),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(bundleTitle(b.name), style = SnapType.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("가 볼 곳 ${b.bundle.places.size}곳 · 걸어서 ${b.minutes}분", style = SnapType.meta, maxLines = 1)
        }
        Text(
            "동선 보기",
            style = SnapType.meta.copy(fontWeight = FontWeight.ExtraBold, color = SnapColors.lime),
            modifier = Modifier.clip(SnapShapes.small).background(SnapColors.ink).padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}
```

- [ ] **Step 4: 전체 테스트와 빌드를 확인해요**

Run: `./gradlew :app:testDebugUnitTest --console=plain -q && ./gradlew :app:assembleDebug --console=plain -q`
Expected: 모두 통과(`StyleGuardTest` 초록), 빌드 성공

- [ ] **Step 5: 에뮬레이터에서 직접 확인해요 (Review Focus 2, 4)**

```bash
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb"
S=C:/Users/hasan/AppData/Local/Temp/claude/C--Users-hasan-Downloads-capstone/a3f4fa50-d298-4b74-83c6-32cb357c2b8a/scratchpad
./gradlew :app:installDebug --console=plain -q
"$ADB" shell monkey -p com.google.android.apps.maps 1 >/dev/null; sleep 3
"$ADB" emu geo fix 127.0582 37.5447
"$ADB" shell am start -n com.capstone.snapspot/.MainActivity; sleep 4
"$ADB" exec-out screencap -p > $S/home-peek.png
```

확인할 것(스크린샷을 Read로 열어 봐요):
- 지도가 상태 표시줄 밑까지 채워지는지
- 왼쪽 위에 "SnapSpot" 칩, 오른쪽 위에 "N곳 지켜보는 중" 칩이 있는지
- 시트가 화면의 약 45%이고 큰 문장 → 묶음 카드 → 장소/쿠폰 → 목록 순서인지
- + 버튼이 오른쪽 아래에 있는지

이어서 확인할 것:
- 핀 하나를 눌러 보세요. 위치는 `"$ADB" shell uiautomator dump`로 찾거나 지도 위 핀 좌표를 직접 눌러요. 고른 핀이 시트 위 보이는 영역 가운데로 오면 맞아요. 시트에 가려지면 `padBottom`을 다시 봐야 해요.
- "동선 보기"를 눌러요. 좌표는 아래 명령으로 찾아요.

```bash
"$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null; "$ADB" exec-out cat /sdcard/ui.xml | grep -o 'text="동선 보기"[^>]*bounds="[^"]*"'
```

동선을 연 뒤 확인할 것:
- 왼쪽 위 칩이 "‹ 성수동 코스"로 바뀌는지
- 시트에 "걸어서 N분"이 보이는지
- 번호가 붙은 목록과 지도의 잉크 선이 맞는지
- "쿠폰 포함" 스위치를 켜면 흰 네모 번호 핀이 생기는지
- 칩을 누르면 목록으로 돌아오는지

마지막으로 동선을 다시 열고, 그 묶음의 장소를 길게 눌러 지워 보세요. 묶음이 3곳 미만이 되면 묶음이 사라지면서 일반 목록과 "SnapSpot" 칩으로 돌아와야 해요. 확인한 뒤에는 `/data/local/tmp/places.json`으로 장소를 되살려요.

```bash
MSYS_NO_PATHCONV=1 "$ADB" shell run-as com.capstone.snapspot cp /data/local/tmp/places.json files/places.json
"$ADB" shell input keyevent HOME; "$ADB" shell am kill com.capstone.snapspot
```

- [ ] **Step 6: 커밋해요**

```bash
git add app/src/main/java/com/capstone/snapspot/ui/home/HomeScreen.kt app/src/main/java/com/capstone/snapspot/MainActivity.kt app/src/test/java/com/capstone/snapspot/ui/StyleGuardTest.kt
git commit -q -m "home: full-screen map with a bottom sheet, headline, grouped rows and route view"
```

---

### Task 6: 스크린샷 추가 화면

**Files:**
- Create: `app/src/main/java/com/capstone/snapspot/ui/add/AddCopy.kt`
- Modify: `app/src/main/java/com/capstone/snapspot/ui/add/AddScreen.kt` (전체 교체)
- Modify: `app/src/test/java/com/capstone/snapspot/ui/StyleGuardTest.kt`: `legacy`에서 `"ui/add/AddScreen.kt"`를 지워요.
- Test: `app/src/test/java/com/capstone/snapspot/ui/add/AddCopyTest.kt`

**Interfaces:**
- Consumes:
  - Task 2: `BigTitle`, `Field`, `IconTap`, `Notice`, `PrimaryButton`, `SecondaryButton`, `Segmented`, `Shot(shape = …)`
  - Task 4: `SpotMap(cornerRadius, maskColor)`
  - 기존: `AddViewModel`, `Draft`, `Stage`, `SaveResult`, `RADII`, `batchSummary`
- Produces:
  - `matchLabel(matched: Candidate?, hasCoords: Boolean): String`
  - `foundKicker(kind: String, isPlace: Boolean): String`
  - `AddScreen`의 시그니처는 그대로예요.

- [ ] **Step 1: 테스트를 먼저 써요**

`app/src/test/java/com/capstone/snapspot/ui/add/AddCopyTest.kt`:

```kotlin
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
        assertEquals("쿠폰에서 찾았어요", foundKicker("coupon", isPlace = false))
        assertEquals("스크린샷에서 찾았어요", foundKicker("place", isPlace = true))
        assertEquals("장소를 찾기 어려웠어요", foundKicker("place", isPlace = false))
    }
}
```

- [ ] **Step 2: 실패하는지 확인해요**

Run: `./gradlew :app:testDebugUnitTest --tests "com.capstone.snapspot.ui.add.AddCopyTest" --console=plain -q`
Expected: 컴파일 실패, `Unresolved reference 'matchLabel'`

- [ ] **Step 3: `AddCopy.kt`를 써요**

```kotlin
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
    kind == "coupon" -> "쿠폰에서 찾았어요"
    isPlace -> "스크린샷에서 찾았어요"
    else -> "장소를 찾기 어려웠어요"
}
```

Run: `./gradlew :app:testDebugUnitTest --tests "com.capstone.snapspot.ui.add.AddCopyTest" --console=plain -q`
Expected: 2개 통과

- [ ] **Step 4: 감시 목록에서 `AddScreen.kt`를 빼고 실패하는지 확인해요**

`StyleGuardTest.kt`의 `legacy`에서 `"ui/add/AddScreen.kt",` 줄을 지워요.

Run: `./gradlew :app:testDebugUnitTest --tests "com.capstone.snapspot.ui.StyleGuardTest" --console=plain -q`
Expected: FAIL. `ui/add/AddScreen.kt:… pre-refresh color` 줄들이 나와요(`paper`, `green`, `amberSoft` 등).

- [ ] **Step 5: `AddScreen.kt`를 통째로 바꿔요**

```kotlin
package com.capstone.snapspot.ui.add

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.capstone.snapspot.core.CONFIDENT
import com.capstone.snapspot.core.Candidate
import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.distanceMeters
import com.capstone.snapspot.core.formatDistance
import com.capstone.snapspot.ui.components.BigTitle
import com.capstone.snapspot.ui.components.Field
import com.capstone.snapspot.ui.components.IconTap
import com.capstone.snapspot.ui.components.Notice
import com.capstone.snapspot.ui.components.PrimaryButton
import com.capstone.snapspot.ui.components.SecondaryButton
import com.capstone.snapspot.ui.components.Segmented
import com.capstone.snapspot.ui.components.Shot
import com.capstone.snapspot.ui.map.MapFocus
import com.capstone.snapspot.ui.map.MapPlace
import com.capstone.snapspot.ui.map.SpotMap
import com.capstone.snapspot.ui.theme.SnapColors
import com.capstone.snapspot.ui.theme.SnapShapes
import com.capstone.snapspot.ui.theme.SnapSpace
import com.capstone.snapspot.ui.theme.SnapType
import kotlinx.coroutines.launch

@Composable
fun AddScreen(
    vm: AddViewModel,
    onClose: () -> Unit,
    onSaved: (kind: String, id: String) -> Unit,
    onFinished: (summary: String) -> Unit,
    onOpenPlace: (String) -> Unit,
    onOpenCoupon: (String) -> Unit,
) {
    val batch by vm.batch.collectAsState()
    val draft by vm.draft.collectAsState()
    val error by vm.error.collectAsState()
    val scope = rememberCoroutineScope()
    var duplicate by remember { mutableStateOf<SaveResult?>(null) }

    // Several screenshots: after the last one, go back with a summary.
    LaunchedEffect(batch?.done) {
        val b = batch ?: return@LaunchedEffect
        if (b.done && b.total > 1) {
            onFinished(batchSummary(b))
            vm.cancel()
        }
    }

    val b = batch ?: return
    val d = draft ?: return
    val many = b.total > 1
    val ready = d.stage == Stage.READY
    val isCoupon = d.kind == "coupon"

    fun save(allowDuplicate: Boolean = false) = scope.launch {
        when (val r = vm.save(allowDuplicate)) {
            is SaveResult.Saved -> if (!many) {
                vm.cancel()
                onSaved(r.kind, r.id)
            }
            is SaveResult.DuplicatePlace, is SaveResult.DuplicateCoupon -> duplicate = r
            SaveResult.NotReady -> Unit
        }
    }

    Column(Modifier.fillMaxSize().background(SnapColors.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (many) "${b.position} / ${b.total}" else "", style = SnapType.label, modifier = Modifier.weight(1f))
            IconTap(Icons.Filled.Close, "닫기", { vm.cancel(); onClose() })
        }
        if (!ready) {
            Reading(d)
        } else {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = SnapSpace.gutter),
                verticalArrangement = Arrangement.spacedBy(SnapSpace.gap),
            ) {
                BigTitle(
                    foundKicker(d.kind, d.isPlace),
                    (if (isCoupon) d.brand else d.name).ifBlank { "이름을 적어 주세요" },
                    Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp),
                )
                WideShot(d.imageUri, if (isCoupon) null else matchLabel(d.matched, d.coords != null))
                Segmented(listOf("place", "coupon"), d.kind, vm::setKind, { if (it == "place") "가 볼 장소" else "프랜차이즈 쿠폰" })
                if (isCoupon) CouponForm(vm, d) else PlaceForm(vm, d)
                Spacer(Modifier.height(8.dp))
            }
            Row(
                Modifier.fillMaxWidth().background(SnapColors.bg).padding(horizontal = SnapSpace.gutter, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(SnapSpace.gap),
            ) {
                if (many) SecondaryButton("건너뛰기", onClick = vm::skip, modifier = Modifier.weight(1f))
                PrimaryButton(
                    if (isCoupon) "쿠폰함에 저장" else "지도에 저장",
                    onClick = { save() },
                    enabled = if (isCoupon) d.brand.isNotBlank() else d.coords != null,
                    modifier = Modifier.weight(2f),
                )
            }
        }
    }

    (error ?: d.readError)?.let { msg ->
        val dismiss = { if (error != null) vm.clearError() else vm.clearReadError() }
        val (title, body) = msg.split("\n", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(title, style = SnapType.title) },
            text = { Text(body, style = SnapType.body) },
            confirmButton = { TextButton(onClick = dismiss) { Text("확인", style = SnapType.button, color = SnapColors.ink) } },
            containerColor = SnapColors.surface,
            shape = SnapShapes.card,
        )
    }
    when (val dup = duplicate) {
        is SaveResult.DuplicatePlace -> AlertDialog(
            onDismissRequest = { duplicate = null },
            title = { Text("이미 저장한 장소예요", style = SnapType.title) },
            text = { Text("${dup.existing.name}\n같은 장소는 한 번만 저장돼서 알림도 한 번만 와요.", style = SnapType.body) },
            confirmButton = {
                // Mid-batch, "see the existing one" would throw away the screenshots still waiting.
                if (many) TextButton(onClick = { duplicate = null; vm.skip() }) { Text("건너뛰기", style = SnapType.button, color = SnapColors.ink) }
                else TextButton(onClick = { duplicate = null; vm.cancel(); onOpenPlace(dup.existing.id) }) { Text("기존 것 보기", style = SnapType.button, color = SnapColors.ink) }
            },
            dismissButton = { TextButton(onClick = { duplicate = null }) { Text("취소", style = SnapType.button, color = SnapColors.ink2) } },
            containerColor = SnapColors.surface,
            shape = SnapShapes.card,
        )
        is SaveResult.DuplicateCoupon -> AlertDialog(
            onDismissRequest = { duplicate = null },
            title = { Text("같은 쿠폰이 이미 있어요", style = SnapType.title) },
            text = { Text("${dup.existing.brand} ${dup.existing.benefit} 쿠폰이 쿠폰함에 있어요. 같은 쿠폰을 한 장 더 가지고 있다면 그래도 추가할 수 있어요.", style = SnapType.body) },
            confirmButton = {
                Row {
                    TextButton(onClick = { duplicate = null; save(allowDuplicate = true) }) { Text("그래도 추가", style = SnapType.button, color = SnapColors.ink2) }
                    if (many) TextButton(onClick = { duplicate = null; vm.skip() }) { Text("건너뛰기", style = SnapType.button, color = SnapColors.ink) }
                    else TextButton(onClick = { duplicate = null; vm.cancel(); onOpenCoupon(dup.existing.id) }) { Text("기존 것 보기", style = SnapType.button, color = SnapColors.ink) }
                }
            },
            dismissButton = { TextButton(onClick = { duplicate = null }) { Text("취소", style = SnapType.button, color = SnapColors.ink2) } },
            containerColor = SnapColors.surface,
            shape = SnapShapes.card,
        )
        else -> Unit
    }
}

/** The screenshot across the screen, with how its place was found on a white chip. */
@Composable
private fun WideShot(uri: String?, label: String?) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(180.dp).clip(SnapShapes.card)) {
        Shot(uri, maxWidth, maxHeight, shape = RectangleShape)
        label?.let {
            Text(
                it,
                style = SnapType.meta.copy(fontWeight = FontWeight.Bold, color = SnapColors.ink),
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).clip(SnapShapes.small)
                    .background(SnapColors.surface).padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun FormCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(SnapShapes.card).background(SnapColors.surface).padding(SnapSpace.card),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun PlaceForm(vm: AddViewModel, d: Draft) {
    val here by vm.here.collectAsState()
    val busy by vm.busy.collectAsState()
    if (!d.isPlace) Notice("이 이미지에서는 장소를 찾기 어려웠어요. 이름과 주소를 직접 고쳐 주세요.")
    FormCard {
        Field("장소 이름", d.name, { v -> vm.update { it.copy(name = v) } }, large = true)
        Field("분류", d.category, { v -> vm.update { it.copy(category = v) } })
        Field("메모", d.note, { v -> vm.update { it.copy(note = v) } })
        Field("주소나 위치 단서", d.address, { v -> vm.update { it.copy(address = v) } })
        Field("종료일 (팝업·전시만)", d.endDate, { v -> vm.update { it.copy(endDate = v) } }, placeholder = "예: 2026-10-31 (없으면 비워 두세요)")
    }
    FormCard {
        Text("지도에서 찾은 위치", style = SnapType.label)
        MatchBox(d.matched, d.coords != null)
        if (d.coords != null && here != null) Text("여기서 ${formatDistance(distanceMeters(here!!, d.coords))}", style = SnapType.meta)
        val others = d.candidates.filter { it != d.matched }.take(3)
        if (others.isNotEmpty()) {
            Column {
                Text("다른 후보", style = SnapType.meta)
                others.forEach { c -> CandidateRow(c) { vm.pickCandidate(c) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(SnapSpace.gap)) {
            SecondaryButton("주소로 다시 찾기", vm::regeocode, Modifier.weight(1f), busy = busy)
            SecondaryButton("지금 위치로", vm::pinToHere, Modifier.weight(1f))
        }
    }
    FormCard {
        Text("이만큼 가까워지면 알려 드려요", style = SnapType.label)
        Segmented(RADII, d.radius, { r -> vm.update { it.copy(radius = r) } }, { "${it}m" })
        d.coords?.let { at ->
            // The chosen radius, drawn right away on a small map.
            SpotMap(
                places = listOf(MapPlace("draft", at, d.radius.toDouble(), d.name.firstOrNull()?.toString() ?: "?", ended = false, selected = true)),
                branches = emptyList(),
                here = here,
                focus = MapFocus(at, d.radius.toLong(), zoomFor(d.radius)),
                modifier = Modifier.fillMaxWidth().height(180.dp),
                cornerRadius = 10.dp,
                maskColor = SnapColors.surface,
                interactive = false,
            )
        }
    }
}

private fun zoomFor(radius: Int) = when {
    radius <= 100 -> 17
    radius <= 300 -> 16
    else -> 15
}

/** What the map search found: an ink bar when sure, an orange bar when the user should check. */
@Composable
private fun MatchBox(matched: Candidate?, hasCoords: Boolean) {
    val sure = hasCoords && matched != null && matched.score >= CONFIDENT
    val (head, body) = when {
        !hasCoords -> "위치를 못 찾았어요" to "주소를 고치고 다시 찾거나 \"지금 위치로\"를 눌러 주세요."
        matched == null -> "주소로 짐작한 위치예요" to "카카오맵에서 같은 가게를 찾지 못했어요. 핀이 맞는지 확인해 주세요."
        sure -> matched.name to "카카오맵에서 찾았어요 · ${matched.address}"
        else -> matched.name to "비슷한 곳이에요. 맞는지 확인해 주세요 · ${matched.address}"
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(SnapShapes.small).background(SnapColors.bg)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(if (sure) SnapColors.ink else SnapColors.urgent))
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(head, style = SnapType.title.copy(fontSize = 15.sp))
            Text(body, style = SnapType.meta)
        }
    }
}

@Composable
private fun CandidateRow(c: Candidate, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        HorizontalDivider(color = SnapColors.line)
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.name, style = SnapType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(c.address, style = SnapType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("선택", style = SnapType.label.copy(color = SnapColors.ink, fontWeight = FontWeight.Bold))
        }
    }
}

/** Coupon fields plus a preview of the branches that will trigger alerts. */
@Composable
private fun CouponForm(vm: AddViewModel, d: Draft) {
    val here by vm.here.collectAsState()
    val busy by vm.busy.collectAsState()
    FormCard {
        Field("브랜드", d.brand, { v -> vm.update { it.copy(brand = v) } }, large = true)
        Field("받을 수 있는 것", d.benefit, { v -> vm.update { it.copy(benefit = v) } })
        Field("유효기간 마지막 날", d.endDate, { v -> vm.update { it.copy(endDate = v) } }, placeholder = "예: 2026-12-31 (없으면 비워 두세요)")
    }
    FormCard {
        Text("알림이 울릴 지점", style = SnapType.label)
        val branches = d.branches
        val at = here
        when {
            at == null -> Notice("위치를 아직 몰라요. 위치를 허용하면 근처 지점을 미리 보여 드려요. 저장은 지금 해도 돼요.")
            branches == null -> Preview("근처 지점을 찾는 중이에요", null)
            branches.isEmpty() -> Notice("근처에서 ${d.brand.ifBlank { "이 브랜드" }} 지점을 못 찾았어요. 브랜드 이름을 고친 뒤 다시 찾아 보세요. 멀리 가면 그곳 지점을 다시 찾아요.")
            else -> {
                val shown = branches.take(7)
                Preview(
                    "가까운 ${d.brand} ${shown.size}곳을 지켜봐요",
                    "가장 가까운 곳은 ${shown[0].name}(${formatDistance(distanceMeters(at, Coords(shown[0].latitude, shown[0].longitude)))})이에요. 이동하면 그 근처 지점으로 바꿔 가며 알려 드려요.",
                )
                Column {
                    shown.take(3).forEachIndexed { i, b ->
                        if (i > 0) HorizontalDivider(color = SnapColors.line)
                        Row(Modifier.padding(vertical = 10.dp)) {
                            Text(b.name, style = SnapType.body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(formatDistance(distanceMeters(at, Coords(b.latitude, b.longitude))), style = SnapType.label)
                        }
                    }
                }
            }
        }
        SecondaryButton("브랜드로 다시 찾기", vm::refreshBranchPreview, Modifier.fillMaxWidth(), busy = busy)
    }
}

@Composable
private fun Preview(head: String, body: String?) {
    Column(Modifier.fillMaxWidth().clip(SnapShapes.small).background(SnapColors.bg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(head, style = SnapType.title.copy(fontSize = 15.sp))
        body?.let { Text(it, style = SnapType.meta) }
    }
}

/** The screenshot, with an ink band sweeping over it while it is being read. */
@Composable
private fun Reading(d: Draft) {
    val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "y",
    )
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        BigTitle(null, "스크린샷 읽는 중", Modifier.fillMaxWidth())
        BoxWithConstraints(Modifier.fillMaxWidth(0.62f).aspectRatio(9f / 16f).clip(SnapShapes.card)) {
            Shot(d.imageUri, maxWidth, maxHeight, shape = RectangleShape)
            Box(Modifier.fillMaxWidth().height(40.dp).offset(y = (maxHeight + 40.dp) * sweep - 40.dp).background(SnapColors.ink.copy(alpha = 0.14f)))
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Step("스크린샷에서 장소 정보 읽기", if (d.stage == Stage.READING) 1 else 2)
            Step(if (d.kind == "coupon") "근처 지점 찾기" else "카카오맵에서 장소 찾기", if (d.stage == Stage.LOCATING) 1 else 0)
            if (d.name.isNotBlank()) Text("찾은 이름: ${d.name}", style = SnapType.meta)
        }
    }
}

/** state: 0 todo, 1 now, 2 done. */
@Composable
private fun Step(label: String, state: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(20.dp).clip(CircleShape).background(if (state == 2) SnapColors.ink else SnapColors.surface)
                .border(2.dp, if (state == 0) SnapColors.line else SnapColors.ink, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (state == 2) Icon(Icons.Filled.Check, null, tint = SnapColors.surface, modifier = Modifier.size(12.dp)) }
        Text(label, style = SnapType.body.copy(color = if (state == 0) SnapColors.muted else SnapColors.ink, fontWeight = if (state == 1) FontWeight.Bold else FontWeight.Normal))
    }
}
```

- [ ] **Step 6: 전체 테스트와 빌드를 확인해요**

Run: `./gradlew :app:testDebugUnitTest --console=plain -q && ./gradlew :app:assembleDebug --console=plain -q`
Expected: 모두 통과(`StyleGuardTest` 초록), 빌드 성공

- [ ] **Step 7: 커밋해요**

```bash
git add app/src/main/java/com/capstone/snapspot/ui/add app/src/test/java/com/capstone/snapspot/ui/add/AddCopyTest.kt app/src/test/java/com/capstone/snapspot/ui/StyleGuardTest.kt
git commit -q -m "add: found name up front, wide screenshot, form in white cards"
```

---

### Task 7: 쿠폰 화면

**Files:**
- Modify: `app/src/main/java/com/capstone/snapspot/ui/coupon/CouponScreen.kt` (전체 교체)
- Modify: `app/src/test/java/com/capstone/snapspot/ui/StyleGuardTest.kt`: `legacy`에서 `"ui/coupon/CouponScreen.kt"`를 지워요.

**Interfaces:**
- Consumes: Task 2의 `BigTitle`, `ConfirmDelete`, `Gap`, `IconTap`, `LargeShot`, `NearChip`, `PrimaryButton`, `SecondaryButton`
- Produces: `CouponScreen(vm, id, onClose)` 시그니처는 그대로예요. 삭제는 오른쪽 위 메뉴로 옮기고, 기간이 지난 쿠폰은 아래 버튼으로도 지울 수 있어요.

- [ ] **Step 1: 감시 목록에서 빼고 실패하는지 확인해요**

`StyleGuardTest.kt`의 `legacy`에서 `"ui/coupon/CouponScreen.kt",` 줄을 지워요.

Run: `./gradlew :app:testDebugUnitTest --tests "com.capstone.snapspot.ui.StyleGuardTest" --console=plain -q`
Expected: FAIL. `ui/coupon/CouponScreen.kt:… pre-refresh` 줄들이 나와요(`paper`, `SnapFonts.display`, `highlight`, `SnapType.distance` 등).

- [ ] **Step 2: `CouponScreen.kt`를 통째로 바꿔요**

```kotlin
package com.capstone.snapspot.ui.coupon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.capstone.snapspot.core.BRANCH_RADIUS_M
import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.brandKey
import com.capstone.snapspot.core.dDay
import com.capstone.snapspot.core.daysLeft
import com.capstone.snapspot.core.distanceMeters
import com.capstone.snapspot.core.formatDistance
import com.capstone.snapspot.core.isActive
import com.capstone.snapspot.core.isUrgent
import com.capstone.snapspot.core.untilLabel
import com.capstone.snapspot.ui.components.BigTitle
import com.capstone.snapspot.ui.components.ConfirmDelete
import com.capstone.snapspot.ui.components.Gap
import com.capstone.snapspot.ui.components.IconTap
import com.capstone.snapspot.ui.components.LargeShot
import com.capstone.snapspot.ui.components.NearChip
import com.capstone.snapspot.ui.components.PrimaryButton
import com.capstone.snapspot.ui.components.SecondaryButton
import com.capstone.snapspot.ui.home.HomeViewModel
import com.capstone.snapspot.ui.theme.SnapColors
import com.capstone.snapspot.ui.theme.SnapShapes
import com.capstone.snapspot.ui.theme.SnapSpace
import com.capstone.snapspot.ui.theme.SnapType

/** One coupon, sized for the counter: the screenshot (with its barcode) as large as the screen allows. */
@Composable
fun CouponScreen(vm: HomeViewModel, id: String, onClose: () -> Unit) {
    val items by vm.coupons.collectAsState()
    val win by vm.window.collectAsState()
    val here by vm.here.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val c = items.firstOrNull { it.coupon.id == id }?.coupon
    val now = System.currentTimeMillis()
    val active = c != null && isActive(c, now)
    val branches: List<Pair<String, Double>> = if (c == null) emptyList() else here?.let { at ->
        win?.branches?.values.orEmpty().filter { it.brandKey == brandKey(c.brand) }
            .map { it.name to distanceMeters(at, Coords(it.latitude, it.longitude)) }.sortedBy { it.second }.take(3)
    }.orEmpty()
    val nearest = branches.firstOrNull()?.takeIf { active && it.second <= BRANCH_RADIUS_M }

    Column(Modifier.fillMaxSize().background(SnapColors.surface).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTap(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", onClose)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                nearest?.let { (name, d) -> NearChip("지금 근처 · $name ${formatDistance(d)}") }
            }
            if (c != null) Box {
                IconTap(Icons.Filled.MoreVert, "더 보기", { menu = true })
                DropdownMenu(menu, { menu = false }, containerColor = SnapColors.surface, shape = SnapShapes.card) {
                    DropdownMenuItem(
                        text = { Text("쿠폰 삭제", style = SnapType.body.copy(color = SnapColors.danger)) },
                        onClick = { menu = false; confirmDelete = true },
                    )
                }
            }
        }
        if (c == null) {
            BigTitle(null, "쿠폰을 찾지 못했어요", Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Text("삭제했거나 다른 기기에서 추가한 쿠폰일 수 있어요.", style = SnapType.meta, modifier = Modifier.padding(horizontal = 20.dp))
            return@Column
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = SnapSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BigTitle(c.brand, c.benefit, Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (c.usedAt != null) "사용한 쿠폰이에요" else untilLabel(c.expiresAt) ?: "유효기간 없음",
                    style = SnapType.body.copy(color = SnapColors.ink2),
                    modifier = Modifier.weight(1f),
                )
                if (active) dDay(daysLeft(c, now))?.let {
                    Text(it, style = SnapType.title.copy(color = if (isUrgent(c, now)) SnapColors.urgent else SnapColors.ink))
                }
            }
            Box(Modifier.fillMaxWidth().clip(SnapShapes.card).background(SnapColors.bg).padding(12.dp)) {
                LargeShot(c.imageUri)
            }
            if (active && branches.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().clip(SnapShapes.card).background(SnapColors.bg).padding(horizontal = SnapSpace.card, vertical = 6.dp)) {
                    Text("가까운 지점", style = SnapType.label, modifier = Modifier.padding(vertical = 6.dp))
                    branches.forEach { (name, d) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, style = SnapType.body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Gap(8.dp)
                            if (d <= BRANCH_RADIUS_M) NearChip() else Text(formatDistance(d), style = SnapType.label)
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = SnapSpace.gutter, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(SnapSpace.gap),
        ) {
            if (active) {
                SecondaryButton("알림 테스트", { vm.testCoupon(c) }, Modifier.weight(1f))
                PrimaryButton("사용했어요", { vm.markUsed(c); onClose() }, Modifier.weight(1.4f))
            } else {
                SecondaryButton("쿠폰 삭제", { confirmDelete = true }, Modifier.fillMaxWidth())
            }
        }
    }

    if (confirmDelete && c != null) {
        ConfirmDelete(
            "${c.brand} 쿠폰 삭제", "이 쿠폰의 지점 알림도 함께 꺼져요.",
            onConfirm = { confirmDelete = false; onClose(); vm.deleteCoupon(c) },
            onDismiss = { confirmDelete = false },
        )
    }
}
```

- [ ] **Step 3: 전체 테스트와 빌드를 확인해요**

Run: `./gradlew :app:testDebugUnitTest --console=plain -q && ./gradlew :app:assembleDebug --console=plain -q`
Expected: 모두 통과(`StyleGuardTest` 초록), 빌드 성공

- [ ] **Step 4: 커밋해요**

```bash
git add app/src/main/java/com/capstone/snapspot/ui/coupon/CouponScreen.kt app/src/test/java/com/capstone/snapspot/ui/StyleGuardTest.kt
git commit -q -m "coupon: white counter screen, near chip on top, delete in the menu"
```

---

### Task 8: 현황 화면

**Files:**
- Modify: `app/src/main/java/com/capstone/snapspot/ui/status/StatusScreen.kt` (전체 교체)
- Modify: `app/src/test/java/com/capstone/snapspot/ui/StyleGuardTest.kt`: `legacy`에서 `"ui/status/StatusScreen.kt"`를 지워요.

**Interfaces:**
- Consumes: Task 2의 `BigTitle`, `Card`, `IconTap`, `Notice`, `SlotMeter`, Task 4의 `SpotMap(cornerRadius, maskColor)`
- Produces: `StatusScreen(vm, onBack, onAskPermissions, onLocationSettings)` 시그니처는 그대로예요.

- [ ] **Step 1: 감시 목록에서 빼고 실패하는지 확인해요**

`StyleGuardTest.kt`의 `legacy`에서 `"ui/status/StatusScreen.kt",` 줄을 지워요.

Run: `./gradlew :app:testDebugUnitTest --tests "com.capstone.snapspot.ui.StyleGuardTest" --console=plain -q`
Expected: FAIL. `ui/status/StatusScreen.kt:50 raw color` 줄(`BRAND_TONES`)과 `pre-refresh` 줄들이 나와요.

- [ ] **Step 2: `StatusScreen.kt`를 통째로 바꿔요**

```kotlin
package com.capstone.snapspot.ui.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.MAX_REGIONS
import com.capstone.snapspot.core.PLACE_GROUP
import com.capstone.snapspot.core.REFRESH_RADIUS_M
import com.capstone.snapspot.core.brandKey
import com.capstone.snapspot.core.placeEnded
import com.capstone.snapspot.ui.components.BigTitle
import com.capstone.snapspot.ui.components.Card
import com.capstone.snapspot.ui.components.IconTap
import com.capstone.snapspot.ui.components.Notice
import com.capstone.snapspot.ui.components.SlotMeter
import com.capstone.snapspot.ui.home.HomeViewModel
import com.capstone.snapspot.ui.map.MapBranch
import com.capstone.snapspot.ui.map.MapFocus
import com.capstone.snapspot.ui.map.MapPlace
import com.capstone.snapspot.ui.map.SpotMap
import com.capstone.snapspot.ui.theme.SnapColors
import com.capstone.snapspot.ui.theme.SnapSpace
import com.capstone.snapspot.ui.theme.SnapType

private fun ago(at: Long): String {
    val min = ((System.currentTimeMillis() - at) / 60_000).toInt()
    return when {
        min < 1 -> "방금"
        min < 60 -> "${min}분 전"
        else -> "${min / 60}시간 전"
    }
}

@Composable
fun StatusScreen(vm: HomeViewModel, onBack: () -> Unit, onAskPermissions: () -> Unit, onLocationSettings: () -> Unit) {
    val perms by vm.perms.collectAsState()
    val win by vm.window.collectAsState()
    val sync by vm.sync.collectAsState()
    val watching by vm.watching.collectAsState()
    val places by vm.places.collectAsState()
    val coupons by vm.coupons.collectAsState()
    val here by vm.here.collectAsState()
    val brandNames = coupons.associate { brandKey(it.coupon.brand) to it.coupon.brand }
    val now = System.currentTimeMillis()

    // Places in ink, coupon branches in lime-deep (urgent brands in orange), the refresh region in grey.
    val parts = buildList {
        add(Triple("저장한 장소", win?.byGroup?.get(PLACE_GROUP) ?: 0, SnapColors.ink))
        win?.byGroup.orEmpty().filterKeys { it != PLACE_GROUP }.forEach { (g, n) ->
            val urgent = win?.branches?.values?.any { it.brandKey == g && it.urgent } == true
            add(Triple("${brandNames[g] ?: g} 지점${if (urgent) " · 임박" else ""}", n, if (urgent) SnapColors.urgent else SnapColors.limeDeep))
        }
        if (win?.here != null) add(Triple("갱신 구역 (반경 ${(REFRESH_RADIUS_M / 1000).toInt()}km)", 1, SnapColors.muted.copy(alpha = 0.45f)))
    }
    val used = parts.sumOf { it.second }

    Column(Modifier.fillMaxSize().background(SnapColors.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) { IconTap(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", onBack) }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = SnapSpace.gutter).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(SnapSpace.gap),
        ) {
            BigTitle(
                if (perms.alertsOn) "앱을 닫아도" else "지금은",
                if (perms.alertsOn) "${watching}곳을 지켜보고 있어요" else "앱을 열어 둔 동안만 알려 드려요",
                Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp),
            )

            if (!(perms.notif && perms.fg && perms.bg)) Notice("앱을 닫아도 알림을 받으려면 알림 권한과 위치 \"항상 허용\"이 필요해요.", "권한 설정하기" to onAskPermissions)
            if (perms.bg && !perms.net) Notice("휴대폰의 \"Google 위치 정확도\"가 꺼져 있어서 앱을 닫으면 알림이 오지 않아요.", "위치 정확도 켜기" to onLocationSettings)
            sync?.error?.takeIf { perms.bg }?.let { Notice("지오펜스를 등록하지 못했어요: $it") }

            Card {
                Column(Modifier.padding(SnapSpace.card), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${MAX_REGIONS}칸 중 ${used}칸 사용", style = SnapType.title.copy(fontSize = 15.sp), modifier = Modifier.weight(1f))
                        Text("${maxOf(MAX_REGIONS - used, 0)}칸 남음", style = SnapType.meta)
                    }
                    SlotMeter(MAX_REGIONS, parts)
                    Text(
                        "휴대폰은 한 번에 ${MAX_REGIONS}곳까지만 지켜볼 수 있어요. 저장한 장소를 먼저, 그다음 유효기간이 임박한 쿠폰 순으로 담고, " +
                            "쿠폰은 브랜드마다 가장 가까운 7곳만 담아요. 지금 위치에서 ${(REFRESH_RADIUS_M / 1000).toInt()}km를 벗어나면 그 자리에서 다시 골라요.",
                        style = SnapType.meta,
                    )
                }
            }

            win?.here?.let { center ->
                SpotMap(
                    places = places.map { MapPlace(it.id, Coords(it.latitude, it.longitude), it.radius.toDouble(), it.name.take(1), placeEnded(it, now), false) },
                    branches = win?.branches.orEmpty().map { (id, b) -> MapBranch(id, Coords(b.latitude, b.longitude), b.name.take(1), b.urgent, false) },
                    here = here,
                    focus = MapFocus(center, 0, 14),
                    refresh = center to REFRESH_RADIUS_M,
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    cornerRadius = 10.dp,
                    maskColor = SnapColors.bg,
                )
            }

            Card {
                Column(Modifier.padding(SnapSpace.card), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("마지막으로 다시 고른 때", style = SnapType.label)
                    val w = win
                    if (w == null) {
                        Text("아직 고른 적이 없어요. 위치 권한을 허용하면 바로 시작해요.", style = SnapType.meta)
                    } else {
                        FactRow("시각", ago(w.at))
                        FactRow("카카오 지점 검색", if (w.requests == 0) "저장해 둔 결과 사용" else "${w.requests}회")
                        FactRow("기준 위치", if (w.here != null) "확인됨" else "아직 모름")
                    }
                }
            }
        }
    }
}

@Composable
private fun FactRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = SnapType.body.copy(color = SnapColors.ink2), modifier = Modifier.weight(1f))
        Text(value, style = SnapType.body.copy(fontWeight = FontWeight.Bold))
    }
}
```

- [ ] **Step 3: 전체 테스트와 빌드를 확인해요**

Run: `./gradlew :app:testDebugUnitTest --console=plain -q && ./gradlew :app:assembleDebug --console=plain -q`
Expected: 모두 통과(`StyleGuardTest` 초록), 빌드 성공

- [ ] **Step 4: 커밋해요**

```bash
git add app/src/main/java/com/capstone/snapspot/ui/status/StatusScreen.kt app/src/test/java/com/capstone/snapspot/ui/StyleGuardTest.kt
git commit -q -m "status: watching count as the headline, slot bar in ink and lime-deep"
```

---

### Task 9: 마무리 (대화상자, 알림 색, 옛 토큰 삭제, 스크린샷, README)

**Files:**
- Modify: `app/src/main/java/com/capstone/snapspot/ui/Nav.kt` (대화상자 두 개)
- Modify: `app/src/main/java/com/capstone/snapspot/notify/AndroidNotifier.kt:43` (알림 강조색)
- Modify: `app/src/main/java/com/capstone/snapspot/ui/theme/Theme.kt` (옛 토큰 삭제)
- Delete: `app/src/main/res/font/black_han_sans.ttf`
- Modify: `app/src/test/java/com/capstone/snapspot/ui/StyleGuardTest.kt` (`legacy`를 빈 목록으로)
- Modify: `README.md:154` (글꼴 안내)
- Modify: `docs/screenshots/*.png` (home, bundle-route, bundle-coupon, coupon-add, coupon-open, status, coupon-alert)

**Interfaces:**
- Consumes: 앞선 작업 전부
- Produces:
  - `legacy = emptySet()`
  - `SnapColors.{paper, green, greenSoft, highlight, amber, amberSoft}`, `SnapFonts.{display, body}`, `SnapType.{hero, distance}`가 사라져요.

- [ ] **Step 1: 감시 목록을 비우고 실패하는지 확인해요**

`StyleGuardTest.kt`의 `legacy`를 다음처럼 바꿔요.

```kotlin
    /** Files not moved to the refreshed design yet. Empty: the refresh is done; keep it that way. */
    private val legacy = emptySet<String>()
```

Run: `./gradlew :app:testDebugUnitTest --tests "com.capstone.snapspot.ui.StyleGuardTest" --console=plain -q`
Expected: FAIL, `ui/Nav.kt:195 pre-refresh color` and `ui/Nav.kt:206 pre-refresh color`

- [ ] **Step 2: `Nav.kt` 대화상자와 알림 강조색을 고쳐요**

```bash
F=app/src/main/java/com/capstone/snapspot/ui/Nav.kt
sed -i 's/SnapColors\.green/SnapColors.ink/g; s/^\(\s*\)containerColor = SnapColors\.surface,$/\1containerColor = SnapColors.surface,\n\1shape = SnapShapes.card,/' "$F"
sed -i 's/^import com\.capstone\.snapspot\.ui\.theme\.SnapColors$/import com.capstone.snapspot.ui.theme.SnapColors\nimport com.capstone.snapspot.ui.theme.SnapShapes/' "$F"
grep -n "SnapShapes\|SnapColors.ink" "$F"
```
Expected: import 1줄, `shape = SnapShapes.card,` 2줄, `SnapColors.ink` 2줄

`AndroidNotifier.kt`의 `.setColor(0xFF2D7A57.toInt())`를 `.setColor(SnapColors.ink.toArgb())`로 바꾸고, import 두 줄을 더해요.

```kotlin
import androidx.compose.ui.graphics.toArgb
import com.capstone.snapspot.ui.theme.SnapColors
```

- [ ] **Step 3: 옛 토큰과 Black Han Sans를 지워요**

`Theme.kt`에서 다음을 지워요.
- `// Pre-refresh colors…` 주석과 그 아래 6줄(`paper`, `green`, `greenSoft`, `highlight`, `amber`, `amberSoft`)
- `// Pre-refresh names…` 주석과 그 아래 `display`, `body` 2줄
- `// Pre-refresh styles…` 주석과 그 아래 `hero`, `distance` 2줄

그다음 글꼴 파일을 지워요.

```bash
git rm -q app/src/main/res/font/black_han_sans.ttf
grep -rn "black_han_sans\|plex_kr\|SnapColors\.\(paper\|green\|greenSoft\|highlight\|amber\|amberSoft\)\b\|SnapType\.\(hero\|distance\)\|SnapFonts\.\(display\|body\)" app/src || echo "clean"
```
Expected: `clean`

- [ ] **Step 4: 전체 테스트와 빌드를 확인해요**

Run: `./gradlew :app:testDebugUnitTest --console=plain -q && ./gradlew :app:assembleDebug --console=plain -q`
Expected: 모두 통과(`StyleGuardTest` 초록, legacy 없음), 빌드 성공

- [ ] **Step 5: README 글꼴 안내를 고쳐요**

`README.md` 154번째 줄 `글꼴은 Black Han Sans(제목, 거리)와 IBM Plex Sans KR(본문)을 앱에 내장했어요.`를 다음으로 바꿔요.

```
글꼴은 Pretendard(SIL Open Font License 1.1, `docs/licenses/pretendard-OFL.txt`)를 앱에 내장했어요.
```

`grep -n "docs/screenshots" README.md`로 README가 쓰는 스크린샷 파일 이름을 확인해요. 다음 단계에서 같은 이름으로 덮어써서 README의 이미지 링크는 그대로 둬요.

- [ ] **Step 6: 에뮬레이터에서 새 스크린샷을 찍어요**

```bash
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb"
D=docs/screenshots
./gradlew :app:installDebug --console=plain -q
"$ADB" shell monkey -p com.google.android.apps.maps 1 >/dev/null; sleep 3
"$ADB" emu geo fix 127.0582 37.5447
"$ADB" shell am start -n com.capstone.snapspot/.MainActivity; sleep 5
"$ADB" exec-out screencap -p > $D/home.png
# "[x1,y1][x2,y2]" bounds of the first node with this text → tap its center.
tap() {
  "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null
  b=$("$ADB" exec-out cat /sdcard/ui.xml | grep -o "text=\"$1\"[^>]*bounds=\"[^\"]*\"" | head -1 \
    | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | sed 's/\]\[/,/; s/[][]//g' | tr ',' ' ')
  set -- $b
  "$ADB" shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); sleep 2
}
tap "동선 보기"; "$ADB" exec-out screencap -p > $D/bundle-route.png
```

`tap` 함수가 좌표를 잘못 읽으면 bounds 문자열을 직접 보고 `input tap x y`로 눌러요. 확인 명령은 `"$ADB" exec-out cat /sdcard/ui.xml | grep -o 'text="동선 보기"[^>]*'`예요.

이어서 다음 순서로 찍어요. 화면마다 찍은 PNG를 Read로 열어서 글자 겹침, 잘림, 색을 확인해요.

| 파일 | 만드는 방법 |
|---|---|
| `bundle-coupon.png` | 동선 화면에서 "쿠폰 포함" 스위치를 누르고 2초 뒤 찍어요. 흰 네모 번호 핀이 보여야 해요. |
| `home.png` 재확인 | 왼쪽 위 "‹ … 코스" 칩을 눌러 목록으로 돌아와요. |
| `coupon-open.png` | "쿠폰 1" 탭, 쿠폰 행 순서로 눌러요. |
| `coupon-alert.png` | 쿠폰 화면에서 "알림 테스트"를 누르고 `"$ADB" shell cmd statusbar expand-notifications`를 실행한 뒤 찍어요. 그다음 `collapse`로 접어요. |
| `status.png` | 홈에서 "N곳 지켜보는 중" 칩을 눌러요. |
| `coupon-add.png` | 공유 시트로 쿠폰 이미지를 넣어 확인 화면이 뜨면 찍어요. 넣는 명령은 아래에 있어요. |

`coupon-add.png`용 이미지를 넣는 명령이에요. `docs/samples/`(추적하지 않는 폴더)에 있는 쿠폰 이미지를 써요.

```bash
"$ADB" push docs/samples/<쿠폰 이미지 파일> /sdcard/Pictures/coupon.png
"$ADB" shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/coupon.png
ID=$("$ADB" shell content query --uri content://media/external/images/media --projection _id:_display_name | grep "coupon.png" | grep -o "_id=[0-9]*" | cut -d= -f2)
"$ADB" shell am start -a android.intent.action.SEND -t image/png --eu android.intent.extra.STREAM "content://media/external/images/media/$ID" -n com.capstone.snapspot/.MainActivity
```

마지막으로 확인 화면이 뜨면 찍어요. 저장하지 않고 닫아요.

- [ ] **Step 7: 큰 글씨에서 확인해요 (Review Focus 1)**

```bash
S=C:/Users/hasan/AppData/Local/Temp/claude/C--Users-hasan-Downloads-capstone/a3f4fa50-d298-4b74-83c6-32cb357c2b8a/scratchpad
"$ADB" shell settings put system font_scale 1.3
"$ADB" shell input keyevent HOME; "$ADB" shell am kill com.capstone.snapspot
"$ADB" shell am start -n com.capstone.snapspot/.MainActivity; sleep 5
"$ADB" exec-out screencap -p > $S/home-large-font.png
"$ADB" shell settings put system font_scale 1.0
```

`home-large-font.png`에서 확인할 것:
- 위쪽 칩 두 개가 한 줄에 들어오는지
- 목록 행의 이름과 메타 줄이 말줄임(…)으로 끝나는지
- "지금 근처" 칩이 잘리지 않는지

문제가 있으면 해당 Text의 `maxLines`/`overflow`를 고치고 Step 4부터 다시 해요.

- [ ] **Step 8: 커밋해요**

```bash
git add -A app/src/main app/src/test README.md docs/screenshots
git status --short   # docs/samples/, set-env.log, .superpowers/ 는 올라가지 않아야 해요
git diff --cached | grep -E "[0-9a-f]{32}|AIza" && echo "KEY LEAK - STOP" || echo "no keys"
git commit -q -m "design: dialogs and notification accent in ink, drop pre-refresh tokens and fonts, new screenshots"
```
Expected: `no keys`
