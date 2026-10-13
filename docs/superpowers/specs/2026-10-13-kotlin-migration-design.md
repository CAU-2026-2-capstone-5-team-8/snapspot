# SnapSpot Kotlin 전환 설계

- 날짜: 2026-10-13
- 브랜치: `kotlin` (기능 동등 달성 후 PR 하나로 `master`에 머지, 같은 PR에서 Expo 코드 제거)
- 관련 이슈: #1 같은 지점이 중복 저장됨 (이번 범위에 포함), #2 스크린샷 자동 인식 (범위 밖)

## 1. 목표와 범위

SnapSpot(Expo SDK 57 / React Native, TypeScript 약 3,600줄)을 **Kotlin 네이티브 안드로이드 앱**으로 다시 만든다.

포함:
- 지금 앱의 모든 기능: 스크린샷 → Gemini 추출(장소/쿠폰) → 카카오 매칭 → 저장, 움직이는 지오펜스 창, 앱이 꺼져 있어도 오는 알림, 지도·목록·쿠폰함·현황 탭, 추가 화면, 쿠폰 상세.
- 이슈 #1 중복 저장 방지.
- 지도: Leaflet + OSM WebView → **Kakao Map Android SDK v2**.

제외:
- 이슈 #2(스크린샷 자동 감지·공유 시트). 팀 협의 후 별도 진행.
- iOS. 안드로이드 전용이다.
- 기존 AsyncStorage 데이터 이전. 프로토타입이라 실사용자가 없다.

성공 기준:
1. TS 테스트(`brand`, `branches`, `coupon`, `kakao`, `match`, `window`)의 모든 케이스가 Kotlin JUnit으로 옮겨져 통과한다.
2. Kotlin 시뮬레이터가 `docs/simulation.md`의 표를 같은 가정에서 재현한다(같은 시드면 같은 수치).
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
│  ├─ Window.kt       planRegions, REFRESH_ID
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
   ├─ theme/  map/  list/  coupons/  status/  add/  coupon/
```

`core`의 각 파일은 대응하는 TS 파일의 동작을 그대로 옮긴다. 상수(가중치 0.6/0.3/0.1, `CONFIDENT = 0.7`, `MIN_AUTO_MATCH = 0.4`, `MIN_BRAND_SIMILARITY = 0.4`, `SEARCH_RADIUS_M = 2500`, `CELL_DEG = 0.01`, `CACHE_TTL = 6h`, 쿨다운 장소 6h / 브랜드 3h, `MAX_REGIONS = 90`, `BRANCH_RADIUS_M = 200`, `REFRESH_RADIUS_M = 1000`, 반경 선택지 100/200/300/500)도 바꾸지 않는다.

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

지금 TS 화면을 같은 구성·문구·색으로 옮긴다.

| TS | Kotlin |
|---|---|
| `(tabs)/index.tsx` 지도 | `ui/map`: Kakao 지도, 내 위치, 장소 핀, 지점 배지, 갱신 구역 점선 원 |
| `(tabs)/list.tsx` | `ui/list`: 거리순 장소 목록, 삭제 |
| `(tabs)/coupons.tsx` | `ui/coupons`: 티켓 카드, D-day, "지금 근처" 띠 |
| `(tabs)/status.tsx` | `ui/status`: 90칸 사용 현황 막대, 권한 상태 |
| `add.tsx` | `ui/add`: reading → locating → ready 단계, 후보 선택, 반경, 중복 다이얼로그 |
| `coupon/[id].tsx` | `ui/coupon`: 쿠폰 이미지 크게, "사용했어요" |

쿠폰 알림을 누르면 `ui/coupon/{id}`로 바로 연다(지금 동작). 장소 알림은 지금처럼 앱의 지도 탭을 연다.

## 8. 오류 처리

- 네트워크·API 오류: 추가 화면에서 한국어 메시지와 [다시 시도]를 띄운다. Gemini 실패 시 직접 입력으로 넘어갈 수 있다.
- 백그라운드에서 지점 검색이 실패하면 해당 브랜드만 건너뛰고 나머지로 계획한다(지금 동작). WorkManager 재시도는 refresh 작업에만 쓴다(최대 3회, 지수 백오프).
- 지오펜스 등록 실패(권한 없음, 위치 꺼짐): 현황 탭에 원인을 표시한다.

## 9. 테스트와 검증

- `core`: TS `*.test.ts`의 모든 케이스를 JUnit으로 옮긴다. 이것이 동등성 기준이다. `Dedup` 테스트를 새로 쓴다.
- `net`: MockWebServer로 카카오 페이지네이션·Gemini 응답 파싱(잘못된 날짜 → null, 빈 응답 → 예외).
- 시뮬레이터: `scripts/simulate-window.ts`를 테스트 소스의 실행 가능한 JVM 프로그램으로 옮기고 Gradle 태스크(`simulateWindow`)로 실행한다. `eval-match.ts`도 같은 방식(`evalMatch`, 실제 API 키 필요)으로 옮긴다.
- CI: `.github/workflows/android.yml`에서 `./gradlew testDebugUnitTest assembleDebug`.
- 수동: 성공 기준 3번 시나리오를 에뮬레이터에서 실행하고 결과를 README의 "실제 동작 확인"에 갱신한다.

## 10. 레포 전환

- `kotlin` 브랜치에서 Gradle 프로젝트를 레포 루트에 만든다(`settings.gradle.kts`, `app/`). 작업 중에는 Expo 파일과 공존한다. Expo가 생성한 `android/`(gitignore 대상)는 지우고 Gradle 프로젝트 `app/`과 겹치지 않게 한다.
- 기능 동등과 성공 기준을 모두 확인한 뒤, 같은 PR에서 `src/`, `index.ts`, `package*.json`, `app.json`, `eas.json`, `tsconfig.json`, `AGENTS.md`의 Expo 지침, `scripts/*.ts`를 제거한다. `eval/`, `docs/`는 유지한다.
- README의 실행 방법·구조·파일 경로를 Kotlin 기준으로 고친다. 이슈 #1 본문의 `src/storage.ts` 언급은 PR 설명에서 새 경로로 안내한다.
- 머지는 팀 결정이다. PR만 올린다.
