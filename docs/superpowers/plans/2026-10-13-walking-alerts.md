# Walking Alerts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hold back geofence alerts while the user is in a vehicle and send them if the user gets off within 1km and walks; never post system alerts while the app is on screen.

**Architecture:** Pure rules in `core/Pending.kt` (vehicle speed, what to do with a held alert, merging). Held alerts live in their own JSON file (`data/PendingStore.kt`, not the team-contract `Repository.kt`). `GeofenceReceiver` passes the event's speed to `GeofenceWorker`, which alerts or holds; `RecheckWorker` runs every 3 minutes while anything is held. `AlertService` stays silent while the app is on screen.

**Tech Stack:** Kotlin 2.2, Play Services Location 21.3 (GeofencingEvent, FusedLocationProviderClient), WorkManager 2.10, kotlinx.serialization, JUnit 5 + kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-10-13-walking-alerts-design.md`

## Global Constraints

- Vehicle = speed ≥ 4.2 m/s (15 km/h). Unknown speed = walking (alert now).
- Recheck delay: 3 minutes. Give up 30 minutes after the first hold. Alert only within 1km of the held target and not at vehicle speed.
- One held alert per group: place id for places, brand key for coupons; merging keeps the first `since` and moves the target to the latest branch.
- Do not modify `data/Repository.kt` or `core/model/Models.kt` (team contract files).
- While the app is on screen: no system notification and no cooldown record, unless `force` (the coupon screen's "알림 테스트").
- The 1km refresh region and window re-planning are unchanged.
- UI and notification copy is Korean 해요체. Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Run unit tests with `./gradlew :app:testDebugUnitTest --console=plain -q` from `C:\Users\hasan\dev\snapspot` (Git Bash). Results land in `app/build/test-results/testDebugUnitTest/*.xml`.

## Review Focus

- A geofence event whose location has no speed (Wi-Fi/cell fix) must alert immediately, exactly as before. → Task 1 test `unknown speed counts as walking`.
- Several branches of one brand passed in a bus within minutes must produce one held alert whose 30-minute clock starts at the first one. → Task 1 test `same group merges, keeps the first since, moves the target`.
- A held alert checked exactly 30 minutes after it was held still gets its last chance; 1ms later it is dropped. → Task 1 test `30 minutes is the last chance`.
- A recheck that runs while the app is on screen must not post a notification, and must not leave the alert held forever. → Task 3 test `on screen, nothing is shown or recorded` plus Task 4 removes every decided alert regardless of `AlertService`'s answer.
- An alert held while `RecheckWorker` is already running must still be rechecked later (not orphaned). → Task 4 reschedules whenever the store is non-empty after its pass, checked on the emulator in Task 6.

---

### Task 1: Pending rules (pure)

**Files:**
- Create: `app/src/main/java/com/capstone/snapspot/core/Pending.kt`
- Test: `app/src/test/java/com/capstone/snapspot/core/PendingTest.kt`

**Interfaces:**
- Produces:
  - `const val VEHICLE_SPEED_MPS: Float = 4.2f`, `const val RECHECK_DELAY_MS: Long = 180_000L`, `const val PENDING_MAX_MS: Long = 1_800_000L`, `const val PENDING_NEAR_M: Double = 1000.0`
  - `fun isVehicleSpeed(speed: Float?): Boolean`
  - `data class Fix(val at: Coords, val speed: Float?)`
  - `@Serializable data class PendingAlert(val regionId: String, val group: String, val latitude: Double, val longitude: Double, val since: Long)`
  - `enum class PendingDecision { ALERT, RECHECK, DROP }`
  - `fun decidePending(p: PendingAlert, fix: Fix?, now: Long): PendingDecision`
  - `fun mergePending(list: List<PendingAlert>, p: PendingAlert): List<PendingAlert>`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.capstone.snapspot.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PendingTest {
    private val target = Coords(37.5446, 127.0565)
    private val held = PendingAlert("p1", "p1", target.latitude, target.longitude, since = NOW)

    /** `meters` north of the target. */
    private fun north(meters: Double) = Coords(target.latitude + meters / 111_320.0, target.longitude)

    @Test
    fun `15 kmh and up is a vehicle`() {
        assertFalse(isVehicleSpeed(4.1f))
        assertTrue(isVehicleSpeed(4.2f))
        assertFalse(isVehicleSpeed(1.4f))
    }

    @Test
    fun `unknown speed counts as walking`() {
        assertFalse(isVehicleSpeed(null))
    }

    @Test
    fun `walking within 1km alerts`() {
        assertEquals(PendingDecision.ALERT, decidePending(held, Fix(north(900.0), 1.2f), NOW + 3 * 60_000))
        assertEquals(PendingDecision.ALERT, decidePending(held, Fix(north(900.0), null), NOW + 3 * 60_000))
    }

    @Test
    fun `still in a vehicle checks again`() {
        assertEquals(PendingDecision.RECHECK, decidePending(held, Fix(north(300.0), 8f), NOW + 3 * 60_000))
    }

    @Test
    fun `no position checks again`() {
        assertEquals(PendingDecision.RECHECK, decidePending(held, null, NOW + 3 * 60_000))
    }

    @Test
    fun `walking more than 1km away gives up`() {
        assertEquals(PendingDecision.DROP, decidePending(held, Fix(north(1100.0), 1.2f), NOW + 3 * 60_000))
    }

    @Test
    fun `30 minutes is the last chance`() {
        assertEquals(PendingDecision.ALERT, decidePending(held, Fix(north(100.0), 1.2f), NOW + PENDING_MAX_MS))
        assertEquals(PendingDecision.DROP, decidePending(held, Fix(north(100.0), 1.2f), NOW + PENDING_MAX_MS + 1))
        assertEquals(PendingDecision.DROP, decidePending(held, Fix(north(100.0), 9f), NOW + PENDING_MAX_MS + 1))
    }

    @Test
    fun `same group merges, keeps the first since, moves the target`() {
        val a = PendingAlert("b|starbucks|1", "starbucks", 37.0, 127.0, since = NOW)
        val b = PendingAlert("b|starbucks|2", "starbucks", 37.1, 127.1, since = NOW + 60_000)
        val other = PendingAlert("p9", "p9", 37.2, 127.2, since = NOW + 30_000)
        val merged = mergePending(mergePending(mergePending(emptyList(), a), other), b)
        assertEquals(2, merged.size)
        val sb = merged.single { it.group == "starbucks" }
        assertEquals("b|starbucks|2", sb.regionId)
        assertEquals(37.1, sb.latitude)
        assertEquals(NOW, sb.since)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*PendingTest*" --console=plain -q 2>&1 | grep "^e:" | head -3`
Expected: compile errors `Unresolved reference 'PendingAlert'` (and the other new names).

- [ ] **Step 3: Write minimal implementation**

```kotlin
/**
 * Alerts held back while the user is in a vehicle. Passing a saved place on a bus is not a moment
 * anyone can act on; getting off nearby and walking is. Pure, so tests cover every rule.
 */
package com.capstone.snapspot.core

import kotlinx.serialization.Serializable

/** 15 km/h. Walking is about 1.4 m/s; city buses average 5–8 m/s. */
const val VEHICLE_SPEED_MPS = 4.2f

const val RECHECK_DELAY_MS = 3 * 60_000L

/** Counted from the first hold; after this the alert is dropped. */
const val PENDING_MAX_MS = 30 * 60_000L

/** Got off within this distance of the held place or branch: worth an alert. */
const val PENDING_NEAR_M = 1000.0

/** Unknown speed (Wi-Fi or cell fix) counts as walking: one extra alert beats a missed one. */
fun isVehicleSpeed(speed: Float?): Boolean = speed != null && speed >= VEHICLE_SPEED_MPS

/** A position with the speed it was moving at, if known (m/s). */
data class Fix(val at: Coords, val speed: Float?)

@Serializable
data class PendingAlert(
    /** A place id, or a branch region id "b|<brandKey>|<kakaoId>". */
    val regionId: String,
    /** One held alert per group: the place id, or the brand key for coupons. */
    val group: String,
    val latitude: Double,
    val longitude: Double,
    /** When it was first held; [PENDING_MAX_MS] runs from here. */
    val since: Long,
)

enum class PendingDecision { ALERT, RECHECK, DROP }

fun decidePending(p: PendingAlert, fix: Fix?, now: Long): PendingDecision {
    if (now - p.since > PENDING_MAX_MS) return PendingDecision.DROP
    if (fix == null || isVehicleSpeed(fix.speed)) return PendingDecision.RECHECK
    val near = distanceMeters(fix.at, Coords(p.latitude, p.longitude)) <= PENDING_NEAR_M
    return if (near) PendingDecision.ALERT else PendingDecision.DROP
}

/** Adds `p`, folding it into a held alert of the same group: the latest target, the first `since`. */
fun mergePending(list: List<PendingAlert>, p: PendingAlert): List<PendingAlert> {
    val old = list.firstOrNull { it.group == p.group } ?: return list + p
    return list.map { if (it.group == p.group) p.copy(since = minOf(old.since, p.since)) else it }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*PendingTest*" --console=plain -q; grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' app/build/test-results/testDebugUnitTest/TEST-com.capstone.snapspot.core.PendingTest.xml`
Expected: `tests="8" skipped="0" failures="0"`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/capstone/snapspot/core/Pending.kt app/src/test/java/com/capstone/snapspot/core/PendingTest.kt
git commit -m "feat(core): rules for alerts held while in a vehicle

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: PendingStore

**Files:**
- Create: `app/src/main/java/com/capstone/snapspot/data/PendingStore.kt`
- Modify: `app/src/main/java/com/capstone/snapspot/SnapSpotApp.kt` (AppContainer: add `val pending = PendingStore(dir)` after `branchCache`)
- Test: `app/src/test/java/com/capstone/snapspot/data/PendingStoreTest.kt`

**Interfaces:**
- Consumes: `PendingAlert`, `mergePending` (Task 1); `JsonFile<T>` (internal, `data/Repository.kt`: `readNow()`, `suspend read()`, `suspend write(value)`).
- Produces: `class PendingStore(dir: File)` with `suspend fun all(): List<PendingAlert>`, `suspend fun add(p: PendingAlert)`, `suspend fun remove(groups: Set<String>)`; `AppContainer.pending`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.capstone.snapspot.data

import com.capstone.snapspot.core.NOW
import com.capstone.snapspot.core.PendingAlert
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PendingStoreTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `held alerts survive a restart, merge by group and can be removed`() = runTest {
        val store = PendingStore(dir)
        store.add(PendingAlert("b|sb|1", "sb", 37.0, 127.0, NOW))
        store.add(PendingAlert("b|sb|2", "sb", 37.1, 127.1, NOW + 1000))
        store.add(PendingAlert("p1", "p1", 37.2, 127.2, NOW + 2000))

        val reopened = PendingStore(dir)
        assertEquals(listOf("b|sb|2", "p1"), reopened.all().map { it.regionId })
        assertEquals(NOW, reopened.all().first().since)

        reopened.remove(setOf("sb"))
        assertEquals(listOf("p1"), PendingStore(dir).all().map { it.regionId })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*PendingStoreTest*" --console=plain -q 2>&1 | grep "^e:" | head -3`
Expected: `Unresolved reference 'PendingStore'`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.capstone.snapspot.data

import com.capstone.snapspot.core.PendingAlert
import com.capstone.snapspot.core.mergePending
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Alerts held while the user was in a vehicle (pending.json). Kept out of [Repository] on purpose:
 * that file is the team's data contract, and this is background bookkeeping.
 */
class PendingStore(dir: File) {
    private val lock = Mutex()
    private val file = JsonFile(File(dir, "pending.json"), ListSerializer(PendingAlert.serializer()))

    suspend fun all(): List<PendingAlert> = lock.withLock { file.read().orEmpty() }

    suspend fun add(p: PendingAlert) = lock.withLock { file.write(mergePending(file.read().orEmpty(), p)) }

    suspend fun remove(groups: Set<String>) = lock.withLock {
        file.write(file.read().orEmpty().filterNot { it.group in groups })
    }
}
```

In `SnapSpotApp.kt`, inside `class AppContainer`, after `val branchCache = FileBranchCache(dir)` add:

```kotlin
    val pending = PendingStore(dir)
```

and add `import com.capstone.snapspot.data.PendingStore` next to the other `data` imports.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*PendingStoreTest*" --console=plain -q; grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' app/build/test-results/testDebugUnitTest/TEST-com.capstone.snapspot.data.PendingStoreTest.xml`
Expected: `tests="1" skipped="0" failures="0"`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/capstone/snapspot/data/PendingStore.kt app/src/test/java/com/capstone/snapspot/data/PendingStoreTest.kt app/src/main/java/com/capstone/snapspot/SnapSpotApp.kt
git commit -m "feat(data): store for alerts held while in a vehicle

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Quiet while the app is on screen

**Files:**
- Modify: `app/src/main/java/com/capstone/snapspot/notify/AlertService.kt`
- Modify: `app/src/main/java/com/capstone/snapspot/SnapSpotApp.kt` (AppContainer)
- Modify: `app/src/main/java/com/capstone/snapspot/ui/home/HomeViewModel.kt` (init block, `onResume`, `onStop`)
- Test: `app/src/test/java/com/capstone/snapspot/notify/AlertServiceTest.kt`

**Interfaces:**
- Produces: `AlertService(repo, notifier, onScreen: () -> Boolean = { false }, clock: () -> Long = System::currentTimeMillis)` (the new parameter goes **before** `clock` so existing `AlertService(repo, notifier) { NOW }` calls still bind the trailing lambda to `clock`); `AppContainer.onScreen: Boolean` (`@Volatile var`).

- [ ] **Step 1: Write the failing test** (append inside `class AlertServiceTest`)

```kotlin
    @Test
    fun `on screen, nothing is shown or recorded`() = runTest {
        val repo = Repository(dir)
        val notifier = FakeNotifier()
        var onScreen = true
        val svc = AlertService(repo, notifier, onScreen = { onScreen }) { NOW }
        repo.upsertPlace(place(id = "p"))
        repo.upsertCoupon(coupon(id = "c", brand = "스타벅스"))
        repo.saveWindow(
            WindowState(NOW, null, 90, emptyMap(), mapOf("b|${brandKey("스타벅스")}|1" to WindowBranch("스타벅스 성수점", brandKey("스타벅스"), 37.0, 127.0, false)), 0),
        )

        assertFalse(svc.onPlaceEnter("p"))
        assertFalse(svc.onBranchEnter("b|${brandKey("스타벅스")}|1"))
        assertEquals(0, notifier.shown.size)
        assertEquals(null, repo.places.value.single().lastNotifiedAt)

        assertTrue(svc.onPlaceEnter("p", force = true))

        onScreen = false
        assertTrue(svc.onBranchEnter("b|${brandKey("스타벅스")}|1"))
        assertEquals(2, notifier.shown.size)
    }
```

Helpers: `place(id = ...)` is in `core/AlertsTest.kt:10`, `coupon(id = ..., brand = ...)` in `core/CouponsTest.kt:14`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*AlertServiceTest*" --console=plain -q 2>&1 | grep "^e:" | head -3`
Expected: `No parameter with name 'onScreen' found`.

- [ ] **Step 3: Write minimal implementation**

In `AlertService.kt`, change the constructor and both entry points:

```kotlin
class AlertService(
    private val repo: Repository,
    private val notifier: Notifier,
    /** True while the app is on screen: its own "지금 근처예요" marks do the job, no notification. */
    private val onScreen: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
) {
```

In `onPlaceEnter`, first line inside `lock.withLock {`:

```kotlin
        if (!force && onScreen()) return@withLock false
```

In `onBranchEnter`, first line inside `lock.withLock {`, the same line.

Update the class doc comment's first sentence to: `Decides whether an Enter event alerts. Nothing is shown while the app is on screen (and no cooldown is recorded, so the next visit still alerts).`

In `SnapSpotApp.kt` `AppContainer`, add above `val alerts`:

```kotlin
    /** Set by the home screen's lifecycle; read by [AlertService]. */
    @Volatile var onScreen = false
```

and change `val alerts = AlertService(repo, AndroidNotifier(ctx))` to:

```kotlin
    val alerts = AlertService(repo, AndroidNotifier(ctx), onScreen = { onScreen })
```

In `HomeViewModel.kt`:
- Delete the "In-app watcher" block in `init` (the `viewModelScope.launch { here.filterNotNull().collect { at -> for (p in c.repo.places.value) { ... onPlaceEnter ... } } }` with its comment). Keep the first-fix map focus block and the resync collector.
- In `onResume()`, add `c.onScreen = true` as the first line.
- Replace `fun onStop() = c.location.stop()` with:

```kotlin
    /** Out of sight, geofences take over; continuous updates would only drain the battery. */
    fun onStop() {
        c.onScreen = false
        c.location.stop()
    }
```

- Remove imports that become unused (`distanceMeters` if no longer referenced; check with `grep -n distanceMeters app/src/main/java/com/capstone/snapspot/ui/home/HomeViewModel.kt`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain -q 2>&1 | grep -E "^e:|FAILED" | head; grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' app/build/test-results/testDebugUnitTest/TEST-com.capstone.snapspot.notify.AlertServiceTest.xml`
Expected: no output from the first grep; failures="0".

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/capstone/snapspot/notify/AlertService.kt app/src/main/java/com/capstone/snapspot/SnapSpotApp.kt app/src/main/java/com/capstone/snapspot/ui/home/HomeViewModel.kt app/src/test/java/com/capstone/snapspot/notify/AlertServiceTest.kt
git commit -m "feat(notify): no notifications while the app is on screen

The list's 지금 근처예요 band already shows it; the in-app watcher that
posted notifications on every location update is gone.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Hold in a vehicle, recheck every 3 minutes

**Files:**
- Modify: `app/src/main/java/com/capstone/snapspot/location/Location.kt` (add `currentFix`)
- Modify: `app/src/main/java/com/capstone/snapspot/geofence/GeofenceReceiver.kt`
- Modify: `app/src/main/java/com/capstone/snapspot/geofence/GeofenceWorker.kt`
- Create: `app/src/main/java/com/capstone/snapspot/geofence/RecheckWorker.kt`

**Interfaces:**
- Consumes: `Fix`, `isVehicleSpeed`, `PendingAlert`, `decidePending`, `PendingDecision`, `RECHECK_DELAY_MS` (Task 1); `AppContainer.pending` (Task 2); `AlertService.onPlaceEnter/onBranchEnter` (Task 3); `isBranchRegion`, `brandKeyOfRegion` (`core/Planner.kt`); `repo.window.value?.branches` (`Map<String, WindowBranch>`).
- Produces: `suspend fun currentFix(ctx: Context): Fix?`; `GeofenceWorker.SPEED` input key (Float, `-1f` = unknown); `RecheckWorker.schedule(ctx: Context, policy: ExistingWorkPolicy)`.

This task is Android glue around tested rules; it is verified by build here and on the emulator in Task 6.

- [ ] **Step 1: `currentFix` in `location/Location.kt`** (top level, below `LocationTracker`)

```kotlin
/**
 * One position with its speed, for background work. A fix from the last minute is reused;
 * otherwise Wi-Fi/cell first, then GPS once (emulators and places without Wi-Fi have no network fix).
 */
@SuppressLint("MissingPermission")
suspend fun currentFix(ctx: Context): Fix? {
    if (!hasFineLocation(ctx)) return null
    val fused = LocationServices.getFusedLocationProviderClient(ctx)
    val last = runCatching { fused.lastLocation.await() }.getOrNull()
        ?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < 60_000_000_000L }
    val loc = last
        ?: runCatching { fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await() }.getOrNull()
        ?: runCatching { fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await() }.getOrNull()
        ?: return null
    return Fix(Coords(loc.latitude, loc.longitude), loc.takeIf { it.hasSpeed() }?.speed)
}
```

Add imports `android.os.SystemClock` and `com.capstone.snapspot.core.Fix`.

- [ ] **Step 2: Pass the event speed in `GeofenceReceiver.kt`**

Replace the `.setInputData(...)` line with:

```kotlin
            .setInputData(
                workDataOf(
                    GeofenceWorker.IDS to ids.toTypedArray(),
                    GeofenceWorker.TRANSITION to event.geofenceTransition,
                    // Speed at the moment of the event (m/s); -1 when the fix carried none.
                    GeofenceWorker.SPEED to (event.triggeringLocation?.takeIf { it.hasSpeed() }?.speed ?: -1f),
                ),
            )
```

- [ ] **Step 3: Hold or alert in `GeofenceWorker.kt`**

Replace `doWork()` and `currentPosition()` with:

```kotlin
    override suspend fun doWork(): Result {
        val c = (applicationContext as SnapSpotApp).container
        c.repo.reload()
        val ids = inputData.getStringArray(IDS).orEmpty()
        val transition = inputData.getInt(TRANSITION, -1)
        val speed = inputData.getFloat(SPEED, -1f).takeIf { it >= 0f }
        for (id in ids) {
            when {
                id == REFRESH_ID -> if (transition == Geofence.GEOFENCE_TRANSITION_EXIT) {
                    val here = currentFix(applicationContext)?.at ?: return if (runAttemptCount < 3) Result.retry() else Result.failure()
                    c.geofenceSync.sync(here)
                }
                transition != Geofence.GEOFENCE_TRANSITION_ENTER -> Unit
                isVehicleSpeed(speed) && hold(c, id) -> Unit
                isBranchRegion(id) -> c.alerts.onBranchEnter(id)
                else -> c.alerts.onPlaceEnter(id)
            }
        }
        return Result.success()
    }

    /** Passing by in a vehicle: keep the alert for when the user gets off nearby. False if the target is unknown. */
    private suspend fun hold(c: AppContainer, id: String): Boolean {
        val now = System.currentTimeMillis()
        val p = if (isBranchRegion(id)) {
            val b = c.repo.window.value?.branches?.get(id) ?: return false
            PendingAlert(id, brandKeyOfRegion(id), b.latitude, b.longitude, now)
        } else {
            val place = c.repo.places.value.firstOrNull { it.id == id } ?: return false
            PendingAlert(id, id, place.latitude, place.longitude, now)
        }
        c.pending.add(p)
        RecheckWorker.schedule(applicationContext, ExistingWorkPolicy.KEEP)
        return true
    }
```

Add to the companion: `const val SPEED = "speed"`. Update the class doc to `Enter → alert, or hold it while in a vehicle. Leaving the refresh region → re-plan the window there.`
Imports: `androidx.work.ExistingWorkPolicy`, `com.capstone.snapspot.AppContainer`, `com.capstone.snapspot.core.PendingAlert`, `com.capstone.snapspot.core.brandKeyOfRegion`, `com.capstone.snapspot.core.isVehicleSpeed`, `com.capstone.snapspot.location.currentFix`. Remove the now-unused `SuppressLint`, `SystemClock`, `Coords`, `LocationServices`, `Priority`, `await` imports.

- [ ] **Step 4: Create `geofence/RecheckWorker.kt`**

```kotlin
package com.capstone.snapspot.geofence

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.capstone.snapspot.SnapSpotApp
import com.capstone.snapspot.core.PendingDecision
import com.capstone.snapspot.core.RECHECK_DELAY_MS
import com.capstone.snapspot.core.decidePending
import com.capstone.snapspot.core.isBranchRegion
import com.capstone.snapspot.location.currentFix
import java.util.concurrent.TimeUnit

/** Every 3 minutes while alerts are held: alert those the user got off near, drop the stale ones. */
class RecheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as SnapSpotApp).container
        c.repo.reload()
        val held = c.pending.all()
        if (held.isEmpty()) return Result.success()
        val fix = currentFix(applicationContext)
        val now = System.currentTimeMillis()
        val done = mutableSetOf<String>()
        for (p in held) {
            when (decidePending(p, fix, now)) {
                PendingDecision.ALERT -> {
                    // Whatever AlertService answers (cooldown, coupon used, app on screen), this one is settled.
                    if (isBranchRegion(p.regionId)) c.alerts.onBranchEnter(p.regionId) else c.alerts.onPlaceEnter(p.regionId)
                    done += p.group
                }
                PendingDecision.DROP -> done += p.group
                PendingDecision.RECHECK -> Unit
            }
        }
        c.pending.remove(done)
        // Re-read: an alert may have been held while this ran.
        if (c.pending.all().isNotEmpty()) schedule(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    companion object {
        private const val NAME = "recheck"

        /** KEEP from a new hold (one check is already coming); APPEND_OR_REPLACE from the worker itself (it is still running). */
        fun schedule(ctx: Context, policy: ExistingWorkPolicy) {
            val work = OneTimeWorkRequestBuilder<RecheckWorker>()
                .setInitialDelay(RECHECK_DELAY_MS, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, policy, work)
        }
    }
}
```

- [ ] **Step 5: Build and run all tests**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain -q 2>&1 | grep -E "^e:|FAILED" | head`
Expected: no output.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/capstone/snapspot/location/Location.kt app/src/main/java/com/capstone/snapspot/geofence/GeofenceReceiver.kt app/src/main/java/com/capstone/snapspot/geofence/GeofenceWorker.kt app/src/main/java/com/capstone/snapspot/geofence/RecheckWorker.kt
git commit -m "feat(geofence): hold alerts while in a vehicle, recheck every 3 minutes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Simulator: how many alerts came from a bus

**Files:**
- Modify: `app/src/test/java/com/capstone/snapspot/sim/SimulateWindow.kt`
- Modify: `docs/simulation.md` (regenerated)

**Interfaces:**
- Consumes: nothing new from earlier tasks.
- Produces: an extra column "버스로 지나가며 울린 알림" in both tables.

- [ ] **Step 1: Keep each trip's speed** — change `private class Trip(val points: List<Coords>, val meters: Double)` to `private class Trip(val points: List<Coords>, val meters: Double, val speed: Double)` and the last line of `makeTrip()` to `return Trip(points, meters, speed)`. The random draws do not change, so existing numbers stay the same.

- [ ] **Step 2: Count alerts and bus alerts in `run()`** — add to `Result`:

```kotlin
    /** Alerts the app would post (caught place visits + brands alerted per trip), and those on bus trips. */
    val alerts: Int, val busAlerts: Int,
```

In `run()`, declare `var alerts = 0; var busAlerts = 0` with the other counters. After `brandCaught += brandSeen.values.count { it }`, add:

```kotlin
        val tripAlerts = brandSeen.values.count { it } + visits(trip, places).count { v -> (v.from..v.to).any { registered[it].contains(v.spot.id) } }
        alerts += tripAlerts
        if (trip.speed > VEHICLE_SPEED_MPS) busAlerts += tripAlerts
```

(import `com.capstone.snapspot.core.VEHICLE_SPEED_MPS`), and pass `alerts, busAlerts` to the `Result(...)` constructor.

- [ ] **Step 3: Show it** — in `table()`, add the header cell `버스로 지나가며 울린 알림` (and one more `---|`), and the row cell `${pct(r.busAlerts, r.alerts)} (${r.busAlerts}/${r.alerts})`. Under the bullet list in `simulationReport()`, add:

```
- **버스로 지나가며 울린 알림**: 지금 앱이 버스 이동 중에 울리는 알림의 비율이에요. 차량 속도에서는 알림을 미뤘다가, 근처에서 내려 걸을 때만 울리게 바꿨어요(이 시뮬레이션의 이동은 중간에 내리지 않아서, 내린 뒤 받는 알림은 세지 않아요).
```

- [ ] **Step 4: Regenerate and check**

Run: `./gradlew :app:testDebugUnitTest -Psim -PsimWrite --console=plain -q; git diff --stat docs/simulation.md; grep -c "버스로 지나가며" docs/simulation.md`
Expected: `docs/simulation.md` changed; count ≥ 3 (bullet + two headers). Existing columns keep their previous values.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/capstone/snapspot/sim/SimulateWindow.kt docs/simulation.md
git commit -m "docs(sim): share of alerts that came from passing by bus

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Emulator check and README

**Files:**
- Modify: `README.md` ("동작 흐름" and "한계와 다음 단계")

- [ ] **Step 1: Find out how to inject speed.** On the running `SnapSpot_API34` emulator (Google Maps open so GPS is on), run `adb emu geo fix 127.0565 37.5446 0 8 10` (the console help says `geo fix <lon> <lat> [<alt> [<satellites> [<velocity>]]]`, velocity in knots) and read `adb shell dumpsys location | grep "last location=Location\[gps"` for `vel=`. If `vel` stays 0, use the test provider instead: `adb shell cmd location providers add-test-provider gps` then `adb shell cmd location providers set-test-provider-location gps --location 37.5446,127.0565 --speed 8` (check `adb shell cmd location` help for the exact flags).

- [ ] **Step 2: Vehicle pass, no alert.** Install the debug build. Save a place near 성수역 (e.g. the ONION sample, `127.0565,37.5446`), go HOME, `adb shell am kill com.capstone.snapspot`. Move the position through the place in 100m steps every 10s at speed ~8 m/s. Expected: no SnapSpot notification; `adb shell run-as com.capstone.snapspot cat files/store/pending.json` shows one held alert.

- [ ] **Step 3: Get off and walk, alert.** Stop 500m from the place and keep feeding that position at speed ~1 m/s. Expected: a "근처예요" notification within about 3–4 minutes; `pending.json` becomes `[]`.

- [ ] **Step 4: Keep riding, no alert.** Repeat Step 2, then keep moving away at ~8 m/s past 1km. Expected: no notification; after the next recheck at walking speed more than 1km away (or after 30 minutes) `pending.json` becomes `[]`.

- [ ] **Step 5: On screen, quiet.** Open the app and walk into the place's radius. Expected: the list shows "지금 근처예요", no system notification. The coupon screen's "알림 테스트" still posts one.

- [ ] **Step 6: README.** In "동작 흐름", after the line `→ 반경 진입: 꺼져 있던 앱이 깨어나 알림 (장소는 6시간, 브랜드는 3시간에 한 번)`, add:

```
     · 차량 속도(시속 15km 이상)로 지나가면 미뤄 두고 3분마다 확인 → 1km 안에서 걸으면 알림, 30분이 지나면 버림
     · 앱이 화면에 떠 있으면 시스템 알림 없이 목록의 "지금 근처예요"로만 보여 줌
```

In "한계와 다음 단계", replace `- 지오펜스 알림 지연(2~4분) 때문에 버스로 빠르게 지나가는 지점은 놓칠 수 있어요.` with `- 차량 판단은 지오펜스 이벤트 위치의 속도로만 해요. 속도가 없는 위치(와이파이)면 걷는 것으로 보고 바로 알려요.` and replace `걸을 때·영업 중일 때만 알리기, ` with nothing in the "다음:" line.

- [ ] **Step 7: Full check and commit**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain -q 2>&1 | grep -E "^e:|FAILED" | head`
Expected: no output.

```bash
git add README.md
git commit -m "docs: README for alerts held while in a vehicle

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
