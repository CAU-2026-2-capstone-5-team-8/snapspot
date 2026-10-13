# SnapSpot Kotlin 전환 설계

- 날짜: 2026-10-13
- 브랜치: `kotlin` (기능 동등 달성 후 PR 하나로 `master`에 머지, 같은 PR에서 Expo 코드 제거)
- 관련 이슈: #1 같은 지점이 중복 저장됨 (이번 범위에 포함), #2 스크린샷 자동 인식 (범위 밖)

## 1. 목표와 범위

SnapSpot(Expo SDK 57 / React Native, TypeScript 약 3,600줄)을 **Kotlin 네이티브 안드로이드 앱**으로 다시 만든다.

**앱의 목적**: 중요한 장소를 스크린샷으로 찍어 두면 그 근처를 지날 때 알려 준다. 쿠폰(기프티콘·프랜차이즈)도 다루지만 **개인이 저장한 장소가 항상 먼저**이고, 쿠폰은 그다음이다. 단, 유효기간이 임박한 쿠폰은 다른 쿠폰보다 먼저 챙긴다(5.4절).

포함:
- 지금 앱의 모든 기능: 스크린샷 → Gemini 추출(장소/쿠폰) → 카카오 매칭 → 저장, 움직이는 지오펜스 창, 앱이 꺼져 있어도 오는 알림, 지도·목록·쿠폰함·현황, 추가 화면, 쿠폰 상세(화면 구성은 7절대로 정리).
- 이슈 #1 중복 저장 방지.
- 지도: Leaflet + OSM WebView → **Kakao Map Android SDK v2**.

제외:
- 이슈 #2(스크린샷 자동 감지·공유 시트). 팀 협의 후 별도 진행.
- iOS. 안드로이드 전용이다.
- 기존 AsyncStorage 데이터 이전. 프로토타입이라 실사용자가 없다.

성공 기준:
1. TS 테스트(`brand`, `branches`, `coupon`, `kakao`, `match`, `window`)의 모든 케이스가 Kotlin JUnit으로 옮겨져 통과한다.
2. Kotlin으로 옮긴 시뮬레이터가 새 우선순위 규칙(5.4절)으로 돌아가고, 브랜드당 지점 수 K=3/5일 때의 놓친 방문 비율을 `docs/simulation.md`에 기록한다(같은 시드면 같은 수치).
3. 에뮬레이터(SnapSpot_API34)에서 README의 시나리오가 재현된다: 압구정에서 스타벅스 쿠폰 저장 → 앱 완전 종료 → 성수동까지 모의 위치 이동 → 출발 때 등록돼 있지 않던 지점의 알림 수신.
4. 같은 스크린샷을 두 번 저장해도 목록과 지오펜스에 항목이 하나만 남는다(쿠폰은 사용자가 "그래도 추가"를 고른 경우만 예외).

## 2. 접근

**단일 Gradle 모듈 `:app`**, 그 안에서 패키지로 경계를 나눈다. 3,600줄 규모에서 멀티 모듈은 과하고, KMP는 iOS 계획이 없어 불필요하다.

```
com.capstone.snapspot
├─ core/        순수 Kotlin. android.* import 금지. JVM 단위 테스트 대상
│  ├─ Distance.kt     distanceMeters, formatDistance, Coords
│  ├─ Match.kt        이름/주소/분류 유사도, scoreCandidate, nameVariants, regionHint
│  ├─ Brand.kt        brandKey, pickBranches
│  ├─ Branches.kt     cellOf, nearbyBranches(캐시·fetcher 주입)
│  ├─ Window.kt       planRegions(우선순위 계층 + 브랜드당 K곳), REFRESH_ID
│  ├─ Coupons.kt      isActive, daysLeft, couponsForBrand, brandCoolingDown
│  ├─ Alerts.kt       suppressReason, 알림 문구 생성
│  ├─ Dedup.kt        findDuplicatePlace, findDuplicateCoupon (이슈 #1)
│  └─ model/          Place, Coupon, Extracted, Branch, Region, Plan
├─ net/         OkHttp + kotlinx.serialization
│  ├─ KakaoClient.kt  Local 키워드 검색(페이지네이션, 최대 3페이지)
│  └─ GeminiClient.kt generateContent, 프롬프트·스키마는 TS와 동일
├─ data/
│  ├─ db/             Room: PlaceEntity, CouponEntity, DAO
│  ├─ WindowStore.kt  DataStore(JSON): 등록된 감시 창 상태
│  ├─ BranchCacheStore.kt DataStore(JSON): 격자 칸별 지점 캐시(6시간)
│  └─ Repository.kt   화면·백그라운드가 공유하는 단일 진입점
├─ geofence/
│  ├─ GeofenceSync.kt     syncGeofences: 계획 → GeofencingClient 등록
│  ├─ GeofenceReceiver.kt 이벤트 수신 → WorkManager 위임
│  ├─ GeofenceWorker.kt   Enter → 알림 / refresh Exit → 위치 획득 후 재계획
│  └─ BootReceiver.kt     재부팅·앱 업데이트 후 재등록
├─ notify/      채널 생성, 장소·쿠폰 알림, Mutex로 직렬화
├─ image/       Photo Picker 결과를 1024px JPEG로 줄여 앱 저장소에 복사
└─ ui/          Compose + Navigation, ViewModel
   ├─ theme/  home/  map/(지도 카드)  status/  add/  coupon/
```

`core`의 각 파일은 대응하는 TS 파일의 동작을 그대로 옮긴다. 예외는 `Window.kt`의 영역 배분 규칙으로, 그룹 간 균등 배분(round-robin) 대신 5.4절의 우선순위 규칙을 따른다. 상수(가중치 0.6/0.3/0.1, `CONFIDENT = 0.7`, `MIN_AUTO_MATCH = 0.4`, `MIN_BRAND_SIMILARITY = 0.4`, `SEARCH_RADIUS_M = 2500`, `CELL_DEG = 0.01`, `CACHE_TTL = 6h`, 쿨다운 장소 6h / 브랜드 3h, `MAX_REGIONS = 90`, `BRANCH_RADIUS_M = 200`, `REFRESH_RADIUS_M = 1000`, 반경 선택지 100/200/300/500)도 바꾸지 않는다.

## 3. 기술 선택

| 영역 | 선택 | 이유 |
|---|---|---|
| 언어/빌드 | Kotlin 2.x, AGP 최신 안정판, Gradle Version Catalog, JDK 21 | 로컬에 JDK 21·Android SDK 있음 |
| SDK | minSdk 26, target/compileSdk 최신 안정판 | Kakao Map SDK·Photo Picker 백포트 모두 충족 |
| UI | Jetpack Compose(Material 3), Navigation Compose | |
| 상태 | ViewModel + StateFlow | |
| 저장 | Room(장소·쿠폰), DataStore(감시 창·지점 캐시) | 목록은 쿼리 대상, 나머지는 통째로 읽고 쓰는 값 |
| 네트워크 | OkHttp + kotlinx.serialization | |
| 위치 | Play Services Location: `FusedLocationProviderClient`, `GeofencingClient` | |
| 백그라운드 | `BroadcastReceiver` + WorkManager(expedited) | 리시버 안에서 네트워크 호출을 하지 않기 위해 |
| 지도 | Kakao Map SDK v2 `com.kakao.maps.open:android:2.15.2`, `AndroidView`로 감쌈 | |
| 폰트 | Black Han Sans, IBM Plex Sans KR (res/font 번들) | 지금 디자인 유지 |
| DI | 수동(`AppContainer`) | 규모상 Hilt 불필요 |
| 테스트 | JUnit 5 + kotlinx-coroutines-test, OkHttp MockWebServer | |

## 4. 키와 설정

- `local.properties`(gitignore)에서 읽어 `BuildConfig`로 주입: `GEMINI_API_KEY`, `GEMINI_MODEL`(기본 `gemini-3.5-flash-lite`), `KAKAO_REST_KEY`, `KAKAO_NATIVE_KEY`.
- 레포에는 `local.properties.example`만 커밋한다. 실제 키는 절대 커밋하지 않는다.
- 키가 비어 있으면 TS와 같은 한국어 안내 메시지를 띄운다.
- Kakao 콘솔: Android 플랫폼에 패키지명 `com.capstone.snapspot`와 팀원별 디버그 키 해시·릴리즈 키 해시를 등록하고, 카카오맵 사용 설정을 켠다. README에 절차를 적는다.

## 5. 데이터 흐름

### 5.1 추가
1. Photo Picker로 이미지 선택 → `image/`가 긴 변 1024px JPEG로 줄여 `filesDir/shots/<uuid>.jpg`에 저장.
2. `GeminiClient.extract(base64)` → `Extracted`.
3. 장소: `KakaoClient` 검색(`nameVariants`로 뒤에서부터 한 단어씩 줄이며) → `scoreCandidate` → 최고 점수가 `MIN_AUTO_MATCH` 이상이면 그 좌표, 아니면 `android.location.Geocoder` → 사용자가 확인·수정(이름, 분류, 메모, 종료일, 반경, 후보 선택).
   쿠폰: 현재 위치 근처 지점 미리보기(`nearbyBranches`).
4. 저장 직전 `Dedup` 검사(6절) → Room에 저장 → `GeofenceSync.sync(here)`.

### 5.2 백그라운드
- `GeofencingClient`에 계획된 영역(최대 90개: 갱신 구역 1개 + 장소·지점 89개)을 등록하고, 이벤트는 `GeofenceReceiver` → `GeofenceWorker`(expedited)로 넘긴다.
- Enter, 영역 id가 장소: `suppressReason` 확인 → 알림 → `lastNotifiedAt` 갱신.
- Enter, 영역 id가 `b|<brandKey>|<kakaoId>`: `couponsForBrand`·`brandCoolingDown` 확인 → 지점 이름(감시 창 상태에서 조회)으로 알림 → 해당 쿠폰들 `lastNotifiedAt` 갱신.
- Exit, 영역 id가 `refresh`: 최근 위치(1분 이내) 또는 현재 위치 → `syncGeofences(here)`.
- 알림 판정·표시·기록은 프로세스 전역 `Mutex`로 한 번에 하나씩 처리한다(TS의 promise 큐와 같은 역할: 진입 이벤트가 짝으로 와도 한 번만 알림).
- `BootReceiver`: `BOOT_COMPLETED`·`MY_PACKAGE_REPLACED`에서 마지막 위치로 재등록.
- 앱이 켜져 있는 동안: Fused Location 업데이트로 현재 위치를 화면에 반영하고, 저장 장소 반경에 들어가면 같은 알림 경로를 탄다(TS의 in-app watcher).

### 5.3 권한
위치(정밀) → 알림(API 33+) → 백그라운드 위치 순서로, 각 단계에서 이유를 먼저 설명한 뒤 요청한다. 백그라운드 위치가 없으면 현황 탭에 "앱이 꺼져 있을 땐 알림이 오지 않아요"와 설정 이동 버튼을 띄운다.


### 5.4 우선순위: 무엇을 감시하고 지도에 보여 줄까

OS가 지켜볼 수 있는 칸은 90개(갱신 구역 1개 포함)다. 지금 TS 앱은 저장 장소와 브랜드들에 칸을 **균등하게** 나눠 주고, 브랜드마다 근처 지점을 최대한 많이 등록한다. Kotlin 버전은 이를 **우선순위 순서**로 바꾼다.

1. **계층 1: 저장한 장소.** 종료되지 않은 장소 전부, 가까운 순. 칸이 모자라면 장소가 먼저 다 들어간다.
2. **계층 2: 임박 쿠폰.** 유효기간이 3일 이내(`daysLeft <= 3`, 오늘 만료 포함)인 쿠폰의 브랜드. 유효기간이 빠른 브랜드부터.
3. **계층 3: 나머지 쿠폰.** 유효기간이 빠른 순, 기한 없는 쿠폰은 마지막.

- **브랜드당 지점 수 제한**: 쿠폰 브랜드마다 내 위치에서 가장 가까운 **K곳(기본 3)**만 등록한다. 검색 범위는 지금과 같은 2.5km다. 한 브랜드가 같은 계층에 여러 장이면 브랜드 단위로 한 번만 센다.
- 칸은 계층 1 → 2 → 3 순서로 채운다. 같은 계층의 브랜드끼리는 돌아가며(round-robin) 한 곳씩 채워, 칸이 모자랄 때 한 브랜드만 들어가는 일을 막는다.
- **지도 표시 = 등록된 것.** 메인 지도에는 이 계획에 실제로 들어간 지점만 점으로 보인다. 그래서 브랜드마다 가까운 몇 곳만 보이고, 지도가 지점 수십 개로 뒤덮이지 않는다.
- **임박 쿠폰 표시**: 쿠폰 목록과 알림에 `D-2` 같은 배지를 붙이고, 임박 쿠폰은 목록 맨 위에 둔다.
- 1km 갱신 구역을 벗어나면 지금처럼 재계획하므로, 이동하면 가까운 K곳도 함께 바뀐다.

**알려진 대가**: 브랜드당 K곳만 등록하면, 1km 안에 같은 브랜드 지점이 K곳보다 많은 동네(성수 2.5km 안 스타벅스 27~33곳)에서는 4번째로 가까운 지점 앞을 지나도 알림이 오지 않을 수 있다. 쿠폰은 "어느 지점이든 한 번 알려 주면 되는" 성격이라 받아들일 만하다고 보지만, 시뮬레이터로 K=3/5의 놓친 방문 비율을 재서 `docs/simulation.md`에 남기고 K를 숫자로 정한다. 저장 장소는 이 제한을 받지 않는다.

## 6. 중복 저장 방지 (이슈 #1)

`core/Dedup.kt`의 순수 함수로 판정하고 단위 테스트를 붙인다.

- **장소**: 기존 장소 중 하나라도 다음에 해당하면 중복이다.
  - 둘 다 `kakaoId`가 있고 같다, 또는
  - `normalizeName`(Match.kt)한 이름이 같고 거리가 50m 이내다.
  - 동작: "이미 저장한 장소예요" 다이얼로그, [기존 것 보기] / [취소]. 추가는 막는다.
- **쿠폰**: 사용하지 않은 기존 쿠폰 중 `brandKey`, 공백을 정규화한 `benefit`, `expiresAt`이 모두 같은 것이 있으면 중복 후보다.
  - 동작: "같은 쿠폰이 이미 있어요" 다이얼로그, [기존 것 보기] / [그래도 추가] / [취소]. 같은 기프티콘을 실제로 두 장 가진 경우가 있어 추가를 허용한다.
- 지오펜스는 저장된 항목에서 계획하므로 장소가 하나면 영역도 하나다. 같은 브랜드 쿠폰이 여러 장이어도 지점 영역은 브랜드 단위로 한 번만 만든다(지금 동작과 같음).

## 7. 화면

기능은 그대로 두고 화면 구성은 더 깔끔하게 바꾼다. **탭 바를 없애고 메인 화면 하나에 지도와 목록을 모은다.** 색과 폰트(Black Han Sans, IBM Plex Sans KR, 녹색 `#2D7A57`)는 유지한다.

### 7.1 메인 화면 (`ui/home`)

```
  SnapSpot        ● 29곳 감시 ›      ← 상단 바: 로고, 감시 현황 배지(누르면 현황 화면)

 ╭────────────────────────╮
 │    ╭───╮                │          ← 지도 카드: 좌우·상하 16dp 여백, 모서리 24dp,
 │   │ 📍 │  ← 알림 반경    │            화면 높이의 약 55%
 │    ╰───╯                │
 │          ◉ 내 위치       │
 │   · · ← 쿠폰 지점 점     │
 │                      ⌖  │          ← 내 위치로 이동 버튼
 ╰────────────────────────╯

  [ 장소 4 ]  쿠폰 2                  ← 세그먼트 탭
  📍 어니언 성수   카페   933m        ← 가까운 순 목록(스크롤)
  📍 더현대 팝업   팝업  2.1km
                          (＋)       ← 추가 버튼(FAB)
```

- **지도 카드**: Kakao 지도를 화면 가장자리에서 띄운 둥근 카드 안에 넣는다(클리핑). 지도 위에 겹치는 카드 캐러셀·떠 있는 배지는 두지 않는다.
- **알림 반경 표시**: 저장한 장소마다 핀과 함께 그 장소의 알림 반경(100/200/300/500m)을 녹색 반투명 원(채움 약 15%, 테두리 1.5dp)으로 그린다. 종료된 장소는 회색으로 그린다.
- **쿠폰 지점**: 5.4절 계획에 들어간 지점(브랜드마다 가장 가까운 K곳)만 작은 점(브랜드 이니셜)으로 표시하고 반경 원은 그리지 않는다. 임박 쿠폰의 지점은 주황 테두리로 구분한다. 지금 근처인 지점은 노란색으로 강조한다(지금 동작).
- 갱신 구역(1km) 점선 원은 메인 지도에서 빼고 현황 화면의 작은 지도에서만 보여 준다.
- **목록**: `장소` / `쿠폰` 세그먼트, 기본 선택은 `장소`. 쿠폰 목록은 임박(D-3 이내) 쿠폰이 맨 위. 장소 행을 누르면 지도 카드가 그 장소로 이동하고 핀을 강조한다. 쿠폰 행은 티켓 카드 모양(D-day, "지금 근처" 띠)이고 누르면 쿠폰 상세로 간다. 길게 누르면 삭제.
- 지도 핀을 누르면 목록에서 그 행으로 스크롤하고 강조한다(지도와 목록이 서로 연결).
- 저장한 것이 없으면 목록 자리에 "스크린샷 한 장으로 시작해 보세요"와 추가 버튼을 보여 준다.

### 7.2 나머지 화면

| TS | Kotlin |
|---|---|
| `(tabs)/index.tsx`, `list.tsx`, `coupons.tsx` | `ui/home` 하나로 합침(7.1) |
| `(tabs)/status.tsx` | `ui/status`: 상단 배지에서 진입. 90칸 사용 현황 막대, 갱신 구역이 보이는 작은 지도, 권한 상태 |
| `add.tsx` | `ui/add`: reading → locating → ready 단계, 후보 선택, 반경 선택(선택한 반경 원을 미리보기 지도에 즉시 반영), 중복 다이얼로그 |
| `coupon/[id].tsx` | `ui/coupon`: 쿠폰 이미지 크게, "사용했어요" |

쿠폰 알림을 누르면 `ui/coupon/{id}`로 바로 연다(지금 동작). 장소 알림은 메인 화면을 열고 지도를 그 장소로 이동한다.

## 8. 오류 처리

- 네트워크·API 오류: 추가 화면에서 한국어 메시지와 [다시 시도]를 띄운다. Gemini 실패 시 직접 입력으로 넘어갈 수 있다.
- 백그라운드에서 지점 검색이 실패하면 해당 브랜드만 건너뛰고 나머지로 계획한다(지금 동작). WorkManager 재시도는 refresh 작업에만 쓴다(최대 3회, 지수 백오프).
- 지오펜스 등록 실패(권한 없음, 위치 꺼짐): 현황 탭에 원인을 표시한다.

## 9. 테스트와 검증

- `core`: TS `*.test.ts`의 모든 케이스를 JUnit으로 옮긴다. 이것이 동등성 기준이다. `Dedup` 테스트를 새로 쓴다.
- `net`: MockWebServer로 카카오 페이지네이션·Gemini 응답 파싱(잘못된 날짜 → null, 빈 응답 → 예외).
- `Window` 테스트: TS의 균등 배분 테스트("one brand cannot starve the others")는 우선순위 규칙에 맞게 다시 쓴다. 장소가 쿠폰보다 먼저 들어가는지, 임박 쿠폰이 일반 쿠폰보다 먼저인지, 브랜드당 K곳을 넘지 않는지, 같은 계층 안에서 돌아가며 채우는지를 검증한다. 나머지 TS 케이스(갱신 구역, 칸 상한, 위치 모를 때)는 그대로 옮긴다.
- 시뮬레이터: `scripts/simulate-window.ts`를 테스트 소스의 실행 가능한 JVM 프로그램으로 옮기고 Gradle 태스크(`simulateWindow`)로 실행한다. 새 규칙으로 K=3, K=5를 비교한다. `eval-match.ts`도 같은 방식(`evalMatch`, 실제 API 키 필요)으로 옮긴다.
- CI: `.github/workflows/android.yml`에서 `./gradlew testDebugUnitTest assembleDebug`.
- 수동: 성공 기준 3번 시나리오를 에뮬레이터에서 실행하고 결과를 README의 "실제 동작 확인"에 갱신한다.

## 10. 레포 전환

- `kotlin` 브랜치에서 Gradle 프로젝트를 레포 루트에 만든다(`settings.gradle.kts`, `app/`). 작업 중에는 Expo 파일과 공존한다. Expo가 생성한 `android/`(gitignore 대상)는 지우고 Gradle 프로젝트 `app/`과 겹치지 않게 한다.
- 기능 동등과 성공 기준을 모두 확인한 뒤, 같은 PR에서 `src/`, `index.ts`, `package*.json`, `app.json`, `eas.json`, `tsconfig.json`, `AGENTS.md`의 Expo 지침, `scripts/*.ts`를 제거한다. `eval/`, `docs/`는 유지한다.
- README의 실행 방법·구조·파일 경로를 Kotlin 기준으로 고친다. 이슈 #1 본문의 `src/storage.ts` 언급은 PR 설명에서 새 경로로 안내한다.
- 머지는 팀 결정이다. PR만 올린다.
