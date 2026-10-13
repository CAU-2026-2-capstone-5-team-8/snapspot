# SnapSpot Kotlin 전환 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expo/TypeScript SnapSpot을 같은 기능(+ 우선순위 감시 창, 중복 방지)을 가진 Kotlin 네이티브 안드로이드 앱으로 다시 만든다.

**Architecture:** 단일 Gradle 모듈 `:app`. 안드로이드 의존성 없는 `core` 패키지(순수 로직, JVM 테스트)를 먼저 TS 테스트를 명세로 옮기고, 그 위에 `net`(OkHttp), `data`(JSON 파일 저장소), `geofence`/`notify`(Play Services + WorkManager), `ui`(Compose + Kakao Map SDK)를 쌓는다.

**Tech Stack:** Kotlin 2.2.20, AGP 8.13.0, Gradle 8.14.3, JDK 21, compileSdk 36 / minSdk 26, Jetpack Compose(BOM), Navigation Compose, kotlinx.serialization, kotlinx.coroutines, OkHttp 4, Play Services Location, WorkManager, Kakao Map SDK v2 2.15.2, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-10-13-kotlin-migration-design.md`

## Global Constraints

- 패키지/applicationId: `com.capstone.snapspot`. minSdk 26.
- `core/` 아래 파일은 `android.*`/`androidx.*` import 금지.
- 상수는 TS와 동일: 매칭 가중치 이름 0.6 / 주소 0.3 / 분류 0.1, `CONFIDENT = 0.7`, `MIN_AUTO_MATCH = 0.4`, `MIN_BRAND_SIMILARITY = 0.4`, `SEARCH_RADIUS_M = 2500`, `CELL_DEG = 0.01`, 지점 캐시 6h, 쿨다운 장소 6h / 브랜드 3h, `MAX_REGIONS = 90`, `BRANCH_RADIUS_M = 200`, `REFRESH_RADIUS_M = 1000`, 반경 선택지 100/200/300/500.
- 우선순위: 장소 → 임박 쿠폰(`daysLeft <= 3`) → 나머지 쿠폰(기한 빠른 순, 없으면 마지막). 브랜드당 가장 가까운 **7곳**.
- 키는 `local.properties` → `BuildConfig`(`GEMINI_API_KEY`, `GEMINI_MODEL` 기본 `gemini-3.5-flash-lite`, `KAKAO_REST_KEY`, `KAKAO_NATIVE_KEY`). 실제 키를 커밋하지 않는다.
- 사용자 문구는 TS 앱의 한국어 문구를 그대로 쓴다.
- 이 PC 메모: `./gradlew`는 ASCII 경로(`C:\Users\hasan\dev\snapspot`)에서 실행. 에뮬레이터는 `SnapSpot_API34`만 쓴다.

**스펙과의 차이(구현 중 결정):** 장소·쿠폰 저장은 Room 대신 kotlinx.serialization JSON 파일 + `Mutex`로 한다. 데이터가 수십 건 수준이고 지금 TS 앱도 통째로 읽고 쓰는 방식이라 동작이 같고, KSP 의존성을 피한다. 스펙 3절을 같은 커밋에서 고친다.

## Review Focus

1. 같은 지오펜스 진입 이벤트가 연달아 두 번 와도 알림은 한 번 → Task 7의 `AlertsTest`(직렬화 + 쿨다운)로 고정.
2. 위치를 아직 모를 때(첫 실행, 권한 거부) 저장·계획이 죽지 않음 → Task 4 `WindowTest` "위치 모를 때", Task 8 저장 경로에서 `here == null` 허용.
3. Gemini가 이상한 JSON(빈 응답, 잘못된 날짜, 모르는 kind)을 줘도 크래시 없이 기본값 → Task 6 `GeminiParseTest`.
4. 카카오 키가 없거나 4xx일 때 추가 화면이 멈추지 않고 기기 지오코더로 넘어감 → Task 6 `KakaoHttpTest` 오류 메시지, Task 8 `locate` 폴백.
5. 만료된 장소/사용한 쿠폰은 계획·지도에서 빠짐 → Task 4 `PlannerTest`.

---

### Task 1: Gradle 프로젝트 + core/Distance

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/*`, `gradlew`, `gradlew.bat`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/capstone/snapspot/MainActivity.kt` (빈 Compose 화면)
- Create: `app/src/main/java/com/capstone/snapspot/core/Distance.kt`
- Test: `app/src/test/java/com/capstone/snapspot/core/DistanceTest.kt`
- Modify: `.gitignore` (`/build`, `/app/build`, `.gradle/`, `local.properties`, `.kotlin/`), Create `local.properties.example`

**Interfaces — Produces:**
```kotlin
data class Coords(val latitude: Double, val longitude: Double)
fun distanceMeters(a: Coords, b: Coords): Double      // haversine, R = 6371000
fun formatDistance(m: Double): String                 // <1000 → "933m", else "2.1km"
```

- [ ] Step 1: 래퍼와 빌드 파일 생성(Gradle 8.14.3, 위 버전). Expo가 만든 `android/`는 gitignore 대상이며 건드리지 않는다.
- [ ] Step 2: 테스트 작성
```kotlin
class DistanceTest {
    @Test fun `seongsu to ttukseom is about 1km`() {
        val d = distanceMeters(Coords(37.5446, 127.0565), Coords(37.5474, 127.0473))
        assertTrue(d in 800.0..900.0, "got $d")
    }
    @Test fun formats() {
        assertEquals("933m", formatDistance(933.2))
        assertEquals("2.1km", formatDistance(2140.0))
    }
}
```
- [ ] Step 3: `./gradlew :app:testDebugUnitTest` → FAIL(미정의)
- [ ] Step 4: `src/distance.ts`를 그대로 옮겨 구현 → PASS, `./gradlew :app:assembleDebug` 성공
- [ ] Step 5: 커밋 `build: Kotlin Android project skeleton`

### Task 2: core/Match + core/Brand

**Files:** Create `core/Match.kt`, `core/Brand.kt`, `core/model/KakaoDoc.kt`; Test `core/MatchTest.kt`, `core/BrandTest.kt`

**Interfaces — Produces:**
```kotlin
@Serializable data class KakaoDoc(val id: String, val place_name: String, val category_name: String,
  val category_group_code: String = "", val address_name: String = "", val road_address_name: String = "",
  val x: String, val y: String, val place_url: String = "")
data class Extract(val name: String, val address: String, val category: String)
fun normalizeName(s: String): String
fun nameSimilarity(a: String, b: String): Double
fun addressSimilarity(extracted: String, doc: KakaoDoc): Double?
fun categorySimilarity(category: String, doc: KakaoDoc): Double?
fun scoreCandidate(doc: KakaoDoc, ex: Extract): Double
fun nameVariants(name: String): List<String>
fun regionHint(address: String): String
fun brandKey(brand: String): String
fun pickBranches(brand: String, docs: List<KakaoDoc>): List<KakaoDoc>
```

- [ ] Step 1: `src/match.test.ts` 8개 케이스와 `src/brand.test.ts` 5개 케이스를 같은 입력·기대값으로 JUnit에 옮긴다(케이스 이름 유지).
- [ ] Step 2: 실행 → FAIL
- [ ] Step 3: `src/match.ts`, `src/brand.ts`를 1:1로 옮긴다. 정규식 `[\s\p{P}\p{S}]+`는 Kotlin `Regex("[\\s\\p{P}\\p{S}]+")`, 끝의 `점` 제거 동일.
- [ ] Step 4: 실행 → PASS
- [ ] Step 5: 커밋 `feat(core): place matching and brand detection`

### Task 3: core 모델 + Coupons + Alerts + Dedup

**Files:** Create `core/model/Place.kt`, `core/model/Coupon.kt`, `core/model/Extracted.kt`, `core/Coupons.kt`, `core/Alerts.kt`, `core/Dedup.kt`; Test `CouponsTest.kt`, `AlertsTest.kt`(순수 부분), `DedupTest.kt`

**Interfaces — Produces:**
```kotlin
@Serializable data class Place(val id: String, val name: String, val address: String, val category: String,
  val note: String, val endDate: String?, val latitude: Double, val longitude: Double, val radius: Int,
  val imageUri: String?, val kakaoId: String? = null, val placeUrl: String? = null,
  val createdAt: Long, val lastNotifiedAt: Long? = null)
@Serializable data class Coupon(val id: String, val brand: String, val benefit: String, val expiresAt: String?,
  val imageUri: String?, val createdAt: Long, val usedAt: Long? = null, val lastNotifiedAt: Long? = null)
@Serializable data class Extracted(val kind: String, val brand: String, val benefit: String, val isPlace: Boolean,
  val name: String, val address: String, val category: String, val note: String, val endDate: String?)
const val BRAND_COOLDOWN_MS = 3 * 60 * 60 * 1000L
const val PLACE_COOLDOWN_MS = 6 * 60 * 60 * 1000L
const val URGENT_DAYS = 3
fun isActive(c: Coupon, now: Long): Boolean
fun daysLeft(c: Coupon, now: Long): Int?
fun isUrgent(c: Coupon, now: Long): Boolean          // isActive && daysLeft != null && daysLeft <= 3
fun couponsForBrand(coupons: List<Coupon>, key: String, now: Long): List<Coupon>
fun brandCoolingDown(coupons: List<Coupon>, key: String, now: Long): Boolean
fun placeEnded(p: Place, now: Long): Boolean
fun suppressReason(p: Place, now: Long): String?     // "운영 종료" | "최근에 알림" | null
fun placeAlertText(p: Place): Pair<String, String>   // ("📍 저장해 둔 카페 근처예요", "어니언 성수 · 소금빵 맛집")
fun couponAlertText(branchName: String, coupons: List<Coupon>, now: Long): Pair<String, String>
fun dDay(days: Int?): String?                        // null | "기간 지남" | "오늘까지" | "D-n"
const val SAME_PLACE_M = 50.0
fun findDuplicatePlace(existing: List<Place>, name: String, kakaoId: String?, at: Coords): Place?
fun findDuplicateCoupon(existing: List<Coupon>, brand: String, benefit: String, expiresAt: String?): Coupon?
```
날짜 처리: `endOfDay("YYYY-MM-DD")`는 기기 시간대(`ZoneId.systemDefault()`) 23:59:59.999. 테스트는 `TimeZone`을 `Asia/Seoul`로 고정한 `now`를 쓴다.

- [ ] Step 1: `src/coupon.test.ts` 4개 케이스 옮김. 추가 테스트:
```kotlin
@Test fun `urgent within 3 days only`() {
    assertTrue(isUrgent(c(expiresAt = "2026-10-13"), now))   // now = 2026-10-10 12:00 KST
    assertFalse(isUrgent(c(expiresAt = "2026-10-14"), now))
    assertFalse(isUrgent(c(expiresAt = "2026-10-11", usedAt = 1), now))
}
@Test fun `coupon alert shows D-day for urgent coupons`() {
    val (title, body) = couponAlertText("스타벅스 뚝섬역", listOf(c(benefit = "아메리카노", expiresAt = "2026-10-12")), now)
    assertEquals("🎟️ 스타벅스 뚝섬역 근처예요", title)
    assertEquals("스타벅스 아메리카노 · 10월 12일까지 · D-2", body)
}
```
`AlertsTest`: `suppressReason`(종료일 지난 날 → "운영 종료", 6시간 안 → "최근에 알림", 그 외 null), `placeAlertText`(메모 없으면 이름만).
`DedupTest`:
```kotlin
@Test fun `same kakao id is the same place`()
@Test fun `same normalized name within 50m is the same place`()      // "어니언 성수점" vs "어니언 성수", 30m
@Test fun `same name 200m apart is a different place`()
@Test fun `coupon duplicate needs brand, benefit and expiry to match and ignores used ones`()
// "스타 벅스"/"아메리카노  Tall" 공백 차이는 같음, 만료일 다르면 다름, usedAt != null 기존 쿠폰은 무시
```
- [ ] Step 2: 실행 → FAIL
- [ ] Step 3: `src/coupon.ts`, `src/notify.ts`의 순수 부분 옮김 + `isUrgent`, `Dedup` 구현
- [ ] Step 4: PASS
- [ ] Step 5: 커밋 `feat(core): coupon rules, alert text, duplicate detection (#1)`

### Task 4: core/Window(우선순위) + core/Planner

**Files:** Create `core/Window.kt`, `core/Planner.kt`; Test `WindowTest.kt`, `PlannerTest.kt`

**Interfaces — Produces:**
```kotlin
const val REFRESH_ID = "refresh"
const val BRANCHES_PER_BRAND = 7
enum class Tier { PLACE, URGENT, NORMAL }
data class Spot(val id: String, val group: String, val tier: Tier, val latitude: Double, val longitude: Double,
  val radius: Double, val rank: Int = 0)            // rank: 같은 계층 안 그룹 순서(작을수록 먼저, 기한 빠른 브랜드)
data class Region(val identifier: String, val latitude: Double, val longitude: Double, val radius: Double,
  val notifyOnEnter: Boolean, val notifyOnExit: Boolean)
data class Plan(val regions: List<Region>, val byGroup: Map<String, Int>)
fun planRegions(here: Coords?, spots: List<Spot>, slots: Int, refreshRadius: Double,
  perGroupCap: Int = BRANCHES_PER_BRAND): Plan     // PLACE 그룹에는 cap 미적용
// Planner: 저장 데이터 → Spot 목록
data class Branch(val kakaoId: String, val name: String, val address: String, val latitude: Double, val longitude: Double)
fun placeSpots(places: List<Place>, now: Long): List<Spot>                      // 종료 장소 제외, group "place"
fun brandOrder(coupons: List<Coupon>, now: Long): List<Pair<String, Tier>>      // (brandKey, tier), 활성 쿠폰만, 순서 = rank
fun branchSpotId(key: String, kakaoId: String) = "b|$key|$kakaoId"
```
배분 알고리즘: 갱신 구역(위치 알 때) → 계층 PLACE를 가까운 순으로 칸이 찰 때까지 → URGENT 그룹들을 rank 순서로 돌아가며 한 곳씩(그룹당 최대 cap, 가까운 순) → NORMAL 같은 방식.

- [ ] Step 1: 테스트 작성. TS의 갱신 구역 / 칸 상한 / 그룹 내 가까운 순 / 위치 모를 때 4개 케이스를 옮기고, 균등 배분 케이스를 아래로 교체:
```kotlin
@Test fun `saved places fill slots before any coupon branch`() {
    val spots = line("place", 5, 5000, Tier.PLACE) + line("A", 50, tier = Tier.NORMAL)
    val p = planRegions(here, spots, slots = 4, refreshRadius = 1000.0)
    assertEquals(mapOf("place" to 3), p.byGroup)
}
@Test fun `urgent brand goes before normal brands`() {
    val spots = line("A", 10, tier = Tier.NORMAL) + line("U", 10, 300, Tier.URGENT)
    val p = planRegions(here, spots, slots = 6, refreshRadius = 1000.0)
    assertEquals(mapOf("U" to 5), p.byGroup)   // cap 7이지만 칸 5개뿐
}
@Test fun `each brand gets at most 7 nearest branches`() {
    val p = planRegions(here, line("A", 30, tier = Tier.NORMAL), slots = 90, refreshRadius = 1000.0)
    assertEquals(7, p.byGroup["A"])
    assertEquals((0 until 7).map { "A-$it" }, p.regions.drop(1).map { it.identifier })
}
@Test fun `brands in the same tier share slots round-robin`() {
    val spots = line("A", 10, tier = Tier.NORMAL, rank = 0) + line("B", 10, 300, Tier.NORMAL, rank = 1)
    val p = planRegions(here, spots, slots = 6, refreshRadius = 1000.0)
    assertEquals(mapOf("A" to 3, "B" to 2), p.byGroup)
}
```
`PlannerTest`: 종료 장소 제외, 사용/만료 쿠폰 브랜드 제외, 같은 브랜드 쿠폰 두 장이면 한 그룹, 임박 쿠폰이 하나라도 있으면 브랜드 tier=URGENT, rank는 가장 빠른 만료일 순(기한 없음 마지막).
- [ ] Step 2: FAIL
- [ ] Step 3: 구현
- [ ] Step 4: PASS
- [ ] Step 5: 커밋 `feat(core): priority geofence window (places first, urgent coupons, 7 per brand)`

### Task 5: core/Branches + core/PlaceSearch (카카오 로직, 전송 계층 주입)

**Files:** Create `core/KakaoApi.kt`, `core/Branches.kt`, `core/PlaceSearch.kt`; Test `BranchesTest.kt`, `PlaceSearchTest.kt`

**Interfaces — Produces:**
```kotlin
@Serializable data class AddressDoc(val address_name: String, val x: String, val y: String,
  val road_address: RoadAddress? = null) { @Serializable data class RoadAddress(val address_name: String) }
interface KakaoApi {                                 // 실패 시 KakaoException(message = "카카오 검색 실패 (status): ...")
    suspend fun keyword(params: Map<String, String>): List<KakaoDoc>
    suspend fun address(query: String): List<AddressDoc>
}
class KakaoException(message: String) : Exception(message)
const val SEARCH_RADIUS_M = 2500; const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
data class Cell(val key: String, val center: Coords)
fun cellOf(p: Coords): Cell
@Serializable data class CachedBranches(val at: Long, val branches: List<Branch>)   // Branch는 @Serializable
interface BranchCache { suspend fun get(key: String): CachedBranches?; suspend fun set(key: String, value: CachedBranches) }
class MemoryBranchCache : BranchCache
data class BranchResult(val branches: List<Branch>, val requests: Int)
suspend fun nearbyBranches(brand: String, here: Coords, api: KakaoApi, cache: BranchCache, now: Long): BranchResult
const val CONFIDENT = 0.7
data class Candidate(val kakaoId: String?, val name: String, val address: String, val category: String,
  val latitude: Double, val longitude: Double, val placeUrl: String?, val score: Double, val source: String)
suspend fun searchPlace(ex: Extract, api: KakaoApi): List<Candidate>
```
- [ ] Step 1: `src/branches.test.ts` 3개, `src/kakao.test.ts` 2개 케이스를 가짜 `KakaoApi`(질의 기록)로 옮김
- [ ] Step 2: FAIL
- [ ] Step 3: `src/branches.ts`, `src/kakao.ts`의 `searchPlace` 옮김(페이지 최대 3, 15개 미만이면 중단, 주소 후보 점수 0.65)
- [ ] Step 4: PASS
- [ ] Step 5: 커밋 `feat(core): Kakao branch search and place search`

### Task 6: net — KakaoHttp, GeminiClient

**Files:** Create `net/KakaoHttp.kt`, `net/GeminiClient.kt`; Test `net/KakaoHttpTest.kt`, `net/GeminiParseTest.kt` (MockWebServer)

**Interfaces — Produces:**
```kotlin
class KakaoHttp(private val key: String, private val client: OkHttpClient,
  private val base: String = "https://dapi.kakao.com/v2/local/search") : KakaoApi
class GeminiClient(private val key: String, private val model: String, private val client: OkHttpClient,
  private val base: String = "https://generativelanguage.googleapis.com/v1beta") {
    suspend fun extract(base64Jpeg: String): Extracted
}
fun parseExtracted(text: String): Extracted          // 정규화: kind ∉ {place,coupon} → place, 날짜 형식 아니면 null, 빈 category → "기타", trim
```
- [ ] Step 1: 테스트: Kakao 헤더 `Authorization: KakaoAK k`, 쿼리 파라미터 인코딩(한글), 401이면 `KakaoException("카카오 검색 실패 (401): ...")`. Gemini: 정상 응답 파싱, `endDate: "10월 31일"` → null, `kind: "gift"` → "place", candidates 비면 `IllegalStateException("Gemini 응답이 비어 있어요.")`, 키 없으면 TS와 같은 안내 메시지(키 이름만 `local.properties의 GEMINI_API_KEY`로).
- [ ] Step 2: FAIL
- [ ] Step 3: 구현(프롬프트·스키마는 `src/gemini.ts` 그대로, `temperature 0`, `responseMimeType application/json`)
- [ ] Step 4: PASS
- [ ] Step 5: 커밋 `feat(net): Kakao and Gemini HTTP clients`

### Task 7: data + notify + geofence (백그라운드)

**Files:** Create `data/JsonStore.kt`, `data/Repository.kt`, `data/FileBranchCache.kt`, `data/WindowState.kt`, `notify/Notifier.kt`, `notify/AlertService.kt`, `geofence/GeofenceSync.kt`, `geofence/GeofenceReceiver.kt`, `geofence/GeofenceWorker.kt`, `geofence/BootReceiver.kt`, `AppContainer.kt`, `SnapSpotApp.kt`; Modify `AndroidManifest.xml`; Test `data/RepositoryTest.kt`, `notify/AlertServiceTest.kt` (JVM, 임시 디렉터리 + 가짜 Notifier)

**Interfaces — Produces:**
```kotlin
@Serializable data class WindowState(val at: Long, val here: Coords?, val slots: Int, val byGroup: Map<String, Int>,
  val branches: Map<String, WindowBranch>, val requests: Int)
@Serializable data class WindowBranch(val name: String, val brandKey: String, val latitude: Double, val longitude: Double,
  val urgent: Boolean)
class Repository(dir: File) {                        // 모든 쓰기는 Mutex 안에서 load→modify→save
    val places: StateFlow<List<Place>>; val coupons: StateFlow<List<Coupon>>; val window: StateFlow<WindowState?>
    suspend fun upsertPlace(p: Place); suspend fun removePlace(id: String); suspend fun markPlaceNotified(id: String, at: Long)
    suspend fun upsertCoupon(c: Coupon); suspend fun removeCoupon(id: String)
    suspend fun updateCoupons(ids: List<String>, f: (Coupon) -> Coupon); suspend fun saveWindow(w: WindowState)
}
interface Notifier { fun show(id: Int, title: String, body: String, deepLink: String) }
class AlertService(repo: Repository, notifier: Notifier, clock: () -> Long) {   // 전역 Mutex로 직렬화
    suspend fun onPlaceEnter(placeId: String, force: Boolean = false): Boolean
    suspend fun onBranchEnter(regionId: String, force: Boolean = false): Boolean
}
class GeofenceSync(ctx: Context, repo: Repository, api: KakaoApi, cache: BranchCache, clock: () -> Long) {
    suspend fun sync(here: Coords?): Plan            // Planner → nearbyBranches(브랜드당 정렬 후) → planRegions(slots 90) → saveWindow → GeofencingClient
}
```
딥링크: 쿠폰 `snapspot://coupon/{id}`, 장소 `snapspot://place/{id}`.

- [ ] Step 1: 테스트: `RepositoryTest` 저장 후 새 인스턴스에서 다시 읽힘, 파일 손상 시 빈 목록. `AlertServiceTest`:
```kotlin
@Test fun `two enter events at once alert once`() = runTest {
    repo.upsertPlace(place)
    val r = listOf(async { svc.onPlaceEnter(place.id) }, async { svc.onPlaceEnter(place.id) }).awaitAll()
    assertEquals(listOf(true, false), r.sorted().reversed())
    assertEquals(1, notifier.shown.size)
}
@Test fun `branch alert uses window branch name and cools the brand down`()
@Test fun `ended place does not alert`()
```
- [ ] Step 2: FAIL
- [ ] Step 3: 구현. `GeofenceReceiver`는 `GeofencingEvent.fromIntent` → `OneTimeWorkRequest`(expedited, 입력: id 목록 + transition)만. `GeofenceWorker`: ENTER → `AlertService`, `refresh` EXIT → `FusedLocation.lastLocation`(1분 이내) 또는 `getCurrentLocation(BALANCED)` → `sync`. `BootReceiver`: 마지막 `WindowState.here`로 `sync`. 알림 채널 `nearby-places`, 이름 "근처 저장 장소 알림", IMPORTANCE_HIGH. 매니페스트 권한: `ACCESS_FINE/COARSE/BACKGROUND_LOCATION`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `INTERNET`.
- [ ] Step 4: PASS + `assembleDebug`
- [ ] Step 5: 커밋 `feat: storage, alerts and background geofencing`

### Task 8: 추가 흐름 (image + AddViewModel)

**Files:** Create `image/Screenshots.kt`, `ui/add/AddViewModel.kt`, `ui/add/Draft.kt`, `geo/DeviceGeocoder.kt`; Test `ui/add/AddLogicTest.kt`

**Interfaces — Produces:**
```kotlin
enum class Stage { READING, LOCATING, READY }
data class Draft(val stage: Stage, val kind: String, val brand: String = "", val benefit: String = "", val imageUri: String,
  val name: String = "", val address: String = "", val category: String = "", val note: String = "", val endDate: String = "",
  val coords: Coords? = null, val isPlace: Boolean = true, val branches: List<Branch>? = null, val matched: Candidate? = null,
  val candidates: List<Candidate> = emptyList(), val radius: Int = 200)
sealed interface SaveResult { data class Saved(val kind: String, val id: String) : SaveResult
  data class DuplicatePlace(val existing: Place) : SaveResult; data class DuplicateCoupon(val existing: Coupon) : SaveResult
  data object NotReady : SaveResult }
suspend fun locate(name: String, address: String, category: String, api: KakaoApi?, geocode: suspend (String, String) -> Coords?): Located
fun buildPlace(d: Draft, now: Long): Place?; fun buildCoupon(d: Draft, now: Long): Coupon?
class AddViewModel : ViewModel { val draft: StateFlow<Draft?>; fun start(uri: Uri); fun update(f: (Draft) -> Draft)
  fun setKind(kind: String); fun regeocode(); fun pickCandidate(c: Candidate); fun pinToHere()
  suspend fun save(allowDuplicate: Boolean = false): SaveResult }
```
- [ ] Step 1: 테스트(`locate`와 `buildPlace/buildCoupon` 순수 부분): 최고 점수 0.4 이상이면 그 좌표, 아니면 지오코더, 카카오 예외면 지오코더, 잘못된 날짜 문자열은 null, 이름 비면 "이름 없는 장소", 혜택 비면 "쿠폰".
- [ ] Step 2: FAIL
- [ ] Step 3: 구현. `Screenshots.prepare(uri)`: 긴 변 1024px로 줄여 `filesDir/shots/<uuid>.jpg`(품질 80) 저장, base64 반환. 저장 전 `findDuplicatePlace/Coupon` 검사.
- [ ] Step 4: PASS
- [ ] Step 5: 커밋 `feat: add flow (screenshot → Gemini → Kakao → save, duplicate check)`

### Task 9: UI — 테마, 메인 화면(지도 카드 + 목록), 지도

**Files:** Create `ui/theme/Theme.kt`, `ui/theme/Type.kt`, `res/font/*.ttf`(TS 앱 `node_modules/@expo-google-fonts`에서 복사), `ui/map/SpotMap.kt`, `ui/home/HomeScreen.kt`, `ui/home/HomeViewModel.kt`, `ui/components/*.kt`, `ui/Nav.kt`; Modify `MainActivity.kt`, `SnapSpotApp.kt`(`KakaoMapSdk.init`)

- 색: paper `#F2F3EE`, surface `#FFFFFF`, ink `#1F2B2A`, muted `#6C7774`, line `#D9DED6`, green `#2D7A57`, greenSoft `#E3EFE7`, highlight `#FFE34D`, amber `#8A5A0A`, amberSoft `#FBF0D4`, 주황 테두리(임박) `#E07B00`.
- `SpotMap(here, places, branches, focus, refresh: Coords?/radius?, onPinClick)`: `AndroidView { MapView }` + `start(lifecycle, KakaoMapReadyCallback)`. 장소마다 `ShapeManager`로 원(채움 green 15% 알파, 테두리 green 1.5dp, 종료 장소는 muted) + 라벨 핀, 지점은 작은 점 라벨(이니셜, 임박은 주황 테두리, 근처는 노랑). 생명주기 resume/pause 연결.
- `HomeScreen`: 상단 바(로고 + "● N곳 감시 ›" → status), 지도 카드(16dp 여백, 24dp 모서리, 높이 55%, ⌖ 버튼), 세그먼트 `장소 n`/`쿠폰 n`(기본 장소), 장소 행(이름/분류/거리, 지금 근처면 노랑 띠), 쿠폰 행(티켓 카드, D-day, 임박 맨 위), 빈 상태 문구 "스크린샷 한 장으로 시작해 보세요", FAB(+ → Photo Picker → add). 행 탭 → 지도 이동, 핀 탭 → 목록 스크롤, 길게 누르면 삭제 확인 다이얼로그(TS 문구).
- 권한 흐름: 첫 실행 시 위치 → 알림 → 백그라운드 위치(설명 다이얼로그 후), 앱 복귀 시 재확인. 위치 업데이트(25m 간격)로 `here` 갱신 + 반경 진입 시 `AlertService.onPlaceEnter`.

- [ ] Step 1: `assembleDebug` 후 에뮬레이터(SnapSpot_API34)에 설치, 키 해시 로그(`Utility.getHashKey`)를 출력해 사용자에게 전달
- [ ] Step 2: 구현 후 스크린샷으로 확인: 지도 카드 여백·모서리, 반경 원, 목록, 빈 상태
- [ ] Step 3: 커밋 `feat(ui): home screen with padded map card and alert radius circles`

### Task 10: UI — 추가, 현황, 쿠폰 상세 + 알림 딥링크

**Files:** Create `ui/add/AddScreen.kt`, `ui/status/StatusScreen.kt`, `ui/coupon/CouponScreen.kt`; Modify `ui/Nav.kt`, `MainActivity.kt`(딥링크 intent 처리)

- 추가 화면: TS `add.tsx`와 같은 단계·문구(읽는 중 하이라이트 애니메이션, 장소/쿠폰 세그먼트, 필드, 매칭 박스, 다른 후보, "주소로 다시 찾기"/"지금 위치로", 반경 세그먼트 + 미리보기 지도에 원 반영, 쿠폰 지점 미리보기). 중복이면 다이얼로그: 장소 "이미 저장한 장소예요" [기존 것 보기]/[취소], 쿠폰 "같은 쿠폰이 이미 있어요" [기존 것 보기]/[그래도 추가]/[취소].
- 현황: TS `status.tsx` 문구에서 "고르게 나눠 담고" → "저장한 장소를 먼저, 그다음 임박한 쿠폰 순으로 담고". SlotMeter, 갱신 구역이 보이는 작은 지도, 권한/위치 정확도 안내.
- 쿠폰 상세: TS `coupon/[id].tsx` 그대로(사용했어요, 알림 테스트, 삭제, 가까운 지점 3곳).
- [ ] Step 1: 구현
- [ ] Step 2: 에뮬레이터에서 eval 이미지(`eval/cafe_post.jpg`, `eval/popup_poster.jpg`)로 추가 → 저장 → 같은 이미지 재저장 시 중복 다이얼로그 확인
- [ ] Step 3: 커밋 `feat(ui): add, status and coupon screens`

### Task 11: 시뮬레이터 + 문서 + CI

**Files:** Create `app/src/test/java/com/capstone/snapspot/sim/SimulateWindow.kt`(main), Gradle 태스크 `simulateWindow`(JavaExec, test classpath), `.github/workflows/android.yml`; Modify `docs/simulation.md`, `README.md`, 스펙 3절(Room → JSON)

- 시뮬레이터: `scripts/simulate-window.ts`를 옮김(같은 mulberry32 시드 20261010, 같은 도시·이동). 장소는 Tier.PLACE, 브랜드 3개는 rank 0..2 NORMAL. 표 행: 기존 전략 행 + "우선순위 · 브랜드당 7곳 (지금 설정)", "우선순위 · 브랜드당 3곳". `--write`면 `docs/simulation.md` 갱신.
- CI: ubuntu, JDK 21, `./gradlew testDebugUnitTest assembleDebug`(키 없이도 빌드되게 BuildConfig 기본값 "").
- README: 실행 방법(Android Studio / `./gradlew installDebug`), `local.properties` 키, 카카오 콘솔 설정(패키지명, 키 해시, 카카오맵 사용 설정), 구조, 우선순위 설명, 시뮬레이션 표 갱신.
- [ ] Step 1: `./gradlew simulateWindow --args=--write` 실행, 수치 확인
- [ ] Step 2: 커밋 `docs: simulation with priority window, README for Kotlin app, CI`

### Task 12: 에뮬레이터 종단 확인 + Expo 제거 + PR

- [ ] Step 1: README 시나리오: 압구정 한강변 모의 위치에서 스타벅스 쿠폰 저장 → 앱 강제 종료 → `adb emu geo fix`로 성수동까지 단계 이동 → 알림 수신 확인(스크린샷 `docs/screenshots/` 갱신)
- [ ] Step 2: Expo 파일 제거: `src/`, `index.ts`, `package.json`, `package-lock.json`, `app.json`, `eas.json`, `tsconfig.json`, `scripts/*.ts`, `.claude/`의 Expo 전용 설정 여부 확인 후 판단, `AGENTS.md`는 Kotlin 지침으로 교체. `eval/`, `docs/` 유지.
- [ ] Step 3: `./gradlew testDebugUnitTest assembleDebug` 통과 확인, 커밋 `chore: remove Expo app`
- [ ] Step 4: `git push -u origin kotlin`, PR 생성(머지는 팀 결정). 본문에 이슈 #1 해결, `storage.ts` → `data/Repository.kt` 안내.
