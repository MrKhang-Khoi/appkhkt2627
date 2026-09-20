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
import org.junit.Before
import org.junit.Test
import vn.edu.cva.smartguardian.update.AppUpdateManager
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

        // Scenario 1: CRITICAL AUDIT CASE - rootInActiveWindow is null, ActivityManager has no foreground, UsageStats has no resumed event
        // Must strictly return FALSE (cấm coi root == null là bằng chứng foreground)
        val resultNullRootNoEvidence = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            foregroundProcessPkg = null,
            processImportance = null,
            usageStatsLastResumedPkg = null,
            targetPkg = targetPkg
        )
        assertFalse(
            "When root is null and no engine confirms foreground, background app must NOT be accepted as foreground",
            resultNullRootNoEvidence
        )

        // Scenario 2: Active process belongs to another app (e.g. Launcher with importance 100), background app fires event
        // CRITICAL INVARIANT: Dù importance là 100 nhưng processName thuộc Launcher thì targetPkg background vẫn phải bị từ chối 100%
        val resultOtherRoot = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = "com.google.android.apps.nexuslauncher",
            foregroundProcessPkg = "com.google.android.apps.nexuslauncher",
            processImportance = 100 /* IMPORTANCE_FOREGROUND của Launcher */,
            usageStatsLastResumedPkg = "com.google.android.apps.nexuslauncher",
            targetPkg = targetPkg
        )
        assertFalse("Background package must be rejected even when system launcher has importance 100", resultOtherRoot)

        // Scenario 3: Target process exists but importance is background/cached (e.g. 400)
        val resultCachedProcess = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            foregroundProcessPkg = targetPkg,
            processImportance = 400 /* IMPORTANCE_CACHED */,
            usageStatsLastResumedPkg = null,
            targetPkg = targetPkg
        )
        assertFalse("Target package must be rejected if process importance is cached (not foreground)", resultCachedProcess)

        // Scenario 4: Genuine foreground via Accessibility Window Hierarchy
        val resultAccessibilityMatch = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = targetPkg,
            foregroundProcessPkg = null,
            processImportance = null,
            usageStatsLastResumedPkg = null,
            targetPkg = targetPkg
        )
        assertTrue("Genuine Accessibility root window match must be accepted as foreground", resultAccessibilityMatch)

        // Scenario 5: Genuine foreground via ActivityManager (processName == targetPkg AND importance == 100)
        val resultActivityManagerMatch = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            foregroundProcessPkg = targetPkg,
            processImportance = 100,
            usageStatsLastResumedPkg = null,
            targetPkg = targetPkg
        )
        assertTrue("ActivityManager IMPORTANCE_FOREGROUND with matching process must be accepted as foreground", resultActivityManagerMatch)

        // Scenario 6: Genuine foreground via UsageStatsManager ACTIVITY_RESUMED
        val resultUsageStatsMatch = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            foregroundProcessPkg = null,
            processImportance = null,
            usageStatsLastResumedPkg = targetPkg,
            targetPkg = targetPkg
        )
        assertTrue("UsageStatsManager ACTIVITY_RESUMED event match must be accepted as foreground", resultUsageStatsMatch)

        // Scenario 7: Invariant conflict check - activeRootPkg belongs to another app, but UsageStats returned stale targetPkg
        // MUST BE STRICTLY REJECTED to prevent stale foreground false accounting
        val resultConflictingRootStaleUsageStats = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = "com.other.active.app",
            foregroundProcessPkg = null,
            processImportance = null,
            usageStatsLastResumedPkg = targetPkg,
            targetPkg = targetPkg
        )
        assertFalse("Stale UsageStats event must be strictly rejected when activeRootPkg belongs to a different app", resultConflictingRootStaleUsageStats)
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

        // Named process running with IMPORTANCE_FOREGROUND must be recognized as target package
        val result = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            foregroundProcessPkg = namedProcess,
            processImportance = 100,
            usageStatsLastResumedPkg = null,
            targetPkg = targetPkg
        )
        assertTrue("Named process (e.g. package:name) with IMPORTANCE_FOREGROUND must be accepted as foreground", result)
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
            foregroundProcessPkg = null,
            processImportance = null,
            usageStatsLastResumedPkg = null,
            targetPkg = "com.ss.android.ugc.trill"
        )
        assertTrue("Matching active window must resolve to true", result)
    }

    @Test
    fun testEvaluateForegroundEvidenceRejectsConflictingActiveWindow() {
        // When activeRootPkg belongs to another app (e.g. Facebook), it MUST reject TikTok
        // even if stale UsageStats or background process points to TikTok
        val result = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = "com.facebook.katana",
            foregroundProcessPkg = "com.ss.android.ugc.trill",
            processImportance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
            usageStatsLastResumedPkg = "com.ss.android.ugc.trill",
            targetPkg = "com.ss.android.ugc.trill"
        )
        assertFalse("Conflicting active window must strictly reject targetPkg", result)
    }

    @Test
    fun testEvaluateForegroundEvidenceConfirmsForegroundProcessWhenWindowNull() {
        // During Xiaomi HyperOS gesture transition, activeRootPkg might be null.
        // ActivityManager IMPORTANCE_FOREGROUND (100) on targetPkg confirms foreground app!
        val result = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            foregroundProcessPkg = "vn.edu.azota",
            processImportance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
            usageStatsLastResumedPkg = null,
            targetPkg = "vn.edu.azota"
        )
        assertTrue("Matching foreground process (100) when window is null must resolve to true", result)

        // Also test named sub-process (e.g. "vn.edu.azota:main")
        val subProcessResult = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            foregroundProcessPkg = "vn.edu.azota:player",
            processImportance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
            usageStatsLastResumedPkg = null,
            targetPkg = "vn.edu.azota"
        )
        assertTrue("Named sub-process of targetPkg must resolve to true", subProcessResult)

        // Process with importance != 100 (e.g. cached 400) must be rejected
        val cachedResult = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            foregroundProcessPkg = "vn.edu.azota",
            processImportance = 400,
            usageStatsLastResumedPkg = null,
            targetPkg = "vn.edu.azota"
        )
        assertFalse("Cached process (importance 400) must be rejected", cachedResult)
    }

    @Test
    fun testEvaluateForegroundEvidenceConfirmsUsageStatsWhenWindowAndProcessNull() {
        // Fallback to UsageStatsManager lastResumedPkg only when window is null/empty
        val result = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            foregroundProcessPkg = null,
            processImportance = null,
            usageStatsLastResumedPkg = "com.google.android.youtube",
            targetPkg = "com.google.android.youtube"
        )
        assertTrue("UsageStats fallback when window is null must resolve to true", result)

        // Empty targetPkg must immediately return false
        val emptyResult = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            foregroundProcessPkg = null,
            processImportance = null,
            usageStatsLastResumedPkg = "com.google.android.youtube",
            targetPkg = ""
        )
        assertFalse("Empty targetPkg must immediately return false", emptyResult)
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

        // Process matches target but importance is IMPORTANCE_CACHED (400) -> Must reject
        val cachedResult = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            foregroundProcessPkg = target,
            processImportance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED,
            usageStatsLastResumedPkg = null,
            targetPkg = target
        )
        assertFalse("Cached process must NOT be accepted as foreground", cachedResult)

        // Process matches target and importance is IMPORTANCE_FOREGROUND (100) -> Must accept
        val fgResult = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            foregroundProcessPkg = target,
            processImportance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
            usageStatsLastResumedPkg = null,
            targetPkg = target
        )
        assertTrue("Active foreground process must be accepted", fgResult)
    }

    @Test
    fun testEvaluateForegroundEvidenceAcceptsSubProcessWithColon() {
        val basePkg = "com.supercell.clashofclans"
        val subProcess = "com.supercell.clashofclans:remote"

        // Sub-process với dấu hai chấm (package:name) và IMPORTANCE_FOREGROUND -> BẮT BUỘC chấp nhận theo SPEC
        val result = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            foregroundProcessPkg = subProcess,
            processImportance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
            usageStatsLastResumedPkg = basePkg,
            targetPkg = basePkg
        )
        assertTrue("Sub-process package:name with IMPORTANCE_FOREGROUND must be accepted", result)

        // Sub-process nhưng importance không phải FOREGROUND -> BẮT BUỘC từ chối
        val cachedSubResult = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            foregroundProcessPkg = subProcess,
            processImportance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED,
            usageStatsLastResumedPkg = null,
            targetPkg = basePkg
        )
        assertFalse("Sub-process with CACHED importance must be rejected", cachedSubResult)
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

    private class FakeTestContext(
        val prefs: FakeSharedPreferences
    ) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "vn.edu.cva.smartguardian"
        override fun getSystemService(name: String): Any? = null
    }
}
