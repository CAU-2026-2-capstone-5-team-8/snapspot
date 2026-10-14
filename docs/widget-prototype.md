# 위젯 프로토타입 진행 기록

홈 화면에서 앱을 열지 않고도 곧 만료되는 기프티콘과 근처 지점을 볼 수 있게 한다. 알림을 꺼 둔 사용자도 볼 수 있는 경로다. 프로토타입이라 동작하는 뼈대를 먼저 만들고 디자인과 예외 처리는 나중에 다듬는다.

마지막 갱신: 2026-10-14

## 원칙

- 새 로직은 새 파일에 둔다(`core/WidgetContent.kt`, `ui/widget/`). 기존 파일은 꼭 필요한 연결만 덧붙인다.
- `data/Repository.kt`, `core/model/Models.kt`는 고치지 않는다(고쳐야 하면 contract PR 먼저).
- 새 의존성 주입 도구를 넣지 않는다. 기존 Worker들처럼 `(applicationContext as SnapSpotApp).container`를 쓴다.
- 알림, 지오펜스, 지도, 홈 화면의 동작은 바뀌면 안 된다. 기존 테스트는 수정하지 않고 통과해야 한다.

## 구성: 위젯 2종, 각각 소형/중형 대응

| 위젯 | 보여 주는 것 | 중형 | 소형 |
|---|---|---|---|
| 곧 만료 | 활성 기프티콘을 만료 빠른 순으로 | 3개 목록 | 2개(브랜드·D-day만 한 줄) |
| 근처 지점 | 알림 감시 창에 같은 브랜드 지점이 있는 활성 기프티콘만, 만료 빠른 순 | 3개 목록 | 2개(브랜드·D-day만 한 줄) |

기프티콘이 없으면 "저장한 기프티콘이 없어요", 기프티콘은 있는데 근처 지점이 없으면(근처 지점 위젯) "근처에 쓸 수 있는 곳이 없어요". 항목을 누르면 `snapspot://coupon/{id}`로 열고 머리글을 누르면 앱을 연다. 위젯에는 개수를 표시하지 않는다("곧 만료 6장"이 곧 만료가 6장이라는 뜻으로 읽혀서). 무엇을 얼마나 보일지는 `core/WidgetContent.kt`(`widgetContent`)에만 있어서, 화면을 건드리지 않고 규칙을 바꿀 수 있다.

## 단계

| ID | 할 일 | 상태 | 확인 결과 |
|---|---|---|---|
| P2-0 | 겹치는 파일 확인 | 완료 | 열린 PR #29는 `HomeScreen.kt`, `HomeViewModel.kt`, README, 스크린샷만 고쳐 겹치지 않음 |
| P2-1 | Glance 의존성 추가 | 완료 | 아래 참고 |
| P2-2 | `core/WidgetContent.kt` + JVM 테스트 | 완료 | 새 테스트 13개 통과 |
| P2-3 | `ui/widget/` 위젯 2종, receiver 2개, `res/xml` 2개, 매니페스트 | 완료 | 에뮬레이터에서 확인(아래 표) |
| P2-4 | 데이터 연결(저장소 읽기, 탭 → `snapspot://coupon/{id}`) | 완료 | 에뮬레이터에서 확인(아래 표) |
| P2-5 | 갱신 연결(`SnapSpotApp`에서 StateFlow 구독 → `updateAll`, 30분 안전망) | 완료 | 새 테스트 6개 + 에뮬레이터 확인 |
| P2-6 | 에뮬레이터 확인 + `assembleRelease` 실행 | 완료 | 에뮬레이터 10항목 ○. `assembleRelease` 성공(아래 "release 빌드" 참고) |
| P2-7 | 이 문서 정리 | 완료 | |

테스트: 전체 JVM 214개(기존 192 + `WidgetContentTest` 13 + `WidgetRefreshTest` 6 + `WidgetBodyTest` 3), 건너뜀 3, 실패 0. 기존 테스트 파일은 수정하지 않았다.

## P2-1: Glance 의존성

- 추가: `androidx.glance:glance-appwidget:1.2.0` (`gradle/libs.versions.toml`에 `glance`, `glance-appwidget`, `app/build.gradle.kts`에 한 줄).
- 선택 근거: Google Maven에 1.1.1(안정), 1.2.0(안정), 1.3.0-alpha(미안정)가 있다. 안정 버전 중 최신인 1.2.0을 골랐다. 1.3.0-alpha는 제외했다.
- 해석된 의존성: Compose runtime은 1.9.3(BOM이 정한 값)으로 올라가고 `work-runtime-ktx`는 기존 2.10.5 그대로다.
- 디버그 APK 크기: 63,633,019 → 65,609,657 바이트(+1,976,638, 약 1.9MB).

## P2-2: 표시 규칙 `core/WidgetContent.kt`

- `widgetContent(coupons, nearBrands, now, kind, limit)`가 `WidgetContent(items, total)`을 돌려준다. `total`은 한도를 넘는 것까지 센 개수다(지금 화면에는 쓰지 않지만 규칙 함수가 돌려준다).
- 활성 기프티콘(`isActive`)만 쓴다. 정렬은 만료일 오름차순, 만료일이 없으면 맨 뒤, 같으면 먼저 저장한 것이 앞이다.
- `nearBrands`는 감시 창 지점의 브랜드 키 집합이다(`window.branches.values.map { it.brandKey }`). 기프티콘 브랜드는 `brandKey`로 맞춘다.
- "근처 지점 있음"은 거리가 아니다. 감시 창의 위치는 현재 위치가 아니라 계획을 세운 위치(갱신 구역의 중심)라서 최대 약 1km 어긋날 수 있다.
- 순수 Kotlin이라 Android나 `data` 패키지를 import하지 않는다. 기존 `core/Coupons.kt`, `core/Brand.kt`의 함수만 재사용하고 수정하지 않았다.

## P2-3 ~ P2-5: 화면, 데이터 읽기, 갱신 (`ui/widget/`)

새 파일:

| 파일 | 하는 일 |
|---|---|
| `WidgetData.kt` | `loadRepository`: `SnapSpotApp.container.repo`를 `reload()`해서 돌려줌. `rememberWidgetState`: 위젯 화면 안에서 `repo.coupons`, `repo.window`를 구독해 `widgetContent`를 계산. 새 의존성 주입 도구 없음 |
| `WidgetBody.kt` | 화면. 높이 160dp 미만이면 소형(2개, 혜택 줄 없이 브랜드·D-day 한 줄), 아니면 중형(3개). 머리글 탭 → 앱 열기, 항목 탭 → `snapspot://coupon/{id}`. 긴급(3일 이내)은 주황 글씨, 근처 지점이 있으면 라임 "근처" 표시 |
| `ExpiringWidget.kt`, `NearbyWidget.kt` | 위젯 2종과 각 receiver. `SizeMode.Responsive`(110x110, 250x160dp) |
| `WidgetRefresh.kt` | `refreshWidgetsOn`: 기프티콘이나 감시 창의 브랜드 집합이 바뀔 때만(위치·카운터 변화는 무시) 0.5초 묶어서 두 위젯을 다시 그림. 실패해도 앱은 계속 동작하고 `SnapSpot` 태그로 로그를 남김 |
| `res/xml/expiring_widget_info.xml`, `nearby_widget_info.xml` | 위젯 정보. 2x2 칸 기본, 가로·세로 크기 조절, 30분 주기(안전망) |
| `res/values/widget_strings.xml` | 위젯 선택 목록의 설명 문구 2개 |

기존 파일 변경 장부:

| 파일 | 변경 | 동작이 안 바뀌는 근거 |
|---|---|---|
| `AndroidManifest.xml` | `<receiver>` 2개를 `<application>` 안에 덧붙임(25줄) | 기존 항목은 그대로 |
| `SnapSpotApp.kt` | `onCreate` 끝에 `refreshWidgetsOn(...)` 한 호출과 import 5줄(8줄 추가) | 기존 줄은 그대로. 기프티콘 저장·사용·삭제·알림 표시·감시 창 저장이 모두 `Repository`의 StateFlow를 거치므로 다른 파일은 수정하지 않음 |
| `app/build.gradle.kts`, `gradle/libs.versions.toml` | P2-1의 Glance 의존성 | |

`Repository.kt`, `Models.kt`는 수정하지 않았다(contract PR 불필요).

## 에뮬레이터 확인 (2026-10-14, Pixel_4_XL, Android 17 / API 37, 디버그 빌드)

테스트 데이터는 에뮬레이터의 앱 저장소에만 넣었고, 확인이 끝난 뒤 앱을 삭제했다. 알림 권한은 처음부터 허용하지 않은 상태였다.

| # | 확인 항목 | 결과 | 근거 |
|---|---|---|---|
| 1 | 위젯 선택 목록에 두 위젯이 보임 | ○ | "곧 만료", "근처 지점"이 이름, 2×2, 설명 문구와 함께 표시. 미리보기는 앱 아이콘(미리보기 이미지는 만들지 않음) |
| 2 | 곧 만료: 만료 임박 순, 사용·만료 제외 | ○ | 6장(사용 1, 만료 1 포함) 중 활성 4장만 "4장"으로 표시, 스타벅스 D-2, 올리브영 D-10, 메가MGC커피 D-40 순 |
| 3 | 근처 지점: 감시 창에 지점이 있는 브랜드만 | ○ | 감시 창에 스타벅스만 둔 경우 스타벅스 1장만 표시, 곧 만료 위젯에서도 "근처" 표시는 스타벅스 줄에만 |
| 4 | 빈 상태 문구 | ○ | 기프티콘 0장: 두 위젯 모두 "저장한 기프티콘이 없어요". 기프티콘은 있는데 근처 지점이 없음: 근처 지점 위젯에 "근처에 쓸 수 있는 곳이 없어요" |
| 5 | 항목 탭 → 해당 기프티콘, 머리글 탭 → 앱 | ○ | 올리브영 행을 누르자 올리브영 상세(1만원 할인, 10월 24일까지, D-10)가 열림. 머리글을 누르자 앱이 앞으로 옴 |
| 6 | 앱에서 사용 처리하면 위젯이 바뀜 | ○ | 스타벅스를 "사용했어요" 처리하자 약 4초 안에 두 위젯이 모두 바뀜(곧 만료 4장 → 3장, 날짜 없는 투썸이 올라옴) |
| 7 | 알림 권한이 꺼진 상태에서도 표시 | ○ | `POST_NOTIFICATIONS: granted=false` 상태로 전 과정 확인 |
| 8 | 재부팅 후 표시 | ○ | 앱을 열지 않고도 부팅이 끝난 뒤 두 위젯이 올바른 내용으로 다시 그려짐 |
| 9 | 긴급 주황색, 라임 "근처" | ○ | D-2는 주황 굵은 글씨, "근처"는 라임 칩 |
| 10 | 크기 대응 | ○ | 2×2 칸에서는 소형(2개 한 줄 항목), 가로로 넓히면 중형(3개 목록) |

확인하면서 찾아 고친 문제:

1. **중형에서 "외 N장" 줄이 잘림**: 3칸 높이로 줄였을 때 4번째 줄이 잘렸다. 이후 개수 표시와 "외 N장" 줄을 모두 없앴다(소형은 한 줄 항목 2개로 바꿈).
2. **빠르게 연달아 바뀐 데이터가 위젯에 반영되지 않음**: 앱이 켜지면서 감시 창을 다시 계산하자 "곧 만료"만 옛 값에 머물렀다. Glance는 세션이 열려 있는 동안 갱신이 오면 `provideGlance`를 다시 부르지 않고 기존 화면만 다시 그린다. 데이터를 `provideGlance`에서 한 번 읽던 것을 화면 안에서 `repo.coupons`, `repo.window`를 구독하도록 바꿔(`rememberWidgetState`) 해결했다. 같은 상황을 다시 만들어 두 위젯이 같은 결과를 보임을 확인했다.

## 알려진 한계와 미확인

- **미확인**: 서명된 release 빌드를 기기에서 실행한 것(R8이 꺼져 있어 해당 없음, 아래 참고). 실기기에서의 동작, 다양한 런처와 해상도.
- **ANR 2건**: 재부팅 직후 시스템이 과부하(System UI도 "응답 없음")일 때 `ANR in com.capstone.snapspot ... failed to complete startup`이 2번 기록됐다. 이후 앱을 여러 번 시작했을 때는 재현되지 않았다. 시스템 부하가 원인으로 보이지만 앱 코드가 원인이 아니라고 확정하지는 못했다.
- 강제 종료된 앱의 위젯은 앱이 한 번 시작될 때까지 회색 플레이스홀더로 남는다(안드로이드 기본 동작). 재부팅은 해당하지 않았다.
- 자정에 만료가 바뀌는 것은 앱이 꺼져 있으면 30분 주기와 위젯이 그려질 때의 `reload()`로만 반영된다(자정 직후 최대 30분 지연).
- Glance는 RemoteViews로 그려서 앱의 Pretendard 글꼴을 쓸 수 없다. 위젯은 시스템 글꼴이고 색만 앱과 같다. 둥근 모서리는 Android 12 이상에서만 적용된다(minSdk 26은 각진 모서리).
- 소형(2×2)에서는 줄바꿈을 피하려고 "근처 지점이 없어요"로 줄여 보인다(중형은 "근처에 쓸 수 있는 곳이 없어요"). 소형 줄바꿈은 에뮬레이터에서 다시 확인하지 않았다.
- 위젯 선택 목록의 미리보기가 앱 아이콘이다(`previewLayout`/`previewImage` 없음).
- "근처 지점 있음"은 거리가 아니라 감시 창의 브랜드 일치다(위 P2-2 참고).
- `lintDebug`는 오류 1건(이 PC의 `local.properties` 경로 표기, 변경과 무관), 경고 42건이고 위젯 파일에서 나온 지적은 없다.

## release 빌드 (P2-6)
- `./gradlew assembleRelease`가 성공했다(`app-release-unsigned.apk`, 약 58.1MB). 두 receiver가 release 매니페스트에 들어 있는 것을 `aapt2 dump xmltree`로 확인했다.
- 이 프로젝트의 `app/build.gradle.kts`에는 release `isMinifyEnabled` 설정이 없어 **R8(코드 축소)이 켜져 있지 않다**(`outputs/mapping`도 생기지 않았다). 그래서 "R8에서 Glance가 깨지는지"는 지금 구조에서는 확인할 대상이 아니다. 나중에 minify를 켜면 위젯 receiver와 Glance 동작을 그때 다시 확인해야 한다.
- 서명된 release APK를 기기에서 실행해 보지는 않았다.

## 소형 위젯 개선 (사용자 피드백)
- 소형(2×2)이 1개밖에 못 보여 주던 것을, 혜택 줄을 빼고 브랜드·D-day 한 줄 항목 2개로 바꿨다(`WIDGET_COMPACT_MAX = 2`). 머리글의 개수와 "외 N장"은 없앴다.
- 에뮬레이터에서 좁고 세로로 긴 모양으로 놓았을 때 2개가 잘림 없이 나오는 것을 확인했다. 폭이 좁으면 높이와 상관없이 소형 레이아웃이 선택되어 아래가 비는 점은 남아 있다(세로로 긴 크기를 따로 두는 개선 가능).
