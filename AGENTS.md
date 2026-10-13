This is a native Android app in Kotlin: Jetpack Compose UI, Google Play Services geofencing, WorkManager, OkHttp, kotlinx.serialization, Kakao Map SDK v2. Single module `app/`, package `com.capstone.snapspot`, minSdk 26.

## Commands

```bash
./gradlew testDebugUnitTest                         # unit tests (JUnit 5)
./gradlew assembleDebug                             # debug APK
./gradlew installDebug                              # install on the connected device/emulator
./gradlew :app:testDebugUnitTest -Psim -PsimWrite   # refresh radius experiment + real-Seoul check → docs/refresh-radius.md
```

Run the unit tests and `assembleDebug` before declaring any task done. CI (`.github/workflows/android.yml`) runs both on every push and PR.

## Layout

- `core/` is plain Kotlin with no Android imports: window planning, brand filtering, matching, coupons, dedup. Put logic here and test it on the JVM. Kakao access goes through the `KakaoApi` interface so tests use `FakeKakao`.
- `data/`, `geofence/`, `net/`, `notify/`, `location/` wrap Android and network APIs and stay thin.
- `ui/` holds Compose screens with their ViewModels; navigation is in `ui/Nav.kt`.
- Version numbers live in `gradle/libs.versions.toml`.

## Rules

- API keys come from `local.properties` (gitignored) via `BuildConfig`. Never commit a key, and never print one in logs or docs.
- Changes to `data/Repository.kt` or `core/model/Models.kt` (the stored data shape) go in their own contract PR first, as the team agreed for the old `storage.ts`/`types.ts`.
- The OS allows 100 geofences per app; `MAX_REGIONS` keeps it at 90. Anything that adds geofences must go through `planRegions` in `core/Window.kt`.
- Kakao Map ships ARM libraries only, so `abiFilters` stays ARM; x86_64 emulators run it through translation.
- UI copy is Korean in 해요체.
