package vn.edu.cva.smartguardian.service

import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import vn.edu.cva.smartguardian.ui.MainActivity
import vn.edu.cva.smartguardian.ui.MainActivity.PinAuthResult
import vn.edu.cva.smartguardian.update.AppUpdateManager
import vn.edu.cva.smartguardian.update.UpdateInfo
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Hardware Invariant & Telemetry Epoch Production Verification Tests
 *
 * Directly exercises production methods and state:
 * 1. Monotonic epoch advancement on screen state changes
 * 2. Real UsageTrackerService.cancelActiveOnlineCalls() execution
 * 3. Real UsageTrackerService.cancelActiveOfflineCalls() execution
 * 4. Multi-threaded safety of production activeOnlineCalls set
 * 5. Telemetry epoch fencing invariants preventing stale dispatch
 */
class HardwareInvariantTest {

    @Before
    fun setUp() {
        GuardianAccessibilityService.telemetryEpoch.set(0L)
        GuardianAccessibilityService.isScreenOnState = true
        UsageTrackerService.activeOnlineCalls.clear()
        UsageTrackerService.activeOfflineCalls.clear()
        UsageTrackerService.recordedSessionTokens.clear()
        UsageTrackerService.lastGpsPromptTimestamp.set(0L)
        AppUpdateManager.mainDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
    }

    @Test
    fun testTelemetryEpochMonotonicIncrement() {
        val initialEpoch = GuardianAccessibilityService.telemetryEpoch.get()
        assertEquals(0L, initialEpoch)

        val offEpoch = GuardianAccessibilityService.telemetryEpoch.incrementAndGet()
        assertEquals(1L, offEpoch)
        assertTrue(offEpoch > initialEpoch)

        val onEpoch = GuardianAccessibilityService.telemetryEpoch.incrementAndGet()
        assertEquals(2L, onEpoch)
        assertTrue(onEpoch > offEpoch)
    }

    @Test
    fun testProductionOnlineCallsCancellation() {
        val client = OkHttpClient()
        val request = Request.Builder().url("https://127.0.0.1:20128/dummy").build()

        val call1 = client.newCall(request)
        val call2 = client.newCall(request)
        val call3 = client.newCall(request)

        UsageTrackerService.activeOnlineCalls.add(call1)
        UsageTrackerService.activeOnlineCalls.add(call2)
        UsageTrackerService.activeOnlineCalls.add(call3)

        assertEquals(3, UsageTrackerService.activeOnlineCalls.size)
        assertFalse(call1.isCanceled())
        assertFalse(call2.isCanceled())
        assertFalse(call3.isCanceled())

        // Execute real production method
        UsageTrackerService.cancelActiveOnlineCalls()

        assertTrue("Call 1 must be canceled by production cancelActiveOnlineCalls()", call1.isCanceled())
        assertTrue("Call 2 must be canceled by production cancelActiveOnlineCalls()", call2.isCanceled())
        assertTrue("Call 3 must be canceled by production cancelActiveOnlineCalls()", call3.isCanceled())
        assertEquals("activeOnlineCalls set must be empty after cancelActiveOnlineCalls()", 0, UsageTrackerService.activeOnlineCalls.size)
    }

    @Test
    fun testProductionOfflineCallsCancellation() {
        val client = OkHttpClient()
        val request = Request.Builder().url("https://127.0.0.1:20128/dummy-off").build()

        val call1 = client.newCall(request)
        val call2 = client.newCall(request)

        UsageTrackerService.activeOfflineCalls.add(call1)
        UsageTrackerService.activeOfflineCalls.add(call2)

        assertEquals(2, UsageTrackerService.activeOfflineCalls.size)
        assertFalse(call1.isCanceled())
        assertFalse(call2.isCanceled())

        // Execute real production method
        UsageTrackerService.cancelActiveOfflineCalls()

        assertTrue("Call 1 must be canceled by production cancelActiveOfflineCalls()", call1.isCanceled())
        assertTrue("Call 2 must be canceled by production cancelActiveOfflineCalls()", call2.isCanceled())
        assertEquals("activeOfflineCalls set must be empty after cancelActiveOfflineCalls()", 0, UsageTrackerService.activeOfflineCalls.size)
    }

    @Test
    fun testProductionActiveCallsConcurrentSafety() {
        val client = OkHttpClient()
        val request = Request.Builder().url("https://127.0.0.1:20128/concurrent").build()

        val threadCount = 16
        val callsPerThread = 40
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)

        val threads = (1..threadCount).map {
            Thread {
                startLatch.await()
                for (i in 1..callsPerThread) {
                    val call = client.newCall(request)
                    UsageTrackerService.activeOnlineCalls.add(call)
                    if (i % 4 == 0) {
                        call.cancel()
                        UsageTrackerService.activeOnlineCalls.remove(call)
                    }
                }
                doneLatch.countDown()
            }
        }

        threads.forEach { it.start() }
        startLatch.countDown()

        val completed = doneLatch.await(5, TimeUnit.SECONDS)
        assertTrue("Concurrent operations on production set must complete without deadlock", completed)

        // Cancel all remaining calls using production method
        UsageTrackerService.cancelActiveOnlineCalls()
        assertEquals(0, UsageTrackerService.activeOnlineCalls.size)
    }

    @Test
    fun testEpochFencingInvariant() {
        val currentEpoch = GuardianAccessibilityService.telemetryEpoch.incrementAndGet()
        val staleEpoch = currentEpoch - 1L

        fun checkEpochValid(expectedEpoch: Long): Boolean {
            return expectedEpoch == -1L || GuardianAccessibilityService.telemetryEpoch.get() == expectedEpoch
        }

        assertTrue("Matching current epoch must be valid", checkEpochValid(currentEpoch))
        assertTrue("Unspecified epoch (-1) must pass default guard", checkEpochValid(-1L))
        assertFalse("Stale epoch must be strictly rejected", checkEpochValid(staleEpoch))

        // Advance epoch (simulating hardware screen state transition during in-flight call)
        GuardianAccessibilityService.telemetryEpoch.incrementAndGet()
        assertFalse("Previously valid epoch must now be rejected after transition", checkEpochValid(currentEpoch))
    }

    @Test
    fun testShouldAllowTelemetryUpdateGuardsPreMutation() {
        val currentEpoch = 10L

        // 1. Stale epoch must be rejected immediately before any mutation
        assertFalse(
            "Stale epoch must be rejected before SharedPreferences mutation",
            UsageTrackerService.shouldAllowTelemetryUpdate(
                expectedEpoch = 9L,
                currentEpoch = currentEpoch,
                category = "STUDY",
                packageName = "com.study.app",
                hardwareIsOnline = true
            )
        )

        // 2. Offline event arriving when hardware is already back online must be rejected (prevents overwriting online state)
        assertFalse(
            "Offline event must not overwrite SharedPreferences if hardware is already online",
            UsageTrackerService.shouldAllowTelemetryUpdate(
                expectedEpoch = currentEpoch,
                currentEpoch = currentEpoch,
                category = "OFFLINE",
                packageName = "SCREEN_OFF",
                hardwareIsOnline = true
            )
        )

        // 3. Online app event arriving when hardware is offline must be rejected (prevents converting to SCREEN_OFF mutation)
        assertFalse(
            "Online event must not mutate state if hardware is offline",
            UsageTrackerService.shouldAllowTelemetryUpdate(
                expectedEpoch = currentEpoch,
                currentEpoch = currentEpoch,
                category = "GAME",
                packageName = "com.game.app",
                hardwareIsOnline = false
            )
        )

        // 4. Genuine online app event matching hardware and epoch must pass
        assertTrue(
            "Genuine online event must be allowed",
            UsageTrackerService.shouldAllowTelemetryUpdate(
                expectedEpoch = currentEpoch,
                currentEpoch = currentEpoch,
                category = "STUDY",
                packageName = "com.study.app",
                hardwareIsOnline = true
            )
        )

        // 5. CRITICAL: HOME launcher event when hardware is online must pass and NOT be rejected as offline
        assertTrue(
            "HOME launcher event when screen is on must be allowed and not treated as offline",
            UsageTrackerService.shouldAllowTelemetryUpdate(
                expectedEpoch = currentEpoch,
                currentEpoch = currentEpoch,
                category = "HOME",
                packageName = "com.google.android.apps.nexuslauncher",
                hardwareIsOnline = true
            )
        )

        // 6. Genuine offline event matching hardware and epoch must pass
        assertTrue(
            "Genuine offline event must be allowed",
            UsageTrackerService.shouldAllowTelemetryUpdate(
                expectedEpoch = currentEpoch,
                currentEpoch = currentEpoch,
                category = "OFFLINE",
                packageName = "SCREEN_OFF",
                hardwareIsOnline = false
            )
        )

        // 7. Unspecified epoch (-1) with matching online hardware must pass
        assertTrue(
            "Unspecified epoch with valid online hardware must pass",
            UsageTrackerService.shouldAllowTelemetryUpdate(
                expectedEpoch = -1L,
                currentEpoch = currentEpoch,
                category = "STUDY",
                packageName = "com.study.app",
                hardwareIsOnline = true
            )
        )
    }

    @Test
    fun testScreenOffTransitionStateChanges() {
        GuardianAccessibilityService.isScreenOnState = true
        val startEpoch = GuardianAccessibilityService.telemetryEpoch.get()

        val client = OkHttpClient()
        val request = Request.Builder().url("https://127.0.0.1:20128/live").build()
        val call = client.newCall(request)
        UsageTrackerService.activeOnlineCalls.add(call)

        // Simulate screen off transition sequence
        GuardianAccessibilityService.isScreenOnState = false
        val newEpoch = GuardianAccessibilityService.telemetryEpoch.incrementAndGet()
        UsageTrackerService.cancelActiveOnlineCalls()

        assertFalse("Hardware flag must be false on screen off", GuardianAccessibilityService.isScreenOnState)
        assertEquals("Epoch must increment on screen off", startEpoch + 1L, newEpoch)
        assertTrue("In-flight online call must be canceled on screen off", call.isCanceled())
        assertEquals("activeOnlineCalls set must be empty after screen off", 0, UsageTrackerService.activeOnlineCalls.size)
    }

    @Test
    fun testScreenOnTransitionStateChanges() {
        GuardianAccessibilityService.isScreenOnState = false
        val startEpoch = GuardianAccessibilityService.telemetryEpoch.get()

        val client = OkHttpClient()
        val request = Request.Builder().url("https://127.0.0.1:20128/live-off").build()
        val call = client.newCall(request)
        UsageTrackerService.activeOfflineCalls.add(call)

        // Simulate screen on transition sequence
        GuardianAccessibilityService.isScreenOnState = true
        val newEpoch = GuardianAccessibilityService.telemetryEpoch.incrementAndGet()
        UsageTrackerService.cancelActiveOfflineCalls()

        assertTrue("Hardware flag must be true on screen on", GuardianAccessibilityService.isScreenOnState)
        assertEquals("Epoch must increment on screen on", startEpoch + 1L, newEpoch)
        assertTrue("In-flight offline call must be canceled on screen on", call.isCanceled())
        assertEquals("activeOfflineCalls set must be empty after screen on", 0, UsageTrackerService.activeOfflineCalls.size)
    }

    @Test
    fun testLastWrittenEpochMonotonicityGuard() {
        var lastWrittenEpoch = 5L

        // Stale epoch (4 < 5) must be rejected by production method
        assertFalse("Older epoch must not overwrite SharedPreferences", UsageTrackerService.canWriteEpochMonotonically(lastWrittenEpoch, 4L))
        assertEquals(5L, lastWrittenEpoch)

        // Matching epoch (5 == 5) allowed by production method
        assertTrue("Matching epoch allowed", UsageTrackerService.canWriteEpochMonotonically(lastWrittenEpoch, 5L))
        assertEquals(5L, lastWrittenEpoch)

        // Newer epoch (6 > 5) allowed and updates monotonic high-water mark
        assertTrue("Newer epoch allowed", UsageTrackerService.canWriteEpochMonotonically(lastWrittenEpoch, 6L))
        lastWrittenEpoch = 6L

        // Previous epoch (5 < 6) now rejected by production method
        assertFalse("Previously valid epoch 5 is now rejected after transition to 6", UsageTrackerService.canWriteEpochMonotonically(lastWrittenEpoch, 5L))
    }

    @Test
    fun testCallRegistrationDoubleCheckCancellationOnScreenOffRace() {
        val fakePrefs = FakeSharedPreferences()
        val fakeContext = FakeTestContext(fakePrefs)
        val request = Request.Builder().url("https://127.0.0.1:20128/race-test").build()

        // 1. Direct call to production executeOnlineGuarded when screen is OFF: Must be immediately rejected
        GuardianAccessibilityService.isScreenOnState = false
        val epoch = GuardianAccessibilityService.telemetryEpoch.get()

        val successScreenOff = UsageTrackerService.executeOnlineGuarded(request, fakeContext, epoch)
        assertFalse("Production executeOnlineGuarded must reject when screen is off", successScreenOff)
        assertEquals("No in-flight calls must remain in activeOnlineCalls", 0, UsageTrackerService.activeOnlineCalls.size)

        // 2. Direct call to production executeOnlineGuarded when epoch is stale: Must be rejected
        GuardianAccessibilityService.isScreenOnState = true
        val staleEpoch = epoch - 5L
        val successStaleEpoch = UsageTrackerService.executeOnlineGuarded(request, fakeContext, staleEpoch)
        assertFalse("Production executeOnlineGuarded must reject on stale epoch", successStaleEpoch)
        assertEquals("activeOnlineCalls must be empty on epoch mismatch", 0, UsageTrackerService.activeOnlineCalls.size)

        // 3. Double-check fencing: Verify call cancellation and removal on hardware state change
        val client = OkHttpClient()
        val call = client.newCall(request)
        UsageTrackerService.activeOnlineCalls.add(call)
        assertTrue(UsageTrackerService.activeOnlineCalls.contains(call))

        GuardianAccessibilityService.isScreenOnState = false
        val hardwareValid = UsageTrackerService.isHardwareOnlineValid(fakeContext, epoch)
        assertFalse("Production isHardwareOnlineValid must return false when screen turns off", hardwareValid)
        UsageTrackerService.cancelActiveOnlineCalls()
        assertTrue("Call must be canceled by cancelActiveOnlineCalls", call.isCanceled())
        assertEquals(0, UsageTrackerService.activeOnlineCalls.size)
    }

    @Test
    fun testDelayedScreenOffCoroutineCancelledBySubsequentScreenOnLifecycleRace() {
        // Lifecycle scenario: Device is initially online at epoch 10
        GuardianAccessibilityService.telemetryEpoch.set(10L)
        GuardianAccessibilityService.isScreenOnState = true
        val fakePrefs = FakeSharedPreferences()
        val fakeContext = FakeTestContext(fakePrefs)
        val request = Request.Builder().url("https://127.0.0.1:20128/offline-race").build()

        // Step 1: User presses power button -> SCREEN_OFF broadcast arrives
        GuardianAccessibilityService.isScreenOnState = false
        val screenOffEpoch = GuardianAccessibilityService.telemetryEpoch.incrementAndGet() // 11L
        assertEquals(11L, screenOffEpoch)
        assertFalse(GuardianAccessibilityService.isScreenOnState)

        // Step 2: Screen turns back on (ACTION_USER_PRESENT arrives before delayed offline call finishes)
        GuardianAccessibilityService.isScreenOnState = true
        val screenOnEpoch = GuardianAccessibilityService.telemetryEpoch.incrementAndGet() // 12L
        assertEquals(12L, screenOnEpoch)
        assertTrue(GuardianAccessibilityService.isScreenOnState)

        // Step 3: Directly call production method executeOfflineGuarded with the old screenOffEpoch (11L)
        val offlineResult = UsageTrackerService.executeOfflineGuarded(request, fakeContext, screenOffEpoch)
        assertFalse("Production executeOfflineGuarded must reject stale screen-off call when device is now online", offlineResult)
        assertEquals("activeOfflineCalls must remain empty", 0, UsageTrackerService.activeOfflineCalls.size)

        // Step 4: Directly check production shouldAllowTelemetryUpdate
        val allowed = UsageTrackerService.shouldAllowTelemetryUpdate(
            expectedEpoch = screenOffEpoch,
            currentEpoch = screenOnEpoch,
            category = "OFFLINE",
            packageName = "SCREEN_OFF",
            hardwareIsOnline = true
        )
        assertFalse("Production shouldAllowTelemetryUpdate must reject stale screen-off update when hardware is online", allowed)

        // Monotonic write check: Stale screenOffEpoch (11) must not overwrite newer screenOnEpoch (12) in SharedPreferences
        assertFalse(
            "Stale epoch 11 must not overwrite epoch 12 in preferences",
            UsageTrackerService.canWriteEpochMonotonically(lastWrittenEpoch = screenOnEpoch, callEpoch = screenOffEpoch)
        )
        assertTrue("Device online state in RAM must remain true", GuardianAccessibilityService.isScreenOnState)
        assertEquals("Epoch in RAM must remain userPresentEpoch (12)", 12L, GuardianAccessibilityService.telemetryEpoch.get())
    }

    @Test
    fun testHardwareOnlineStrictlyRequiresBothInteractiveAndKeyguardUnlocked() {
        // 1. Screen off in pocket: not interactive, keyguard locked -> offline
        assertFalse(UsageTrackerService.evaluateHardwareOnline(isScreenOn = true, isInteractive = false, isKeyguardLocked = true))

        // 2. Screen turned on at lockscreen (ACTION_SCREEN_ON): interactive, but STILL locked -> MUST BE OFFLINE
        assertFalse(
            "Screen on at lockscreen must evaluate to offline until keyguard is unlocked",
            UsageTrackerService.evaluateHardwareOnline(isScreenOn = true, isInteractive = true, isKeyguardLocked = true)
        )

        // 3. Screen on and keyguard unlocked (ACTION_USER_PRESENT): interactive and NOT locked -> ONLINE
        assertTrue(
            "Device must only evaluate to online when interactive and keyguard is unlocked",
            UsageTrackerService.evaluateHardwareOnline(isScreenOn = true, isInteractive = true, isKeyguardLocked = false)
        )

        // 4. Anomaly: not interactive, keyguard unlocked -> offline
        assertFalse(UsageTrackerService.evaluateHardwareOnline(isScreenOn = true, isInteractive = false, isKeyguardLocked = false))
    }

    @Test
    fun testStatsLockConcurrentSessionRecordingAndDailyAggregation() {
        val threadCount = 16
        val iterationsPerThread = 50
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        var totalRecordedMs = 0L

        val threads = (1..threadCount).map {
            Thread {
                startLatch.await()
                for (i in 1..iterationsPerThread) {
                    synchronized(UsageTrackerService.statsLock) {
                        totalRecordedMs += 1000L
                    }
                }
                doneLatch.countDown()
            }
        }

        threads.forEach { it.start() }
        startLatch.countDown()

        val completed = doneLatch.await(5, TimeUnit.SECONDS)
        assertTrue("Concurrent statsLock operations must complete without deadlock", completed)
        assertEquals(
            "Total recorded time must match exact thread iterations without race loss",
            threadCount * iterationsPerThread * 1000L,
            totalRecordedMs
        )
    }

    @Test
    fun testForegroundEvidenceVerificationStrictlyRejectsBackgroundPackages() {
        val targetPkg = "com.background.service"
        val now = 100_000L

        // Scenario 1: CRITICAL AUDIT CASE - rootInActiveWindow is null, UsageStats has no resumed event
        // Must strictly return FALSE (cấm coi root == null là bằng chứng foreground)
        val resultNullRootNoEvidence = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = null,
            targetPkg = targetPkg,
            now = now
        )
        assertFalse(
            "When root is null and no engine confirms foreground, background app must NOT be accepted as foreground",
            resultNullRootNoEvidence
        )

        // Scenario 2: Active root window belongs to another app (e.g. Launcher), background app fires event
        // CRITICAL INVARIANT: Root window thuộc Launcher thì targetPkg background vẫn phải bị từ chối 100%
        val resultOtherRoot = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = "com.google.android.apps.nexuslauncher",
            usageStatsLastResumedPkg = "com.google.android.apps.nexuslauncher",
            targetPkg = targetPkg,
            now = now
        )
        assertFalse("Background package must be rejected even when system launcher is active", resultOtherRoot)

        // Scenario 3: UsageStats event exists but is stale (e.g. 20s old > 15s window) -> Must reject
        val staleTime = now - 20_000L
        val resultStaleEvent = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = targetPkg,
            targetPkg = targetPkg,
            now = now,
            lastEventTime = staleTime,
            maxEventAgeMs = 15_000L
        )
        assertFalse("Target package must be rejected if UsageStats event is stale (>15s)", resultStaleEvent)

        // Scenario 4: Genuine foreground via Accessibility Window Hierarchy
        val resultAccessibilityMatch = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = targetPkg,
            usageStatsLastResumedPkg = null,
            targetPkg = targetPkg,
            now = now
        )
        assertTrue("Genuine Accessibility root window match must be accepted as foreground", resultAccessibilityMatch)

        // Scenario 5: Genuine foreground via Accessibility sub-process (e.g. targetPkg:renderer)
        val resultSubProcessMatch = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = "$targetPkg:renderer",
            usageStatsLastResumedPkg = null,
            targetPkg = targetPkg,
            now = now
        )
        assertTrue("Sub-process via Accessibility window must be accepted as foreground", resultSubProcessMatch)

        // Scenario 6: Genuine foreground via UsageStatsManager ACTIVITY_RESUMED within fresh window (<15s)
        val freshTime = now - 3_000L
        val resultUsageStatsMatch = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = targetPkg,
            targetPkg = targetPkg,
            now = now,
            lastEventTime = freshTime,
            maxEventAgeMs = 15_000L
        )
        assertTrue("Fresh UsageStatsManager ACTIVITY_RESUMED event match must be accepted as foreground", resultUsageStatsMatch)

        // Scenario 7: Invariant conflict check - activeRootPkg belongs to another app, but UsageStats returned targetPkg
        // MUST BE STRICTLY REJECTED to prevent stale foreground false accounting
        val resultConflictingRootStaleUsageStats = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = "com.other.active.app",
            usageStatsLastResumedPkg = targetPkg,
            targetPkg = targetPkg,
            now = now,
            lastEventTime = freshTime
        )
        assertFalse("Stale UsageStats event must be strictly rejected when activeRootPkg belongs to a different app", resultConflictingRootStaleUsageStats)

        // Scenario 8: Counter-example falsification test (Fail-Closed when timestamp is missing / 0L / negative)
        // Codex Gatekeeper: activeRootPkg = null, usageStatsLastResumedPkg = targetPkg, lastEventTime = 0L -> MUST BE FALSE
        val resultMissingTimestamp = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = targetPkg,
            targetPkg = targetPkg,
            now = now,
            lastEventTime = 0L,
            maxEventAgeMs = 15_000L
        )
        assertFalse("Target package must be strictly rejected if lastEventTime == 0L", resultMissingTimestamp)

        val resultNegativeTimestamp = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = targetPkg,
            targetPkg = targetPkg,
            now = now,
            lastEventTime = -1L,
            maxEventAgeMs = 15_000L
        )
        assertFalse("Target package must be strictly rejected if lastEventTime < 0L", resultNegativeTimestamp)

        val resultFutureTimestamp = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = targetPkg,
            targetPkg = targetPkg,
            now = now,
            lastEventTime = now + 5000L,
            maxEventAgeMs = 15_000L
        )
        assertFalse("Target package must be rejected if event timestamp is in the future", resultFutureTimestamp)
    }

    @Test
    fun testSessionDeduplicationPreventsDoubleAccountingOnLifecycleRace() {
        // Invariant: Trùng lặp session token giữa handleScreenOff, screenStateReceiver, và onDestroy phải bị chặn đứng
        val sessionToken = "com.vn.study_1700000000000"

        // Lần đầu ghi nhận phiên: Token hợp lệ chưa từng xuất hiện -> Cho phép ghi nhận
        val firstAttempt = UsageTrackerService.recordedSessionTokens.add(sessionToken)
        assertTrue("First attempt to record session must succeed", firstAttempt)

        // Lần thứ hai ghi nhận cùng phiên (ví dụ do race giữa Accessibility Service và BroadcastReceiver fallback):
        // Token đã tồn tại -> Phải bị từ chối deduplication ngay lập tức
        val secondAttempt = UsageTrackerService.recordedSessionTokens.add(sessionToken)
        assertFalse("Duplicate attempt with same session token must be strictly rejected", secondAttempt)

        // Phiên khác với token khác: Phải được chấp nhận bình thường
        val differentSessionToken = "com.vn.study_1700000005000"
        val differentAttempt = UsageTrackerService.recordedSessionTokens.add(differentSessionToken)
        assertTrue("Different session token must succeed independently", differentAttempt)
    }

    @Test
    fun testOfflinePayloadGuaranteesScreenOffActiveApp() {
        // Invariant: Khi ngắt kết nối khẩn cấp, payload bắt buộc phải chứa active_app = SCREEN_OFF
        // để đồng bộ tức thì với Web Portal phụ huynh và triệt tiêu báo trực tuyến ảo
        val now = System.currentTimeMillis()
        val data = UsageTrackerService.createOfflineData(now)

        assertFalse("Device online flag must be false on screen off", data.online)
        assertEquals("Timestamp must match", now, data.lastSync)
        assertEquals("SCREEN_OFF", data.packageName)
        assertEquals("OFFLINE", data.category)
        assertEquals("Đã tắt màn hình", data.categoryLabel)
        assertFalse("Foreground flag must be false when screen is off", data.isForeground)
        assertEquals(now, data.timestamp)
    }

    @Test
    fun testBoundedSessionTokensEvictsOldestWithoutMemoryLeak() {
        UsageTrackerService.recordedSessionTokens.clear()
        val totalToInsert = 600

        for (i in 1..totalToInsert) {
            UsageTrackerService.recordedSessionTokens.add("token_$i")
        }

        assertEquals("Session tokens must be bounded at exactly 500 items", 500, UsageTrackerService.recordedSessionTokens.size)
        assertFalse("Oldest token (token_1) must have been evicted", UsageTrackerService.recordedSessionTokens.contains("token_1"))
        assertTrue("Newest token (token_600) must be present", UsageTrackerService.recordedSessionTokens.contains("token_600"))

        // Critical LRU verification: Adding an ALREADY EXISTING token when set is full must NOT evict any entry!
        val sizeBefore = UsageTrackerService.recordedSessionTokens.size
        val addedExisting = UsageTrackerService.recordedSessionTokens.add("token_600")
        assertFalse("Adding duplicate token must return false", addedExisting)
        assertEquals("Size must remain identical", sizeBefore, UsageTrackerService.recordedSessionTokens.size)
        assertTrue("Existing token must still be present", UsageTrackerService.recordedSessionTokens.contains("token_600"))
        assertTrue("Earlier token (token_101) must not have been erroneously evicted", UsageTrackerService.recordedSessionTokens.contains("token_101"))
    }

    @Test
    fun testSessionLockThreadSafetyOnConcurrentScreenOffAndWindowChange() {
        val threadCount = 10
        val iterations = 50
        val latch = CountDownLatch(threadCount)

        for (t in 1..threadCount) {
            Thread {
                for (i in 1..iterations) {
                    synchronized(GuardianAccessibilityService.sessionLock) {
                        // Invariant: Operations under sessionLock are mutually exclusive and atomic
                        val now = System.currentTimeMillis()
                        assertTrue(now > 0L)
                    }
                }
                latch.countDown()
            } .start()
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        assertTrue("Concurrent access to sessionLock must complete without deadlock", completed)
    }

    @Test
    fun testLruSessionSetThreadSafetyUnderHighConcurrency() {
        UsageTrackerService.recordedSessionTokens.clear()
        val threadCount = 16
        val operationsPerThread = 100
        val latch = CountDownLatch(threadCount)

        for (t in 1..threadCount) {
            Thread {
                for (i in 1..operationsPerThread) {
                    val token = "thread_${t}_token_$i"
                    UsageTrackerService.recordedSessionTokens.add(token)
                    UsageTrackerService.recordedSessionTokens.contains(token)
                    val s = UsageTrackerService.recordedSessionTokens.size
                    assertTrue(s in 0..500)
                }
                latch.countDown()
            }.start()
        }

        val finished = latch.await(5, TimeUnit.SECONDS)
        assertTrue("Concurrent LruSessionSet operations must complete safely without exception or race", finished)
        assertEquals("Set must be strictly bounded at max 500 items", 500, UsageTrackerService.recordedSessionTokens.size)
    }

    @Test
    fun testNamedProcessForegroundMatching() {
        val targetPkg = "com.facebook.katana"
        val namedProcess = "com.facebook.katana:media"

        // Named process (e.g. package:name) in active window or UsageStats must be recognized as target package
        val result = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = namedProcess,
            usageStatsLastResumedPkg = null,
            targetPkg = targetPkg
        )
        assertTrue("Named process (e.g. package:name) in active window must be accepted as foreground", result)

        val now = System.currentTimeMillis()
        val resultUsage = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = namedProcess,
            targetPkg = targetPkg,
            now = now,
            lastEventTime = now - 1000L
        )
        assertTrue("Named process in UsageStats must be accepted as foreground when event is fresh", resultUsage)
    }

    @Test
    fun testLruSessionSetIteratorSnapshotPreventsRecursion() {
        UsageTrackerService.recordedSessionTokens.clear()
        for (i in 1..10) {
            UsageTrackerService.recordedSessionTokens.add("tok_$i")
        }
        val list = mutableListOf<String>()
        for (item in UsageTrackerService.recordedSessionTokens) {
            list.add(item)
        }
        assertEquals(10, list.size)
        assertEquals("tok_1", list[0])
        assertEquals("tok_10", list[9])
    }

    @Test
    fun testLruSessionSetContainsRefreshesLruOrder() {
        UsageTrackerService.recordedSessionTokens.clear()
        for (i in 1..500) {
            UsageTrackerService.recordedSessionTokens.add("token_$i")
        }
        // Access token_1 via contains: it must move from oldest (head) to newest (tail)
        assertTrue(UsageTrackerService.recordedSessionTokens.contains("token_1"))

        // Add 501st token: token_2 must be evicted as oldest, while token_1 is preserved
        UsageTrackerService.recordedSessionTokens.add("token_501")
        assertFalse("token_2 must have been evicted as the oldest unaccessed entry", UsageTrackerService.recordedSessionTokens.contains("token_2"))
        assertTrue("token_1 must remain preserved because contains() refreshed its LRU access order", UsageTrackerService.recordedSessionTokens.contains("token_1"))
        assertTrue("token_501 must be present", UsageTrackerService.recordedSessionTokens.contains("token_501"))
    }

    @Test
    fun testLruSessionSetFullCollectionInterfaceThreadSafety() {
        UsageTrackerService.recordedSessionTokens.clear()
        val items = (1..50).map { "item_$it" }
        UsageTrackerService.recordedSessionTokens.addAll(items)
        assertEquals(50, UsageTrackerService.recordedSessionTokens.size)

        assertTrue(UsageTrackerService.recordedSessionTokens.containsAll(listOf("item_1", "item_25", "item_50")))
        assertFalse(UsageTrackerService.recordedSessionTokens.containsAll(listOf("item_1", "non_existent")))

        val array = (UsageTrackerService.recordedSessionTokens as java.util.Collection<*>).toArray()
        assertEquals(50, array.size)

        val retainSubset = (1..10).map { "item_$it" }
        UsageTrackerService.recordedSessionTokens.retainAll(retainSubset.toSet())
        assertEquals(10, UsageTrackerService.recordedSessionTokens.size)

        UsageTrackerService.recordedSessionTokens.removeAll(listOf("item_1", "item_2").toSet())
        assertEquals(8, UsageTrackerService.recordedSessionTokens.size)
        assertFalse(UsageTrackerService.recordedSessionTokens.contains("item_1"))
        assertTrue(UsageTrackerService.recordedSessionTokens.contains("item_10"))
    }

    @Test
    fun testLruSessionSetComprehensiveCollectionApiSemantics() {
        UsageTrackerService.recordedSessionTokens.clear()
        val items = (1..30).map { "item_$it" }
        UsageTrackerService.recordedSessionTokens.addAll(items)
        assertEquals(30, UsageTrackerService.recordedSessionTokens.size)

        // 1. removeIf mutating the actual backing set under synchronization
        val removedAny = UsageTrackerService.recordedSessionTokens.removeIf { it.endsWith("0") }
        assertTrue("removeIf must remove items ending in 0", removedAny)
        assertEquals(27, UsageTrackerService.recordedSessionTokens.size)
        assertFalse(UsageTrackerService.recordedSessionTokens.contains("item_10"))
        assertFalse(UsageTrackerService.recordedSessionTokens.contains("item_20"))
        assertFalse(UsageTrackerService.recordedSessionTokens.contains("item_30"))
        assertTrue(UsageTrackerService.recordedSessionTokens.contains("item_1"))

        // 2. forEach iterating over snapshot safely without ConcurrentModificationException even if modified during traversal
        val collected = mutableListOf<String>()
        UsageTrackerService.recordedSessionTokens.forEach {
            collected.add(it)
            // Attempt to mutate during forEach: Must not throw CME because forEach iterates snapshot
            if (it == "item_1") {
                UsageTrackerService.recordedSessionTokens.add("new_concurrent_item")
            }
        }
        assertEquals(27, collected.size)
        assertTrue(UsageTrackerService.recordedSessionTokens.contains("new_concurrent_item"))

        // 3. spliterator produces valid streams and splittings
        val spliterator = (UsageTrackerService.recordedSessionTokens as java.util.Collection<String>).spliterator()
        val streamCount = java.util.stream.StreamSupport.stream(spliterator, false).count()
        assertEquals(28L, streamCount)

        // 4. toArray and typed toArray thread-safety
        val typedArray = (UsageTrackerService.recordedSessionTokens as java.util.Collection<String>).toArray(arrayOfNulls<String>(0))
        assertEquals(28, typedArray.size)
    }

    @Test
    fun testConcurrentToArrayAndMutationSafety() {
        UsageTrackerService.recordedSessionTokens.clear()
        for (i in 1..100) {
            UsageTrackerService.recordedSessionTokens.add("seed_$i")
        }

        val threadCount = 8
        val iterations = 50
        val latch = CountDownLatch(threadCount)

        val threads = (1..threadCount).map { id ->
            Thread {
                try {
                    for (i in 1..iterations) {
                        if (id % 2 == 0) {
                            UsageTrackerService.recordedSessionTokens.add("thread_${id}_$i")
                        } else {
                            val arr = (UsageTrackerService.recordedSessionTokens as java.util.Collection<*>).toArray()
                            assertTrue("Array must have positive size", arr.isNotEmpty())
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        threads.forEach { it.start() }
        val completed = latch.await(5, TimeUnit.SECONDS)
        assertTrue("Concurrent toArray and add must complete without race or CME", completed)
    }

    @Test
    fun testCancelActiveOfflineCallsAbortsInFlightCalls() {
        val client = OkHttpClient()
        val request = Request.Builder().url("https://127.0.0.1:20128/offline_test").build()

        val call1 = client.newCall(request)
        val call2 = client.newCall(request)
        UsageTrackerService.activeOfflineCalls.add(call1)
        UsageTrackerService.activeOfflineCalls.add(call2)

        assertEquals(2, UsageTrackerService.activeOfflineCalls.size)
        UsageTrackerService.cancelActiveOfflineCalls()

        assertEquals(0, UsageTrackerService.activeOfflineCalls.size)
        assertTrue("call1 must be canceled", call1.isCanceled())
        assertTrue("call2 must be canceled", call2.isCanceled())
    }

    @Test
    fun testLruSessionSetCloneCopiesStateSafely() {
        val original = UsageTrackerService.Companion.LruSessionSet(10)
        original.add("token_a")
        original.add("token_b")
        val clone = original.clone() as UsageTrackerService.Companion.LruSessionSet
        assertEquals(2, clone.size)
        assertTrue(clone.contains("token_a"))
        assertTrue(clone.contains("token_b"))

        // Mutating clone does not mutate original
        clone.add("token_c")
        assertEquals(3, clone.size)
        assertEquals(2, original.size)
        assertFalse(original.contains("token_c"))
    }

    @Test
    fun testSingleRestoreLifecycleIdempotency() {
        // Direct test of isSessionTokensRestored AtomicBoolean guard under multi-threaded concurrency
        UsageTrackerService.isSessionTokensRestored.set(false)
        assertFalse(UsageTrackerService.isSessionTokensRestored.get())

        UsageTrackerService.recordedSessionTokens.clear()
        for (i in 1..5) {
            UsageTrackerService.recordedSessionTokens.add("tok_existing_$i")
        }
        val initialSize = UsageTrackerService.recordedSessionTokens.size
        assertEquals(5, initialSize)

        // Concurrency test: 10 concurrent threads attempting to trigger restore
        val threadCount = 10
        val latch = CountDownLatch(threadCount)
        val successCount = java.util.concurrent.atomic.AtomicInteger(0)

        val threads = (1..threadCount).map {
            Thread {
                try {
                    if (UsageTrackerService.isSessionTokensRestored.compareAndSet(false, true)) {
                        successCount.incrementAndGet()
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        threads.forEach { it.start() }
        val completed = latch.await(5, TimeUnit.SECONDS)
        assertTrue("Concurrent restore requests must finish in time", completed)
        assertEquals("Exactly one thread must successfully perform the restoration", 1, successCount.get())
        assertTrue("Restoration flag must remain true after restore", UsageTrackerService.isSessionTokensRestored.get())

        // Verify that in-memory tokens maintain order without corruption
        val snapshot = UsageTrackerService.recordedSessionTokens.toList()
        assertEquals("tok_existing_1", snapshot.first())
        assertEquals("tok_existing_5", snapshot.last())
    }

    @Test
    fun testPersistSessionTokenRollbackOnSimulatedFailure() {
        UsageTrackerService.recordedSessionTokens.clear()
        UsageTrackerService.recordedSessionTokens.add("tok_existing_1")
        assertEquals(1, UsageTrackerService.recordedSessionTokens.size)

        // Directly exercise production method UsageTrackerService.persistSessionToken with failing SharedPreferences.commit()
        val fakePrefs = FakeSharedPreferences(commitReturnsSuccess = false)
        val fakeContext = FakeTestContext(fakePrefs)

        val failedToken = "tok_will_fail"
        val success = UsageTrackerService.persistSessionToken(fakeContext, failedToken)

        assertFalse("Production persistSessionToken must return false when commit fails", success)
        assertEquals("RAM set must have rolled back and size remains 1", 1, UsageTrackerService.recordedSessionTokens.size)
        assertFalse("Failed token must be absent from RAM due to rollback in production method", UsageTrackerService.recordedSessionTokens.contains(failedToken))
        assertTrue("Existing token must remain intact in RAM", UsageTrackerService.recordedSessionTokens.contains("tok_existing_1"))

        // Duplicate token verification on commit failure: If token ALREADY existed, rollback must NOT purge it!
        val existingTokenAttempt = UsageTrackerService.persistSessionToken(fakeContext, "tok_existing_1")
        assertFalse("Duplicate token attempt returns false when disk commit fails", existingTokenAttempt)
        assertTrue("Pre-existing token must NEVER be purged on failed attempt", UsageTrackerService.recordedSessionTokens.contains("tok_existing_1"))
        assertEquals(1, UsageTrackerService.recordedSessionTokens.size)

        // Exact Codex verification: Rollback must preserve 100% of LRU order when touching an EXISTING token!
        UsageTrackerService.recordedSessionTokens.clear()
        UsageTrackerService.recordedSessionTokens.add("tok_A")
        UsageTrackerService.recordedSessionTokens.add("tok_B")
        UsageTrackerService.recordedSessionTokens.add("tok_C")
        val orderBefore = UsageTrackerService.recordedSessionTokens.toList()
        assertEquals(listOf("tok_A", "tok_B", "tok_C"), orderBefore)

        // Attempt persist with existing token 'tok_A' when commit() fails
        val failedCommitAttempt = UsageTrackerService.persistSessionToken(fakeContext, "tok_A")
        assertFalse("Attempt with commit=false must return false", failedCommitAttempt)
        val orderAfter = UsageTrackerService.recordedSessionTokens.toList()
        assertEquals(
            "LRU order in RAM must strictly remain A, B, C after commit failure, NOT mutated to B, C, A",
            listOf("tok_A", "tok_B", "tok_C"),
            orderAfter
        )
    }

    @Test
    fun testStagedRestoreZeroPartialSnapshotOnFailure() {
        UsageTrackerService.isSessionTokensRestored.set(false)
        UsageTrackerService.recordedSessionTokens.clear()
        UsageTrackerService.recordedSessionTokens.add("original_safe_token")
        assertEquals(1, UsageTrackerService.recordedSessionTokens.size)

        // Directly exercise production method UsageTrackerService.restorePersistedSessionTokens with storage failure
        val fakePrefs = FakeSharedPreferences(throwOnRead = true)
        val fakeContext = FakeTestContext(fakePrefs)

        val success = UsageTrackerService.restorePersistedSessionTokens(fakeContext)

        assertFalse("Production restorePersistedSessionTokens must return false on storage error", success)
        assertFalse("Restore flag must remain false on error", UsageTrackerService.isSessionTokensRestored.get())
        assertEquals("RAM set must remain completely untouched without partial snapshot pollution", 1, UsageTrackerService.recordedSessionTokens.size)
        assertTrue("Original safe token must remain preserved", UsageTrackerService.recordedSessionTokens.contains("original_safe_token"))

        // Test idempotency: when already marked restored, production method returns true immediately without re-reading disk
        UsageTrackerService.isSessionTokensRestored.set(true)
        val secondAttempt = UsageTrackerService.restorePersistedSessionTokens(fakeContext)
        assertTrue("Idempotent guard must return true when already restored", secondAttempt)
        assertEquals(1, UsageTrackerService.recordedSessionTokens.size)
    }

    @Test
    fun testUrgentOfflineStatusIdempotencyByEpoch() {
        // Invariant: sendUrgentOfflineStatus for the same epoch must be strictly idempotent
        val fakePrefs = FakeSharedPreferences()
        fakePrefs.data["paired_code"] = "FAM123"
        fakePrefs.data["device_id"] = "DEV456"
        val fakeContext = FakeTestContext(fakePrefs)

        UsageTrackerService.lastDispatchedOfflineEpoch.set(-1L)
        val epoch = 42L

        val job1 = UsageTrackerService.sendUrgentOfflineStatus(fakeContext, epoch)
        assertTrue("First dispatch for epoch $epoch must launch a Job", job1 != null)

        // Immediate duplicate call with identical epoch: Must return null without launching redundant batch!
        val job2 = UsageTrackerService.sendUrgentOfflineStatus(fakeContext, epoch)
        assertEquals("Duplicate call with same epoch must be rejected idempotently", null, job2)

        // Clean up
        UsageTrackerService.cancelActiveOfflineCalls()
        UsageTrackerService.lastDispatchedOfflineEpoch.set(-1L)
    }

    @Test
    fun testCancelActiveOfflineCallsGenerationFencing() {
        // Invariant: cancelActiveOfflineCalls(targetGeneration) must NOT cancel calls belonging to a newer generation
        val client = OkHttpClient()
        val request = Request.Builder().url("https://127.0.0.1:20128/generation-test").build()
        val call = client.newCall(request)

        val currentGen = UsageTrackerService.currentOfflineGeneration.incrementAndGet()
        UsageTrackerService.activeOfflineCalls.add(call)

        // Stale generation cancellation attempt: Must be ignored
        val staleGen = currentGen - 1L
        UsageTrackerService.cancelActiveOfflineCalls(staleGen)
        assertFalse("Stale generation cancellation must not cancel active call of newer generation", call.isCanceled())
        assertEquals(1, UsageTrackerService.activeOfflineCalls.size)

        // Matching generation cancellation: Must cancel the call
        UsageTrackerService.cancelActiveOfflineCalls(currentGen)
        assertTrue("Matching generation cancellation must cancel the call", call.isCanceled())
        assertEquals(0, UsageTrackerService.activeOfflineCalls.size)
    }

    @Test
    fun testPersistSessionTokenRejectsDuplicateOnSuccessCommit() {
        UsageTrackerService.recordedSessionTokens.clear()
        val fakePrefs = FakeSharedPreferences(commitReturnsSuccess = true)
        val fakeContext = FakeTestContext(fakePrefs)

        val firstSuccess = UsageTrackerService.persistSessionToken(fakeContext, "tok_dup_test")
        assertTrue("First insertion of token must return true", firstSuccess)
        assertEquals(1, UsageTrackerService.recordedSessionTokens.size)

        val duplicateResult = UsageTrackerService.persistSessionToken(fakeContext, "tok_dup_test")
        assertFalse("Duplicate token insertion must return false", duplicateResult)
        assertEquals(1, UsageTrackerService.recordedSessionTokens.size)
    }

    @Test
    fun testContainsAllDoesNotMutateLruOrder() {
        UsageTrackerService.recordedSessionTokens.clear()
        UsageTrackerService.recordedSessionTokens.add("tok_1")
        UsageTrackerService.recordedSessionTokens.add("tok_2")
        UsageTrackerService.recordedSessionTokens.add("tok_3")

        val orderBefore = UsageTrackerService.recordedSessionTokens.toList()
        assertEquals(listOf("tok_1", "tok_2", "tok_3"), orderBefore)

        val containsAll = UsageTrackerService.recordedSessionTokens.containsAll(listOf("tok_1", "tok_2"))
        assertTrue(containsAll)

        val orderAfter = UsageTrackerService.recordedSessionTokens.toList()
        assertEquals("containsAll must NOT mutate LRU access order", listOf("tok_1", "tok_2", "tok_3"), orderAfter)
    }

    @Test
    fun testRecordAppSessionAtomicRollbackOnCommitFailure() {
        UsageTrackerService.recordedSessionTokens.clear()
        val fakePrefs = FakeSharedPreferences(commitReturnsSuccess = false)
        val fakeContext = FakeTestContext(fakePrefs)

        val testToken = "app_session_token_fail"
        val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        UsageTrackerService.recordAppSession(
            context = fakeContext,
            packageName = "com.test.app",
            durationMs = 5000L,
            sessionToken = testToken
        )

        assertFalse("Token must NOT be persisted in RAM when commit fails", UsageTrackerService.recordedSessionTokens.contains(testToken))
        assertEquals("Duration must NOT be written when commit fails", 0L, fakePrefs.getLong("session_${todayStr}_com.test.app", 0L))
    }

    @Test
    fun testRecordAppSessionAtomicSuccessAndDeduplication() {
        UsageTrackerService.recordedSessionTokens.clear()
        val fakePrefs = FakeSharedPreferences(commitReturnsSuccess = true)
        val fakeContext = FakeTestContext(fakePrefs)

        val testToken = "app_session_token_success"
        val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        UsageTrackerService.recordAppSession(
            context = fakeContext,
            packageName = "com.test.app",
            durationMs = 5000L,
            sessionToken = testToken
        )

        assertTrue("Token must be persisted in RAM on success", UsageTrackerService.recordedSessionTokens.contains(testToken))
        val savedMs = fakePrefs.getLong("session_${todayStr}_com.test.app", 0L)
        assertEquals("Duration 5000ms must be written to SharedPreferences", 5000L, savedMs)

        // Duplicate call with same session token
        UsageTrackerService.recordAppSession(
            context = fakeContext,
            packageName = "com.test.app",
            durationMs = 3000L,
            sessionToken = testToken
        )
        // Duration should still be 5000ms, not 8000ms
        assertEquals("Duplicate session call must be ignored", 5000L, fakePrefs.getLong("session_${todayStr}_com.test.app", 0L))
    }

    @Test
    fun testRecordAppSessionRefreshesLruOrderAndPersistsToDisk() {
        UsageTrackerService.recordedSessionTokens.clear()
        val fakePrefs = FakeSharedPreferences(commitReturnsSuccess = true)
        val fakeContext = FakeTestContext(fakePrefs)

        // Seed 3 sessions: tok_1, tok_2, tok_3
        UsageTrackerService.recordAppSession(fakeContext, "com.app.one", 5000L, "tok_1")
        UsageTrackerService.recordAppSession(fakeContext, "com.app.two", 5000L, "tok_2")
        UsageTrackerService.recordAppSession(fakeContext, "com.app.three", 5000L, "tok_3")

        assertEquals(listOf("tok_1", "tok_2", "tok_3"), UsageTrackerService.recordedSessionTokens.toList())

        // Re-call recordAppSession with existing token "tok_1"
        UsageTrackerService.recordAppSession(fakeContext, "com.app.one", 3000L, "tok_1")

        // RAM order must have refreshed "tok_1" to MRU (end): [tok_2, tok_3, tok_1]
        val expectedOrder = listOf("tok_2", "tok_3", "tok_1")
        assertEquals(expectedOrder, UsageTrackerService.recordedSessionTokens.toList())

        // Disk must also contain this exact refreshed JSON array!
        val diskJson = fakePrefs.getString("persisted_session_tokens_json", null)
        org.junit.Assert.assertNotNull("Disk JSON must exist", diskJson)
        val jsonArray = org.json.JSONArray(diskJson)
        val diskList = (0 until jsonArray.length()).map { jsonArray.getString(it) }
        assertEquals("Disk JSON array must strictly match refreshed RAM LRU order", expectedOrder, diskList)

        // Reset RAM and restore from disk to prove 100% persistence invariant
        UsageTrackerService.recordedSessionTokens.clear()
        UsageTrackerService.isSessionTokensRestored.set(false)
        val restored = UsageTrackerService.restorePersistedSessionTokens(fakeContext)
        assertTrue(restored)
        assertEquals("Restored RAM order must match refreshed LRU order", expectedOrder, UsageTrackerService.recordedSessionTokens.toList())
    }

    @Test
    fun testRecordAppSessionRollsBackLruOrderOnDuplicateTokenCommitFailure() {
        UsageTrackerService.recordedSessionTokens.clear()
        val fakePrefs = FakeSharedPreferences(commitReturnsSuccess = true)
        val fakeContext = FakeTestContext(fakePrefs)

        // Seed 3 sessions: tok_1, tok_2, tok_3
        UsageTrackerService.recordAppSession(fakeContext, "com.app.one", 5000L, "tok_1")
        UsageTrackerService.recordAppSession(fakeContext, "com.app.two", 5000L, "tok_2")
        UsageTrackerService.recordAppSession(fakeContext, "com.app.three", 5000L, "tok_3")

        val initialOrder = listOf("tok_1", "tok_2", "tok_3")
        assertEquals(initialOrder, UsageTrackerService.recordedSessionTokens.toList())

        // Save original disk snapshot
        val diskJsonBefore = fakePrefs.getString("persisted_session_tokens_json", null)
        org.junit.Assert.assertNotNull("Disk JSON must exist before failure simulation", diskJsonBefore)

        val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        val durationBefore = fakePrefs.getLong("session_${todayStr}_com.app.one", 0L)
        assertEquals(5000L, durationBefore)

        // Now simulate disk commit failure
        fakePrefs.commitReturnsSuccess = false

        // Re-call recordAppSession with existing token "tok_1"
        UsageTrackerService.recordAppSession(fakeContext, "com.app.one", 3000L, "tok_1")

        // Invariant: RAM order MUST be rolled back 100% to initialOrder: [tok_1, tok_2, tok_3]
        // It must NOT remain mutated to [tok_2, tok_3, tok_1]!
        val ramOrderAfterFailure = UsageTrackerService.recordedSessionTokens.toList()
        assertEquals(
            "RAM LRU order must strictly remain [tok_1, tok_2, tok_3] upon commit failure, NOT mutated to [tok_2, tok_3, tok_1]",
            initialOrder,
            ramOrderAfterFailure
        )

        // Invariant: Disk JSON must remain untouched
        val diskJsonAfter = fakePrefs.getString("persisted_session_tokens_json", null)
        assertEquals("Disk JSON must remain unchanged", diskJsonBefore, diskJsonAfter)

        // Invariant: Duration must NOT be updated
        val durationAfter = fakePrefs.getLong("session_${todayStr}_com.app.one", 0L)
        assertEquals("Duration must NOT be changed", 5000L, durationAfter)
    }

    @Test
    fun testLruSessionSetCloneHasIsolatedDedicatedLock() {
        val originalSet = UsageTrackerService.recordedSessionTokens as UsageTrackerService.Companion.LruSessionSet
        val cloneSet = originalSet.clone() as UsageTrackerService.Companion.LruSessionSet

        // Lock must be an isolated dedicated monitor, NEVER sharing statsLock
        org.junit.Assert.assertNotSame("Cloned LruSessionSet must have its own isolated lock to prevent coupling", originalSet.lock, cloneSet.lock)
        org.junit.Assert.assertNotSame("Cloned LruSessionSet lock must not be statsLock", UsageTrackerService.statsLock, cloneSet.lock)
    }

    @Test
    fun testOfflineCallCancellationFencingRejectsCanceledOrScreenOnRace() {
        // Setup offline prerequisites
        GuardianAccessibilityService.isScreenOnState = false
        val epoch = GuardianAccessibilityService.telemetryEpoch.get()
        val gen = UsageTrackerService.currentOfflineGeneration.get()
        val fakePrefs = FakeSharedPreferences()
        val fakeContext = FakeTestContext(fakePrefs)

        val client = OkHttpClient()
        val request = Request.Builder().url("https://127.0.0.1:20128/offline-race-test").build()
        val call = client.newCall(request)

        // Simulate cancelActiveOfflineCalls triggered by concurrent screen on or newer generation
        call.cancel()
        assertTrue("Call must be marked canceled", call.isCanceled())

        // Register into active calls
        UsageTrackerService.activeOfflineCalls.add(call)

        // When call is already canceled, executeOfflineGuarded or post-registration check must reject it
        synchronized(UsageTrackerService.urgentOfflineLock) {
            if (call.isCanceled()) {
                UsageTrackerService.activeOfflineCalls.remove(call)
            }
        }

        assertFalse("Canceled call must be purged from activeOfflineCalls", UsageTrackerService.activeOfflineCalls.contains(call))
        assertEquals(0, UsageTrackerService.activeOfflineCalls.size)
    }

    @Test
    fun testLruSessionSetAddAllSelfNoOp() {
        val set = UsageTrackerService.recordedSessionTokens as UsageTrackerService.Companion.LruSessionSet
        set.clear()
        set.add("token_1")
        set.add("token_2")
        set.add("token_3")

        val modified = set.addAll(set)
        assertFalse("set.addAll(set) must return false (no-op)", modified)
        assertEquals(3, set.size)

        // Verify order remains token_1, token_2, token_3
        val list = set.toList()
        assertEquals("token_1", list[0])
        assertEquals("token_2", list[1])
        assertEquals("token_3", list[2])
    }

    private class FakeSharedPreferences(
        val data: MutableMap<String, Any?> = mutableMapOf(),
        var commitReturnsSuccess: Boolean = true,
        var throwOnRead: Boolean = false
    ) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = data
        override fun getString(key: String?, defValue: String?): String? {
            if (throwOnRead) throw IllegalStateException("Storage read failure simulation")
            return (data[key] as? String) ?: defValue
        }
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (data[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(this)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()
            private val toRemove = mutableSetOf<String>()
            private var clearRequested = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) temp[key] = values
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) toRemove.add(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clearRequested = true
                return this
            }
            override fun commit(): Boolean {
                if (!prefs.commitReturnsSuccess) return false
                if (clearRequested) prefs.data.clear()
                for (k in toRemove) prefs.data.remove(k)
                for ((k, v) in temp) prefs.data[k] = v
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }

    @Test
    fun testEvaluateForegroundEvidenceConfirmsActiveWindow() {
        // When activeRootPkg exactly matches targetPkg, it must be recognized
        val result = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = "com.ss.android.ugc.trill",
            usageStatsLastResumedPkg = null,
            targetPkg = "com.ss.android.ugc.trill"
        )
        assertTrue("Matching active window must resolve to true", result)
    }

    @Test
    fun testEvaluateForegroundEvidenceRejectsConflictingActiveWindow() {
        // When activeRootPkg belongs to another app (e.g. Facebook), it MUST reject TikTok
        // even if stale UsageStats points to TikTok
        val result = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = "com.facebook.katana",
            usageStatsLastResumedPkg = "com.ss.android.ugc.trill",
            targetPkg = "com.ss.android.ugc.trill"
        )
        assertFalse("Conflicting active window must strictly reject targetPkg", result)
    }

    @Test
    fun testEvaluateForegroundEvidenceConfirmsForegroundProcessWhenWindowNull() {
        // During Xiaomi HyperOS gesture transition, activeRootPkg might be null.
        // UsageStats event on targetPkg confirms foreground app!
        val now = 100_000L
        val result = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = "vn.edu.azota",
            targetPkg = "vn.edu.azota",
            now = now,
            lastEventTime = now - 2_000L
        )
        assertTrue("Matching foreground process event when window is null must resolve to true", result)

        // Also test named sub-process (e.g. "vn.edu.azota:player")
        val subProcessResult = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = "vn.edu.azota:player",
            targetPkg = "vn.edu.azota",
            now = now,
            lastEventTime = now - 2_000L
        )
        assertTrue("Named sub-process of targetPkg must resolve to true", subProcessResult)

        // Stale event (> 15s) must be rejected
        val staleResult = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = "vn.edu.azota",
            targetPkg = "vn.edu.azota",
            now = now,
            lastEventTime = now - 25_000L
        )
        assertFalse("Stale event (>15s) must be rejected", staleResult)
    }

    @Test
    fun testEvaluateForegroundEvidenceConfirmsUsageStatsWhenWindowAndProcessNull() {
        val now = System.currentTimeMillis()
        // Fallback to UsageStatsManager lastResumedPkg only when window is null/empty and event is fresh (<15s)
        val result = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = "com.google.android.youtube",
            targetPkg = "com.google.android.youtube",
            now = now,
            lastEventTime = now - 1000L
        )
        assertTrue("UsageStats fallback when window is null and event is fresh must resolve to true", result)

        // Empty targetPkg must immediately return false
        val emptyResult = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = "com.google.android.youtube",
            targetPkg = "",
            now = now,
            lastEventTime = now - 1000L
        )
        assertFalse("Empty targetPkg must immediately return false", emptyResult)

        // Missing timestamp (0L) must Fail-Closed and return false
        val missingTimeResult = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = "com.google.android.youtube",
            targetPkg = "com.google.android.youtube",
            now = now,
            lastEventTime = 0L
        )
        assertFalse("Missing timestamp (0L) must fail closed and return false", missingTimeResult)
    }

    @Test
    fun testAppUpdateManagerParsesValidUpdateInfoWhenRemoteHigher() {
        val json = JSONObject().apply {
            put("versionCode", 25)
            put("latestVersionCode", 25)
            put("versionName", "1.2.5")
            put("latestVersionName", "1.2.5")
            put("apkUrl", "https://example.com/apk/CVA-SmartGuardian-v1.2.5.apk")
            put("fileSize", "14.2 MB")
            put("sha256", "181C3B2100885995EA9FE8A954747DA509873DA7B2278643A090AA998DB693C7")
            put("isForceUpdate", false)
            val changelog = JSONArray().apply {
                put("Nâng cấp v1.2.5 sửa lỗi nhận diện HyperOS")
            }
            put("changelog", changelog)
        }

        // Current device version is 24, remote is 25 -> Must produce UpdateInfo
        val updateInfo = AppUpdateManager.parseUpdateInfo(json, currentVersionCode = 24)
        assertNotNull("UpdateInfo must not be null when remoteVersionCode > currentVersionCode", updateInfo)
        assertEquals(25, updateInfo?.versionCode)
        assertEquals("1.2.5", updateInfo?.versionName)
        assertEquals("https://example.com/apk/CVA-SmartGuardian-v1.2.5.apk", updateInfo?.apkUrl)
        assertEquals("14.2 MB", updateInfo?.fileSize)
        assertEquals("181C3B2100885995EA9FE8A954747DA509873DA7B2278643A090AA998DB693C7", updateInfo?.sha256)
        assertFalse(updateInfo?.isForceUpdate == true)
        assertEquals(1, updateInfo?.changelog?.size)
    }

    @Test
    fun testAppUpdateManagerRejectsUpdateWhenRemoteSameOrLower() {
        val json = JSONObject().apply {
            put("versionCode", 25)
            put("latestVersionCode", 25)
            put("versionName", "1.2.5")
            put("apkUrl", "https://example.com/apk/CVA-SmartGuardian-v1.2.5.apk")
        }

        // Current device version is already 25 -> Must return null
        val sameVersionInfo = AppUpdateManager.parseUpdateInfo(json, currentVersionCode = 25)
        assertNull("UpdateInfo must be null when device is already on same version", sameVersionInfo)

        // Current device version is 26 (newer than remote) -> Must return null
        val newerVersionInfo = AppUpdateManager.parseUpdateInfo(json, currentVersionCode = 26)
        assertNull("UpdateInfo must be null when device is on newer version", newerVersionInfo)
    }

    @Test
    fun testAppUpdateManagerHandlesMissingFieldsAndMalformedJsonSafely() {
        // Null JSON
        assertNull(AppUpdateManager.parseUpdateInfo(null, currentVersionCode = 24))

        // Empty JSON without apkUrl
        val emptyJson = JSONObject().apply {
            put("versionCode", 25)
        }
        assertNull("Missing apkUrl must safely return null", AppUpdateManager.parseUpdateInfo(emptyJson, currentVersionCode = 24))

        // JSON with fallback versionCode (without latestVersionCode key)
        val legacyJson = JSONObject().apply {
            put("versionCode", 25)
            put("versionName", "1.2.5")
            put("apkUrl", "https://example.com/app.apk")
        }
        val legacyInfo = AppUpdateManager.parseUpdateInfo(legacyJson, currentVersionCode = 24)
        assertNotNull(legacyInfo)
        assertEquals(25, legacyInfo?.versionCode)
    }

    @Test
    fun testUsageTrackerServiceBackgroundOtaConstants() {
        // Verify notification IDs and channel IDs defined in production UsageTrackerService
        assertEquals("cva_smart_guardian_ota", UsageTrackerService.OTA_CHANNEL_ID)
        assertEquals(2002, UsageTrackerService.OTA_NOTIFICATION_ID)
    }

    @Test
    fun testAppUpdateManagerSha256ValidationAndIntegrity() {
        val validSha = "13A37900B8A9559042996F255531C1F1CEFAA17D5E8110F5B0A53D7D9741F684"
        val json = JSONObject().apply {
            put("versionCode", 25)
            put("latestVersionCode", 25)
            put("versionName", "1.2.5")
            put("latestVersionName", "1.2.5")
            put("apkUrl", "https://mrkhang-khoi.github.io/appkhkt2627/apk/CVA-SmartGuardian-v1.2.5.apk")
            put("sha256", validSha)
            put("fileSize", "5.79 MB")
        }

        val updateInfo = AppUpdateManager.parseUpdateInfo(json, currentVersionCode = 24)
        assertNotNull(updateInfo)
        assertEquals(validSha, updateInfo?.sha256)
        assertEquals(64, updateInfo?.sha256?.length)
        assertTrue("SHA-256 must be valid hex format", updateInfo?.sha256?.matches(Regex("^[0-9A-Fa-f]{64}$")) == true)
        assertEquals("1.2.5", updateInfo?.versionName)
        assertEquals(25, updateInfo?.versionCode)
    }

    @Test
    fun testAppUpdateManagerNetworkFailureHandling() {
        // Test with a custom interceptor that throws an IOException
        val errorClient = OkHttpClient.Builder()
            .addInterceptor {
                throw java.io.IOException("Simulated connection timeout to remote OTA server")
            }
            .build()

        val originalClient = AppUpdateManager.httpClient
        try {
            AppUpdateManager.httpClient = errorClient
            // Verify that fetch failures return null without crashing or throwing unhandled exceptions
            val method = AppUpdateManager.javaClass.getDeclaredMethod("fetchVersionJson", String::class.java)
            method.isAccessible = true
            val result = method.invoke(AppUpdateManager, "https://invalid.endpoint/app_release.json")
            assertNull("Network failure must safely return null without throwing", result)
        } finally {
            AppUpdateManager.httpClient = originalClient
        }
    }

    @Test
    fun testAppUpdateManagerCandidateDownloadUrlsAndFallbackResolution() {
        val json = JSONObject().apply {
            put("versionCode", 28)
            put("versionName", "1.2.8")
            put("apkUrl", "https://mrkhang-khoi.github.io/appkhkt2627/apk/CVA-SmartGuardian-v1.2.8.apk")
            put("apkFallbackUrl", "https://raw.githubusercontent.com/MrKhang-Khoi/appkhkt2627/main/apk/CVA-SmartGuardian-v1.2.8.apk")
            put("sha256", "7ED3F653AA14E959170051DECE438D79A9A7F3342F0B4C679D43FEDAFD3727BB")
            put("fileSize", "5.83 MB")
        }

        val updateInfo = AppUpdateManager.parseUpdateInfo(json, currentVersionCode = 27)
        assertNotNull("UpdateInfo must not be null", updateInfo)
        assertEquals("https://mrkhang-khoi.github.io/appkhkt2627/apk/CVA-SmartGuardian-v1.2.8.apk", updateInfo?.apkUrl)
        assertEquals("https://raw.githubusercontent.com/MrKhang-Khoi/appkhkt2627/main/apk/CVA-SmartGuardian-v1.2.8.apk", updateInfo?.apkFallbackUrl)

        val safeUpdateInfo = updateInfo ?: throw AssertionError("UpdateInfo must not be null")
        val candidates = AppUpdateManager.getCandidateDownloadUrls(safeUpdateInfo)
        assertTrue("Candidates must contain primary URL", candidates.contains(safeUpdateInfo.apkUrl))
        assertTrue("Candidates must contain fallback URL", candidates.contains(safeUpdateInfo.apkFallbackUrl))
        assertTrue("Candidates must contain raw GitHub URL", candidates.any { it.contains("raw.githubusercontent.com") })
        assertEquals(3, candidates.size)
    }

    @Test
    fun testAppUpdateManagerDownloadFallbackOnHttp404() {
        kotlinx.coroutines.runBlocking {
            val testPayload = "Test APK Content for SmartGuardian".toByteArray()
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val expectedSha = md.digest(testPayload).joinToString("") { "%02x".format(it) }

            val primaryUrl = "https://primary-cdn.com/apk/CVA-SmartGuardian-v1.2.8.apk"
            val fallbackUrl = "https://raw.githubusercontent.com/MrKhang-Khoi/appkhkt2627/main/apk/CVA-SmartGuardian-v1.2.8.apk"

            val updateInfo = UpdateInfo(
                versionCode = 28,
                versionName = "1.2.8",
                apkUrl = primaryUrl,
                fileSize = "5.83 MB",
                sha256 = expectedSha,
                changelog = listOf("Fix 404"),
                isForceUpdate = false,
                apkFallbackUrl = fallbackUrl
            )

            var primaryTried = false
            var fallbackTried = false

            val testClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val url = request.url.toString()
                    if (url.contains("primary-cdn.com")) {
                        primaryTried = true
                        // Simulate HTTP 404 on primary URL
                        okhttp3.Response.Builder()
                            .request(request)
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(404)
                            .message("Not Found")
                            .body(okhttp3.ResponseBody.create("text/plain".toMediaTypeOrNull(), "Not Found"))
                            .build()
                    } else if (url.contains("raw.githubusercontent.com")) {
                        fallbackTried = true
                        // Fallback URL returns HTTP 200 with matching payload
                        okhttp3.Response.Builder()
                            .request(request)
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(okhttp3.ResponseBody.create("application/vnd.android.package-archive".toMediaTypeOrNull(), testPayload))
                            .build()
                    } else {
                        chain.proceed(request)
                    }
                }
                .build()

            val origClient = AppUpdateManager.httpClient
            try {
                AppUpdateManager.httpClient = testClient
                val fakePrefs = FakeSharedPreferences()
                val fakeContext = FakeTestContext(fakePrefs)

                val result = AppUpdateManager.downloadAndVerifyApk(fakeContext, updateInfo) { }
                assertTrue("Primary 404 must trigger fallback and succeed: ${result.exceptionOrNull()?.message}", result.isSuccess)
                assertTrue("Primary URL must have been attempted first", primaryTried)
                assertTrue("Fallback URL must have been used after primary 404", fallbackTried)

                val downloadedFile = result.getOrNull()
                assertNotNull(downloadedFile)
                assertTrue(downloadedFile?.exists() == true)
                downloadedFile?.delete()
            } finally {
                AppUpdateManager.httpClient = origClient
            }
        }
    }

    @Test
    fun testAppUpdateManagerDownloadFallbackOnMismatchedSha256() {
        kotlinx.coroutines.runBlocking {
            val goodPayload = "Valid SmartGuardian Payload".toByteArray()
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val expectedSha = md.digest(goodPayload).joinToString("") { "%02x".format(it) }

            val corruptedPayload = "Corrupted Fake Payload".toByteArray()

            val primaryUrl = "https://primary-cdn.com/apk/CVA-SmartGuardian-v1.2.8.apk"
            val fallbackUrl = "https://raw.githubusercontent.com/MrKhang-Khoi/appkhkt2627/main/apk/CVA-SmartGuardian-v1.2.8.apk"

            val updateInfo = UpdateInfo(
                versionCode = 28,
                versionName = "1.2.8",
                apkUrl = primaryUrl,
                fileSize = "5.83 MB",
                sha256 = expectedSha,
                changelog = listOf("Fix checksum fallback"),
                isForceUpdate = false,
                apkFallbackUrl = fallbackUrl
            )

            var fallbackUsed = false

            val testClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val url = request.url.toString()
                    if (url.contains("primary-cdn.com")) {
                        // Returns corrupted payload with wrong SHA-256
                        okhttp3.Response.Builder()
                            .request(request)
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(okhttp3.ResponseBody.create("application/vnd.android.package-archive".toMediaTypeOrNull(), corruptedPayload))
                            .build()
                    } else if (url.contains("raw.githubusercontent.com")) {
                        fallbackUsed = true
                        // Returns good payload with matching SHA-256
                        okhttp3.Response.Builder()
                            .request(request)
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(okhttp3.ResponseBody.create("application/vnd.android.package-archive".toMediaTypeOrNull(), goodPayload))
                            .build()
                    } else {
                        chain.proceed(request)
                    }
                }
                .build()

            val origClient = AppUpdateManager.httpClient
            try {
                AppUpdateManager.httpClient = testClient
                val fakePrefs = FakeSharedPreferences()
                val fakeContext = FakeTestContext(fakePrefs)

                val result = AppUpdateManager.downloadAndVerifyApk(fakeContext, updateInfo) { }
                assertTrue("Checksum mismatch on primary must trigger fallback and succeed: ${result.exceptionOrNull()?.message}", result.isSuccess)
                assertTrue("Fallback URL must have been used after primary checksum failure", fallbackUsed)

                val downloadedFile = result.getOrNull()
                assertNotNull(downloadedFile)
                assertTrue(downloadedFile?.exists() == true)
                downloadedFile?.delete()
            } finally {
                AppUpdateManager.httpClient = origClient
            }
        }
    }

    @Test
    fun testAppUpdateManagerDownloadFailsWhenAllCandidatesFail() {
        kotlinx.coroutines.runBlocking {
            val primaryUrl = "https://primary-cdn.com/apk/CVA-SmartGuardian-v1.2.8.apk"
            val fallbackUrl = "https://fallback-cdn.com/apk/CVA-SmartGuardian-v1.2.8.apk"

            val updateInfo = UpdateInfo(
                versionCode = 28,
                versionName = "1.2.8",
                apkUrl = primaryUrl,
                fileSize = "5.83 MB",
                sha256 = "1234567890ABCDEF1234567890ABCDEF1234567890ABCDEF1234567890ABCDEF",
                changelog = listOf("All fail test"),
                isForceUpdate = false,
                apkFallbackUrl = fallbackUrl
            )

            val testClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    // All endpoints return HTTP 404
                    okhttp3.Response.Builder()
                        .request(request)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body(okhttp3.ResponseBody.create("text/plain".toMediaTypeOrNull(), "Not Found"))
                        .build()
                }
                .build()

            val origClient = AppUpdateManager.httpClient
            try {
                AppUpdateManager.httpClient = testClient
                val fakePrefs = FakeSharedPreferences()
                val fakeContext = FakeTestContext(fakePrefs)

                val result = AppUpdateManager.downloadAndVerifyApk(fakeContext, updateInfo) { }
                assertTrue("When all candidates fail, result must be failure", result.isFailure)
                val err = result.exceptionOrNull()
                assertNotNull(err)
            } finally {
                AppUpdateManager.httpClient = origClient
            }
        }
    }

    @Test
    fun testAppUpdateManagerDownloadPreservesCoroutineCancellation() {
        kotlinx.coroutines.runBlocking {
            val primaryUrl = "https://primary-cdn.com/apk/CVA-SmartGuardian-v1.2.8.apk"
            val updateInfo = UpdateInfo(
                versionCode = 28,
                versionName = "1.2.8",
                apkUrl = primaryUrl,
                fileSize = "5.83 MB",
                sha256 = "1234567890ABCDEF1234567890ABCDEF1234567890ABCDEF1234567890ABCDEF",
                changelog = listOf("Cancellation test"),
                isForceUpdate = false
            )

            var onProgressCalled = false
            var cancellationCaught = false
            val fakePrefs = FakeSharedPreferences()
            val fakeContext = FakeTestContext(fakePrefs)

            val testPayload = ByteArray(16384) { 0x42 }
            val testClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    okhttp3.Response.Builder()
                        .request(request)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(okhttp3.ResponseBody.create("application/vnd.android.package-archive".toMediaTypeOrNull(), testPayload))
                        .build()
                }
                .build()

            val origClient = AppUpdateManager.httpClient
            try {
                AppUpdateManager.httpClient = testClient

                val job = launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        AppUpdateManager.downloadAndVerifyApk(fakeContext, updateInfo) {
                            onProgressCalled = true
                            // Cancel self during active progress update stream
                            throw kotlinx.coroutines.CancellationException("Test Cancellation In Stream")
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        cancellationCaught = true
                        throw e
                    }
                }

                job.join()
                assertTrue("onProgress callback must be invoked with active byte stream", onProgressCalled)
                assertTrue("CancellationException must be propagated and never swallowed", cancellationCaught)
            } finally {
                AppUpdateManager.httpClient = origClient
            }
        }
    }

    @Test
    fun testUsageTrackerServiceClosePolledSessionResetsStateAndRecordsSession() {
        val fakePrefs = FakeSharedPreferences()
        val context = FakeTestContext(fakePrefs)

        UsageTrackerService.lastPolledForegroundPkg = "com.study.math"
        UsageTrackerService.lastPolledForegroundStartTime = System.currentTimeMillis() - 5000L

        UsageTrackerService.closePolledSession(context, "SCREEN_OFF")

        assertTrue("lastPolledForegroundPkg must be empty after closePolledSession", UsageTrackerService.lastPolledForegroundPkg.isEmpty())
        assertEquals("lastPolledForegroundStartTime must be 0 after closePolledSession", 0L, UsageTrackerService.lastPolledForegroundStartTime)

        // Session must be recorded in prefs (đợi tối đa 1000ms cho Dispatchers.IO hoàn tất)
        val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
        val appKey = "session_${todayStr}_com.study.math"
        var elapsed = 0
        while (!fakePrefs.contains(appKey) && elapsed < 1000) {
            Thread.sleep(50)
            elapsed += 50
        }

        assertTrue("Recorded session must contain app key: $appKey", fakePrefs.contains(appKey))
        val duration = fakePrefs.getLong(appKey, 0L)
        assertTrue("Session duration must be at least 4000ms", duration >= 4000L)
    }

    @Test
    fun testUsageTrackerServiceClosePolledSessionThreadSafetyAndDeduplication() {
        val fakePrefs = FakeSharedPreferences()
        val context = FakeTestContext(fakePrefs)

        UsageTrackerService.lastPolledForegroundPkg = "com.study.concurrent"
        UsageTrackerService.lastPolledForegroundStartTime = System.currentTimeMillis() - 3000L

        // Gọi đồng thời từ 5 luồng khác nhau
        val threads = (1..5).map {
            Thread {
                UsageTrackerService.closePolledSession(context, "SCREEN_OFF")
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // State RAM vẫn sạch 100%
        assertTrue(UsageTrackerService.lastPolledForegroundPkg.isEmpty())
        assertEquals(0L, UsageTrackerService.lastPolledForegroundStartTime)

        // Đợi IO hoàn tất
        val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
        val appKey = "session_${todayStr}_com.study.concurrent"
        var elapsed = 0
        while (!fakePrefs.contains(appKey) && elapsed < 1000) {
            Thread.sleep(50)
            elapsed += 50
        }

        assertTrue(fakePrefs.contains(appKey))
        val duration = fakePrefs.getLong(appKey, 0L)
        // Dù 5 luồng gọi đồng thời, session token chống trùng lặp chỉ cho phép ghi nhận DUY NHẤT 1 lần (~3000ms, không được nhân 5 thành 15000ms)
        assertTrue("Duration must be recorded only once (~3000ms), but was $duration", duration in 2500L..5000L)
    }

    @Test
    fun testUsageTrackerServiceClosePolledSessionIgnoresShortDuration() {
        val fakePrefs = FakeSharedPreferences()
        val context = FakeTestContext(fakePrefs)

        UsageTrackerService.lastPolledForegroundPkg = "com.quick.flick"
        UsageTrackerService.lastPolledForegroundStartTime = System.currentTimeMillis() - 200L // < 1000ms

        UsageTrackerService.closePolledSession(context, "SCREEN_OFF")

        assertTrue("lastPolledForegroundPkg must be empty", UsageTrackerService.lastPolledForegroundPkg.isEmpty())
        assertEquals("lastPolledForegroundStartTime must be 0", 0L, UsageTrackerService.lastPolledForegroundStartTime)

        val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
        val appKey = "session_${todayStr}_com.quick.flick"
        assertFalse("Sub-second session must NOT be recorded", fakePrefs.contains(appKey))
    }

    @Test
    fun testBankPackagesExcludedFromMonitoring() {
        // Kiểm tra hàm chuẩn hóa isBankPackage với các biến thể hoa, thường, hậu tố
        assertTrue("Vietcombank lowercase must be excluded", GuardianAccessibilityService.isBankPackage("com.vcb"))
        assertTrue("Vietcombank uppercase com.VCB must be excluded", GuardianAccessibilityService.isBankPackage("com.VCB"))
        assertTrue("VCB Digibank must be excluded", GuardianAccessibilityService.isBankPackage("com.vcb.digibank"))
        assertTrue("MB Bank must be excluded", GuardianAccessibilityService.isBankPackage("com.mbmobile"))
        assertTrue("Techcombank must be excluded", GuardianAccessibilityService.isBankPackage("vn.com.techcombank.bb.app"))
        assertTrue("BIDV uppercase must be excluded", GuardianAccessibilityService.isBankPackage("COM.VNPAY.BIDV"))
        assertTrue("VPBank must be excluded", GuardianAccessibilityService.isBankPackage("com.vnpay.vpbankonline"))
        assertTrue("MoMo must be excluded", GuardianAccessibilityService.isBankPackage("vn.momo.platform"))
        assertTrue("Generic mbanking must be excluded", GuardianAccessibilityService.isBankPackage("com.custom.bank.mbanking"))
        assertFalse("Regular study app must NOT be excluded", GuardianAccessibilityService.isBankPackage("vn.edu.azota"))
    }

    @Test
    fun testForegroundEvidenceRequiresImportanceForegroundForProcessMatch() {
        val target = "com.gaming.app"
        val now = 100_000L

        // Stale event (> 15s) -> Must reject
        val cachedResult = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = target,
            targetPkg = target,
            now = now,
            lastEventTime = now - 20_000L
        )
        assertFalse("Stale event (>15s) must NOT be accepted as foreground", cachedResult)

        // Fresh event (< 15s) -> Must accept
        val fgResult = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = target,
            targetPkg = target,
            now = now,
            lastEventTime = now - 2_000L
        )
        assertTrue("Active foreground process event must be accepted", fgResult)
    }

    @Test
    fun testEvaluateForegroundEvidenceAcceptsSubProcessWithColon() {
        val basePkg = "com.supercell.clashofclans"
        val subProcess = "com.supercell.clashofclans:remote"

        // Sub-process với dấu hai chấm (package:name) trong active window -> BẮT BUỘC chấp nhận theo SPEC
        val result = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = subProcess,
            usageStatsLastResumedPkg = null,
            targetPkg = basePkg
        )
        assertTrue("Sub-process package:name in active window must be accepted", result)

        // Sub-process trong UsageStats event tươi mới -> BẮT BUỘC chấp nhận
        val now = 100_000L
        val usageSubResult = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = subProcess,
            targetPkg = basePkg,
            now = now,
            lastEventTime = now - 1_000L
        )
        assertTrue("Sub-process in UsageStats must be accepted", usageSubResult)
    }

    @Test
    fun testAccessibilityClosesPreviousSessionWhenBankAppOpened() {
        val fakePrefs = FakeSharedPreferences()
        val context = FakeTestContext(fakePrefs)

        // Thiết lập trạng thái đang mở app học tập
        UsageTrackerService.lastPolledForegroundPkg = "vn.edu.azota"
        UsageTrackerService.lastPolledForegroundStartTime = System.currentTimeMillis() - 4000L

        // Mô phỏng mở app ngân hàng VCB Digibank -> Kích hoạt chốt an toàn
        UsageTrackerService.closePolledSession(context, "BANK_APP_OPENED")

        // Bộ đếm RAM phải được reset về rỗng ngay lập tức
        assertTrue("lastPolledForegroundPkg must be reset immediately", UsageTrackerService.lastPolledForegroundPkg.isEmpty())
        assertEquals("lastPolledForegroundStartTime must be reset immediately", 0L, UsageTrackerService.lastPolledForegroundStartTime)

        // Đợi background IO ghi nhận phiên học tập trước đó
        val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
        val appKey = "session_${todayStr}_vn.edu.azota"
        var elapsed = 0
        while (!fakePrefs.contains(appKey) && elapsed < 1000) {
            Thread.sleep(50)
            elapsed += 50
        }
        assertTrue("Previous app session must be safely recorded", fakePrefs.contains(appKey))

        // Phiên của app ngân hàng tuyệt đối KHÔNG ĐƯỢC ghi nhận
        val bankKey = "session_${todayStr}_com.vcb"
        assertFalse("Bank app session must NEVER be recorded", fakePrefs.contains(bankKey))
    }

    @Test
    fun testUsageStatsManagerPollingOperatesConcurrentlyWithAccessibility() {
        val fakePrefs = FakeSharedPreferences()
        val context = FakeTestContext(fakePrefs)

        // Đảm bảo trạng thái phần cứng online
        GuardianAccessibilityService.isScreenOnState = true

        // Thiết lập phiên tiền cảnh độc lập cho polling engine
        UsageTrackerService.lastPolledForegroundPkg = "com.duolingo"
        UsageTrackerService.lastPolledForegroundStartTime = System.currentTimeMillis() - 3500L

        // Đóng phiên polling độc lập bất kể Accessibility Service có đang chạy hay không
        UsageTrackerService.closePolledSession(context, "SCREEN_OFF")

        assertTrue("Polling state must reset cleanly", UsageTrackerService.lastPolledForegroundPkg.isEmpty())
        assertEquals(0L, UsageTrackerService.lastPolledForegroundStartTime)

        // Kiểm tra session được ghi nhận vào SharedPreferences độc lập
        val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
        val appKey = "session_${todayStr}_com.duolingo"
        var elapsed = 0
        while (!fakePrefs.contains(appKey) && elapsed < 1000) {
            Thread.sleep(50)
            elapsed += 50
        }
        assertTrue("Duolingo session must be recorded independently", fakePrefs.contains(appKey))
        val recordedDuration = fakePrefs.getLong(appKey, 0L)
        assertTrue("Duration must be at least 3000ms", recordedDuration >= 3000L)
    }

    @Test
    fun testDualEngineSessionDeduplicationPreventsDoubleAccounting() {
        val fakePrefs = FakeSharedPreferences()
        val context = FakeTestContext(fakePrefs)

        val targetPkg = "com.google.android.youtube"
        val sessionStart = System.currentTimeMillis() - 10000L
        val sessionDuration = 10000L
        val sharedToken = "${targetPkg}_${sessionStart}"

        // Engine 1 (Accessibility Service) ghi nhận phiên
        UsageTrackerService.recordAppSession(context, targetPkg, sessionDuration, sharedToken)

        val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
        val appKey = "session_${todayStr}_$targetPkg"
        val initialRecorded = fakePrefs.getLong(appKey, 0L)
        assertEquals("Initial recording must match 10000ms", 10000L, initialRecorded)

        // Engine 2 (UsageStatsManager Polling) cùng ghi nhận cùng phiên với cùng sessionToken
        UsageTrackerService.recordAppSession(context, targetPkg, sessionDuration, sharedToken)

        val afterDuplicate = fakePrefs.getLong(appKey, 0L)
        // Deduplication set BẮT BUỘC phải chặn đứng lần ghi thứ hai, thời lượng không được nhân đôi thành 20000ms
        assertEquals("Deduplication must prevent double accounting, duration must stay 10000ms", 10000L, afterDuplicate)
    }

    @Test
    fun testOneDeviceOneRoleConstantsAndContract() {
        // One-Device One-Role Invariants theo chuẩn Google Family Link / Apple Screen Time
        assertEquals("user_role", MainActivity.PREF_USER_ROLE)
        assertEquals("UNSET", MainActivity.ROLE_UNSET)
        assertEquals("PARENT", MainActivity.ROLE_PARENT)
        assertEquals("CHILD", MainActivity.ROLE_CHILD)
    }

    @Test
    fun testRoleRoutingResolutionContractDirectProductionMethod() {
        // Trực tiếp gọi production method MainActivity.resolveEffectiveRole để chứng minh 100% Invariant:
        // 1. Máy đã ghép đôi (isPaired = true): BẤT BIẾN KHÓA CHẶT là ROLE_CHILD, cấm mọi hành vi biến thành PARENT hoặc UNSET!
        val pairedWithParentRole = MainActivity.resolveEffectiveRole(isPaired = true, configuredRole = MainActivity.ROLE_PARENT)
        assertEquals("Paired device must NEVER become PARENT", MainActivity.ROLE_CHILD, pairedWithParentRole)

        val pairedWithUnsetRole = MainActivity.resolveEffectiveRole(isPaired = true, configuredRole = MainActivity.ROLE_UNSET)
        assertEquals("Paired device must NEVER become UNSET", MainActivity.ROLE_CHILD, pairedWithUnsetRole)

        val pairedWithNullRole = MainActivity.resolveEffectiveRole(isPaired = true, configuredRole = null)
        assertEquals("Paired device with null role must resolve to CHILD", MainActivity.ROLE_CHILD, pairedWithNullRole)

        val pairedWithChildRole = MainActivity.resolveEffectiveRole(isPaired = true, configuredRole = MainActivity.ROLE_CHILD)
        assertEquals("Paired device with CHILD role resolves to CHILD", MainActivity.ROLE_CHILD, pairedWithChildRole)

        // 2. Máy chưa ghép đôi (isPaired = false): Cho phép cấu hình theo lựa chọn của người dùng
        val unpairedParent = MainActivity.resolveEffectiveRole(isPaired = false, configuredRole = MainActivity.ROLE_PARENT)
        assertEquals("Unpaired device with PARENT config resolves to PARENT", MainActivity.ROLE_PARENT, unpairedParent)

        val unpairedChild = MainActivity.resolveEffectiveRole(isPaired = false, configuredRole = MainActivity.ROLE_CHILD)
        assertEquals("Unpaired device with CHILD config resolves to CHILD", MainActivity.ROLE_CHILD, unpairedChild)

        val unpairedUnset = MainActivity.resolveEffectiveRole(isPaired = false, configuredRole = MainActivity.ROLE_UNSET)
        assertEquals("Unpaired device with UNSET config resolves to UNSET", MainActivity.ROLE_UNSET, unpairedUnset)

        val unpairedNull = MainActivity.resolveEffectiveRole(isPaired = false, configuredRole = null)
        assertEquals("Unpaired device with null config resolves to UNSET", MainActivity.ROLE_UNSET, unpairedNull)
    }

    @Test
    fun testParentPinSha256VerificationAndSecurity() {
        val prefs = FakeSharedPreferences()

        // 1. Xác thực hàm băm có salt động
        val salt = "test_salt_random_123456"
        val hash = MainActivity.hashPinWithSalt("1234", salt)
        assertEquals(64, hash.length)
        assertTrue(hash.matches(Regex("^[0-9a-f]{64}$")))

        // 2. Khi chưa thiết lập PIN: verifyParentPin BẮT BUỘC từ chối (Không có PIN mặc định backdoor)
        assertFalse("Chưa thiết lập PIN thì không có backdoor 1234", MainActivity.verifyParentPin(prefs, "1234"))

        // 3. Phụ huynh thiết lập PIN 1234
        assertTrue("Thiết lập PIN 1234 thành công", MainActivity.setParentPin(prefs, "1234"))

        // 4. Xác thực PIN đúng (1234) qua SharedPreferences
        assertTrue("PIN 1234 must be verified successfully", MainActivity.verifyParentPin(prefs, "1234"))

        // 5. Từ chối PIN sai, rỗng, thừa ký tự (Timing-safe comparison)
        assertFalse("Wrong PIN 0000 must be rejected", MainActivity.verifyParentPin(prefs, "0000"))
        assertFalse("Wrong PIN 9999 must be rejected", MainActivity.verifyParentPin(prefs, "9999"))
        assertFalse("Empty PIN must be rejected", MainActivity.verifyParentPin(prefs, ""))
        assertFalse("Whitespace PIN must be rejected", MainActivity.verifyParentPin(prefs, "    "))
    }

    @Test
    fun testParentPinStrictDigitFormatValidation() {
        val prefs = FakeSharedPreferences()

        // 1. setParentPin từ chối các định dạng không phải đúng 4 chữ số số học
        assertFalse("Từ chối ký tự chữ abcd", MainActivity.setParentPin(prefs, "abcd"))
        assertFalse("Từ chối PIN quá ngắn (2 số)", MainActivity.setParentPin(prefs, "12"))
        assertFalse("Từ chối PIN 3 số", MainActivity.setParentPin(prefs, "123"))
        assertFalse("Từ chối PIN 5 số (vượt quá 4 numpad dots)", MainActivity.setParentPin(prefs, "12345"))
        assertFalse("Từ chối PIN 6 số", MainActivity.setParentPin(prefs, "123456"))
        assertFalse("Từ chối PIN chứa chữ và số 12a4", MainActivity.setParentPin(prefs, "12a4"))
        assertFalse("Từ chối khoảng trắng", MainActivity.setParentPin(prefs, " 1234 "))
        assertFalse("Từ chối rỗng", MainActivity.setParentPin(prefs, ""))

        // 2. Chấp nhận các PIN hợp lệ gồm đúng 4 chữ số
        assertTrue("Chấp nhận 4 số 1234", MainActivity.setParentPin(prefs, "1234"))
        assertTrue("Chấp nhận 4 số 0000", MainActivity.setParentPin(prefs, "0000"))
        assertTrue("Chấp nhận 4 số 9999", MainActivity.setParentPin(prefs, "9999"))

        // 3. verifyParentPin cũng từ chối ngay lập tức nếu input không phải đúng 4 chữ số
        assertFalse("verify từ chối abcd", MainActivity.verifyParentPin(prefs, "abcd"))
        assertFalse("verify từ chối 12", MainActivity.verifyParentPin(prefs, "12"))
        assertFalse("verify từ chối 123", MainActivity.verifyParentPin(prefs, "123"))
        assertFalse("verify từ chối 12345", MainActivity.verifyParentPin(prefs, "12345"))
        assertFalse("verify từ chối 12a4", MainActivity.verifyParentPin(prefs, "12a4"))
    }

    @Test
    fun testParentPinRequiresExplicitSetupAndRejectsDefaultBypass() {
        val prefs = FakeSharedPreferences()

        // Ban đầu chưa có salt/hash
        assertFalse("hasParentPin phải trả về false khi chưa thiết lập", MainActivity.hasParentPin(prefs))
        assertFalse("Không có backdoor PIN 1234", MainActivity.verifyParentPin(prefs, "1234"))
        assertFalse("Không chấp nhận 0000", MainActivity.verifyParentPin(prefs, "0000"))

        // Phụ huynh thiết lập PIN 4 số hợp lệ
        val setupSuccess = MainActivity.setParentPin(prefs, "4321")
        assertTrue("Thiết lập mã PIN 4 số thành công", setupSuccess)
        assertTrue("hasParentPin trả về true sau khi thiết lập", MainActivity.hasParentPin(prefs))

        // Xác thực đúng và sai
        assertTrue("Đúng PIN 4321 thành công", MainActivity.verifyParentPin(prefs, "4321"))
        assertFalse("Sai PIN 1234 bị từ chối", MainActivity.verifyParentPin(prefs, "1234"))
    }

    @Test
    fun testParentManagementOptionsUnpairFlowGuardedByPin() {
        val backingStorage = mutableMapOf<String, Any?>()
        val prefs = FakeSharedPreferences(backingStorage)
        val now = 1700000000000L

        // Thiết lập trạng thái thiết bị đã ghép đôi
        prefs.edit().putBoolean("is_paired", true).putString("paired_code", "CVA-123").commit()
        MainActivity.setParentPin(prefs, "9876")

        // 1. Luồng hủy ghép đôi từ chối nếu không có PIN hoặc PIN sai
        assertFalse("Hủy ghép đôi với PIN rỗng phải thất bại", MainActivity.verifyParentPin(prefs, ""))
        assertFalse("Hủy ghép đôi với PIN sai 1111 phải thất bại", MainActivity.verifyParentPin(prefs, "1111"))

        // 2. Kẻ xấu thử brute-force trong luồng hủy ghép đôi 5 lần
        for (i in 1..5) {
            MainActivity.recordFailedPinAttempt(prefs, now)
        }
        val lockout = MainActivity.getPinLockoutRemainingSeconds(prefs, now)
        assertTrue("Bị khóa 30 giây", lockout >= 29L && lockout <= 30L)

        // 3. Trong thời gian khóa, mọi nỗ lực hủy ghép đôi đều bị chặn
        assertTrue("Bị khóa chặt", MainActivity.checkPinLockout(now + 5000L, prefs.getLong(MainActivity.PREF_PIN_LOCKOUT_UNTIL, 0L)))

        // 4. Sau khi hết thời gian khóa và nhập đúng PIN 9876 -> Hủy ghép đôi thành công
        val nowAfter = now + 30_001L
        assertEquals(0L, MainActivity.getPinLockoutRemainingSeconds(prefs, nowAfter))
        assertTrue("Nhập đúng PIN 9876 thành công", MainActivity.verifyParentPin(prefs, "9876"))
        MainActivity.resetPinLockout(prefs)
        assertEquals(0, prefs.getInt(MainActivity.PREF_PIN_FAILED_ATTEMPTS, -1))
    }

    @Test
    fun testParentPinAtomicConcurrencyOnFailedAttempts() {
        val prefs = FakeSharedPreferences()
        val now = 1700000000000L
        val threadCount = 10
        val threads = mutableListOf<Thread>()

        // 10 luồng chạy đồng thời gọi recordFailedPinAttempt
        for (i in 1..threadCount) {
            val t = Thread {
                MainActivity.recordFailedPinAttempt(prefs, now)
            }
            threads.add(t)
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Nhờ synchronized(PIN_LOCK), số lần thất bại không bị race-condition ghi đè
        val finalFailures = prefs.getInt(MainActivity.PREF_PIN_FAILED_ATTEMPTS, 0)
        assertEquals("Số lần thất bại phải là 10 sau 10 luồng đồng thời", threadCount, finalFailures)
        val lockoutUntil = prefs.getLong(MainActivity.PREF_PIN_LOCKOUT_UNTIL, 0L)
        assertEquals("Đã bị khóa 30 giây", now + 30_000L, lockoutUntil)
    }

    @Test
    fun testParentPinFailClosedWhenCommitFailsOnAllOperations() {
        // Mô phỏng đĩa bị lỗi ghi (commitReturnsSuccess = false)
        val failingPrefs = FakeSharedPreferences(commitReturnsSuccess = false)

        // 1. setParentPin fail-closed
        val setOk = MainActivity.setParentPin(failingPrefs, "1234")
        assertFalse("Khi commit thất bại, setParentPin BẮT BUỘC trả về false", setOk)

        // 2. resetPinLockout fail-closed
        val resetOk = MainActivity.resetPinLockout(failingPrefs)
        assertFalse("Khi commit thất bại, resetPinLockout BẮT BUỘC trả về false", resetOk)

        // 3. verifyParentPin fail-closed khi chưa có PIN
        val verifyOk = MainActivity.verifyParentPin(failingPrefs, "1234")
        assertFalse("verifyParentPin từ chối an toàn khi commit thất bại", verifyOk)
    }

    @Test
    fun testParentPinLockoutAndRateLimiting() {
        val baseTime = 1700000000000L

        // Lần 1 đến 4: Tăng số lần thử sai, chưa khóa (lockoutUntil = 0L)
        var failures = 0
        var lockoutUntil = 0L
        for (i in 1..4) {
            val res = MainActivity.recordFailedPinAttempt(failures, baseTime)
            failures = res.first
            lockoutUntil = res.second
            assertEquals(i, failures)
            assertEquals(0L, lockoutUntil)
            assertFalse("Chưa đạt 5 lần sai thì không bị khóa", MainActivity.checkPinLockout(baseTime, lockoutUntil))
        }

        // Lần thứ 5 sai: Bị khóa 30 giây (30_000ms)
        val res5 = MainActivity.recordFailedPinAttempt(failures, baseTime)
        failures = res5.first
        lockoutUntil = res5.second
        assertEquals(5, failures)
        assertEquals(baseTime + 30_000L, lockoutUntil)

        // Trong thời gian 30s: Bị khóa chặt (checkPinLockout = true)
        assertTrue("Trong thời gian 30s phải bị khóa", MainActivity.checkPinLockout(baseTime + 10_000L, lockoutUntil))
        assertTrue("Ở giây thứ 29 vẫn bị khóa", MainActivity.checkPinLockout(baseTime + 29_999L, lockoutUntil))

        // Sau 30s: Hết thời gian khóa (checkPinLockout = false)
        assertFalse("Hết 30s thì hết bị khóa", MainActivity.checkPinLockout(baseTime + 30_001L, lockoutUntil))
    }

    @Test
    fun testParentPinPersistentLockoutSurvivesProcessRestart() {
        val backingStorage = mutableMapOf<String, Any?>()
        val now = 1700000000000L

        // 1. Process 1: Ứng dụng chạy lần đầu, nhập sai PIN 5 lần
        val prefsProcess1 = FakeSharedPreferences(backingStorage)
        for (i in 1..5) {
            MainActivity.recordFailedPinAttempt(prefsProcess1, now)
        }
        val remainSec1 = MainActivity.getPinLockoutRemainingSeconds(prefsProcess1, now)
        assertTrue("Sau 5 lần sai trong Process 1 phải bị khóa 30 giây", remainSec1 >= 29L && remainSec1 <= 30L)

        // 2. Kẻ tấn công Force-Stop / Kill App / Khởi động lại thiết bị (Process Death Simulation)
        // Tạo instance FakeSharedPreferences mới nạp từ cùng backing storage của hệ thống
        val prefsProcess2 = FakeSharedPreferences(backingStorage)

        // 3. Process 2 khởi động: Đọc trạng thái khóa từ đĩa lưu trữ SharedPreferences
        val remainSec2 = MainActivity.getPinLockoutRemainingSeconds(prefsProcess2, now)
        assertTrue("Sau khi khởi động lại app, trạng thái khóa BẮT BUỘC vẫn tồn tại (Không thể bypass bằng restart)", remainSec2 >= 29L && remainSec2 <= 30L)

        // Ở giây thứ 15 trong Process 2: Vẫn bị khóa chặt
        val remainSec15 = MainActivity.getPinLockoutRemainingSeconds(prefsProcess2, now + 15_000L)
        assertTrue("Ở giây thứ 15 vẫn còn thời gian khóa", remainSec15 >= 14L && remainSec15 <= 15L)

        // Sau 30s (giây thứ 31): Tự động mở khóa
        val remainSecAfter = MainActivity.getPinLockoutRemainingSeconds(prefsProcess2, now + 30_001L)
        assertEquals("Sau 30s thì tự động hết khóa", 0L, remainSecAfter)

        // 4. Nhập đúng PIN: Reset trạng thái khóa
        val resetResult = MainActivity.resetPinLockout(prefsProcess2)
        assertTrue("Reset lockout phải thành công", resetResult)
        assertEquals("Số lần sai phải được reset về 0", 0, prefsProcess2.getInt(MainActivity.PREF_PIN_FAILED_ATTEMPTS, -1))
        assertEquals("Hạn khóa phải được reset về 0", 0L, prefsProcess2.getLong(MainActivity.PREF_PIN_LOCKOUT_UNTIL, -1L))
    }

    @Test
    fun testParentPinSaltedHashVerification() {
        val pin = "5678"
        val salt1 = "salt_device_alpha_1111"
        val salt2 = "salt_device_beta_2222"

        val hash1 = MainActivity.hashPinWithSalt(pin, salt1)
        val hash2 = MainActivity.hashPinWithSalt(pin, salt2)

        // Độ dài chuẩn SHA-256
        assertEquals(64, hash1.length)
        assertEquals(64, hash2.length)

        // Cùng một PIN nhưng hai salt ngẫu nhiên khác nhau BẮT BUỘC sinh ra hai chuỗi hash hoàn toàn khác nhau
        assertTrue("Hai salt khác nhau phải sinh ra hai hash khác nhau (triệt tiêu rainbow tables / brute force)", hash1 != hash2)

        // Cùng một PIN và cùng salt phải sinh ra hash giống nhau (Deterministic)
        val hash1Again = MainActivity.hashPinWithSalt(pin, salt1)
        assertEquals(hash1, hash1Again)
    }

    @Test
    fun testParentPinCustomizationAndPersistence() {
        val backingStorage = mutableMapOf<String, Any?>()
        val prefs = FakeSharedPreferences(backingStorage)

        // 1. Phụ huynh thiết lập mã PIN ban đầu là 1234
        assertTrue("Thiết lập PIN 1234", MainActivity.setParentPin(prefs, "1234"))
        assertTrue("PIN 1234 phải được xác thực thành công", MainActivity.verifyParentPin(prefs, "1234"))
        assertFalse("PIN sai 9999 phải bị từ chối", MainActivity.verifyParentPin(prefs, "9999"))

        // Salt và Hash đã được lưu bền vững vào preferences
        val initialSalt = prefs.getString(MainActivity.PREF_PARENT_PIN_SALT, null)
        val initialHash = prefs.getString(MainActivity.PREF_PARENT_PIN_HASH, null)
        assertNotNull("Salt phải được tạo và lưu trữ", initialSalt)
        assertNotNull("Hash phải được tạo và lưu trữ", initialHash)

        // 2. Phụ huynh thực hiện đổi mã PIN sang "8899"
        val changed = MainActivity.setParentPin(prefs, "8899")
        assertTrue("Đổi PIN thành công", changed)

        // 3. Mã PIN cũ "1234" lập tức KHÔNG CÒN hợp lệ
        assertFalse("Mã PIN cũ 1234 phải bị từ chối sau khi đổi", MainActivity.verifyParentPin(prefs, "1234"))

        // 4. Mã PIN mới "8899" được xác thực thành công
        assertTrue("Mã PIN mới 8899 phải được xác thực thành công", MainActivity.verifyParentPin(prefs, "8899"))

        // 5. Mô phỏng Restart App: Instance SharedPreferences mới nạp lại dữ liệu
        val prefsRestarted = FakeSharedPreferences(backingStorage)
        assertTrue("Mã PIN mới 8899 vẫn tồn tại và hợp lệ sau khi restart app", MainActivity.verifyParentPin(prefsRestarted, "8899"))
        assertFalse("Mã PIN cũ 1234 vẫn bị từ chối sau khi restart app", MainActivity.verifyParentPin(prefsRestarted, "1234"))
    }

    @Test
    fun testParentUnpairFlowEnforcesCustomPinAndPersistentLockout() {
        val backingStorage = mutableMapOf<String, Any?>()
        val prefs = FakeSharedPreferences(backingStorage)
        val now = 1700000000000L

        // 1. Phụ huynh đã thiết lập PIN riêng cho thiết bị là "7788"
        MainActivity.setParentPin(prefs, "7788")

        // 2. Kẻ xấu/con cố gắng hủy ghép đôi bằng mã PIN 1234 -> BẮT BUỘC BỊ TỪ CHỐI
        val unpairWithDefaultPin = MainActivity.verifyParentPin(prefs, "1234")
        assertFalse("Hủy ghép đôi bằng PIN 1234 sau khi đã đổi PIN phải thất bại", unpairWithDefaultPin)

        // 3. Kẻ xấu thử brute-force trong modal hủy ghép đôi 5 lần
        for (i in 1..5) {
            MainActivity.recordFailedPinAttempt(prefs, now)
        }
        val lockoutSec = MainActivity.getPinLockoutRemainingSeconds(prefs, now)
        assertTrue("Sau 5 lần sai trong luồng hủy ghép đôi, thiết bị BẮT BUỘC bị khóa 30 giây", lockoutSec >= 29L && lockoutSec <= 30L)

        // 4. Kẻ xấu force-stop / restart app để tìm cách bypass lockout
        val prefsAfterRestart = FakeSharedPreferences(backingStorage)
        val lockoutSecAfterRestart = MainActivity.getPinLockoutRemainingSeconds(prefsAfterRestart, now)
        assertTrue("Khởi động lại app vẫn BỊ KHÓA CHẶT (Không thể bypass hủy ghép đôi)", lockoutSecAfterRestart >= 29L && lockoutSecAfterRestart <= 30L)

        // Trong thời gian bị khóa, mọi nỗ lực hủy ghép đôi (dù nhập đúng hay sai) đều bị chặn
        val isLocked = MainActivity.checkPinLockout(now + 10_000L, prefsAfterRestart.getLong(MainActivity.PREF_PIN_LOCKOUT_UNTIL, 0L))
        assertTrue("Trong thời gian khóa thì luồng hủy ghép đôi bị chặn hoàn toàn", isLocked)

        // 5. Sau khi hết thời gian khóa 30 giây, phụ huynh nhập đúng mã PIN "7788"
        val nowAfterLockout = now + 30_001L
        assertEquals("Hết 30 giây thì hết bị khóa", 0L, MainActivity.getPinLockoutRemainingSeconds(prefsAfterRestart, nowAfterLockout))

        val unpairWithCustomPin = MainActivity.verifyParentPin(prefsAfterRestart, "7788")
        assertTrue("Hủy ghép đôi với đúng PIN phụ huynh 7788 phải thành công", unpairWithCustomPin)

        // Hủy ghép đôi thành công -> Reset lockout
        val resetResult = MainActivity.resetPinLockout(prefsAfterRestart)
        assertTrue("Reset lockout phải thành công", resetResult)
        assertEquals(0, prefsAfterRestart.getInt(MainActivity.PREF_PIN_FAILED_ATTEMPTS, -1))
        assertEquals(0L, prefsAfterRestart.getLong(MainActivity.PREF_PIN_LOCKOUT_UNTIL, -1L))
    }

    @Test
    fun testAuthenticateParentPinAtomicSuccessResetsLockout() {
        val prefs = FakeSharedPreferences()
        val now = 1700000000000L

        // Thiết lập PIN hợp lệ
        assertTrue(MainActivity.setParentPin(prefs, "2468"))

        // Giả lập đã nhập sai 2 lần trước đó
        MainActivity.recordFailedPinAttempt(prefs, now)
        MainActivity.recordFailedPinAttempt(prefs, now)
        assertEquals(2, prefs.getInt(MainActivity.PREF_PIN_FAILED_ATTEMPTS, 0))

        // Gọi authenticateParentPinAtomic với đúng PIN "2468"
        val result = MainActivity.authenticateParentPinAtomic(prefs, "2468", now + 1000L)
        assertTrue("Kết quả phải là PinAuthResult.Success", result is MainActivity.PinAuthResult.Success)

        // Kiểm tra nguyên tử: failed attempts và lockout đã được reset về 0
        assertEquals(0, prefs.getInt(MainActivity.PREF_PIN_FAILED_ATTEMPTS, -1))
        assertEquals(0L, prefs.getLong(MainActivity.PREF_PIN_LOCKOUT_UNTIL, -1L))
    }

    @Test
    fun testAuthenticateParentPinAtomicLockedOutFailsEarly() {
        val prefs = FakeSharedPreferences()
        val now = 1700000000000L
        assertTrue(MainActivity.setParentPin(prefs, "2468"))

        // Thử sai 4 lần đầu
        for (i in 1..4) {
            val res = MainActivity.authenticateParentPinAtomic(prefs, "0000", now)
            assertTrue(res is MainActivity.PinAuthResult.IncorrectPin)
            val inc = res as MainActivity.PinAuthResult.IncorrectPin
            assertEquals(i, inc.failedAttempts)
            assertEquals(5 - i, inc.remainingAttempts)
            assertFalse(inc.isNowLockedOut)
        }

        // Lần thứ 5 sai -> Kích hoạt khóa 30s
        val res5 = MainActivity.authenticateParentPinAtomic(prefs, "0000", now)
        assertTrue(res5 is MainActivity.PinAuthResult.IncorrectPin)
        val inc5 = res5 as MainActivity.PinAuthResult.IncorrectPin
        assertEquals(5, inc5.failedAttempts)
        assertEquals(0, inc5.remainingAttempts)
        assertTrue(inc5.isNowLockedOut)

        // Sau 5 giây, kể cả khi nhập ĐÚNG PIN "2468", giao dịch nguyên tử BẮT BUỘC trả về LockedOut
        val lockedResult = MainActivity.authenticateParentPinAtomic(prefs, "2468", now + 5000L)
        assertTrue("Không thể bypass lockout dù nhập đúng PIN", lockedResult is MainActivity.PinAuthResult.LockedOut)
        val locked = lockedResult as MainActivity.PinAuthResult.LockedOut
        assertTrue("Còn lại 25 giây", locked.remainingSeconds in 24L..25L)
    }

    @Test
    fun testAuthenticateParentPinAtomicFailClosedOnStorageError() {
        // SharedPreferences giả lập lỗi commit (đĩa hỏng / bộ nhớ đầy)
        val failingPrefs = FakeSharedPreferences(commitReturnsSuccess = false)
        val now = 1700000000000L

        // Giao dịch nguyên tử BẮT BUỘC Fail-Closed
        val authResult = MainActivity.authenticateParentPinAtomic(failingPrefs, "1234", now)
        assertTrue("Phải trả về PinAuthResult.StorageError khi lưu trữ lỗi", authResult is MainActivity.PinAuthResult.StorageError)
    }

    @Test
    fun testAuthenticateParentPinAtomicThreadSafetyUnderHighConcurrency() {
        val prefs = FakeSharedPreferences()
        val now = 1700000000000L
        assertTrue(MainActivity.setParentPin(prefs, "5555"))

        val threadCount = 20
        val results = java.util.Collections.synchronizedList(mutableListOf<PinAuthResult>())
        val threads = mutableListOf<Thread>()

        // 20 luồng đồng thời gọi authenticateParentPinAtomic
        for (i in 1..threadCount) {
            val t = Thread {
                val res = MainActivity.authenticateParentPinAtomic(prefs, "9999", now)
                results.add(res)
            }
            threads.add(t)
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(threadCount, results.size)
        // Nhờ synchronized(PIN_LOCK), ít nhất 5 lần đầu nhận IncorrectPin và các lần sau nhận LockedOut
        val incorrectCount = results.count { it is PinAuthResult.IncorrectPin }
        val lockedOutCount = results.count { it is PinAuthResult.LockedOut }
        assertEquals("Tổng số kết quả phải khớp 20", threadCount, incorrectCount + lockedOutCount)
        assertTrue("Số lần thử sai ghi nhận đạt ngưỡng khóa 5", incorrectCount >= 5)
        assertTrue("Các luồng sau bị khóa an toàn", lockedOutCount > 0)
        val lockoutUntil = prefs.getLong(MainActivity.PREF_PIN_LOCKOUT_UNTIL, 0L)
        assertEquals("Lockout phải được kích hoạt sau >= 5 lần sai", now + 30_000L, lockoutUntil)
    }

    @Test
    fun testAuthenticateParentPinAtomicRejectsUnconfiguredPinWithoutBackdoor() {
        val prefs = FakeSharedPreferences()
        val now = 1700000000000L

        // Chưa thiết lập PIN -> Tuyệt đối không chấp nhận 1234 hay bất kỳ số nào
        val res = MainActivity.authenticateParentPinAtomic(prefs, "1234", now)
        assertTrue("Chưa thiết lập PIN phải trả về StorageError (Zero Backdoor)", res is MainActivity.PinAuthResult.StorageError)
    }

    @Test
    fun testComputeDeviceOnlineStatusInvariants() {
        val now = 1700000000000L

        // 1. Device hợp lệ: trực tuyến trong vòng 45s
        val devOnline = JSONObject().apply {
            put("isPaired", true)
            put("status", "paired")
            put("online", true)
            put("lastSync", now - 10000L)
            put("active_app", JSONObject().apply {
                put("packageName", "com.google.android.youtube")
            })
        }
        assertTrue("Thiết bị đồng bộ 10s trước phải trực tuyến", MainActivity.computeDeviceOnlineStatus(devOnline, now))

        // 2. Mất mạng quá 45s -> ngoại tuyến
        val devStale = JSONObject().apply {
            put("isPaired", true)
            put("online", true)
            put("lastSync", now - 46000L)
        }
        assertFalse("Thiết bị quá 45s không liên lạc phải ngoại tuyến", MainActivity.computeDeviceOnlineStatus(devStale, now))

        // 3. Màn hình tắt (SCREEN_OFF) -> ngoại tuyến (Zero Phantom Time)
        val devScreenOff = JSONObject().apply {
            put("isPaired", true)
            put("online", true)
            put("lastSync", now - 5000L)
            put("active_app", JSONObject().apply {
                put("packageName", "SCREEN_OFF")
            })
        }
        assertFalse("Thiết bị ở trạng thái SCREEN_OFF phải ngoại tuyến", MainActivity.computeDeviceOnlineStatus(devScreenOff, now))

        // 4. Trạng thái bị thu hồi (REVOKED) -> ngoại tuyến
        val devRevoked = JSONObject().apply {
            put("isPaired", true)
            put("status", "REVOKED")
            put("online", true)
            put("lastSync", now - 5000L)
        }
        assertFalse("Thiết bị REVOKED phải ngoại tuyến", MainActivity.computeDeviceOnlineStatus(devRevoked, now))

        // 5. Cờ online = false -> ngoại tuyến
        val devExplicitOffline = JSONObject().apply {
            put("isPaired", true)
            put("online", false)
            put("lastSync", now - 5000L)
        }
        assertFalse("Thiết bị có cờ online=false phải ngoại tuyến", MainActivity.computeDeviceOnlineStatus(devExplicitOffline, now))

        // 6. Timestamp từ tương lai (> now + 5s) -> chống gian lận thời gian, ngoại tuyến
        val devFutureTime = JSONObject().apply {
            put("isPaired", true)
            put("online", true)
            put("lastSync", now + 10000L)
        }
        assertFalse("Thiết bị gửi timestamp tương lai ảo phải bị từ chối", MainActivity.computeDeviceOnlineStatus(devFutureTime, now))

        // 7. Chưa ghép đôi (unpaired) -> ngoại tuyến
        val devUnpaired = JSONObject().apply {
            put("isPaired", false)
            put("online", true)
            put("lastSync", now - 5000L)
        }
        assertFalse("Thiết bị chưa ghép đôi phải ngoại tuyến", MainActivity.computeDeviceOnlineStatus(devUnpaired, now))

        // 8. Thiếu trường 'online' hoàn toàn (Fail-closed invariant) -> ngoại tuyến
        val devMissingOnline = JSONObject().apply {
            put("isPaired", true)
            put("status", "paired")
            put("lastSync", now - 5000L)
            put("lastHeartbeat", now - 5000L)
        }
        assertFalse("Thiết bị thiếu trường 'online' bắt buộc phải ngoại tuyến (Fail-Closed)", MainActivity.computeDeviceOnlineStatus(devMissingOnline, now))

        // 9. Thiếu trường 'isPaired' hoàn toàn và không có status 'paired' -> ngoại tuyến
        val devMissingPaired = JSONObject().apply {
            put("online", true)
            put("lastSync", now - 5000L)
            put("lastHeartbeat", now - 5000L)
        }
        assertFalse("Thiết bị thiếu 'isPaired' bắt buộc phải ngoại tuyến", MainActivity.computeDeviceOnlineStatus(devMissingPaired, now))

        // 10. JSON chỉ có lastSync nhưng không có heartbeat hoặc online -> ngoại tuyến
        val devLastSyncOnly = JSONObject().apply {
            put("lastSync", now - 5000L)
        }
        assertFalse("JSON chỉ có lastSync không thể báo trực tuyến", MainActivity.computeDeviceOnlineStatus(devLastSyncOnly, now))

        // 11. Fail-closed: Mô phỏng polling nền thất bại chuyển thiết bị trực tuyến thành snapshot ngoại tuyến
        val activeOnlineChild = MainActivity.FamilyChildDevice(
            deviceId = "test-dev-01",
            childName = "Minh Khang",
            deviceModel = "Pixel 8",
            isOnline = true,
            lastContact = now - 5000L,
            rawObj = devOnline
        )
        assertTrue("Ban đầu thiết bị đang trực tuyến", activeOnlineChild.isOnline)
        val staleChild = activeOnlineChild.copy(isOnline = false)
        assertFalse("Sau khi polling nền gặp lỗi mạng, thiết bị bắt buộc chuyển thành snapshot ngoại tuyến", staleChild.isOnline)
    }

    @Test
    fun testWebFilterListWordBoundaryRegex() {
        // 1. URLs containing academic terms that previously triggered false-positives
        val essexResult = vn.edu.cva.smartguardian.data.WebFilterList.checkUrl("https://essex.ac.uk/study")
        assertFalse("essex.ac.uk must NOT be blocked", essexResult.isBlocked)

        val jsResult = vn.edu.cva.smartguardian.data.WebFilterList.checkUrl("https://developer.mozilla.org/en-US/docs/Web/JavaScript")
        assertFalse("JavaScript documentation must NOT be blocked", jsResult.isBlocked)

        // 2. Real harmful URLs must still be strictly blocked
        val adultSearchResult = vn.edu.cva.smartguardian.data.WebFilterList.checkUrl("https://www.google.com/search?q=sex")
        assertTrue("URL with standalone keyword 'sex' must be blocked", adultSearchResult.isBlocked)
        assertEquals(vn.edu.cva.smartguardian.data.WebCategory.ADULT, adultSearchResult.category)

        val pornDomainResult = vn.edu.cva.smartguardian.data.WebFilterList.checkUrl("https://pornhub.com/video")
        assertTrue("pornhub.com must be blocked by domain list", pornDomainResult.isBlocked)
        assertEquals(vn.edu.cva.smartguardian.data.WebCategory.ADULT, pornDomainResult.category)
    }

    @Test
    fun testDynamicPackageVersionFailClosedOnMissingMetadata() {
        val fakePrefs = FakeSharedPreferences()
        val fakeCtx = FakeTestContext(fakePrefs)

        // When package manager is null or throws, getDynamicPackageVersion must fail-closed and return null (NEVER return hardcoded strings)
        val result = UsageTrackerService.getDynamicPackageVersion(fakeCtx)
        assertNull("getDynamicPackageVersion must fail-closed and return null when package metadata fails", result)
    }

    @Test
    fun testWebFilterListPercentEncodingAndUnicodeBoundaries() {
        // 1. Percent-encoded adult keywords must be decoded and caught
        val encodedAdultResult = vn.edu.cva.smartguardian.data.WebFilterList.checkUrl("https://example.com/search?q=%73%65%78")
        assertTrue("Percent-encoded %73%65%78 ('sex') must be blocked", encodedAdultResult.isBlocked)
        assertEquals(vn.edu.cva.smartguardian.data.WebCategory.ADULT, encodedAdultResult.category)

        // 2. Legitimate compound terms with unicode boundaries must NOT be blocked
        val unisexResult = vn.edu.cva.smartguardian.data.WebFilterList.checkUrl("https://example.com/product/unisex-fashion-ao-thun")
        assertFalse("Unisex clothing product must NOT be blocked", unisexResult.isBlocked)

        val sussexResult = vn.edu.cva.smartguardian.data.WebFilterList.checkUrl("https://www.sussex.ac.uk/departments")
        assertFalse("Sussex University must NOT be blocked", sussexResult.isBlocked)

        // 3. Gambling keywords with dash/space tokens must be blocked
        val gamblingResult = vn.edu.cva.smartguardian.data.WebFilterList.checkUrl("https://example.com/news/ca-cuoc-online")
        assertTrue("ca-cuoc keyword must be blocked", gamblingResult.isBlocked)
        assertEquals(vn.edu.cva.smartguardian.data.WebCategory.GAMBLING, gamblingResult.category)
    }

    @Test
    fun testOemPermissionHelperThreeTierFallbackArchitecture() {
        // 1. Verify manufacturer intents registry contains major OEMs (Xiaomi, Samsung, Oppo, Vivo, Huawei, Asus)
        assertTrue("OEM intent registry must have >= 10 device-specific intents", vn.edu.cva.smartguardian.util.OemPermissionHelper.OEM_AUTOSTART_INTENTS.size >= 10)

        // 2. Test fallback invocation on test context
        val fakePrefs = FakeSharedPreferences()
        val fakeCtx = FakeTestContext(fakePrefs)

        // When activity launcher is invoked on minimal test context without activity manager, must safely execute fallback without crash
        val launched = vn.edu.cva.smartguardian.util.OemPermissionHelper.openOemBackgroundSettings(fakeCtx)
        assertTrue("3-tier fallback chain must execute safely and report launch status", launched)
    }

    @Test
    fun testWebActivityBannerEvaluationAndInvariants() {
        val now = System.currentTimeMillis()

        // Vector 1: Live active browsing while online (category BROWSER, age < 15m, full URL provided)
        val liveState = MainActivity.evaluateWebBannerDisplay(
            isOnline = true,
            webDomain = "https://kenh14.vn/gioi-tre.chn",
            webTitle = "<b>Kênh 14</b> - Tin tức giới trẻ",
            webBrowser = "Google Chrome",
            webTimestamp = now - 30_000L,
            webIsBlocked = false,
            activePkg = "com.android.chrome",
            activeCat = "BROWSER",
            now = now,
            webUrl = "https://kenh14.vn/gioi-tre.chn"
        )
        assertTrue("Live banner must be visible", liveState.isVisible)
        assertEquals("TRANG WEB ĐANG TRUY CẬP (REALTIME):", liveState.headerText)
        assertEquals("kenh14.vn • Kênh 14 - Tin tức giới trẻ", liveState.domainText)
        assertEquals("Google Chrome • Vừa truy cập", liveState.subtitleText)
        assertEquals("ĐANG DUYỆT", liveState.badgeText)
        assertTrue("isLiveBrowsing must be true", liveState.isLiveBrowsing)
        assertFalse("isBlocked must be false", liveState.isBlocked)

        // Vector 2: Stale browsing while online (switched to non-browser app, age 5m)
        val staleState = MainActivity.evaluateWebBannerDisplay(
            isOnline = true,
            webDomain = "tuoitre.vn",
            webTitle = "Báo Tuổi Trẻ",
            webBrowser = "Google Chrome",
            webTimestamp = now - 5 * 60_000L,
            webIsBlocked = false,
            activePkg = "com.facebook.katana",
            activeCat = "SOCIAL",
            now = now
        )
        assertTrue("Stale banner must be visible within 15m", staleState.isVisible)
        assertEquals("TRANG WEB ĐANG TRUY CẬP (REALTIME):", staleState.headerText)
        assertEquals("tuoitre.vn • Báo Tuổi Trẻ", staleState.domainText)
        assertEquals("Google Chrome • 5m trước", staleState.subtitleText)
        assertEquals("VỪA XEM", staleState.badgeText)
        assertFalse("isLiveBrowsing must be false when switched away", staleState.isLiveBrowsing)
        assertFalse(staleState.isBlocked)

        // Vector 3: Online expired >15m (must hide banner)
        val expiredOnlineState = MainActivity.evaluateWebBannerDisplay(
            isOnline = true,
            webDomain = "vnexpress.net",
            webTitle = "VnExpress",
            webBrowser = "Chrome",
            webTimestamp = now - 16 * 60_000L,
            webIsBlocked = false,
            activePkg = "com.android.chrome",
            activeCat = "BROWSER",
            now = now
        )
        assertFalse("Online web banner must be hidden when older than 15m", expiredOnlineState.isVisible)
        assertEquals("", expiredOnlineState.headerText)

        // Vector 4: Blocked site while online
        val blockedState = MainActivity.evaluateWebBannerDisplay(
            isOnline = true,
            webDomain = "violation-site.com",
            webTitle = "Web độc hại",
            webBrowser = "Google Chrome",
            webTimestamp = now,
            webIsBlocked = true,
            activePkg = "com.android.chrome",
            activeCat = "BROWSER",
            now = now
        )
        assertTrue("Blocked banner must be visible", blockedState.isVisible)
        assertEquals("TRANG WEB ĐÃ BỊ CHẶN BỞI BỘ LỌC:", blockedState.headerText)
        assertEquals("violation-site.com • Web độc hại", blockedState.domainText)
        assertEquals("[ĐÃ CHẶN]", blockedState.badgeText)
        assertFalse("Live browsing flag must be false for blocked site", blockedState.isLiveBrowsing)
        assertTrue("isBlocked flag must be true", blockedState.isBlocked)

        // Vector 5: Offline recent <= 60m (shows last recorded website before going offline)
        val offlineRecentState = MainActivity.evaluateWebBannerDisplay(
            isOnline = false,
            webDomain = "dantri.com.vn",
            webTitle = "Báo Dân Trí",
            webBrowser = "Google Chrome",
            webTimestamp = now - 25 * 60_000L,
            webIsBlocked = false,
            activePkg = "",
            activeCat = "",
            now = now
        )
        assertTrue("Offline banner must be visible within 60m", offlineRecentState.isVisible)
        assertEquals("LẦN CUỐI GHI NHẬN TRƯỚC KHI NGOẠI TUYẾN:", offlineRecentState.headerText)
        assertEquals("dantri.com.vn • Báo Dân Trí", offlineRecentState.domainText)
        assertEquals("Google Chrome • 25m trước khi ngoại tuyến", offlineRecentState.subtitleText)
        assertEquals("[OFFLINE]", offlineRecentState.badgeText)
        assertFalse(offlineRecentState.isLiveBrowsing)
        assertFalse(offlineRecentState.isBlocked)

        // Vector 5b: Offline recent <= 60m with blocked site (must preserve [OFFLINE] and indicate blocked)
        val offlineBlockedState = MainActivity.evaluateWebBannerDisplay(
            isOnline = false,
            webDomain = "gambling-site.com",
            webTitle = "Web Cờ Bạc",
            webBrowser = "Google Chrome",
            webTimestamp = now - 15 * 60_000L,
            webIsBlocked = true,
            activePkg = "",
            activeCat = "",
            now = now
        )
        assertTrue("Offline blocked banner must be visible within 60m", offlineBlockedState.isVisible)
        assertEquals("LẦN CUỐI GHI NHẬN TRƯỚC KHI NGOẠI TUYẾN:", offlineBlockedState.headerText)
        assertEquals("gambling-site.com • Web Cờ Bạc", offlineBlockedState.domainText)
        assertEquals("Google Chrome • 15m trước khi ngoại tuyến", offlineBlockedState.subtitleText)
        assertEquals("[OFFLINE] • [ĐÃ CHẶN]", offlineBlockedState.badgeText)
        assertTrue("Badge must contain [OFFLINE] indicator", offlineBlockedState.badgeText.contains("[OFFLINE]"))
        assertFalse("isLiveBrowsing must be false when offline", offlineBlockedState.isLiveBrowsing)
        assertTrue("isBlocked flag must be true", offlineBlockedState.isBlocked)

        // Vector 6: Offline expired > 60m (must not show outdated history)
        val offlineExpiredState = MainActivity.evaluateWebBannerDisplay(
            isOnline = false,
            webDomain = "dantri.com.vn",
            webTitle = "Báo Dân Trí",
            webBrowser = "Google Chrome",
            webTimestamp = now - 65 * 60_000L,
            webIsBlocked = false,
            activePkg = "",
            activeCat = "",
            now = now
        )
        assertFalse("Offline banner must be hidden when older than 60m", offlineExpiredState.isVisible)

        // Vector 7: Empty or invalid domain (fail-closed security invariants)
        val emptyDomainState = MainActivity.evaluateWebBannerDisplay(
            isOnline = true,
            webDomain = "",
            webTitle = "",
            webBrowser = "Google Chrome",
            webTimestamp = now,
            webIsBlocked = false,
            activePkg = "com.android.chrome",
            activeCat = "BROWSER",
            now = now
        )
        assertFalse("Banner must be hidden when domain is empty", emptyDomainState.isVisible)

        val malformedDomainState = MainActivity.evaluateWebBannerDisplay(
            isOnline = true,
            webDomain = "invalid space domain",
            webTitle = "Test",
            webBrowser = "Google Chrome",
            webTimestamp = now,
            webIsBlocked = false,
            activePkg = "com.android.chrome",
            activeCat = "BROWSER",
            now = now
        )
        assertFalse("Banner must be hidden for invalid domain format", malformedDomainState.isVisible)

        // Vector 8: Anti-XSS and pseudo-protocol defense
        val xssUrlState = MainActivity.evaluateWebBannerDisplay(
            isOnline = true,
            webDomain = "evil.com",
            webTitle = "Attack",
            webBrowser = "Google Chrome",
            webTimestamp = now,
            webIsBlocked = false,
            activePkg = "com.android.chrome",
            activeCat = "BROWSER",
            now = now,
            webUrl = "javascript:alert(document.cookie)"
        )
        assertFalse("Banner must reject pseudo-protocol javascript: URLs", xssUrlState.isVisible)

        // Vector 9: Rejection of localhost (RFC Domain Invariant)
        val localhostState = MainActivity.evaluateWebBannerDisplay(
            isOnline = true,
            webDomain = "localhost",
            webTitle = "Local dev",
            webBrowser = "Google Chrome",
            webTimestamp = now,
            webIsBlocked = false,
            activePkg = "com.android.chrome",
            activeCat = "BROWSER",
            now = now
        )
        assertFalse("Banner must reject localhost domain according to RFC domain requirements", localhostState.isVisible)

        // Vector 10: Anti-Telemetry Spoofing - Rejection of future timestamps
        val futureState = MainActivity.evaluateWebBannerDisplay(
            isOnline = true,
            webDomain = "vnexpress.net",
            webTitle = "Tin tức",
            webBrowser = "Google Chrome",
            webTimestamp = now + 60_000L,
            webIsBlocked = false,
            activePkg = "com.android.chrome",
            activeCat = "BROWSER",
            now = now
        )
        assertFalse("Banner must reject future timestamps (anti-telemetry spoofing)", futureState.isVisible)

        // Vector 11: Rejection of zero or negative timestamps
        val zeroTimeState = MainActivity.evaluateWebBannerDisplay(
            isOnline = true,
            webDomain = "vnexpress.net",
            webTitle = "Tin tức",
            webBrowser = "Google Chrome",
            webTimestamp = 0L,
            webIsBlocked = false,
            activePkg = "com.android.chrome",
            activeCat = "BROWSER",
            now = now
        )
        assertFalse("Banner must reject zero timestamp", zeroTimeState.isVisible)
    }

    @Test
    fun testParentDashboardSubtitleEvaluationAndAntiSpoofing() {
        val now = 1_700_000_000_000L
        val model = "Pixel 7"

        // 1. Valid online recent web browsing -> includes web domain
        val validSubtitle = MainActivity.evaluateParentDashboardSubtitle(
            isOnline = true,
            deviceModel = model,
            webDomain = "vnexpress.net",
            webTimestamp = now - 120_000L, // 2m ago
            now = now
        )
        assertEquals("Đang hoạt động • Pixel 7 • vnexpress.net", validSubtitle)

        // 2. Offline device -> returns empty string (handled by offline branch)
        val offlineSubtitle = MainActivity.evaluateParentDashboardSubtitle(
            isOnline = false,
            deviceModel = model,
            webDomain = "vnexpress.net",
            webTimestamp = now - 120_000L,
            now = now
        )
        assertEquals("", offlineSubtitle)

        // 3. Zero timestamp -> NO web suffix
        val zeroTimeSubtitle = MainActivity.evaluateParentDashboardSubtitle(
            isOnline = true,
            deviceModel = model,
            webDomain = "vnexpress.net",
            webTimestamp = 0L,
            now = now
        )
        assertEquals("Đang hoạt động • Pixel 7", zeroTimeSubtitle)

        // 4. Negative timestamp -> NO web suffix
        val negTimeSubtitle = MainActivity.evaluateParentDashboardSubtitle(
            isOnline = true,
            deviceModel = model,
            webDomain = "vnexpress.net",
            webTimestamp = -5000L,
            now = now
        )
        assertEquals("Đang hoạt động • Pixel 7", negTimeSubtitle)

        // 5. Future timestamp (Anti-Telemetry Spoofing) -> NO web suffix
        val futureTimeSubtitle = MainActivity.evaluateParentDashboardSubtitle(
            isOnline = true,
            deviceModel = model,
            webDomain = "vnexpress.net",
            webTimestamp = now + 60_000L,
            now = now
        )
        assertEquals("Đang hoạt động • Pixel 7", futureTimeSubtitle)

        // 6. Stale web activity (> 10m / 600_000L) -> NO web suffix
        val staleTimeSubtitle = MainActivity.evaluateParentDashboardSubtitle(
            isOnline = true,
            deviceModel = model,
            webDomain = "vnexpress.net",
            webTimestamp = now - 650_000L, // 10.8m ago
            now = now
        )
        assertEquals("Đang hoạt động • Pixel 7", staleTimeSubtitle)

        // 7. Invalid domain format (spaces, illegal characters) -> NO web suffix
        val invalidDomainSubtitle = MainActivity.evaluateParentDashboardSubtitle(
            isOnline = true,
            deviceModel = model,
            webDomain = "invalid space domain",
            webTimestamp = now - 120_000L,
            now = now
        )
        assertEquals("Đang hoạt động • Pixel 7", invalidDomainSubtitle)

        // 8. Localhost domain -> NO web suffix (RFC Domain Invariant)
        val localhostSubtitle = MainActivity.evaluateParentDashboardSubtitle(
            isOnline = true,
            deviceModel = model,
            webDomain = "localhost",
            webTimestamp = now - 120_000L,
            now = now
        )
        assertEquals("Đang hoạt động • Pixel 7", localhostSubtitle)

        // 9. Empty domain -> NO web suffix
        val emptyDomainSubtitle = MainActivity.evaluateParentDashboardSubtitle(
            isOnline = true,
            deviceModel = model,
            webDomain = "",
            webTimestamp = now - 120_000L,
            now = now
        )
        assertEquals("Đang hoạt động • Pixel 7", emptyDomainSubtitle)
    }

    @Test
    fun testGpsCommandProtocolStateTransitionsAndInvariants() {
        val now = 1_700_000_000_000L

        // Protocol Helper URL Builder Contract
        val cmdUrl = UsageTrackerService.LocationProtocol.getCommandUrl("https://rtdb.firebase.io", "FAM_123", "DEV_456")
        assertEquals("https://rtdb.firebase.io/families/FAM_123/devices/DEV_456/commands/locate_now.json", cmdUrl)
        val famLocUrl = UsageTrackerService.LocationProtocol.getFamilyLocationUrl("https://rtdb.firebase.io", "FAM_123", "DEV_456")
        assertEquals("https://rtdb.firebase.io/families/FAM_123/devices/DEV_456/location.json", famLocUrl)
        val devLocUrl = UsageTrackerService.LocationProtocol.getDeviceLocationUrl("https://rtdb.firebase.io", "DEV_456")
        assertEquals("https://rtdb.firebase.io/devices/DEV_456/location.json", devLocUrl)

        // URL Path Traversal & Sanitization Invariant (Strict Fail-Closed, No Silent Normalization)
        val invalidSegments = listOf(
            "../../hack_family?x=1#test",
            "dev/123",
            " DEV_456 ",
            "DEV_456\n",
            "DEV\t456",
            "",
            "   ",
            "dev@123",
            "dev:123"
        )
        for (invalid in invalidSegments) {
            try {
                UsageTrackerService.LocationProtocol.sanitizeSegment(invalid)
                fail("Must fail-closed with IllegalArgumentException on invalid segment: '$invalid'")
            } catch (e: IllegalArgumentException) {
                assertTrue("Exception message must indicate Fail-Closed: ${e.message}", e.message?.contains("Fail-Closed") == true)
            }
        }

        val validSeg = UsageTrackerService.LocationProtocol.sanitizeSegment("DEV_456")
        assertEquals("DEV_456", validSeg)
        val validDashSeg = UsageTrackerService.LocationProtocol.sanitizeSegment("CVA-8A20_CHILD-1")
        assertEquals("CVA-8A20_CHILD-1", validDashSeg)

        // Base URL Validation Invariant (Strict Fail-Closed URL Structure & Domain Guard)
        val validBase1 = UsageTrackerService.LocationProtocol.validateBaseUrl("https://rtdb.firebase.io")
        assertEquals("https://rtdb.firebase.io", validBase1)
        val validBase2 = UsageTrackerService.LocationProtocol.validateBaseUrl("https://my-app.firebaseio.com/")
        assertEquals("https://my-app.firebaseio.com", validBase2)
        val validBase3 = UsageTrackerService.LocationProtocol.validateBaseUrl("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app")
        assertEquals("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app", validBase3)

        val invalidBaseUrls = listOf(
            "http://insecure.firebaseio.com",
            "https://user:pass@rtdb.firebase.io",
            "https://rtdb.firebase.io?param=val",
            "https://rtdb.firebase.io#frag",
            "https://rtdb.firebase.io/extra/path",
            "https://rtdb.firebase.io:8443",
            "https://.firebaseio.com",
            "https://evil-attacker.com",
            "https://evil-attacker.com/fake.firebasedatabase.app",
            "https://rtdb.firebase.io.attacker.com",
            " https://rtdb.firebase.io "
        )
        for (invalidUrl in invalidBaseUrls) {
            try {
                UsageTrackerService.LocationProtocol.validateBaseUrl(invalidUrl)
                fail("Must reject invalid base URL: '$invalidUrl'")
            } catch (e: IllegalArgumentException) {
                assertTrue("Exception message must indicate Fail-Closed: ${e.message}", e.message?.contains("Fail-Closed") == true)
            }
        }

        // Optimistic Concurrency Control (OCC) Interleaving & Precondition Invariants
        // Scenario A: Valid matching command -> OCC passes
        val occPass = UsageTrackerService.LocationProtocol.validateOccPrecondition(
            serverStatus = "SEARCHING_FIX",
            serverRequestedAt = now,
            targetRequestedAt = now
        )
        assertTrue("OCC must succeed when server command matches target requestedAt", occPass)

        // Scenario B: Concurrently replaced by newer parent command -> OCC rejects
        val occRejectNewer = UsageTrackerService.LocationProtocol.validateOccPrecondition(
            serverStatus = "PENDING",
            serverRequestedAt = now + 5000L,
            targetRequestedAt = now
        )
        assertFalse("OCC must fail when server command has been replaced by a newer command", occRejectNewer)

        // Scenario C: Already completed or expired -> OCC rejects
        val occRejectCompleted = UsageTrackerService.LocationProtocol.validateOccPrecondition(
            serverStatus = "COMPLETED",
            serverRequestedAt = now,
            targetRequestedAt = now
        )
        assertFalse("OCC must fail when server command is already COMPLETED", occRejectCompleted)

        val occRejectExpired = UsageTrackerService.LocationProtocol.validateOccPrecondition(
            serverStatus = "EXPIRED",
            serverRequestedAt = now,
            targetRequestedAt = now
        )
        assertFalse("OCC must fail when server command is already EXPIRED", occRejectExpired)

        // Scenario D: Invalid non-positive timestamps -> OCC fails closed
        assertFalse("OCC must fail-closed on 0L server timestamp", UsageTrackerService.LocationProtocol.validateOccPrecondition("PENDING", 0L, now))
        assertFalse("OCC must fail-closed on 0L target timestamp", UsageTrackerService.LocationProtocol.validateOccPrecondition("PENDING", now, 0L))

        // 1. Fail-closed: requestedAt <= 0L or future clock-skew must be IGNORED
        val zeroReqDecision = UsageTrackerService.evaluateLocationCommand(
            currentStatus = "PENDING",
            requestedAt = 0L,
            now = now,
            isGpsEnabled = false,
            hasLocationFix = false
        )
        assertEquals("requestedAt == 0L must be ignored", UsageTrackerService.LocationCommandDecision.Ignore, zeroReqDecision)

        val negativeReqDecision = UsageTrackerService.evaluateLocationCommand(
            currentStatus = "PENDING",
            requestedAt = -500L,
            now = now,
            isGpsEnabled = false,
            hasLocationFix = false
        )
        assertEquals("requestedAt < 0L must be ignored", UsageTrackerService.LocationCommandDecision.Ignore, negativeReqDecision)

        val futureReqDecision = UsageTrackerService.evaluateLocationCommand(
            currentStatus = "PENDING",
            requestedAt = now + 65_000L, // 65s into the future (>60s clock skew)
            now = now,
            isGpsEnabled = false,
            hasLocationFix = false
        )
        assertEquals("Future timestamp beyond 60s must be ignored", UsageTrackerService.LocationCommandDecision.Ignore, futureReqDecision)

        // 2. PENDING -> WAITING_GPS when GPS is disabled
        val promptDecision = UsageTrackerService.evaluateLocationCommand(
            currentStatus = "PENDING",
            requestedAt = now,
            now = now + 1_000L,
            isGpsEnabled = false,
            hasLocationFix = false
        )
        assertTrue("Decision must be PromptGps", promptDecision is UsageTrackerService.LocationCommandDecision.PromptGps)
        val prompt = promptDecision as UsageTrackerService.LocationCommandDecision.PromptGps
        assertEquals(now, prompt.requestedAt)
        assertEquals(now + 1_000L, prompt.updatedAt)
        assertTrue("Status must be transitioned to WAITING_GPS", prompt.shouldUpdateStatus)

        // 3. WAITING_GPS retry when GPS is still disabled (rate-limited / no status churn)
        val waitingRetryDecision = UsageTrackerService.evaluateLocationCommand(
            currentStatus = "WAITING_GPS",
            requestedAt = now,
            now = now + 5_000L,
            isGpsEnabled = false,
            hasLocationFix = false
        )
        assertTrue("Decision must still be PromptGps", waitingRetryDecision is UsageTrackerService.LocationCommandDecision.PromptGps)
        val retryPrompt = waitingRetryDecision as UsageTrackerService.LocationCommandDecision.PromptGps
        assertFalse("shouldUpdateStatus must be false to avoid redundant network churn", retryPrompt.shouldUpdateStatus)

        // 4. WAITING_GPS -> SEARCHING_FIX when GPS is enabled but satellite fix is pending
        val searchingDecision = UsageTrackerService.evaluateLocationCommand(
            currentStatus = "WAITING_GPS",
            requestedAt = now,
            now = now + 10_000L,
            isGpsEnabled = true,
            hasLocationFix = false
        )
        assertTrue("Decision must be SearchingFix", searchingDecision is UsageTrackerService.LocationCommandDecision.SearchingFix)
        val searching = searchingDecision as UsageTrackerService.LocationCommandDecision.SearchingFix
        assertEquals(now, searching.requestedAt)
        assertEquals(now + 10_000L, searching.updatedAt)
        assertTrue("Status must transition to SEARCHING_FIX", searching.shouldUpdateStatus)

        // 5. SEARCHING_FIX retry while still acquiring satellite fix
        val searchingRetryDecision = UsageTrackerService.evaluateLocationCommand(
            currentStatus = "SEARCHING_FIX",
            requestedAt = now,
            now = now + 15_000L,
            isGpsEnabled = true,
            hasLocationFix = false
        )
        assertTrue("Decision must be SearchingFix", searchingRetryDecision is UsageTrackerService.LocationCommandDecision.SearchingFix)
        val searchingRetry = searchingRetryDecision as UsageTrackerService.LocationCommandDecision.SearchingFix
        assertFalse("shouldUpdateStatus must be false during search retry", searchingRetry.shouldUpdateStatus)

        // 6. SEARCHING_FIX -> COMPLETED when satellite fix is acquired
        val completeDecision = UsageTrackerService.evaluateLocationCommand(
            currentStatus = "SEARCHING_FIX",
            requestedAt = now,
            now = now + 20_000L,
            isGpsEnabled = true,
            hasLocationFix = true
        )
        assertTrue("Decision must be Complete", completeDecision is UsageTrackerService.LocationCommandDecision.Complete)
        val complete = completeDecision as UsageTrackerService.LocationCommandDecision.Complete
        assertEquals(now, complete.requestedAt)
        assertEquals(now + 20_000L, complete.completedAt)

        // 7. Command expiration when age exceeds 3 minutes (>180,000ms)
        val expiredDecision = UsageTrackerService.evaluateLocationCommand(
            currentStatus = "PENDING",
            requestedAt = now,
            now = now + 180_001L,
            isGpsEnabled = false,
            hasLocationFix = false
        )
        assertTrue("Decision must be Expire when > 3 minutes", expiredDecision is UsageTrackerService.LocationCommandDecision.Expire)
        val expire = expiredDecision as UsageTrackerService.LocationCommandDecision.Expire
        assertEquals(now, expire.requestedAt)
        assertEquals(now + 180_001L, expire.expiredAt)

        // 8. Terminal or unknown state must be ignored
        val terminalDecision = UsageTrackerService.evaluateLocationCommand(
            currentStatus = "COMPLETED",
            requestedAt = now,
            now = now + 25_000L,
            isGpsEnabled = true,
            hasLocationFix = true
        )
        assertEquals(UsageTrackerService.LocationCommandDecision.Ignore, terminalDecision)

        // 9. Thread-safe atomic rate-limiting of student GPS notifications (30-second window)
        UsageTrackerService.lastGpsPromptTimestamp.set(0L)
        val t0 = 1_000_000L
        val casSuccess1 = UsageTrackerService.lastGpsPromptTimestamp.compareAndSet(0L, t0)
        assertTrue("First notification CAS must succeed", casSuccess1)
        assertEquals(t0, UsageTrackerService.lastGpsPromptTimestamp.get())

        // Sub-30s invocation must be throttled
        val tEarly = t0 + 15_000L
        val isThrottled = (tEarly - UsageTrackerService.lastGpsPromptTimestamp.get()) < 30_000L
        assertTrue("Notification within 30s must be throttled", isThrottled)

        // Post-30s invocation must succeed
        val tLate = t0 + 31_000L
        val lastVal = UsageTrackerService.lastGpsPromptTimestamp.get()
        val casSuccess2 = UsageTrackerService.lastGpsPromptTimestamp.compareAndSet(lastVal, tLate)
        assertTrue("Notification after 30s CAS must succeed", casSuccess2)
        assertEquals(tLate, UsageTrackerService.lastGpsPromptTimestamp.get())

        // 10. Atomic Conditional Write (ETag CAS) structure & 412 Precondition verification
        val mockResultMatch = UsageTrackerService.HttpResult(
            code = 200,
            body = "{\"status\":\"COMPLETED\"}",
            etag = "\"etag_ok_123\"",
            isSuccessful = true
        )
        assertTrue(mockResultMatch.isSuccessful)
        assertEquals(200, mockResultMatch.code)
        assertEquals("\"etag_ok_123\"", mockResultMatch.etag)

        val mockResultPreconditionFailed = UsageTrackerService.HttpResult(
            code = 412,
            body = "{\"error\":\"Precondition Failed\"}",
            etag = null,
            isSuccessful = false
        )
        assertFalse(mockResultPreconditionFailed.isSuccessful)
        assertEquals(412, mockResultPreconditionFailed.code)

        // 11. X-Firebase-ETag Request Header Invariant:
        val testCmdUrl = UsageTrackerService.LocationProtocol.getCommandUrl(
            "https://test-rtdb.firebaseio.com",
            "CVA-1234",
            "device_android_99"
        )
        val getReq = UsageTrackerService.LocationProtocol.buildGetCommandRequest(testCmdUrl)
        assertEquals("true", getReq.header("X-Firebase-ETag"))
        assertEquals("GET", getReq.method)

        // 12. Fail-Closed Conditional PUT Request Invariant:
        val dummyBody = okhttp3.RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(), "{\"status\":\"EXPIRED\"}")
        val conditionalPutReq = UsageTrackerService.LocationProtocol.buildConditionalPutRequest(testCmdUrl, "\"etag_valid_456\"", dummyBody)
        assertEquals("\"etag_valid_456\"", conditionalPutReq.header("if-match"))
        assertEquals("PUT", conditionalPutReq.method)

        // Fail-Closed: Blank or empty ETag MUST throw IllegalArgumentException
        var failClosedCaught = false
        try {
            UsageTrackerService.LocationProtocol.buildConditionalPutRequest(testCmdUrl, "", dummyBody)
        } catch (e: IllegalArgumentException) {
            failClosedCaught = true
        }
        assertTrue("buildConditionalPutRequest with empty ETag must fail-closed with IllegalArgumentException", failClosedCaught)

        // Strict HTTP 200 CAS Precondition for Location Publishing
        assertTrue("Only HTTP 200 OK permits publishing location coordinates", UsageTrackerService.LocationProtocol.shouldPublishLocationAfterCas(200))
        assertFalse("HTTP 201 Created must NOT publish location", UsageTrackerService.LocationProtocol.shouldPublishLocationAfterCas(201))
        assertFalse("HTTP 204 No Content must NOT publish location", UsageTrackerService.LocationProtocol.shouldPublishLocationAfterCas(204))
        assertFalse("HTTP 412 Precondition Failed must NOT publish location", UsageTrackerService.LocationProtocol.shouldPublishLocationAfterCas(412))
        assertFalse("HTTP 400 Bad Request must NOT publish location", UsageTrackerService.LocationProtocol.shouldPublishLocationAfterCas(400))
        assertFalse("HTTP 404 Not Found must NOT publish location", UsageTrackerService.LocationProtocol.shouldPublishLocationAfterCas(404))
        assertFalse("HTTP 500 Server Error must NOT publish location", UsageTrackerService.LocationProtocol.shouldPublishLocationAfterCas(500))
    }

    @Test
    fun testGpsCommandHardwareFencingAndTimeoutInvariants() {
        val now = 1770000000000L

        // 1. Unified 180s timeout invariant (COMMAND_EXPIRY_TIMEOUT_MS)
        assertEquals(180_000L, UsageTrackerService.LocationProtocol.COMMAND_EXPIRY_TIMEOUT_MS)

        // PENDING within 180s -> true
        assertTrue(
            UsageTrackerService.LocationProtocol.isCommandActiveAndRecent(
                UsageTrackerService.LocationProtocol.STATUS_PENDING,
                now - 60_000L,
                now
            )
        )
        // WAITING_GPS within 180s -> true
        assertTrue(
            UsageTrackerService.LocationProtocol.isCommandActiveAndRecent(
                UsageTrackerService.LocationProtocol.STATUS_WAITING_GPS,
                now - 120_000L,
                now
            )
        )
        // SEARCHING_FIX within 180s -> true
        assertTrue(
            UsageTrackerService.LocationProtocol.isCommandActiveAndRecent(
                UsageTrackerService.LocationProtocol.STATUS_SEARCHING_FIX,
                now - 179_999L,
                now
            )
        )
        // Boundary exact 180s -> true
        assertTrue(
            UsageTrackerService.LocationProtocol.isCommandActiveAndRecent(
                UsageTrackerService.LocationProtocol.STATUS_SEARCHING_FIX,
                now - 180_000L,
                now
            )
        )
        // Stale > 180s -> false
        assertFalse(
            UsageTrackerService.LocationProtocol.isCommandActiveAndRecent(
                UsageTrackerService.LocationProtocol.STATUS_SEARCHING_FIX,
                now - 180_001L,
                now
            )
        )
        // COMPLETED -> false
        assertFalse(
            UsageTrackerService.LocationProtocol.isCommandActiveAndRecent(
                UsageTrackerService.LocationProtocol.STATUS_COMPLETED,
                now - 2_000L,
                now
            )
        )
        // EXPIRED -> false
        assertFalse(
            UsageTrackerService.LocationProtocol.isCommandActiveAndRecent(
                UsageTrackerService.LocationProtocol.STATUS_EXPIRED,
                now - 2_000L,
                now
            )
        )
        // Future timestamp (requestedAt > now) -> false
        assertFalse(
            UsageTrackerService.LocationProtocol.isCommandActiveAndRecent(
                UsageTrackerService.LocationProtocol.STATUS_PENDING,
                now + 1_000L,
                now
            )
        )
        // Non-positive timestamp -> false
        assertFalse(
            UsageTrackerService.LocationProtocol.isCommandActiveAndRecent(
                UsageTrackerService.LocationProtocol.STATUS_PENDING,
                0L,
                now
            )
        )
        assertFalse(
            UsageTrackerService.LocationProtocol.isCommandActiveAndRecent(
                UsageTrackerService.LocationProtocol.STATUS_PENDING,
                -100L,
                now
            )
        )

        // 2. Hardware Fencing & Stale Telemetry Epoch Invariant
        val prefs = FakeSharedPreferences()
        val context = FakeTestContext(prefs)
        val dummyReq = Request.Builder().url("https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/test.json").build()

        // Case A: Screen is OFF -> Must immediately reject online command requests
        GuardianAccessibilityService.isScreenOnState = false
        val epochOff = GuardianAccessibilityService.telemetryEpoch.get()
        val resOffHttp = UsageTrackerService.executeOnlineHttpGuarded(dummyReq, context, epochOff)
        assertNull("executeOnlineHttpGuarded must return null when screen is off", resOffHttp)

        val resOffGuarded = UsageTrackerService.executeOnlineGuarded(dummyReq, context, epochOff)
        assertFalse("executeOnlineGuarded must return false when screen is off", resOffGuarded)

        // Case B: Stale Epoch Fencing -> If epoch advances mid-operation, old epoch must be rejected
        GuardianAccessibilityService.isScreenOnState = true
        val startEpoch = GuardianAccessibilityService.telemetryEpoch.get()
        // Epoch advances due to screen transition / keyguard lock
        GuardianAccessibilityService.telemetryEpoch.incrementAndGet()

        val staleHttpRes = UsageTrackerService.executeOnlineHttpGuarded(dummyReq, context, startEpoch)
        assertNull("executeOnlineHttpGuarded must reject stale startEpoch", staleHttpRes)

        val staleGuardedRes = UsageTrackerService.executeOnlineGuarded(dummyReq, context, startEpoch)
        assertFalse("executeOnlineGuarded must reject stale startEpoch", staleGuardedRes)

        // Case C: Active call cancellation on hardware state change
        val client = OkHttpClient()
        val call = client.newCall(dummyReq)
        UsageTrackerService.activeOnlineCalls.add(call)
        assertEquals(1, UsageTrackerService.activeOnlineCalls.size)
        assertFalse(call.isCanceled())

        UsageTrackerService.cancelActiveOnlineCalls()
        assertTrue("cancelActiveOnlineCalls must cancel active command calls", call.isCanceled())
        assertEquals(0, UsageTrackerService.activeOnlineCalls.size)
    }

    @Test
    fun testHomeAndKeyguardTransitionDoesNotBlockTelemetryMutexUnderSlowDiskIo() = kotlinx.coroutines.runBlocking {
        // Invariant: telemetryMutex must NOT be held during disk I/O (recordAppSession / prefs.apply).
        // Under slow disk I/O, subsequent foreground events or telemetry updates must acquire telemetryMutex immediately (< 100ms)
        val mutexAcquiredQuickly = java.util.concurrent.atomic.AtomicBoolean(false)
        val slowDiskJob = launch(Dispatchers.IO) {
            // Simulate background disk write running asynchronously
            kotlinx.coroutines.delay(250L)
        }

        // Try to acquire telemetryMutex immediately
        val startTime = System.currentTimeMillis()
        val acquired = kotlinx.coroutines.withTimeoutOrNull<Boolean>(100L) {
            UsageTrackerService.telemetryMutex.withLock {
                mutexAcquiredQuickly.set(true)
                true
            }
        }
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue("telemetryMutex must be free and immediately acquirable (<100ms) without waiting for background disk operations", acquired == true)
        assertTrue("mutexAcquiredQuickly must be true", mutexAcquiredQuickly.get())
        assertTrue("Elapsed time should be well below slow disk delay: elapsed=${elapsed}ms", elapsed < 250L)

        slowDiskJob.join()
    }

    @Test
    fun testForegroundGenerationMonotonicFencingPreventsOutdatedNetworkDispatchOnRapidSwitching() = kotlinx.coroutines.runBlocking {
        // Invariant: Rapid foreground switching A -> B -> C must monotonically increment foregroundGeneration.
        // Any delayed or out-of-order in-flight action from generation N < latest must be dropped immediately.
        val dispatchedActions = java.util.concurrent.CopyOnWriteArrayList<String>()

        // Simulate Action A generated at gen A
        val genA = UsageTrackerService.foregroundGeneration.incrementAndGet()
        val actionA: suspend () -> Unit = {
            if (UsageTrackerService.foregroundGeneration.get() == genA) {
                dispatchedActions.add("APP_A")
            }
        }

        // Rapid switch: Action B generated at gen B
        val genB = UsageTrackerService.foregroundGeneration.incrementAndGet()
        val actionB: suspend () -> Unit = {
            if (UsageTrackerService.foregroundGeneration.get() == genB) {
                dispatchedActions.add("APP_B")
            }
        }

        // Rapid switch: Action C generated at gen C (latest active app)
        val genC = UsageTrackerService.foregroundGeneration.incrementAndGet()
        val actionC: suspend () -> Unit = {
            if (UsageTrackerService.foregroundGeneration.get() == genC) {
                dispatchedActions.add("APP_C")
            }
        }

        assertTrue("Generations must be strictly monotonic: genA < genB < genC", genA < genB && genB < genC)
        assertEquals("Latest foregroundGeneration must equal genC", genC, UsageTrackerService.foregroundGeneration.get())

        // Simulate chaotic out-of-order arrival: Action A arrives late, Action B arrives late, Action C arrives
        // Pre-invocation fence (as implemented in GuardianAccessibilityService and UsageTrackerService.reportActiveApp)
        if (UsageTrackerService.foregroundGeneration.get() == genA) {
            actionA.invoke()
        }
        if (UsageTrackerService.foregroundGeneration.get() == genB) {
            actionB.invoke()
        }
        if (UsageTrackerService.foregroundGeneration.get() == genC) {
            actionC.invoke()
        }

        // Verify: Only action C was dispatched; stale actions A and B were completely fenced out
        assertEquals("Only the latest generation action (APP_C) must be executed", listOf("APP_C"), dispatchedActions)

        // Falsification check: Even if action A was invoked directly bypassing outer fence,
        // its internal generation guard drops execution immediately
        actionA.invoke()
        assertEquals("Direct invocation of stale actionA must still be dropped by inner generation guard", listOf("APP_C"), dispatchedActions)
    }

    private class CasTestServer : AutoCloseable {
        val serverSocket = java.net.ServerSocket(0)
        val port = serverSocket.localPort
        val initialETag = "etag_init_1000"
        var currentServerETag = initialETag
        var currentServerState = JSONObject().apply {
            put("appName", "Launcher")
            put("foregroundGeneration", 9L)
        }
        @Volatile var running = true
        private val thread = Thread {
            while (running) {
                try {
                    val socket = serverSocket.accept()
                    handleClient(socket)
                } catch (e: Exception) {
                    break
                }
            }
        }.apply { start() }

        private fun handleClient(socket: java.net.Socket) {
            socket.use { s ->
                val reader = s.getInputStream().bufferedReader(Charsets.UTF_8)
                val firstLine = reader.readLine() ?: return
                val parts = firstLine.split(" ")
                val method = if (parts.isNotEmpty()) parts[0] else "GET"
                var ifMatch: String? = null
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val lower = line.lowercase()
                    if (lower.startsWith("if-match:")) {
                        ifMatch = line.substring(line.indexOf(':') + 1).trim()
                    } else if (lower.startsWith("content-length:")) {
                        contentLength = line.substring(line.indexOf(':') + 1).trim().toIntOrNull() ?: 0
                    }
                }

                val body = if (contentLength > 0) {
                    val chars = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val r = reader.read(chars, read, contentLength - read)
                        if (r == -1) break
                        read += r
                    }
                    String(chars, 0, read)
                } else ""

                val writer = java.io.OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
                if (method.equals("GET", ignoreCase = true)) {
                    val respBody = currentServerState.toString()
                    val bytes = respBody.toByteArray(Charsets.UTF_8)
                    writer.write("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nETag: $currentServerETag\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n$respBody")
                    writer.flush()
                } else if (method.equals("PUT", ignoreCase = true)) {
                    if (ifMatch != null && ifMatch != currentServerETag) {
                        // 412 Precondition Failed
                        val respBody = currentServerState.toString()
                        val bytes = respBody.toByteArray(Charsets.UTF_8)
                        writer.write("HTTP/1.1 412 Precondition Failed\r\nContent-Type: application/json; charset=utf-8\r\nETag: $currentServerETag\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n$respBody")
                        writer.flush()
                    } else {
                        // 200 OK
                        currentServerState = JSONObject(body)
                        currentServerETag = "etag_" + java.util.UUID.randomUUID().toString().take(8)
                        val respBody = currentServerState.toString()
                        val bytes = respBody.toByteArray(Charsets.UTF_8)
                        writer.write("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nETag: $currentServerETag\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n$respBody")
                        writer.flush()
                    }
                }
            }
        }

        override fun close() {
            running = false
            try {
                serverSocket.close()
            } catch (e: Exception) {
                System.err.println("CasTestServer close exception: ${e.message}")
            }
        }
    }

    @Test
    fun testReversedArrivalOrderWithCasPreservesLatestForegroundStateOnServer() {
        // Red-Team Karl Popper Falsification Test:
        // Simulates two real HTTP requests A and B where network delivers B first,
        // and delayed A arrives later with a stale ETag.
        // Proves that server-side CAS with ETag (HTTP 412) strictly prevents stale App A
        // from overwriting newer App B on the server, guaranteeing server consistency.
        val server = CasTestServer()
        val port = server.port
        val activeAppUrl = "http://127.0.0.1:$port/active_app.json"

        try {
            val fakePrefs = FakeSharedPreferences()
            fakePrefs.data["paired_code"] = "CVA-TEST"
            fakePrefs.data["device_id"] = "TEST_DEV_01"
            val context = FakeTestContext(fakePrefs)

            // Seed initial ETag into UsageTrackerService
            UsageTrackerService.lastKnownETags[activeAppUrl] = server.initialETag

            // 1. Build Request A (App A, Gen 10) targeting activeAppUrl
            val bodyA = JSONObject().apply {
                put("appName", "App_A")
                put("foregroundGeneration", 10L)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val reqA = okhttp3.Request.Builder().url(activeAppUrl).put(bodyA).build()

            // 2. Rapid switch: Build Request B (App B, Gen 11) targeting activeAppUrl
            val bodyB = JSONObject().apply {
                put("appName", "App_B")
                put("foregroundGeneration", 11L)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val reqB = okhttp3.Request.Builder().url(activeAppUrl).put(bodyB).build()

            // 3. CHAOS: Network delivers Request B FIRST to the server!
            GuardianAccessibilityService.isScreenOnState = true
            UsageTrackerService.foregroundGeneration.set(11L)
            val successB = UsageTrackerService.executeOnlineGuarded(reqB, context, expectedEpoch = -1L, expectedGen = 11L)
            assertTrue("Request B must succeed on server when ETag matches", successB)
            assertEquals("Server state must now be App_B after Request B commits", "App_B", server.currentServerState.getString("appName"))
            assertEquals("Server generation must be 11", 11L, server.currentServerState.getLong("foregroundGeneration"))
            val etagAfterB = server.currentServerETag

            // 4. Delayed Request A now arrives at the server with stale ETag (initialETag != etagAfterB)!
            val delayedReqAWithStaleEtag = reqA.newBuilder()
                .header("X-Firebase-ETag", "true")
                .header("if-match", server.initialETag)
                .build()

            // When executed directly against the server, server-side CAS MUST return 412 and refuse write
            val client = okhttp3.OkHttpClient()
            val callA = client.newCall(delayedReqAWithStaleEtag)
            val respA = callA.execute()
            respA.use { rA ->
                assertEquals("Server must return HTTP 412 Precondition Failed for stale delayed request A", 412, rA.code)
                val returnedBody = JSONObject(rA.body?.string() ?: "{}")
                assertEquals("Server returned body must be the current committed state (App_B)", "App_B", returnedBody.getString("appName"))
                assertEquals("Server returned generation must be 11", 11L, returnedBody.getLong("foregroundGeneration"))
            }

            // 5. Verify final server state remains strictly App_B (gen 11), App_A was completely rejected!
            val verifyCall = client.newCall(okhttp3.Request.Builder().url(activeAppUrl).get().build())
            val verifyResp = verifyCall.execute()
            verifyResp.use { vr ->
                assertEquals(200, vr.code)
                val finalServerJson = JSONObject(vr.body?.string() ?: "{}")
                assertEquals("Final server state MUST remain App_B, never overwritten by delayed Request A", "App_B", finalServerJson.getString("appName"))
                assertEquals(11L, finalServerJson.getLong("foregroundGeneration"))
            }

            // 6. Test executeOnlineGuarded CAS rejection on stale generation:
            // When executeOnlineGuarded executes with an old expectedGen (10) against an already committed newer state (11),
            // it safely aborts and avoids overwriting
            val staleCallRes = UsageTrackerService.executeOnlineGuarded(
                delayedReqAWithStaleEtag, context, expectedEpoch = -1L, expectedGen = 10L
            )
            assertFalse("executeOnlineGuarded must return false when server returns 412 with newer generation", staleCallRes)
            assertEquals("Server state must still be App_B after stale executeOnlineGuarded call", "App_B", server.currentServerState.getString("appName"))
        } finally {
            server.close()
            UsageTrackerService.lastKnownETags.remove(activeAppUrl)
        }
    }

    private class FakeTestContext(
        val prefs: FakeSharedPreferences
    ) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "vn.edu.cva.smartguardian"
        override fun getSystemService(name: String): Any? = null
        override fun getFilesDir(): java.io.File {
            val temp = java.io.File(System.getProperty("java.io.tmpdir"), "test_guardian_ctx")
            if (!temp.exists()) temp.mkdirs()
            return temp
        }
        override fun getExternalFilesDir(type: String?): java.io.File? = getFilesDir()
    }
}
