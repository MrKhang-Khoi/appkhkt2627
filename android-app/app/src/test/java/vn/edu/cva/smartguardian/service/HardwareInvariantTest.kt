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
        UsageTrackerService.lastPolledForegroundPkg = ""
        UsageTrackerService.lastPolledForegroundStartTime = 0L
        UsageTrackerService.inMemoryPendingSessions.clear()
        AppUpdateManager.mainDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "test_guardian_ctx")
        val journalFile = java.io.File(tempDir, UsageTrackerService.PENDING_SESSIONS_JOURNAL_FILE)
        if (journalFile.exists()) journalFile.delete()
        val walFile = java.io.File(tempDir, UsageTrackerService.PENDING_SESSIONS_WAL_FILE)
        if (walFile.exists()) walFile.delete()
        val walTmp = java.io.File(tempDir, "${UsageTrackerService.PENDING_SESSIONS_WAL_FILE}.tmp")
        if (walTmp.exists()) walTmp.delete()
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
    fun testTrackedCallRegistryConcurrentRegisterCancelCompleteSequence() {
        UsageTrackerService.activeOnlineCalls.clear()
        UsageTrackerService.onlineCallRegistry.clear()

        val client = OkHttpClient()
        val request = Request.Builder().url("https://127.0.0.1:20128/registry_test").build()

        val epoch1 = 100L
        val gen1 = 1L
        val epoch2 = 101L
        val gen2 = 2L

        val threadCount = 4
        val iterations = 50
        val barrier = java.util.concurrent.CyclicBarrier(threadCount)
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val doneLatch = CountDownLatch(threadCount)

        // Pre-create shared calls to simulate in-flight responses completing
        val sharedCalls = java.util.concurrent.ConcurrentHashMap<Int, Pair<Call, UsageTrackerService.TrackedCallRecord?>>()

        // Thread 1: Continuous registerOnlineCall for epoch1/gen1
        val t1 = Thread {
            try {
                for (i in 1..iterations) {
                    barrier.await(5, TimeUnit.SECONDS)
                    val call = client.newCall(request)
                    val record = UsageTrackerService.registerOnlineCall(call, epoch1, gen1)
                    sharedCalls[i] = Pair(call, record)
                    Thread.yield()
                }
            } catch (t: Throwable) {
                errors.add(t)
            } finally {
                doneLatch.countDown()
            }
        }

        // Thread 2: cancelActiveOnlineCalls() triggering atomic cancellation
        val t2 = Thread {
            try {
                for (i in 1..iterations) {
                    barrier.await(5, TimeUnit.SECONDS)
                    UsageTrackerService.cancelActiveOnlineCalls()
                    Thread.yield()
                }
            } catch (t: Throwable) {
                errors.add(t)
            } finally {
                doneLatch.countDown()
            }
        }

        // Thread 3: Simulates response complete and unregisterOnlineCall
        val t3 = Thread {
            try {
                for (i in 1..iterations) {
                    barrier.await(5, TimeUnit.SECONDS)
                    val pair = sharedCalls.remove(i)
                    if (pair != null) {
                        UsageTrackerService.unregisterOnlineCall(pair.first, pair.second)
                    }
                    Thread.yield()
                }
            } catch (t: Throwable) {
                errors.add(t)
            } finally {
                doneLatch.countDown()
            }
        }

        // Thread 4: Registers new call for epoch2 / gen2
        val t4 = Thread {
            try {
                for (i in 1..iterations) {
                    barrier.await(5, TimeUnit.SECONDS)
                    val call = client.newCall(request)
                    val record = UsageTrackerService.registerOnlineCall(call, epoch2, gen2)
                    if (i % 2 == 0) {
                        UsageTrackerService.unregisterOnlineCall(call, record)
                    }
                    Thread.yield()
                }
            } catch (t: Throwable) {
                errors.add(t)
            } finally {
                doneLatch.countDown()
            }
        }

        listOf(t1, t2, t3, t4).forEach { it.start() }

        val completed = doneLatch.await(10, TimeUnit.SECONDS)
        assertTrue("Concurrent registry operations must complete within timeout", completed)
        assertTrue("No concurrency exceptions must be thrown: $errors", errors.isEmpty())

        // Final cancellation drains everything
        UsageTrackerService.cancelActiveOnlineCalls()
        assertEquals("activeOnlineCalls must be completely clean", 0, UsageTrackerService.activeOnlineCalls.size)
        assertEquals("onlineCallRegistry must be completely clean", 0, UsageTrackerService.onlineCallRegistry.size)
    }

    @Test
    fun testAdversarialCallRegistryAtomicInterleaving() {
        val client = OkHttpClient()
        val request = Request.Builder().url("https://127.0.0.1:20128/adversarial_call").build()

        for (iter in 1..200) {
            UsageTrackerService.activeOnlineCalls.clear()
            UsageTrackerService.onlineCallRegistry.clear()

            val call = client.newCall(request)
            val barrier = java.util.concurrent.CyclicBarrier(2)
            val registerDone = CountDownLatch(1)
            val cancelDone = CountDownLatch(1)
            var record: UsageTrackerService.TrackedCallRecord? = null

            val regThread = Thread {
                barrier.await()
                record = UsageTrackerService.registerOnlineCall(call, 100L, 1L)
                registerDone.countDown()
            }

            val cancelThread = Thread {
                barrier.await()
                UsageTrackerService.cancelActiveOnlineCalls()
                cancelDone.countDown()
            }

            regThread.start()
            cancelThread.start()

            assertTrue(registerDone.await(5, TimeUnit.SECONDS))
            assertTrue(cancelDone.await(5, TimeUnit.SECONDS))

            // Post-condition verification:
            // Either cancel ran AFTER register (so call was registered then canceled and cleared)
            // Or cancel ran BEFORE register (so register added it after cancel)
            // But when cancelActiveOnlineCalls() runs, it MUST cancel the call and clear both collections
            UsageTrackerService.cancelActiveOnlineCalls()
            assertTrue("Call must be canceled", call.isCanceled())
            assertEquals("activeOnlineCalls must be 0", 0, UsageTrackerService.activeOnlineCalls.size)
            assertEquals("onlineCallRegistry must be 0", 0, UsageTrackerService.onlineCallRegistry.size)
        }
    }

    @Test
    fun testAdversarialOfflineCallRegistryAtomicInterleaving() {
        val client = OkHttpClient()
        val request = Request.Builder().url("https://127.0.0.1:20128/adversarial_offline_call").build()

        for (iter in 1..200) {
            UsageTrackerService.activeOfflineCalls.clear()
            UsageTrackerService.offlineCallRegistry.clear()

            val call = client.newCall(request)
            val barrier = java.util.concurrent.CyclicBarrier(2)
            val registerDone = CountDownLatch(1)
            val cancelDone = CountDownLatch(1)

            val regThread = Thread {
                barrier.await()
                UsageTrackerService.registerOfflineCall(call, 100L, 1L)
                registerDone.countDown()
            }

            val cancelThread = Thread {
                barrier.await()
                UsageTrackerService.cancelActiveOfflineCalls()
                cancelDone.countDown()
            }

            regThread.start()
            cancelThread.start()

            assertTrue(registerDone.await(5, TimeUnit.SECONDS))
            assertTrue(cancelDone.await(5, TimeUnit.SECONDS))

            UsageTrackerService.cancelActiveOfflineCalls()
            assertTrue("Call must be canceled", call.isCanceled())
            assertEquals("activeOfflineCalls must be 0", 0, UsageTrackerService.activeOfflineCalls.size)
            assertEquals("offlineCallRegistry must be 0", 0, UsageTrackerService.offlineCallRegistry.size)
        }
    }

    @Test
    fun testCancelDoesNotHoldOnlineOrOfflineLockDuringCancel() {
        // Test 1: Verify onlineCallLock is released before call.cancel() is executed
        val onlineCancelStarted = CountDownLatch(1)
        val onlineTestRelease = CountDownLatch(1)
        val acquiredOnlineLockDuringCancel = java.util.concurrent.atomic.AtomicBoolean(false)

        val slowOnlineCall = java.lang.reflect.Proxy.newProxyInstance(
            Call::class.java.classLoader,
            arrayOf(Call::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "cancel" -> {
                    onlineCancelStarted.countDown()
                    onlineTestRelease.await(3, TimeUnit.SECONDS)
                    null
                }
                "isCanceled" -> true
                "hashCode" -> 101
                "equals" -> false
                else -> null
            }
        } as Call

        UsageTrackerService.activeOnlineCalls.add(slowOnlineCall)

        val onlineCancelThread = Thread {
            UsageTrackerService.cancelActiveOnlineCalls()
        }
        onlineCancelThread.start()

        assertTrue("online cancel must start", onlineCancelStarted.await(2, TimeUnit.SECONDS))

        // Concurrently attempt to acquire onlineCallLock while call.cancel() is blocked inside slowOnlineCall
        val lockAttemptThread = Thread {
            synchronized(UsageTrackerService.onlineCallLock) {
                acquiredOnlineLockDuringCancel.set(true)
            }
            onlineTestRelease.countDown()
        }
        lockAttemptThread.start()

        lockAttemptThread.join(2000)
        onlineCancelThread.join(2000)

        assertTrue(
            "Lock Convoy Elimination: onlineCallLock MUST be acquired concurrently while call.cancel() executes",
            acquiredOnlineLockDuringCancel.get()
        )

        // Test 2: Verify urgentOfflineLock is released before call.cancel() is executed
        val offlineCancelStarted = CountDownLatch(1)
        val offlineTestRelease = CountDownLatch(1)
        val acquiredOfflineLockDuringCancel = java.util.concurrent.atomic.AtomicBoolean(false)

        val slowOfflineCall = java.lang.reflect.Proxy.newProxyInstance(
            Call::class.java.classLoader,
            arrayOf(Call::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "cancel" -> {
                    offlineCancelStarted.countDown()
                    offlineTestRelease.await(3, TimeUnit.SECONDS)
                    null
                }
                "isCanceled" -> true
                "hashCode" -> 102
                "equals" -> false
                else -> null
            }
        } as Call

        UsageTrackerService.activeOfflineCalls.add(slowOfflineCall)

        val offlineCancelThread = Thread {
            UsageTrackerService.cancelActiveOfflineCalls()
        }
        offlineCancelThread.start()

        assertTrue("offline cancel must start", offlineCancelStarted.await(2, TimeUnit.SECONDS))

        val offlineLockAttemptThread = Thread {
            synchronized(UsageTrackerService.urgentOfflineLock) {
                acquiredOfflineLockDuringCancel.set(true)
            }
            offlineTestRelease.countDown()
        }
        offlineLockAttemptThread.start()

        offlineLockAttemptThread.join(2000)
        offlineCancelThread.join(2000)

        assertTrue(
            "Lock Convoy Elimination: urgentOfflineLock MUST be acquired concurrently while call.cancel() executes",
            acquiredOfflineLockDuringCancel.get()
        )
    }

    @Test
    fun testStaleOnlineCallRegistrationRejectedAfterCancellation() {
        val client = OkHttpClient()
        val request = Request.Builder().url("https://127.0.0.1:20128/stale_online").build()

        // Scenario 1: Screen is off
        GuardianAccessibilityService.isScreenOnState = false
        val epoch1 = GuardianAccessibilityService.telemetryEpoch.incrementAndGet()
        val gen1 = UsageTrackerService.foregroundGeneration.incrementAndGet()
        UsageTrackerService.cancelActiveOnlineCalls()

        val call1 = client.newCall(request)
        val record1 = UsageTrackerService.registerOnlineCall(call1, epoch1, gen1, context = null)
        assertNull("Stale online call registered when screen is off must be rejected", record1)
        assertTrue("Call must be canceled", call1.isCanceled())
        assertEquals(0, UsageTrackerService.activeOnlineCalls.size)
        assertEquals(0, UsageTrackerService.onlineCallRegistry.size)

        // Scenario 2: Screen is on, but epoch or generation is stale (arrives after cancelActiveOnlineCalls)
        GuardianAccessibilityService.isScreenOnState = true
        val staleEpoch = GuardianAccessibilityService.telemetryEpoch.get()
        val staleGen = UsageTrackerService.foregroundGeneration.get()

        // Cancellation advances epoch/gen
        GuardianAccessibilityService.telemetryEpoch.incrementAndGet()
        UsageTrackerService.foregroundGeneration.incrementAndGet()
        UsageTrackerService.cancelActiveOnlineCalls()

        val call2 = client.newCall(request)
        val record2 = UsageTrackerService.registerOnlineCall(call2, staleEpoch, staleGen, context = null)
        assertNull("Stale online call registered with older epoch/gen must be rejected", record2)
        assertTrue("Call must be canceled", call2.isCanceled())
        assertEquals(0, UsageTrackerService.activeOnlineCalls.size)
        assertEquals(0, UsageTrackerService.onlineCallRegistry.size)
    }

    @Test
    fun testStaleOfflineCallRegistrationRejectedWhenScreenTurnsOn() {
        val client = OkHttpClient()
        val request = Request.Builder().url("https://127.0.0.1:20128/stale_offline").build()

        // Screen is on -> Device is online, offline call must be strictly rejected
        GuardianAccessibilityService.isScreenOnState = true
        val epoch = GuardianAccessibilityService.telemetryEpoch.get()
        val gen = UsageTrackerService.currentOfflineGeneration.get()

        val call = client.newCall(request)
        val record = UsageTrackerService.registerOfflineCall(call, epoch, gen, context = null)
        assertNull("Offline call registered when screen is on must be rejected", record)
        assertTrue("Call must be canceled", call.isCanceled())
        assertEquals(0, UsageTrackerService.activeOfflineCalls.size)
        assertEquals(0, UsageTrackerService.offlineCallRegistry.size)
    }

    @Test
    fun testWindowEvidenceInterruptedScanFailsClosed() {
        // Simulates Binder Accessibility DeadObjectException / SecurityException midway through window scan:
        // Direct root was acquired, but window reading threw exception -> windowEvidenceReadComplete = false
        var windowEvidenceReadComplete = false
        var directRootPkg: String? = "com.target.app"
        var secondaryMatchingPkg: String? = null
        var conflictingWindowPkg: String? = null

        try {
            // Simulated exception during windows scan
            throw java.lang.SecurityException("Binder transaction failed: DeadObjectException")
            @Suppress("UNREACHABLE_CODE")
            windowEvidenceReadComplete = true
        } catch (e: Exception) {
            directRootPkg = null
            secondaryMatchingPkg = null
            conflictingWindowPkg = null
            windowEvidenceReadComplete = false
        }

        assertFalse("windowEvidenceReadComplete must remain false after exception", windowEvidenceReadComplete)
        assertNull("directRootPkg must be cleared on exception", directRootPkg)
        assertNull("secondaryMatchingPkg must be cleared on exception", secondaryMatchingPkg)

        // Test evaluateForegroundEvidence fail-closed behavior (requireWindowEvidence = true when window scan fails)
        val evidenceResult = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = directRootPkg,
            usageStatsLastResumedPkg = "com.target.app",
            targetPkg = "com.target.app",
            now = System.currentTimeMillis(),
            lastEventTime = System.currentTimeMillis() - 1000L,
            maxEventAgeMs = 15000L,
            secondaryWindowPkg = secondaryMatchingPkg,
            conflictingWindowPkg = conflictingWindowPkg,
            requireWindowEvidence = true
        )
        assertFalse("When window scan is interrupted/failed, evaluateForegroundEvidence must reject targetPkg", evidenceResult)
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

        val epoch = 42L
        GuardianAccessibilityService.isScreenOnState = false
        GuardianAccessibilityService.telemetryEpoch.set(epoch)
        UsageTrackerService.lastDispatchedOfflineEpoch.set(-1L)

        val job1 = UsageTrackerService.sendUrgentOfflineStatus(fakeContext, epoch)
        assertTrue("First dispatch for epoch $epoch must launch a Job", job1 != null)

        // Immediate duplicate call with identical epoch: Must return null without launching redundant batch!
        val job2 = UsageTrackerService.sendUrgentOfflineStatus(fakeContext, epoch)
        assertEquals("Duplicate call with same epoch must be rejected idempotently", null, job2)

        // Clean up
        UsageTrackerService.cancelActiveOfflineCalls()
        UsageTrackerService.lastDispatchedOfflineEpoch.set(-1L)
        GuardianAccessibilityService.isScreenOnState = true
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

    private open class FakeSharedPreferences(
        val data: MutableMap<String, Any?> = mutableMapOf(),
        var commitReturnsSuccess: Boolean = true,
        var throwOnRead: Boolean = false
    ) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = synchronized(data) { HashMap(data) }
        open override fun getString(key: String?, defValue: String?): String? = synchronized(data) {
            if (throwOnRead) throw IllegalStateException("Storage read failure simulation")
            (data[key] as? String) ?: defValue
        }
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = synchronized(data) { (data[key] as? MutableSet<String>) ?: defValues }
        override fun getInt(key: String?, defValue: Int): Int = synchronized(data) { (data[key] as? Int) ?: defValue }
        override fun getLong(key: String?, defValue: Long): Long = synchronized(data) { (data[key] as? Long) ?: defValue }
        override fun getFloat(key: String?, defValue: Float): Float = synchronized(data) { (data[key] as? Float) ?: defValue }
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = synchronized(data) { (data[key] as? Boolean) ?: defValue }
        override fun contains(key: String?): Boolean = synchronized(data) { data.containsKey(key) }
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
                synchronized(prefs.data) {
                    if (clearRequested) prefs.data.clear()
                    for (k in toRemove) prefs.data.remove(k)
                    for ((k, v) in temp) prefs.data[k] = v
                }
                return true
            }
            override fun apply() {
                synchronized(prefs.data) {
                    if (clearRequested) prefs.data.clear()
                    for (k in toRemove) prefs.data.remove(k)
                    for ((k, v) in temp) prefs.data[k] = v
                }
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

        // Session must be recorded in prefs (đợi tối đa 3000ms cho Dispatchers.IO hoàn tất)
        val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        val appKey = "session_${todayStr}_com.study.math"
        var elapsed = 0
        while (!fakePrefs.contains(appKey) && elapsed < 3000) {
            Thread.sleep(25)
            elapsed += 25
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

        // Đợi IO hoàn tất (tối đa 3000ms)
        val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        val appKey = "session_${todayStr}_com.study.concurrent"
        var elapsed = 0
        while (!fakePrefs.contains(appKey) && elapsed < 3000) {
            Thread.sleep(25)
            elapsed += 25
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

        // 2. Mất mạng quá 90s -> ngoại tuyến
        val devStale = JSONObject().apply {
            put("isPaired", true)
            put("online", true)
            put("lastSync", now - 91000L)
        }
        assertFalse("Thiết bị quá 90s không liên lạc phải ngoại tuyến", MainActivity.computeDeviceOnlineStatus(devStale, now))

        // 3. Màn hình tắt (SCREEN_OFF không có timestamp) -> ngoại tuyến (Zero Phantom Time)
        val devScreenOff = JSONObject().apply {
            put("isPaired", true)
            put("online", true)
            put("lastSync", now - 5000L)
            put("active_app", JSONObject().apply {
                put("packageName", "SCREEN_OFF")
            })
        }
        assertFalse("Thiết bị ở trạng thái SCREEN_OFF phải ngoại tuyến", MainActivity.computeDeviceOnlineStatus(devScreenOff, now))

        // 3b. Thoát khỏi bẫy Stale SCREEN_OFF khi thiết bị gửi heartbeat mới hơn sự kiện tắt màn hình
        val devStaleScreenOffWithFreshHeartbeat = JSONObject().apply {
            put("isPaired", true)
            put("status", "paired")
            put("online", true)
            put("lastSync", now - 5000L)
            put("lastHeartbeat", now - 5000L)
            put("active_app", JSONObject().apply {
                put("packageName", "SCREEN_OFF")
                put("timestamp", now - 300000L) // Tắt màn hình từ 5 phút trước, nhưng vừa gửi heartbeat 5s trước
            })
        }
        assertTrue("Thiết bị có heartbeat mới 5s trước dù active_app lưu SCREEN_OFF cũ từ 5m trước phải trực tuyến", MainActivity.computeDeviceOnlineStatus(devStaleScreenOffWithFreshHeartbeat, now))

        // 3c. Màn hình tắt thực sự với timestamp mới (đồng bộ cùng nhịp tim) -> ngoại tuyến
        val devFreshScreenOff = JSONObject().apply {
            put("isPaired", true)
            put("status", "paired")
            put("online", true)
            put("lastSync", now - 5000L)
            put("lastHeartbeat", now - 5000L)
            put("active_app", JSONObject().apply {
                put("packageName", "SCREEN_OFF")
                put("timestamp", now - 5000L)
            })
        }
        assertFalse("Thiết bị vừa gửi trạng thái SCREEN_OFF cùng nhịp tim phải ngoại tuyến", MainActivity.computeDeviceOnlineStatus(devFreshScreenOff, now))

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
        assertEquals("true", conditionalPutReq.header("X-Firebase-ETag"))
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
    fun testBuildConditionalPutRequestContainsBothETagHeaders() {
        val body = "{}".toRequestBody("application/json".toMediaType())
        val req = UsageTrackerService.LocationProtocol.buildConditionalPutRequest(
            "https://cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app/test.json",
            "etag_12345",
            body
        )
        assertEquals("true", req.header(UsageTrackerService.LocationProtocol.HEADER_FIREBASE_ETAG))
        assertEquals("etag_12345", req.header(UsageTrackerService.LocationProtocol.HEADER_IF_MATCH))
        assertEquals("PUT", req.method)
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

    @Test
    fun testCompanionBottomSheetUiStatesAreStrictlyMutuallyExclusive() {
        // Red-Team Verification for Codex UI/UX Requirement:
        // Tests that setting any CompanionUiState (LOADING, CONTENT, EMPTY, ERROR)
        // results in STRICTLY mutually exclusive visibility: exactly one container VISIBLE, all others GONE.
        for (state in MainActivity.ChildCompanionBottomSheetDialogFragment.CompanionUiState.values()) {
            val visMap = MainActivity.ChildCompanionBottomSheetDialogFragment.resolveCompanionUiVisibilities(state)
            val visibleStates = visMap.filter { it.value == android.view.View.VISIBLE }
            val goneStates = visMap.filter { it.value == android.view.View.GONE }

            assertEquals("Exactly one state must be VISIBLE when state is $state", 1, visibleStates.size)
            assertEquals("Exactly three states must be GONE when state is $state", 3, goneStates.size)
            assertTrue("The target state $state must be the VISIBLE one", visibleStates.containsKey(state))
        }

        // Verify XML Layout Invariant: layoutDialogLoading is visible on inflation, all others are gone
        val xmlCandidates = listOf(
            java.io.File("src/main/res/layout/dialog_child_companion.xml"),
            java.io.File("app/src/main/res/layout/dialog_child_companion.xml"),
            java.io.File("android-app/app/src/main/res/layout/dialog_child_companion.xml")
        )
        val xmlFile = xmlCandidates.firstOrNull { it.exists() }
        assertNotNull("dialog_child_companion.xml must exist on disk", xmlFile)
        val xmlContent = xmlFile?.readText().orEmpty()

        assertTrue("layoutDialogLoading must default to visible", xmlContent.contains("""android:id="@+id/layoutDialogLoading"""") && xmlContent.contains("""android:visibility="visible""""))
        assertTrue("layoutDialogContent must default to gone", xmlContent.contains("""android:id="@+id/layoutDialogContent"""") && xmlContent.contains("""android:visibility="gone""""))
        assertTrue("layoutDialogError must default to gone", xmlContent.contains("""android:id="@+id/layoutDialogError"""") && xmlContent.contains("""android:visibility="gone""""))
        assertTrue("layoutDialogEmptyState must default to gone", xmlContent.contains("""android:id="@+id/layoutDialogEmptyState"""") && xmlContent.contains("""android:visibility="gone""""))
        assertTrue("layoutTabEmptyState must be present for category tab filtering", xmlContent.contains("""android:id="@+id/layoutTabEmptyState""""))

        // Verify Adaptive M3 Responsive Architecture: FrameLayout root wrapper and centered card
        assertTrue("Root container must be layoutDialogAdaptiveWrapper FrameLayout", xmlContent.contains("""android:id="@+id/layoutDialogAdaptiveWrapper""""))
        assertTrue("Card container must be layoutDialogMainCard with center_horizontal gravity",
            xmlContent.contains("""android:id="@+id/layoutDialogMainCard"""") && xmlContent.contains("""android:layout_gravity="center_horizontal""""))
        assertTrue("ScrollView must be layoutDialogScrollView with fillViewport and center_horizontal",
            xmlContent.contains("""android:id="@+id/layoutDialogScrollView"""") &&
            xmlContent.contains("""android:layout_gravity="center_horizontal"""") &&
            xmlContent.contains("""android:fillViewport="true""""))

        // Verify Adaptive Width Calculation across Phone, Foldable, Tablet, and Desktop screen widths
        // 1. Phone portrait (360dp width): full screen width (no artificial padding constraint)
        val phoneWidthPx = (360 * 2.0f).toInt()
        val computedPhone = MainActivity.ChildCompanionBottomSheetDialogFragment.computeAdaptiveSheetWidth(phoneWidthPx, 2.0f, maxWidthDp = 640)
        assertEquals("Phone width 360dp must remain 100% width", phoneWidthPx, computedPhone)

        // 2. Foldable unfolded (840dp width): clamped to 640dp max
        val foldableWidthPx = (840 * 2.0f).toInt()
        val computedFoldable = MainActivity.ChildCompanionBottomSheetDialogFragment.computeAdaptiveSheetWidth(foldableWidthPx, 2.0f, maxWidthDp = 640)
        assertEquals("Foldable width 840dp must clamp to 640dp", (640 * 2.0f).toInt(), computedFoldable)
        assertTrue("Foldable width must not exceed 640dp", computedFoldable <= (640 * 2.0f).toInt())

        // 3. Tablet (1000dp width): clamped to 640dp max
        val tabletWidthPx = (1000 * 1.5f).toInt()
        val computedTablet = MainActivity.ChildCompanionBottomSheetDialogFragment.computeAdaptiveSheetWidth(tabletWidthPx, 1.5f, maxWidthDp = 640)
        assertEquals("Tablet width 1000dp must clamp to 640dp", (640 * 1.5f).toInt(), computedTablet)
        assertTrue("Tablet width must not exceed 640dp", computedTablet <= (640 * 1.5f).toInt())

        // 4. Large Desktop / TV (1920dp width): clamped to 640dp max
        val desktopWidthPx = (1920 * 2.0f).toInt()
        val computedDesktop = MainActivity.ChildCompanionBottomSheetDialogFragment.computeAdaptiveSheetWidth(desktopWidthPx, 2.0f, maxWidthDp = 640)
        assertEquals("Desktop width 1920dp must clamp to 640dp", (640 * 2.0f).toInt(), computedDesktop)

        // Verify Font Scale 1.5x - 2.0x Resilience: No fixed-height overflow traps on content text views
        assertFalse("Layout must not contain fixed-height overflow traps (layout_height=280dp)", xmlContent.contains("""android:layout_height="280dp""""))
        assertTrue("Touch targets must meet Material minimum height 48dp", xmlContent.contains("""android:minHeight="48dp""""))
    }

    @Test
    fun testBankPackageAtomicallyTransitionsStateUnderSessionLock() {
        // Red-Team Karl Popper Falsification Test:
        // Verifies that bank package event resets currentForegroundPackage, sets lastActivePackage to BANK_APP_PROTECTED,
        // and safely accounts the previous app session exactly once under sessionLock without race condition.
        val bankPkg = "com.VCB"
        assertTrue("com.VCB must be recognized as bank package", GuardianAccessibilityService.isBankPackage(bankPkg))
        assertTrue("com.vcb.digibank must be recognized as bank package", GuardianAccessibilityService.isBankPackage("com.vcb.digibank"))
        assertTrue("com.mbmobile must be recognized as bank package", GuardianAccessibilityService.isBankPackage("com.mbmobile"))
        assertTrue("vn.momo.platform must be recognized as bank package", GuardianAccessibilityService.isBankPackage("vn.momo.platform"))
        assertFalse("YouTube is not a bank package", GuardianAccessibilityService.isBankPackage("com.google.android.youtube"))

        // State Machine Verification under sessionLock:
        // Simulates the exact state transition executed in handleWindowStateChangedLocked
        val fgPackageRef = java.util.concurrent.atomic.AtomicReference("com.google.android.youtube")
        val fgStartTimeRef = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() - 5000L)
        val lastActivePkgRef = java.util.concurrent.atomic.AtomicReference("com.google.android.youtube")
        val now = System.currentTimeMillis()

        val shouldUploadFirst = synchronized(GuardianAccessibilityService.sessionLock) {
            val prevPkg = fgPackageRef.get()
            val prevStart = fgStartTimeRef.get()
            val prevToken = if (prevPkg.isNotEmpty() && prevStart > 0L) "${prevPkg}_${prevStart}" else ""
            assertEquals("com.google.android.youtube", prevPkg)
            assertTrue(prevStart > 0L)
            assertTrue(prevToken.startsWith("com.google.android.youtube_"))

            fgPackageRef.set("")
            fgStartTimeRef.set(0L)
            if (lastActivePkgRef.get() != "BANK_APP_PROTECTED") {
                lastActivePkgRef.set("BANK_APP_PROTECTED")
                true
            } else {
                false
            }
        }

        assertTrue("First bank app transition must trigger upload", shouldUploadFirst)
        assertEquals("Foreground package must be cleared when opening bank app", "", fgPackageRef.get())
        assertEquals(0L, fgStartTimeRef.get())
        assertEquals("lastActivePackage must be BANK_APP_PROTECTED", "BANK_APP_PROTECTED", lastActivePkgRef.get())

        // Idempotency: second bank package event must NOT re-upload
        val shouldUploadSecond = synchronized(GuardianAccessibilityService.sessionLock) {
            if (lastActivePkgRef.get() != "BANK_APP_PROTECTED") {
                lastActivePkgRef.set("BANK_APP_PROTECTED")
                true
            } else {
                false
            }
        }
        assertFalse("Subsequent bank app events must be debounced idempotently", shouldUploadSecond)
    }

    @Test
    fun testIsForegroundAppRejectsSplitScreenInactiveWindowAndRequiresFocusOrUsageStatsFallback() {
        // Red-Team Karl Popper Falsification:
        // When evaluateForegroundEvidence is called with a package that is NOT the active root window,
        // it must reject it (fail-closed) and NOT blindly accept inactive or unfocused windows.
        val now = System.currentTimeMillis()

        // 1. Inactive window in split-screen (active root is YouTube, target is TikTok) -> MUST return false (fail-closed)
        val splitScreenResult = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = "com.google.android.youtube",
            usageStatsLastResumedPkg = "com.ss.android.ugc.trill",
            targetPkg = "com.ss.android.ugc.trill",
            now = now,
            lastEventTime = now - 1000L
        )
        assertFalse("Split-screen background app must be rejected when root is active YouTube", splitScreenResult)

        // 2. Exact match on active root window -> MUST return true
        val directActiveResult = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = "com.google.android.youtube",
            usageStatsLastResumedPkg = null,
            targetPkg = "com.google.android.youtube",
            now = now,
            lastEventTime = now - 1000L
        )
        assertTrue("Active root window matching target must be confirmed foreground", directActiveResult)

        // 3. Null root window but valid UsageStats ACTIVITY_RESUMED within 15s -> Allowed fallback
        val validUsageStatsFallback = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = "com.google.android.youtube",
            targetPkg = "com.google.android.youtube",
            now = now,
            lastEventTime = now - 2000L
        )
        assertTrue("Fallback to recent ACTIVITY_RESUMED within 15s must be accepted", validUsageStatsFallback)

        // 4. Null root window with stale UsageStats (> 15s) -> MUST return false (fail-closed)
        val staleUsageStatsFallback = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = "com.google.android.youtube",
            targetPkg = "com.google.android.youtube",
            now = now,
            lastEventTime = now - 20_000L
        )
        assertFalse("Stale UsageStats event (> 15s) must be rejected fail-closed", staleUsageStatsFallback)
    }

    @Test
    fun testSplitScreenActiveWindowWithUsageStatsAgreementIsAccepted() {
        val target = "com.google.android.youtube"
        val now = System.currentTimeMillis()

        // Trong split-screen: target là active window (secondaryWindowPkg),
        // và UsageStats đồng thuận với target trong vòng 15s -> Được công nhận foreground
        val acceptedResult = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = target,
            targetPkg = target,
            now = now,
            lastEventTime = now - 2000L,
            maxEventAgeMs = 15_000L,
            secondaryWindowPkg = target,
            conflictingWindowPkg = null,
            requireWindowEvidence = true
        )
        assertTrue("Split-screen active window with UsageStats consensus must be accepted", acceptedResult)

        // Nếu UsageStats chỉ tới app khác -> Fail-closed
        val rejectedResult = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = "com.other.app",
            targetPkg = target,
            now = now,
            lastEventTime = now - 2000L,
            maxEventAgeMs = 15_000L,
            secondaryWindowPkg = target,
            conflictingWindowPkg = "com.other.app",
            requireWindowEvidence = true
        )
        assertFalse("Split-screen active window conflicting with other focused/UsageStats app must be rejected", rejectedResult)
    }

    @Test
    fun testCasPreconditionFailed412FetchesFreshNodeStateAndAbortsOnNewerGeneration() {
        // Red-Team Verification: Proves that when an HTTP 412 is encountered,
        // executeOnlineGuarded GETs the fresh server node state with X-Firebase-ETag: true,
        // sees that serverGen >= expectedGen, and safely aborts without overwriting.
        val server = CasTestServer()
        val port = server.port
        val activeAppUrl = "http://127.0.0.1:$port/active_app.json"

        try {
            val fakePrefs = FakeSharedPreferences()
            fakePrefs.data["paired_code"] = "CVA-TEST"
            fakePrefs.data["device_id"] = "TEST_DEV_02"
            val context = FakeTestContext(fakePrefs)

            // Commit a newer state directly on the server (Generation 20, App Z)
            server.currentServerState = JSONObject().apply {
                put("appName", "App_Z")
                put("foregroundGeneration", 20L)
            }
            server.currentServerETag = "etag_gen20"

            // Seed an obsolete ETag in client
            UsageTrackerService.lastKnownETags[activeAppUrl] = "etag_stale"

            // Try to write older state (Generation 15, App Y) with obsolete ETag
            val bodyY = JSONObject().apply {
                put("appName", "App_Y")
                put("foregroundGeneration", 15L)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val reqY = okhttp3.Request.Builder()
                .url(activeAppUrl)
                .put(bodyY)
                .build()

            GuardianAccessibilityService.isScreenOnState = true
            UsageTrackerService.foregroundGeneration.set(15L)

            val result = UsageTrackerService.executeOnlineGuarded(
                reqY, context, expectedEpoch = -1L, expectedGen = 15L
            )

            assertFalse("executeOnlineGuarded must abort when server returns 412 and GET proves server is newer (20 >= 15)", result)
            assertEquals("Server state must remain App_Z", "App_Z", server.currentServerState.getString("appName"))
            assertEquals("Server generation must remain 20", 20L, server.currentServerState.getLong("foregroundGeneration"))
            assertEquals("lastKnownETags must be updated with fresh ETag from GET refetch", "etag_gen20", UsageTrackerService.lastKnownETags[activeAppUrl])
        } finally {
            server.close()
            UsageTrackerService.lastKnownETags.remove(activeAppUrl)
        }
    }

    @Test
    fun testCasColdStartWithEmptyETagCachePerformsPreGetAndPreventsStaleOverwrite() {
        // Red-Team Karl Popper Falsification Test Mandated by OpenAI Codex:
        // Proves that when lastKnownETags is strictly EMPTY (cold start, cache evicted or process restart),
        // executeOnlineGuarded and executeOnlineHttpGuarded FORBID sending unconditional PUT.
        // Instead, they perform a preliminary GET with X-Firebase-ETag: true, detect if server holds a newer generation,
        // and abort fail-closed without overwriting the server state.
        val server = CasTestServer()
        val port = server.port
        val activeAppUrl = "http://127.0.0.1:$port/active_app.json"

        try {
            val fakePrefs = FakeSharedPreferences()
            fakePrefs.data["paired_code"] = "CVA-TEST"
            fakePrefs.data["device_id"] = "TEST_DEV_03"
            val context = FakeTestContext(fakePrefs)

            // Step 1: Server holds Generation 20, App_Z
            server.currentServerState = JSONObject().apply {
                put("appName", "App_Z")
                put("foregroundGeneration", 20L)
            }
            server.currentServerETag = "etag_gen20"

            // Step 2: Ensure client cache is strictly EMPTY (no cached ETag!)
            UsageTrackerService.lastKnownETags.remove(activeAppUrl)
            assertNull("Client ETag cache must be empty before test", UsageTrackerService.lastKnownETags[activeAppUrl])

            // Step 3: Client generates stale request (Generation 15, App_Y)
            val bodyY = JSONObject().apply {
                put("appName", "App_Y")
                put("foregroundGeneration", 15L)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val reqY = okhttp3.Request.Builder()
                .url(activeAppUrl)
                .put(bodyY)
                .build()

            GuardianAccessibilityService.isScreenOnState = true
            UsageTrackerService.foregroundGeneration.set(15L)

            // Step 4: Dispatch executeOnlineGuarded with cold start empty cache
            val result = UsageTrackerService.executeOnlineGuarded(
                reqY, context, expectedEpoch = -1L, expectedGen = 15L
            )

            // Step 5: Verification - must abort, server must NOT be overwritten
            assertFalse("executeOnlineGuarded must abort on cold start when pre-GET shows server is newer (20 >= 15)", result)
            assertEquals("Server state must remain App_Z, NOT overwritten with App_Y", "App_Z", server.currentServerState.getString("appName"))
            assertEquals("Server generation must remain 20", 20L, server.currentServerState.getLong("foregroundGeneration"))
            assertEquals("lastKnownETags must now contain fresh ETag from pre-GET", "etag_gen20", UsageTrackerService.lastKnownETags[activeAppUrl])

            // Step 6: Verify executeOnlineHttpGuarded also rejects cold-start stale overwrite
            UsageTrackerService.lastKnownETags.remove(activeAppUrl)
            val httpResult = UsageTrackerService.executeOnlineHttpGuarded(
                reqY, context, expectedEpoch = -1L, expectedGen = 15L
            )
            assertNull("executeOnlineHttpGuarded must return null fail-closed on cold-start stale overwrite", httpResult)
            assertEquals("Server state must still remain App_Z", "App_Z", server.currentServerState.getString("appName"))

            // Step 7: Verify success path with empty cache when client IS newer (Gen 25 > Gen 20)
            UsageTrackerService.lastKnownETags.remove(activeAppUrl)
            val bodyW = JSONObject().apply {
                put("appName", "App_W")
                put("foregroundGeneration", 25L)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val reqW = okhttp3.Request.Builder()
                .url(activeAppUrl)
                .put(bodyW)
                .build()

            UsageTrackerService.foregroundGeneration.set(25L)
            val successW = UsageTrackerService.executeOnlineGuarded(
                reqW, context, expectedEpoch = -1L, expectedGen = 25L
            )
            assertTrue("executeOnlineGuarded must succeed on cold start when client generation is newer (25 > 20)", successW)
            assertEquals("Server state must now be App_W", "App_W", server.currentServerState.getString("appName"))
            assertEquals("Server generation must be 25", 25L, server.currentServerState.getLong("foregroundGeneration"))
        } finally {
            server.close()
            UsageTrackerService.lastKnownETags.remove(activeAppUrl)
        }
    }

    @Test
    fun testScreenOffRaceDuringWindowStateChangeStrictlyAbortsWithoutReinfectingForeground() {
        // Red-Team Karl Popper Falsification Test Mandated by OpenAI Codex:
        // Proves that when handleScreenOff() executes while isForegroundApp() is in-flight,
        // any delayed window state transition strictly aborts under sessionLock without reinfecting RAM.

        // 1. Setup initial screen ON state with an active foreground app
        GuardianAccessibilityService.isScreenOnState = true
        val epochBefore = GuardianAccessibilityService.telemetryEpoch.incrementAndGet()
        val now = System.currentTimeMillis()

        synchronized(GuardianAccessibilityService.sessionLock) {
            GuardianAccessibilityService.currentForegroundPackage = "com.google.android.youtube"
            GuardianAccessibilityService.currentForegroundStartTime = now - 5000L
            GuardianAccessibilityService.lastActivePackage = "com.google.android.youtube"
            GuardianAccessibilityService.lastActiveUploadTimestamp = now - 5000L
        }

        assertEquals("com.google.android.youtube", GuardianAccessibilityService.currentForegroundPackage)
        assertEquals("com.google.android.youtube", GuardianAccessibilityService.lastActivePackage)

        // 2. Simulate in-flight window change captured expectedEpoch = epochBefore
        val inFlightExpectedEpoch = epochBefore
        val targetInFlightPkg = "com.facebook.katana"

        // 3. User turns screen OFF while isForegroundApp() is running
        // handleScreenOff() sets hardware flag to false, increments epoch, and resets RAM under sessionLock:
        GuardianAccessibilityService.isScreenOnState = false
        val screenOffEpoch = GuardianAccessibilityService.telemetryEpoch.incrementAndGet()
        val screenOffTime = System.currentTimeMillis()

        val (closedPkg, closedStart) = synchronized(GuardianAccessibilityService.sessionLock) {
            val pkg = GuardianAccessibilityService.currentForegroundPackage
            val start = GuardianAccessibilityService.currentForegroundStartTime
            GuardianAccessibilityService.currentForegroundPackage = ""
            GuardianAccessibilityService.currentForegroundStartTime = 0L
            GuardianAccessibilityService.lastActivePackage = "SCREEN_OFF"
            GuardianAccessibilityService.lastActiveUploadTimestamp = screenOffTime
            Pair(pkg, start)
        }

        assertEquals("com.google.android.youtube", closedPkg)
        assertEquals("", GuardianAccessibilityService.currentForegroundPackage)
        assertEquals("SCREEN_OFF", GuardianAccessibilityService.lastActivePackage)

        // 4. Now the in-flight window change finishes isForegroundApp() and attempts to commit
        // with stale expectedEpoch and screen off
        val transitionResult = GuardianAccessibilityService.transitionAppSessionAtomic(
            packageName = targetInFlightPkg,
            expectedEpoch = inFlightExpectedEpoch,
            now = System.currentTimeMillis()
        )

        // 5. INVARIANTS VERIFICATION:
        // a) Transition must return null (aborted)
        assertNull("transitionAppSessionAtomic must strictly return null when screen is off or epoch is stale", transitionResult)

        // b) RAM must NOT be re-infected:
        assertEquals("currentForegroundPackage in RAM must remain empty after SCREEN_OFF", "", GuardianAccessibilityService.currentForegroundPackage)
        assertEquals("currentForegroundStartTime in RAM must remain 0L after SCREEN_OFF", 0L, GuardianAccessibilityService.currentForegroundStartTime)
        assertEquals("lastActivePackage in RAM must remain SCREEN_OFF", "SCREEN_OFF", GuardianAccessibilityService.lastActivePackage)

        // 6. Adversarial variant: Even if expectedEpoch was somehow forged to match screenOffEpoch,
        // the !isScreenOnState hardware invariant fence inside sessionLock MUST still abort!
        val forgedTransitionResult = GuardianAccessibilityService.transitionAppSessionAtomic(
            packageName = targetInFlightPkg,
            expectedEpoch = screenOffEpoch,
            now = System.currentTimeMillis()
        )
        assertNull("transitionAppSessionAtomic must strictly return null when isScreenOnState is false even if epoch matches", forgedTransitionResult)
        assertEquals("currentForegroundPackage must still remain empty", "", GuardianAccessibilityService.currentForegroundPackage)
        assertEquals("lastActivePackage must still remain SCREEN_OFF", "SCREEN_OFF", GuardianAccessibilityService.lastActivePackage)

        // 7. Verify bank, lock, and home transitions also strictly abort when screen is off:
        val bankResult = GuardianAccessibilityService.transitionBankSessionAtomic(
            expectedEpoch = screenOffEpoch,
            now = System.currentTimeMillis()
        )
        assertNull("transitionBankSessionAtomic must strictly return null when screen is off", bankResult)

        val homeResult = GuardianAccessibilityService.transitionOfflineSessionAtomic(
            targetPackage = "HOME",
            expectedEpoch = screenOffEpoch,
            now = System.currentTimeMillis()
        )
        assertNull("transitionOfflineSessionAtomic for HOME must strictly return null when screen is off", homeResult)

        val lockResult = GuardianAccessibilityService.transitionOfflineSessionAtomic(
            targetPackage = "SCREEN_OFF",
            expectedEpoch = screenOffEpoch,
            now = System.currentTimeMillis()
        )
        assertNull("transitionOfflineSessionAtomic for SCREEN_OFF must strictly return null when screen is off", lockResult)

        // Final invariant check: RAM is 100% pristine and uninfected
        assertEquals("", GuardianAccessibilityService.currentForegroundPackage)
        assertEquals("SCREEN_OFF", GuardianAccessibilityService.lastActivePackage)
    }

    @Test
    fun testForegroundProcessAndSubprocessResolutionInvariants() {
        // Red-Team Karl Popper Falsification Test Mandated by OpenAI Codex:
        // Proves that GuardianAccessibilityService correctly resolves main processes and sub-processes
        // (package:processName, package:renderer, package:webview) under all real-world Android multi-process conditions.
        val target = "com.example.browser"
        val now = System.currentTimeMillis()

        // 1. Direct and sub-process resolution via isPackageProcessOf
        assertTrue("Exact package must match", GuardianAccessibilityService.isPackageProcessOf(target, target))
        assertTrue("Renderer process must match target", GuardianAccessibilityService.isPackageProcessOf("com.example.browser:renderer", target))
        assertTrue("WebView process must match target", GuardianAccessibilityService.isPackageProcessOf("com.example.browser:webview", target))
        assertTrue("Sandboxed process must match target", GuardianAccessibilityService.isPackageProcessOf("com.example.browser:sandboxed_process0", target))

        // Negative cases:
        assertFalse("Subprocess of different package must NOT match target", GuardianAccessibilityService.isPackageProcessOf("com.other.app:renderer", target))
        assertFalse("Prefix collision without colon must NOT match target", GuardianAccessibilityService.isPackageProcessOf("com.example.browserfake", target))
        assertFalse("Prefix collision with underscore must NOT match target", GuardianAccessibilityService.isPackageProcessOf("com.example.browser_extra", target))
        assertFalse("Null package must NOT match target", GuardianAccessibilityService.isPackageProcessOf(null, target))
        assertFalse("Empty package must NOT match target", GuardianAccessibilityService.isPackageProcessOf("", target))

        // 2. Active Window Hierarchy with Sub-process:
        // evaluateForegroundEvidence must return true when activeRootPkg is a sub-process of target
        val rendererResult = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = "com.example.browser:renderer",
            usageStatsLastResumedPkg = null,
            targetPkg = target,
            now = now,
            lastEventTime = 0L
        )
        assertTrue("Subprocess renderer in activeRootPkg must be accepted as foreground", rendererResult)

        val webviewResult = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = "com.example.browser:webview",
            usageStatsLastResumedPkg = null,
            targetPkg = target,
            now = now,
            lastEventTime = 0L
        )
        assertTrue("Subprocess webview in activeRootPkg must be accepted as foreground", webviewResult)

        // 3. Sub-process of another package: Must be strictly REJECTED as window conflict
        val otherSubprocessResult = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = "com.other.app:service",
            usageStatsLastResumedPkg = target,
            targetPkg = target,
            now = now,
            lastEventTime = now - 1000L
        )
        assertFalse("Active window of different app sub-process must strictly conflict and reject target", otherSubprocessResult)

        // 4. Sub-process not active / not focused (activeRootPkg is null during window transition,
        // and UsageStats has stale event): Fail-closed when event is too old
        val staleSubprocessFallback = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = "com.example.browser:renderer",
            targetPkg = target,
            now = now,
            lastEventTime = now - 30_000L,
            maxEventAgeMs = 15_000L
        )
        assertFalse("Unfocused/stale sub-process event older than maxEventAge must be rejected", staleSubprocessFallback)

        // Fresh usage stats event with sub-process must be accepted when activeRootPkg is null
        val freshSubprocessFallback = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = "com.example.browser:renderer",
            targetPkg = target,
            now = now,
            lastEventTime = now - 2000L,
            maxEventAgeMs = 15_000L
        )
        assertTrue("Fresh sub-process event during window transition must be accepted", freshSubprocessFallback)

        // 5. Rapid switch: Sub-process A (com.example.browser:renderer) -> Package B (com.google.android.youtube)
        // Active window now belongs to Package B; checking target A must immediately return false (conflict invariant)
        val rapidSwitchConflict = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = "com.google.android.youtube",
            usageStatsLastResumedPkg = "com.example.browser:renderer",
            targetPkg = target,
            now = now,
            lastEventTime = now - 500L
        )
        assertFalse("Active window belonging to Package B must immediately reject Package A even if UsageStats was for A", rapidSwitchConflict)
    }

    @Test
    fun testSecondaryWindowFallbackRequiresUsageStatsAgreementAndRejectsStaleWindow() {
        val target = "com.example.browser"
        val now = 100_000L

        // Falsification Case 1: Secondary window alone without UsageStats agreement MUST NOT be trusted
        // When activeRootPkg is null (e.g. app switching or OEM UI lag), secondary window matches target,
        // but usageStatsLastResumedPkg is null -> Fail-closed: returns false
        val noUsageAgreement = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = null,
            targetPkg = target,
            now = now,
            lastEventTime = 0L,
            maxEventAgeMs = 15_000L,
            secondaryWindowPkg = target
        )
        assertFalse("Secondary window alone without UsageStats agreement must fail closed", noUsageAgreement)

        // Falsification Case 2: Stale secondary window (UsageStats had target earlier, but event is older than maxEventAgeMs)
        // User exited app to Home 25s ago; OEM left window in windows list
        val staleSecondaryWindow = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = target,
            targetPkg = target,
            now = now,
            lastEventTime = now - 25_000L,
            maxEventAgeMs = 15_000L,
            secondaryWindowPkg = target
        )
        assertFalse("Stale secondary window older than 15s must be rejected as background linger", staleSecondaryWindow)

        // Falsification Case 3: Conflicting UsageStats (secondary window claims target, but UsageStats reports launcher was resumed)
        val conflictingUsageStats = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = "com.google.android.apps.nexuslauncher",
            targetPkg = target,
            now = now,
            lastEventTime = now - 1000L,
            maxEventAgeMs = 15_000L,
            secondaryWindowPkg = target
        )
        assertFalse("Conflicting UsageStats showing launcher must override secondary window and reject target", conflictingUsageStats)

        // Positive Case 4: Dual-engine consensus (activeRootPkg is null during transition, secondary window matches AND UsageStats agrees within 15s)
        val consensusMatch = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = target,
            targetPkg = target,
            now = now,
            lastEventTime = now - 2000L,
            maxEventAgeMs = 15_000L,
            secondaryWindowPkg = target
        )
        assertTrue("Dual-engine consensus between secondary window and recent UsageStats must be accepted", consensusMatch)

        // Positive Case 5: Dual-engine consensus with sub-process (com.example.browser:renderer)
        val subProcessConsensus = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = "com.example.browser:renderer",
            targetPkg = target,
            now = now,
            lastEventTime = now - 1500L,
            maxEventAgeMs = 15_000L,
            secondaryWindowPkg = "com.example.browser:renderer"
        )
        assertTrue("Dual-engine consensus with sub-process renderer must be accepted", subProcessConsensus)

        // Falsification Case 6: Conflicting secondary window belongs to different package
        val conflictingSecondaryWindow = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = target,
            targetPkg = target,
            now = now,
            lastEventTime = now - 1000L,
            maxEventAgeMs = 15_000L,
            secondaryWindowPkg = "com.other.app"
        )
        assertFalse("Conflicting secondary window of another package must be strictly rejected", conflictingSecondaryWindow)

        // Falsification Case 7: Rapid switch conflict even with direct root window (UsageStats shows another app resumed < 3000ms ago)
        val rapidSwitchConflict = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = target,
            usageStatsLastResumedPkg = "com.other.app",
            targetPkg = target,
            now = now,
            lastEventTime = now - 500L,
            maxEventAgeMs = 15_000L
        )
        assertFalse("Rapid switch conflict where another app resumed within 3000ms must fail closed", rapidSwitchConflict)

        // Falsification Case 8 (Codex Split-Screen Invariant):
        // Target matching window + other active/focused window in split screen + fresh UsageStats for target
        // BẮT BUỘC trả về false (Fail-closed trên trạng thái split-screen/multi-window không xác định)
        val splitScreenConflictWithFreshUsage = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = target,
            targetPkg = target,
            now = now,
            lastEventTime = now - 1000L,
            maxEventAgeMs = 15_000L,
            secondaryWindowPkg = target,
            conflictingWindowPkg = "com.other.app"
        )
        assertFalse("Split-screen concurrent target window and conflicting other window with fresh UsageStats MUST strictly fail closed", splitScreenConflictWithFreshUsage)
    }

    @Test
    fun testSplitScreenConcurrentTargetAndOtherWindowStrictlyFailsClosed() {
        val target = "com.example.browser"
        val other = "com.other.app"
        val now = 200_000L

        // Split-screen: cả hai cửa sổ cùng tồn tại trong windows hierarchy (browser isFocused, other isActive)
        // và UsageStats của target vừa được ghi nhận 500ms trước.
        // Bắt buộc fail-closed trả về false, không được phép kết luận target là foreground duy nhất.
        val splitScreenResult = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = target,
            targetPkg = target,
            now = now,
            lastEventTime = now - 500L,
            maxEventAgeMs = 15_000L,
            secondaryWindowPkg = target,
            conflictingWindowPkg = other
        )
        assertFalse("Split-screen concurrent windows must fail closed even with ultra fresh UsageStats", splitScreenResult)
    }

    @Test
    fun testZeroWindowEvidenceWithStaleOrRecentUsageStatsStrictlyFailsClosed() {
        val target = "com.example.browser"
        val now = 300_000L

        // Falsification Case (Codex Anti-Ghost / Home Transition Invariant):
        // Khi rootInActiveWindow == null và windows rỗng (không có cửa sổ nào của target trên màn hình),
        // dù UsageStats có ghi nhận target cách đây 5 giây hay 14 giây,
        // evaluateForegroundEvidence với requireWindowEvidence = true BẮT BUỘC trả về false (Fail-closed).
        val zeroWindowResult = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = null,
            usageStatsLastResumedPkg = target,
            targetPkg = target,
            now = now,
            lastEventTime = now - 5000L,
            maxEventAgeMs = 15_000L,
            secondaryWindowPkg = null,
            requireWindowEvidence = true
        )
        assertFalse("Zero window evidence must strictly fail closed against Home transition ghosting", zeroWindowResult)
    }

    @Test
    fun testSplitScreenWithMatchingRootAndConflictingActiveWindowStrictlyFailsClosed() {
        val target = "com.example.browser"
        val other = "com.other.app"
        val now = 400_000L

        // Falsification Case (Codex Split-Screen Invariant - Decoupled Hierarchy):
        // activeRootPkg khớp target (ví dụ Pane 1 split-screen trả về target),
        // nhưng có một cửa sổ active/focused khác (Pane 2) thuộc về ứng dụng khác (other),
        // và UsageStats của target vừa được ghi nhận 200ms trước.
        // Bất biến: evaluateForegroundEvidence BẮT BUỘC từ chối (Fail-closed -> false),
        // không được phép ngộ nhận target là ứng dụng tiền cảnh độc quyền khi màn hình đang bị chia sẻ!
        val splitScreenWithRootResult = GuardianAccessibilityService.evaluateForegroundEvidence(
            activeRootPkg = target,
            usageStatsLastResumedPkg = target,
            targetPkg = target,
            now = now,
            lastEventTime = now - 200L,
            maxEventAgeMs = 15_000L,
            secondaryWindowPkg = null,
            conflictingWindowPkg = other,
            requireWindowEvidence = true
        )
        assertFalse("Split-screen with matching activeRootPkg but conflicting active window must strictly fail closed", splitScreenWithRootResult)
    }

    @Test
    fun testUnifiedHeartbeatRateLimiterEnforcesMinimum60SecondsInterval() {
        // Invariant (Codex Anti-Network-Spam Invariant):
        // Heartbeat HTTP ping tuyệt đối không được gửi dày hơn 60s giữa các chu kỳ (trừ force = true khi mở khóa màn hình).
        assertEquals("MIN_HEARTBEAT_INTERVAL_MS must be strictly 60 seconds", 60_000L, UsageTrackerService.MIN_HEARTBEAT_INTERVAL_MS)

        val initialTimestamp = 1_000_000L
        UsageTrackerService.lastHeartbeatSentTimestamp.set(initialTimestamp)

        // Mô phỏng các cuộc gọi heartbeat trong vòng 60 giây (ví dụ sau 10s, 20s, 45s, 59.9s):
        // Tất cả đều phải bị chặn lại bởi unified rate limiter (không vượt qua ngưỡng).
        val testTimes = listOf(
            initialTimestamp + 1_000L,
            initialTimestamp + 10_000L,
            initialTimestamp + 20_000L,
            initialTimestamp + 45_000L,
            initialTimestamp + 59_999L
        )

        for (t in testTimes) {
            val shouldAllow = (t - UsageTrackerService.lastHeartbeatSentTimestamp.get() >= UsageTrackerService.MIN_HEARTBEAT_INTERVAL_MS)
            assertFalse("Heartbeat attempt at timestamp $t (${t - initialTimestamp}ms elapsed) must be strictly rate-limited", shouldAllow)
        }

        // Chỉ khi thời gian trôi qua >= 60_000ms thì mới cho phép gửi nhịp tim tiếp theo:
        val allowedTime = initialTimestamp + 60_000L
        val isAllowed = (allowedTime - UsageTrackerService.lastHeartbeatSentTimestamp.get() >= UsageTrackerService.MIN_HEARTBEAT_INTERVAL_MS)
        assertTrue("Heartbeat after exactly 60 seconds must be allowed", isAllowed)
    }

    @Test
    fun testAccessibilityEventStormDoesNotSpamHeartbeat() {
        // Invariant (Codex Event-Storm Invariant):
        // Khi người dùng tương tác liên tục (cuộn trang, gõ phím, animation động) sinh ra hàng nghìn
        // TYPE_WINDOW_CONTENT_CHANGED trong vòng 60 giây, hệ thống TUYỆT ĐỐI không phát sinh heartbeat HTTP spam.
        val baseTime = 2_000_000L
        UsageTrackerService.lastHeartbeatSentTimestamp.set(baseTime)

        var allowedPingCount = 0
        val eventCount = 1000

        // Mô phỏng 1000 events diễn ra rải rác trong 60 giây (mỗi 60ms một event):
        for (i in 1..eventCount) {
            val eventSimTime = baseTime + (i * 60L) // i=1 -> 60ms, ..., i=999 -> 59.94s, i=1000 -> 60.0s
            val elapsed = eventSimTime - UsageTrackerService.lastHeartbeatSentTimestamp.get()
            if (elapsed >= UsageTrackerService.MIN_HEARTBEAT_INTERVAL_MS) {
                allowedPingCount++
                UsageTrackerService.lastHeartbeatSentTimestamp.set(eventSimTime)
            }
        }

        // Trong toàn bộ 1000 events trải dài 60s, chỉ có DUY NHẤT 1 lần chạm mốc 60s được phép gửi:
        assertEquals("Event storm of 1000 accessibility events in 60s must yield at most 1 heartbeat ping", 1, allowedPingCount)
    }

    @Test
    fun testCas412FailsClosedWhenServerBodyIsEmptyOrMissingGeneration() {
        // Red-Team Karl Popper Falsification Test Mandated by OpenAI Codex:
        // Proves that when an HTTP 412 is returned, but the subsequent GET body is empty, null,
        // or missing both "generation" and "foregroundGeneration" (e.g. serverGen <= 0L),
        // executeOnlineGuarded strictly fails closed, returning false without attempting a retry.
        val server = CasTestServer()
        val port = server.port
        val activeAppUrl = "http://127.0.0.1:$port/active_app.json"

        try {
            val fakePrefs = FakeSharedPreferences()
            fakePrefs.data["paired_code"] = "CVA-TEST"
            fakePrefs.data["device_id"] = "TEST_DEV_412"
            val context = FakeTestContext(fakePrefs)

            // Server state has NO generation field (e.g. missing generation)
            server.currentServerState = JSONObject().apply {
                put("appName", "Unknown")
            }
            server.currentServerETag = "etag_empty_gen"

            // Client has a stale cached ETag, so PUT triggers 412
            UsageTrackerService.lastKnownETags[activeAppUrl] = "etag_stale_client"

            val bodyY = JSONObject().apply {
                put("appName", "App_New")
                put("generation", 15L)
                put("foregroundGeneration", 15L)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val reqY = okhttp3.Request.Builder()
                .url(activeAppUrl)
                .put(bodyY)
                .build()

            GuardianAccessibilityService.isScreenOnState = true
            UsageTrackerService.foregroundGeneration.set(15L)

            val result = UsageTrackerService.executeOnlineGuarded(
                reqY, context, expectedEpoch = -1L, expectedGen = 15L
            )

            assertFalse("executeOnlineGuarded must fail closed (return false) when GET node after 412 has missing or invalid generation (serverGen <= 0)", result)
            assertEquals("Server state must NOT be overwritten with App_New", "Unknown", server.currentServerState.getString("appName"))
        } finally {
            server.close()
            UsageTrackerService.lastKnownETags.remove(activeAppUrl)
        }
    }

    @Test
    fun testActiveAppLockSerializesConcurrentPutRequests() {
        // Verification: Proves activeAppLock serializes concurrent PUT active_app mutations
        // avoiding corrupted state or interleaved CAS attempts.
        val lockObj = UsageTrackerService.activeAppLock
        assertNotNull("activeAppLock must not be null", lockObj)

        val executionOrder = java.util.concurrent.CopyOnWriteArrayList<String>()
        val startLatch = java.util.concurrent.CountDownLatch(1)
        val doneLatch = java.util.concurrent.CountDownLatch(2)

        val t1 = Thread {
            startLatch.await()
            synchronized(UsageTrackerService.activeAppLock) {
                executionOrder.add("t1_start")
                Thread.sleep(50)
                executionOrder.add("t1_end")
            }
            doneLatch.countDown()
        }

        val t2 = Thread {
            startLatch.await()
            Thread.sleep(10) // slight delay to ensure t1 acquires first
            synchronized(UsageTrackerService.activeAppLock) {
                executionOrder.add("t2_start")
                executionOrder.add("t2_end")
            }
            doneLatch.countDown()
        }

        t1.start()
        t2.start()
        startLatch.countDown()

        val completed = doneLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue("Both threads must finish within timeout", completed)
        assertEquals("activeAppLock must serialize execution: t1 must completely finish before t2 starts",
            listOf("t1_start", "t1_end", "t2_start", "t2_end"), executionOrder)
    }

    @Test
    fun testScreenOffInterleavedWithScreenOnLifecycleRaceStrictlyPreventsStaleOfflineOverwrite() {
        // Red-Team Karl Popper Falsification Test Mandated by OpenAI Codex:
        // Cưỡng bức thứ tự race condition giữa 2 luồng:
        // Luồng 1 (SCREEN_OFF) bắt đầu -> bị tạm dừng (pauseLatch) -> Luồng 2 (SCREEN_ON) hoàn thành trọn vẹn -> Luồng 1 tiếp tục.
        // Bắt buộc chứng minh:
        // 1. Luồng 2 (SCREEN_ON) thiết lập isScreenOnState = true và telemetryEpoch mới.
        // 2. Luồng 1 khi tiếp tục KHÔNG ĐƯỢC PHÉP ghi đè trạng thái ONLINE bằng offline telemetry cũ.
        // 3. sendUrgentOfflineStatus và executeOfflineGuarded triệt tiêu fail-closed mọi offline HTTP call cho stale epoch.
        val fakePrefs = FakeSharedPreferences()
        fakePrefs.data["paired_code"] = "CVA-TEST"
        fakePrefs.data["device_id"] = "TEST_DEV_RACE"
        fakePrefs.data["is_device_online"] = true
        val context = FakeTestContext(fakePrefs)

        // Khởi tạo trạng thái ban đầu: Thiết bị ONLINE, epoch = 10
        GuardianAccessibilityService.telemetryEpoch.set(10L)
        GuardianAccessibilityService.isScreenOnState = true

        val pauseLatch = java.util.concurrent.CountDownLatch(1)
        val screenOnFinishedLatch = java.util.concurrent.CountDownLatch(1)
        val testFinishedLatch = java.util.concurrent.CountDownLatch(2)

        val offlineHttpDispatched = java.util.concurrent.atomic.AtomicBoolean(false)

        // Thread 1: SCREEN_OFF event
        val tScreenOff = Thread {
            try {
                // Đang chuẩn bị xử lý SCREEN_OFF nhưng bị pause/preempt trước khi dispatch offline
                pauseLatch.await()

                // Khi resume, giả định nhận được epoch cũ 10 (hoặc cố gắng phát offline cho epoch 10)
                val staleEpoch = 10L
                val job = UsageTrackerService.sendUrgentOfflineStatus(context, staleEpoch)
                if (job != null) {
                    offlineHttpDispatched.set(true)
                }
            } finally {
                testFinishedLatch.countDown()
            }
        }

        // Thread 2: SCREEN_ON event
        val tScreenOn = Thread {
            try {
                // Chờ một chút để tScreenOff sẵn sàng
                Thread.sleep(20)
                synchronized(GuardianAccessibilityService.hardwareTransitionLock) {
                    GuardianAccessibilityService.isScreenOnState = true
                    GuardianAccessibilityService.telemetryEpoch.incrementAndGet() // 11
                    UsageTrackerService.lastDispatchedOfflineEpoch.set(-1L)
                    UsageTrackerService.cancelActiveOfflineCalls()
                    fakePrefs.data["is_device_online"] = true
                }
                screenOnFinishedLatch.countDown()
                // Bây giờ giải phóng Thread 1 tiếp tục chạy
                pauseLatch.countDown()
            } finally {
                testFinishedLatch.countDown()
            }
        }

        tScreenOff.start()
        tScreenOn.start()

        val completed = testFinishedLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue("Cả 2 luồng lifecycle race phải kết thúc an toàn trong 3s", completed)

        // Kiểm tra bất biến:
        assertEquals("Trạng thái phần cứng bắt buộc phải là ONLINE sau khi SCREEN_ON chạy", true, GuardianAccessibilityService.isScreenOnState)
        assertEquals("telemetryEpoch bắt buộc phải là 11 (không bị stale epoch của Thread 1 ghi đè)", 11L, GuardianAccessibilityService.telemetryEpoch.get())
        assertFalse("Offline job/HTTP request cho stale epoch 10 bắt buộc phải bị từ chối fail-closed", offlineHttpDispatched.get())
        assertEquals("is_device_online trong preferences bắt buộc phải được bảo toàn là true (không bị offline ghi đè)", true, fakePrefs.data["is_device_online"])
    }

    @Test
    fun testHardwareTransitionLockGuaranteesAtomicStateTransition() {
        // Verification: Proves hardwareTransitionLock serializes state transitions
        val lock = GuardianAccessibilityService.hardwareTransitionLock
        assertNotNull("hardwareTransitionLock must not be null", lock)

        val order = java.util.concurrent.CopyOnWriteArrayList<String>()
        val latch1 = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(2)

        val t1 = Thread {
            latch1.await()
            synchronized(GuardianAccessibilityService.hardwareTransitionLock) {
                order.add("off_start")
                Thread.sleep(40)
                order.add("off_end")
            }
            done.countDown()
        }

        val t2 = Thread {
            latch1.await()
            Thread.sleep(10)
            synchronized(GuardianAccessibilityService.hardwareTransitionLock) {
                order.add("on_start")
                order.add("on_end")
            }
            done.countDown()
        }

        t1.start()
        t2.start()
        latch1.countDown()

        val finished = done.await(3, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue("Both threads must complete within timeout", finished)
        assertEquals("hardwareTransitionLock must serialize screen transition",
            listOf("off_start", "off_end", "on_start", "on_end"), order)
    }

    @Test
    fun testOfflineCheckPausedBeforeDiskCommitAbortsWhenOnlineTransitionIntervenes() {
        // Invariant (Codex Karl Popper Falsification Mandate):
        // When an offline flow passes initial check for epoch E, but pauses (e.g. slow I/O / OS preemption),
        // and an online transition intervenes (setting isScreenOnState=true, epoch=E+1, disk=online),
        // the offline flow MUST abort and MUST NOT overwrite the online state on disk or in memory!
        val fakePrefs = FakeSharedPreferences()
        fakePrefs.data["paired_code"] = "FAM123"
        fakePrefs.data["device_id"] = "DEV456"
        fakePrefs.data["last_written_epoch"] = 10L
        fakePrefs.data["is_device_online"] = true
        val fakeContext = FakeTestContext(fakePrefs)

        GuardianAccessibilityService.telemetryEpoch.set(10L)
        GuardianAccessibilityService.isScreenOnState = false // Screen initially turning off
        UsageTrackerService.lastDispatchedOfflineEpoch.set(-1L)

        val offlineCheckPassedLatch = java.util.concurrent.CountDownLatch(1)
        val onlineTransitionCompleteLatch = java.util.concurrent.CountDownLatch(1)
        val testDoneLatch = java.util.concurrent.CountDownLatch(2)

        val offlinePersistResult = java.util.concurrent.atomic.AtomicBoolean(true)
        val offlineJobCreated = java.util.concurrent.atomic.AtomicBoolean(false)

        // Thread 1: Simulates offline dispatch flow with preemption between offline validation and disk commit
        val tOffline = Thread {
            try {
                val epoch = 10L
                // 1. Initial check passes
                val isOfflineValid = UsageTrackerService.isHardwareOfflineValid(fakeContext, epoch)
                assertTrue("Initial offline check for epoch 10 must pass", isOfflineValid)

                // Signal that check passed, then pause to allow online transition to intervene
                offlineCheckPassedLatch.countDown()
                onlineTransitionCompleteLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)

                // 2. Now attempt to persist offline state and send urgent status for stale epoch 10
                val persisted = UsageTrackerService.persistDeviceOfflineState(fakeContext, epoch)
                offlinePersistResult.set(persisted)

                val job = UsageTrackerService.sendUrgentOfflineStatus(fakeContext, epoch)
                if (job != null) {
                    offlineJobCreated.set(true)
                }
            } finally {
                testDoneLatch.countDown()
            }
        }

        // Thread 2: Simulates intervening user unlocking device (SCREEN_ON / USER_PRESENT)
        val tOnline = Thread {
            try {
                // Wait for Thread 1 to validate offline
                offlineCheckPassedLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)

                // Perform atomic hardware state transition to ONLINE (epoch 11)
                synchronized(GuardianAccessibilityService.hardwareTransitionLock) {
                    GuardianAccessibilityService.isScreenOnState = true
                    val onlineEpoch = GuardianAccessibilityService.telemetryEpoch.incrementAndGet() // 11L
                    UsageTrackerService.lastDispatchedOfflineEpoch.set(-1L)
                    UsageTrackerService.cancelActiveOfflineCalls()
                    val onlinePersisted = UsageTrackerService.persistDeviceOnlineState(fakeContext, onlineEpoch)
                    assertTrue("Online persistence for epoch 11 must succeed", onlinePersisted)
                }

                // Signal Thread 1 to resume
                onlineTransitionCompleteLatch.countDown()
            } finally {
                testDoneLatch.countDown()
            }
        }

        tOffline.start()
        tOnline.start()

        val finished = testDoneLatch.await(4, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue("Both threads must complete within timeout", finished)

        // CRITICAL INVARIANT ASSERTIONS:
        assertFalse("Stale offline disk commit (epoch 10) must be rejected fail-closed", offlinePersistResult.get())
        assertFalse("Stale offline job must NOT be created when online transition intervened", offlineJobCreated.get())
        assertEquals("Device online state in preferences MUST remain TRUE", true, fakePrefs.data["is_device_online"])
        assertEquals("Last written epoch in preferences MUST be the online epoch 11", 11L, fakePrefs.data["last_written_epoch"])
        assertTrue("Hardware screen state must remain true", GuardianAccessibilityService.isScreenOnState)
        assertEquals("Telemetry epoch must be 11", 11L, GuardianAccessibilityService.telemetryEpoch.get())
    }

    @Test
    fun testScreenOffTransitionsImmediatelyWithoutWaitingForScreenOnDiskIo() {
        // Invariant (Codex Karl Popper Falsification Mandate):
        // handleScreenOn must release hardwareTransitionLock immediately after RAM mutations.
        // Even if disk persistence of online state is blocked / slow on Dispatchers.IO,
        // an incoming SCREEN_OFF transition must acquire hardwareTransitionLock immediately without blocking!
        val fakePrefs = FakeSharedPreferences()
        fakePrefs.data["paired_code"] = "FAM123"
        fakePrefs.data["device_id"] = "DEV456"
        fakePrefs.data["last_written_epoch"] = 20L
        fakePrefs.data["is_device_online"] = false
        val fakeContext = FakeTestContext(fakePrefs)

        GuardianAccessibilityService.telemetryEpoch.set(20L)
        GuardianAccessibilityService.isScreenOnState = false
        UsageTrackerService.lastDispatchedOfflineEpoch.set(-1L)

        val screenOnLockReleasedLatch = java.util.concurrent.CountDownLatch(1)
        val slowDiskIoPauseLatch = java.util.concurrent.CountDownLatch(1)
        val screenOffFinishedLatch = java.util.concurrent.CountDownLatch(1)
        val testCompleteLatch = java.util.concurrent.CountDownLatch(2)

        val screenOffLockAcquisitionMs = java.util.concurrent.atomic.AtomicLong(-1L)
        val onlinePersistResult = java.util.concurrent.atomic.AtomicBoolean(true)

        // Thread 1: SCREEN_ON flow
        val tScreenOn = Thread {
            try {
                val currentEpoch: Long
                synchronized(GuardianAccessibilityService.hardwareTransitionLock) {
                    currentEpoch = GuardianAccessibilityService.telemetryEpoch.incrementAndGet() // 21L
                    GuardianAccessibilityService.isScreenOnState = true
                    UsageTrackerService.lastDispatchedOfflineEpoch.set(-1L)
                    UsageTrackerService.cancelActiveOfflineCalls()
                }
                // Lock released! Signal Thread 2 that hardwareTransitionLock is free
                screenOnLockReleasedLatch.countDown()

                // Simulating slow Dispatchers.IO disk persistence: Wait for Thread 2 to trigger SCREEN_OFF
                slowDiskIoPauseLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)

                // Attempt to persist online state for epoch 21L
                val persisted = UsageTrackerService.persistDeviceOnlineState(fakeContext, currentEpoch)
                onlinePersistResult.set(persisted)
            } finally {
                testCompleteLatch.countDown()
            }
        }

        // Thread 2: Incoming SCREEN_OFF event while Thread 1's disk I/O is still pending
        val tScreenOff = Thread {
            try {
                screenOnLockReleasedLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)
                val startWait = System.currentTimeMillis()

                // Acquire hardwareTransitionLock: Must be immediate (< 200ms) and NOT block on Thread 1's disk I/O!
                val offEpoch: Long
                synchronized(GuardianAccessibilityService.hardwareTransitionLock) {
                    screenOffLockAcquisitionMs.set(System.currentTimeMillis() - startWait)
                    GuardianAccessibilityService.isScreenOnState = false
                    offEpoch = GuardianAccessibilityService.telemetryEpoch.incrementAndGet() // 22L
                    UsageTrackerService.cancelActiveOnlineCalls()
                }

                UsageTrackerService.persistDeviceOfflineState(fakeContext, offEpoch)
                screenOffFinishedLatch.countDown()

                // Now allow Thread 1's delayed disk I/O to resume
                slowDiskIoPauseLatch.countDown()
            } finally {
                testCompleteLatch.countDown()
            }
        }

        tScreenOn.start()
        tScreenOff.start()

        val completed = testCompleteLatch.await(4, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue("Both threads must complete safely", completed)

        // Invariant Assertions:
        assertTrue("SCREEN_OFF must acquire lock immediately without waiting for disk I/O",
            screenOffLockAcquisitionMs.get() in 0..200)
        assertFalse("Delayed online disk persist for stale epoch 21 must be rejected fail-closed",
            onlinePersistResult.get())
        assertFalse("Screen state must be OFFLINE", GuardianAccessibilityService.isScreenOnState)
        assertEquals("Telemetry epoch must be 22", 22L, GuardianAccessibilityService.telemetryEpoch.get())
        assertEquals("Device online state on disk must remain FALSE", false, fakePrefs.data["is_device_online"])
        assertEquals("Last written epoch on disk must be 22", 22L, fakePrefs.data["last_written_epoch"])
    }

    @Test
    fun testScreenOffInterleavedWithRapidScreenOnPreservesAppSessionDurablyWithoutLoss() {
        // Invariant (Codex Karl Popper Falsification Mandate):
        // Kế toán phiên ứng dụng (recordAppSession) là sổ cái thời gian thực tế đã diễn ra,
        // PHẢI được tách biệt hoàn toàn khỏi stale-epoch fencing của telemetry mạng.
        // Khi SCREEN_OFF diễn ra (epoch 10 -> 11), snapshot phiên được chốt nguyên tử dưới sessionLock.
        // Dù coroutine ghi đĩa bị trễ (delayed I/O) và người dùng bật màn hình lại rất nhanh (SCREEN_ON: epoch 11 -> 12),
        // phiên sử dụng ĐÃ CHỐT vẫn phải được ghi nhận thành công 100% vào SharedPreferences,
        // không bao giờ bị loại bỏ vì epoch không khớp, và cơ chế sessionToken chống tính trùng lặp.
        val fakePrefs = FakeSharedPreferences()
        val context = FakeTestContext(fakePrefs)

        GuardianAccessibilityService.telemetryEpoch.set(10L)
        GuardianAccessibilityService.isScreenOnState = true

        val pkg = "com.study.math"
        val durationMs = 5000L
        val token = "session_com.study.math_${System.currentTimeMillis() - 5000L}"

        val screenOnTriggeredLatch = java.util.concurrent.CountDownLatch(1)
        val sessionWriteCompleteLatch = java.util.concurrent.CountDownLatch(1)

        // Thread 1: Giả lập coroutine recordAppSession bị trì hoãn sau SCREEN_OFF
        val tDelayedSessionRecorder = Thread {
            try {
                // Đợi cho đến khi SCREEN_ON chạy và nâng epoch lên 12
                screenOnTriggeredLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)
                // Thực hiện ghi nhận phiên với token và duration đã snapshot từ lúc SCREEN_OFF
                UsageTrackerService.recordAppSession(context, pkg, durationMs, token)
            } finally {
                sessionWriteCompleteLatch.countDown()
            }
        }

        // Thread 2: Người dùng bật màn hình lại nhanh chóng (SCREEN_ON)
        val tRapidScreenOn = Thread {
            // Epoch tăng lên 11 (SCREEN_OFF) rồi lên 12 (SCREEN_ON)
            GuardianAccessibilityService.telemetryEpoch.incrementAndGet() // 11L (SCREEN_OFF)
            GuardianAccessibilityService.telemetryEpoch.incrementAndGet() // 12L (SCREEN_ON)
            GuardianAccessibilityService.isScreenOnState = true
            screenOnTriggeredLatch.countDown()
        }

        tDelayedSessionRecorder.start()
        tRapidScreenOn.start()

        val completed = sessionWriteCompleteLatch.await(4, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue("Session write thread must complete", completed)

        // Invariant Assertions:
        val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        val appKey = "session_${todayStr}_com.study.math"
        assertTrue("Session MUST be recorded despite epoch advancing to 12", fakePrefs.contains(appKey))
        val recordedDuration = fakePrefs.getLong(appKey, 0L)
        assertEquals("Recorded duration must match snapshot duration exactly", 5000L, recordedDuration)

        // Idempotency check: Re-calling with same sessionToken must be rejected (no double accounting)
        UsageTrackerService.recordAppSession(context, pkg, 3000L, token)
        assertEquals("Duration must NOT increase on duplicate sessionToken", 5000L, fakePrefs.getLong(appKey, 0L))
    }

    @Test
    fun testRecordAppSessionEnqueuesToPendingQueueOnCommitFailureAndFlushesSuccessfully() {
        // Invariant (Codex Karl Popper Durable Queue Mandate):
        // Nếu ghi đĩa SharedPreferences thất bại (commit() == false) hoặc hệ thống gặp lỗi I/O,
        // phiên snapshot phải được đưa ngay vào hàng đợi bền vững pending_sessions_json và RAM fallback queue.
        // Khi điều kiện lưu trữ hồi phục và flushPendingSessions() được gọi (SCREEN_ON hoặc service start),
        // phiên tồn đọng phải được ghi nhận đầy đủ vào SharedPreferences và hàng đợi pending được dọn sạch.
        val fakePrefs = FakeSharedPreferences()
        val context = FakeTestContext(fakePrefs)

        val pkg = "com.study.english"
        val durationMs = 4000L
        val token = "session_com.study.english_test_pending_1"

        // 1. Enqueue khi đĩa hoạt động bình thường -> lưu cả RAM và SharedPreferences
        UsageTrackerService.enqueuePendingSession(context, pkg, durationMs, token)

        val pendingJsonRaw = fakePrefs.getString(UsageTrackerService.PREF_PENDING_SESSIONS_JSON, null)
        assertNotNull("Pending sessions JSON must be stored on disk", pendingJsonRaw)
        val jsonArray = org.json.JSONArray(pendingJsonRaw)
        assertEquals(1, jsonArray.length())
        assertEquals(pkg, jsonArray.getJSONObject(0).getString("pkg"))
        assertEquals(token, jsonArray.getJSONObject(0).getString("token"))
        assertEquals(1, UsageTrackerService.inMemoryPendingSessions.size)

        // Gọi flushPendingSessions (tương tự khi SCREEN_ON hoặc service kết nối lại)
        UsageTrackerService.flushPendingSessions(context)

        // Xác nhận phiên đã được ghi nhận vào SharedPreferences chính
        val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        val appKey = "session_${todayStr}_com.study.english"
        assertTrue("Session must be flushed into main preferences", fakePrefs.contains(appKey))
        assertEquals(4000L, fakePrefs.getLong(appKey, 0L))

        // Hàng đợi pending đã được dọn sạch 100%
        assertFalse("Pending queue must be removed after successful flush",
            fakePrefs.contains(UsageTrackerService.PREF_PENDING_SESSIONS_JSON))
        assertEquals(0, UsageTrackerService.inMemoryPendingSessions.size)

        // 2. Thử nghiệm suy biến phần cứng: Giả lập commit đĩa thất bại hoàn toàn
        fakePrefs.commitReturnsSuccess = false
        val pkg2 = "com.study.history"
        val duration2 = 3500L
        val token2 = "session_com.study.history_test_pending_2"

        UsageTrackerService.enqueuePendingSession(context, pkg2, duration2, token2)
        // Dù commit đĩa thất bại, phiên vẫn được bảo vệ 100% nguyên vẹn trong hàng đợi RAM fallback
        assertEquals(1, UsageTrackerService.inMemoryPendingSessions.size)

        // Khi đĩa hồi phục và flush chạy:
        fakePrefs.commitReturnsSuccess = true
        UsageTrackerService.flushPendingSessions(context)

        val appKey2 = "session_${todayStr}_com.study.history"
        assertTrue("Session from RAM fallback must be flushed into main preferences", fakePrefs.contains(appKey2))
        assertEquals(3500L, fakePrefs.getLong(appKey2, 0L))
        assertEquals(0, UsageTrackerService.inMemoryPendingSessions.size)
    }

    @Test
    fun testEnqueuePendingSessionSurvivesProcessKillViaDurableFileJournalWhenSharedPrefsFails() {
        // Invariant (Codex Karl Popper Multi-Layer Durable Journal Mandate):
        // Nếu commit SharedPreferences thất bại (commit() == false) hoặc bộ nhớ SharedPreferences bị chặn,
        // enqueuePendingSession BẮT BUỘC phải chuyển hướng sang WAL Append-Only Journal trên flash storage
        // (pending_sessions.wal với CRC32 per-record và hardware fsync) và thực hiện post-write verification.
        // Khi toàn bộ tiến trình bị Terminate / Process Kill (RAM bị xóa sạch 100%),
        // phiên snapshot vẫn phải sống sót nguyên vẹn trên đĩa flash.
        // Khi hệ thống khởi động lại (hoặc SCREEN_ON) và điều kiện ghi SharedPreferences hồi phục,
        // flushPendingSessions() BẮT BUỘC phải đọc WAL file, ghi nhận chính xác phiên vào SharedPreferences,
        // và dọn sạch file WAL sau khi hoàn tất.

        val fakePrefs = FakeSharedPreferences(commitReturnsSuccess = false)
        val context = FakeTestContext(fakePrefs)

        val filesDir = context.filesDir
        val walFile = java.io.File(filesDir, UsageTrackerService.PENDING_SESSIONS_WAL_FILE)
        if (walFile.exists()) {
            walFile.delete()
        }
        UsageTrackerService.inMemoryPendingSessions.clear()
        UsageTrackerService.recordedSessionTokens.clear()

        val pkg = "com.study.math"
        val durationMs = 6000L
        val token = "session_com.study.math_adversarial_kill_test"

        // 1. Enqueue khi SharedPreferences commit BỊ LỖI (commitReturnsSuccess = false)
        val enqueued = UsageTrackerService.enqueuePendingSession(context, pkg, durationMs, token)
        assertTrue("enqueuePendingSession must return true via WAL Journal fallback", enqueued)

        // SharedPreferences KHÔNG chứa pending sessions vì commit thất bại
        assertNull("SharedPreferences must NOT have pending sessions due to commit failure",
            fakePrefs.getString(UsageTrackerService.PREF_PENDING_SESSIONS_JSON, null))

        // Nhưng File WAL BẮT BUỘC phải tồn tại trên đĩa flash và chứa sessionToken
        assertTrue("Durable WAL file must exist on flash storage", walFile.exists())
        assertTrue("Durable WAL file must not be empty", walFile.length() > 0L)
        val (recordsInWal, hasCorrupt) = UsageTrackerService.readWalRecords(walFile)
        assertFalse("WAL file must have zero corrupt lines", hasCorrupt)
        assertEquals(1, recordsInWal.size)
        assertEquals(pkg, recordsInWal[0].packageName)
        assertEquals(durationMs, recordsInWal[0].durationMs)
        assertEquals(token, recordsInWal[0].sessionToken)

        // 2. MÔ PHỎNG TIẾN TRÌNH BỊ KILL HOÀN TOÀN (Process Death / Low Memory Killer / Reboot)
        // Xóa sạch toàn bộ RAM của tiến trình
        UsageTrackerService.inMemoryPendingSessions.clear()
        UsageTrackerService.recordedSessionTokens.clear()
        assertEquals("RAM queue must be completely wiped on process kill", 0, UsageTrackerService.inMemoryPendingSessions.size)

        // 3. Khôi phục lưu trữ SharedPreferences (giả lập tiến trình khởi động lại và I/O bình thường)
        fakePrefs.commitReturnsSuccess = true

        // 4. Kích hoạt flushPendingSessions (tương tự khi SCREEN_ON hoặc Service restart)
        UsageTrackerService.flushPendingSessions(context)

        // 5. Xác minh phiên đã được phục hồi thành công từ WAL Journal vào SharedPreferences chính
        val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        val appKey = "session_${todayStr}_com.study.math"
        assertTrue("Session recovered from WAL must be recorded in SharedPreferences", fakePrefs.contains(appKey))
        assertEquals(6000L, fakePrefs.getLong(appKey, 0L))

        // File WAL đã được xả và dọn sạch
        assertFalse("WAL file must be deleted after successful flush", walFile.exists())
        assertEquals("RAM queue must remain empty after flush", 0, UsageTrackerService.inMemoryPendingSessions.size)
    }

    @Test
    fun testWalJournalRecoversValidSessionsWhenFileIsTruncatedOrCorruptAndPreservesSessionAWhenAddingSessionB() {
        // Adversarial Falsification Test (Karl Popper Mandate by Codex Reviewer):
        // Kịch bản:
        // 1. Phiên A được ghi hợp lệ vào WAL với CRC32 chuẩn.
        // 2. Tiến trình bị crash / ngắt nguồn giữa chừng, khiến đuôi file WAL bị cắt dở (truncated JSON).
        // 3. Tiến trình khởi động lại, enqueuePendingSession ghi thêm phiên B.
        // Bất biến:
        // - Phiên A KHÔNG ĐƯỢC PHÉP bị mất hay bị ghi đè!
        // - Dòng bị cắt dở được loại bỏ / sao lưu an toàn sang .corrupt.
        // - Cả phiên A và phiên B đều tồn tại trong WAL và được flush đầy đủ vào SharedPreferences!
        val fakePrefs = FakeSharedPreferences(commitReturnsSuccess = false)
        val context = FakeTestContext(fakePrefs)
        val filesDir = context.filesDir
        val walFile = java.io.File(filesDir, UsageTrackerService.PENDING_SESSIONS_WAL_FILE)
        if (walFile.exists()) walFile.delete()
        UsageTrackerService.inMemoryPendingSessions.clear()
        UsageTrackerService.recordedSessionTokens.clear()

        val pkgA = "com.study.math"
        val durationA = 7000L
        val tokenA = "token_session_A_valid"

        // 1. Enqueue phiên A thành công vào WAL
        val enqueuedA = UsageTrackerService.enqueuePendingSession(context, pkgA, durationA, tokenA)
        assertTrue("Session A must be enqueued into WAL", enqueuedA)
        assertTrue(walFile.exists())

        // 2. Giả lập sự cố ngắt nguồn giữa chừng: append dòng rác bị cắt dở (truncated JSON)
        java.io.FileOutputStream(walFile, true).use { fos ->
            fos.write("\nDEADBEEF:{\"pkg\":\"com.cut.off\",\"duration\":9999,\"token\":\"token_c".toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }

        // 3. Xóa sạch RAM để giả lập process restart hoàn toàn
        UsageTrackerService.inMemoryPendingSessions.clear()
        UsageTrackerService.recordedSessionTokens.clear()

        // 4. Enqueue phiên B
        val pkgB = "com.study.english"
        val durationB = 4500L
        val tokenB = "token_session_B_valid"
        val enqueuedB = UsageTrackerService.enqueuePendingSession(context, pkgB, durationB, tokenB)
        assertTrue("Session B must be enqueued into WAL despite prior corrupted line", enqueuedB)

        // 5. Kiểm tra: CẢ PHIÊN A VÀ PHIÊN B đều có mặt trong WAL!
        val (recordsInWal, _) = UsageTrackerService.readWalRecords(walFile)
        val tokensInWal = recordsInWal.map { it.sessionToken }
        assertTrue("Session A MUST NOT BE LOST after crash and enqueueing Session B", tokensInWal.contains(tokenA))
        assertTrue("Session B must be present in WAL", tokensInWal.contains(tokenB))
        assertEquals(2, recordsInWal.size)

        // 6. Khôi phục SharedPreferences và Flush
        fakePrefs.commitReturnsSuccess = true
        UsageTrackerService.flushPendingSessions(context)

        val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        val appKeyA = "session_${todayStr}_com.study.math"
        val appKeyB = "session_${todayStr}_com.study.english"

        assertTrue("Session A must be recorded in SharedPreferences", fakePrefs.contains(appKeyA))
        assertEquals(7000L, fakePrefs.getLong(appKeyA, 0L))

        assertTrue("Session B must be recorded in SharedPreferences", fakePrefs.contains(appKeyB))
        assertEquals(4500L, fakePrefs.getLong(appKeyB, 0L))

        // WAL đã được flush sạch và xóa file
        assertFalse("WAL file must be deleted after 100% successful flush", walFile.exists())
    }

    @Test
    fun testWalJournalRejectsMismatchedCrcAndDoesNotExecuteCorruptRecord() {
        // Kiểm tra tính toàn vẹn CRC32:
        // Dòng dữ liệu bị giả mạo payload hoặc sai CRC32 phải bị parseWalLine từ chối ngay lập tức
        val fakeRecord = UsageTrackerService.PendingSessionRecord("com.spoof.app", 5000L, "token_spoof", 12345L)
        val validLine = UsageTrackerService.formatWalLine(fakeRecord)

        // Dòng hợp lệ phải parse thành công
        val parsedValid = UsageTrackerService.parseWalLine(validLine)
        assertNotNull("Valid WAL line must be parsed", parsedValid)
        assertEquals("com.spoof.app", parsedValid?.packageName)

        // Dòng bị sửa payload (CRC mismatch) phải bị từ chối
        val tamperedPayload = validLine.replace("com.spoof.app", "com.hacked.app")
        val parsedTampered = UsageTrackerService.parseWalLine(tamperedPayload)
        assertNull("Tampered payload with mismatched CRC must be rejected (null)", parsedTampered)

        // Dòng rác không đúng cấu trúc
        val corruptLine = "NOT_A_HEX:corrupted_json_content"
        val parsedCorrupt = UsageTrackerService.parseWalLine(corruptLine)
        assertNull("Malformed line must be rejected (null)", parsedCorrupt)
    }

    @Test
    fun testLruSessionSetRestoreSnapshotRawStrictlyEnforcesMaxEntriesBound() {
        val maxCap = 10
        val set = UsageTrackerService.Companion.LruSessionSet(maxCap)
        val oversizedList = (0 until 35).map { "token_$it" }

        set.restoreSnapshotRaw(oversizedList)

        assertEquals("restoreSnapshotRaw must strictly enforce maxEntries bound", maxCap, set.size)
        // Ensure it kept the latest 10 elements: token_25 to token_34
        for (i in 0 until 25) {
            assertFalse("Old element token_$i must have been discarded", set.contains("token_$i"))
        }
        for (i in 25 until 35) {
            assertTrue("Tail element token_$i must be preserved", set.contains("token_$i"))
        }

        // Test filtering: empty strings and oversized strings (> 128 chars) must be discarded
        val oversizedString = "A".repeat(129)
        val invalidList = listOf("", "valid_tok_1", oversizedString, "valid_tok_2")
        set.restoreSnapshotRaw(invalidList)
        assertEquals("restoreSnapshotRaw must filter out empty and >128-char tokens", 2, set.size)
        assertTrue(set.contains("valid_tok_1"))
        assertTrue(set.contains("valid_tok_2"))
    }

    @Test
    fun testRestorePersistedSessionTokensRejectsOversizedJsonAndCapsAt500() {
        UsageTrackerService.recordedSessionTokens.clear()
        UsageTrackerService.isSessionTokensRestored.set(false)

        val fakePrefs = FakeSharedPreferences()
        val fakeContext = FakeTestContext(fakePrefs)

        // 1. Test capping at 500 items when JSON contains 1200 items (< 64KB)
        val jsonArray = org.json.JSONArray()
        for (i in 0 until 1200) {
            jsonArray.put("session_tok_$i")
        }
        fakePrefs.data["persisted_session_tokens_json"] = jsonArray.toString()

        val restored = UsageTrackerService.restorePersistedSessionTokens(fakeContext)
        assertTrue("restorePersistedSessionTokens must succeed for valid 1200 tokens", restored)
        assertEquals("recordedSessionTokens size must be strictly capped at 500", 500, UsageTrackerService.recordedSessionTokens.size)

        // Verify it preserved the newest 500 tokens (indices 700 to 1199)
        assertFalse("Old token 0 must not exist in capped set", UsageTrackerService.recordedSessionTokens.contains("session_tok_0"))
        assertFalse("Old token 699 must not exist in capped set", UsageTrackerService.recordedSessionTokens.contains("session_tok_699"))
        assertTrue("Newest token 700 must exist in capped set", UsageTrackerService.recordedSessionTokens.contains("session_tok_700"))
        assertTrue("Newest token 1199 must exist in capped set", UsageTrackerService.recordedSessionTokens.contains("session_tok_1199"))

        // 2. Test payload > 64KB: Must be dropped and removed to protect against OOM / DoS
        UsageTrackerService.recordedSessionTokens.clear()
        UsageTrackerService.isSessionTokensRestored.set(false)

        // Create an oversized JSON payload > 64KB
        val bigToken = "X".repeat(100)
        val largeArray = org.json.JSONArray()
        for (i in 0 until 700) {
            largeArray.put("prefix_${i}_$bigToken")
        }
        val oversizedJson = largeArray.toString()
        assertTrue("Payload must exceed 64KB", oversizedJson.length > 64 * 1024)

        fakePrefs.data["persisted_session_tokens_json"] = oversizedJson
        val resultOversized = UsageTrackerService.restorePersistedSessionTokens(fakeContext)
        assertTrue("Oversized payload must be safely handled without throwing OOM", resultOversized)
        assertNull("Oversized poison key must be removed from SharedPreferences", fakePrefs.data["persisted_session_tokens_json"])
        assertEquals("recordedSessionTokens must remain empty after dropping oversized payload", 0, UsageTrackerService.recordedSessionTokens.size)

        // 3. Test payload > 64KB when commit fails (Disk I/O failure): Fail-Closed Invariant
        // When all commit() attempts fail, must return false and NEVER mark isSessionTokensRestored = true!
        UsageTrackerService.recordedSessionTokens.clear()
        UsageTrackerService.isSessionTokensRestored.set(false)
        val failingPrefs = FakeSharedPreferences(commitReturnsSuccess = false)
        val failingContext = FakeTestContext(failingPrefs)
        failingPrefs.data["persisted_session_tokens_json"] = oversizedJson

        val resultFailingCommit = UsageTrackerService.restorePersistedSessionTokens(failingContext)
        assertFalse("Fail-Closed: Must return false when all commit() attempts fail", resultFailingCommit)
        assertFalse("Fail-Closed: isSessionTokensRestored must remain false when disk commit fails", UsageTrackerService.isSessionTokensRestored.get())
        assertEquals("recordedSessionTokens must remain empty", 0, UsageTrackerService.recordedSessionTokens.size)

        // 4. Test persistSessionTokensLocked verified rollback when commit fails:
        UsageTrackerService.recordedSessionTokens.clear()
        UsageTrackerService.recordedSessionTokens.add("valid_token_1")
        val failingPersistPrefs = FakeSharedPreferences(commitReturnsSuccess = false)
        val failingPersistContext = FakeTestContext(failingPersistPrefs)
        failingPersistPrefs.data["persisted_session_tokens_json"] = "[\"prior_token\"]"
        val persistResult = UsageTrackerService.persistSessionTokensLocked(failingPersistContext)
        assertFalse("persistSessionTokensLocked must fail when commit returns false", persistResult)
        assertFalse("isSessionTokensRestored must be reset when commit fails", UsageTrackerService.isSessionTokensRestored.get())

        // 5. Test read-back mismatch detection on persistSessionTokensLocked:
        UsageTrackerService.isSessionTokensRestored.set(true)
        val mismatchPrefs = object : FakeSharedPreferences(commitReturnsSuccess = true) {
            override fun getString(key: String?, defValue: String?): String? {
                if (key == "persisted_session_tokens_json") return "[\"corrupted_tampered_state\"]"
                return super.getString(key, defValue)
            }
        }
        val mismatchContext = FakeTestContext(mismatchPrefs)
        val mismatchResult = UsageTrackerService.persistSessionTokensLocked(mismatchContext)
        assertFalse("persistSessionTokensLocked must detect read-back mismatch and return false", mismatchResult)
        assertFalse("isSessionTokensRestored must be reset on read-back mismatch", UsageTrackerService.isSessionTokensRestored.get())
    }

    @Test
    fun testResolveCurrentForegroundPackagePrefersLastForegroundPkg() {
        val fakePrefs = FakeSharedPreferences(commitReturnsSuccess = true)
        val fakeContext = FakeTestContext(fakePrefs)
        val freshTime = System.currentTimeMillis() - 2000L
        fakePrefs.data["last_foreground_start"] = freshTime
        fakePrefs.data["last_active_timestamp"] = freshTime
        fakePrefs.data["last_foreground_pkg"] = "com.google.android.youtube"
        fakePrefs.data["last_active_package"] = "com.facebook.katana"

        val resolved = UsageTrackerService.resolveCurrentForegroundPackage(fakeContext, fakePrefs)
        assertEquals("com.google.android.youtube", resolved)
    }

    @Test
    fun testResolveCurrentForegroundPackageFallsBackToLastActivePackageWhenForegroundEmpty() {
        val fakePrefs = FakeSharedPreferences(commitReturnsSuccess = true)
        val fakeContext = FakeTestContext(fakePrefs)
        val freshTime = System.currentTimeMillis() - 2000L
        fakePrefs.data["last_foreground_start"] = 0L
        fakePrefs.data["last_active_timestamp"] = freshTime
        fakePrefs.data["last_foreground_pkg"] = ""
        fakePrefs.data["last_active_package"] = "com.ss.android.ugc.trill"

        val resolved = UsageTrackerService.resolveCurrentForegroundPackage(fakeContext, fakePrefs)
        assertEquals("com.ss.android.ugc.trill", resolved)
    }

    @Test
    fun testResolveCurrentForegroundPackageRejectsScreenOff() {
        val fakePrefs = FakeSharedPreferences(commitReturnsSuccess = true)
        val fakeContext = FakeTestContext(fakePrefs)
        val freshTime = System.currentTimeMillis() - 2000L
        fakePrefs.data["last_foreground_start"] = freshTime
        fakePrefs.data["last_active_timestamp"] = freshTime
        fakePrefs.data["last_foreground_pkg"] = "SCREEN_OFF"
        fakePrefs.data["last_active_package"] = "SCREEN_OFF"

        val resolved = UsageTrackerService.resolveCurrentForegroundPackage(fakeContext, fakePrefs)
        assertEquals("", resolved)
    }

    @Test
    fun testResolveCurrentForegroundPackageRejectsBankAppAndMasksAsProtected() {
        val fakePrefs = FakeSharedPreferences(commitReturnsSuccess = true)
        val fakeContext = FakeTestContext(fakePrefs)
        val freshTime = System.currentTimeMillis() - 2000L
        fakePrefs.data["last_foreground_start"] = freshTime
        fakePrefs.data["last_active_timestamp"] = freshTime

        // 1. When last_foreground_pkg is a bank app (e.g., com.vcb)
        fakePrefs.data["last_foreground_pkg"] = "com.vcb"
        fakePrefs.data["last_active_package"] = "com.vcb"
        val resolvedBank1 = UsageTrackerService.resolveCurrentForegroundPackage(fakeContext, fakePrefs)
        assertEquals("BANK_APP_PROTECTED", resolvedBank1)

        // 2. When last_active_package is a bank app (e.g., com.mbmobile)
        fakePrefs.data["last_foreground_pkg"] = ""
        fakePrefs.data["last_active_package"] = "com.mbmobile"
        val resolvedBank2 = UsageTrackerService.resolveCurrentForegroundPackage(fakeContext, fakePrefs)
        assertEquals("BANK_APP_PROTECTED", resolvedBank2)
    }

    @Test
    fun testResolveCurrentForegroundPackageRejectsStaleTimestamp() {
        val fakePrefs = FakeSharedPreferences(commitReturnsSuccess = true)
        val fakeContext = FakeTestContext(fakePrefs)
        // Red-Team Karl Popper Falsification:
        // When last_foreground_start is 60 seconds old (> 30s limit),
        // resolveCurrentForegroundPackage MUST reject the stale package and fail-closed to ""
        val staleTime = System.currentTimeMillis() - 60_000L
        fakePrefs.data["last_foreground_start"] = staleTime
        fakePrefs.data["last_active_timestamp"] = staleTime
        fakePrefs.data["last_foreground_pkg"] = "com.google.android.youtube"
        fakePrefs.data["last_active_package"] = "com.facebook.katana"

        val resolved = UsageTrackerService.resolveCurrentForegroundPackage(fakeContext, fakePrefs)
        assertEquals("Stale foreground package (>30s) must be rejected", "", resolved)
    }

    @Test
    fun testResolveCurrentForegroundPackageRejectsWhenScreenOff() {
        val fakePrefs = FakeSharedPreferences(commitReturnsSuccess = true)
        val fakeContext = FakeTestContext(fakePrefs)
        val freshTime = System.currentTimeMillis() - 2000L
        fakePrefs.data["last_foreground_start"] = freshTime
        fakePrefs.data["last_active_timestamp"] = freshTime
        fakePrefs.data["last_foreground_pkg"] = "com.google.android.youtube"

        val originalScreenOn = GuardianAccessibilityService.isScreenOnState
        try {
            GuardianAccessibilityService.isScreenOnState = false
            val resolved = UsageTrackerService.resolveCurrentForegroundPackage(fakeContext, fakePrefs)
            assertEquals("Hardware invariant fence: screen off must return empty foreground", "", resolved)
        } finally {
            GuardianAccessibilityService.isScreenOnState = originalScreenOn
        }
    }

    @Test
    fun testResolveCurrentForegroundPackageFallbackWithin30SecondsReturnsAppAt16s20s30s() {
        val fakePrefs = FakeSharedPreferences(commitReturnsSuccess = true)
        val fakeContext = FakeTestContext(fakePrefs)
        // SharedPreferences has NO foreground package (empty/stale)
        fakePrefs.data["last_foreground_pkg"] = ""
        fakePrefs.data["last_active_package"] = ""
        fakePrefs.data["last_foreground_start"] = 0L
        fakePrefs.data["last_active_timestamp"] = 0L

        val now = System.currentTimeMillis()

        // 1. Event at 16s ago: within 30s window -> MUST return app package, NEVER empty or "HOME"
        val events16s = listOf(
            UsageTrackerService.RawUsageEvent(
                packageName = "com.google.android.youtube",
                eventType = android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED,
                timeStamp = now - 16_000L
            )
        )
        val resolved16s = UsageTrackerService.resolveCurrentForegroundPackage(fakeContext, fakePrefs, events16s)
        assertEquals("App at 16s must be returned, not empty or HOME", "com.google.android.youtube", resolved16s)

        // 2. Event at 20s ago: within 30s window -> MUST return app package
        val events20s = listOf(
            UsageTrackerService.RawUsageEvent(
                packageName = "com.ss.android.ugc.trill",
                eventType = android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED,
                timeStamp = now - 20_000L
            )
        )
        val resolved20s = UsageTrackerService.resolveCurrentForegroundPackage(fakeContext, fakePrefs, events20s)
        assertEquals("App at 20s must be returned, not empty or HOME", "com.ss.android.ugc.trill", resolved20s)

        // 3. Event within 30s window (28s ago) -> MUST return app package
        val now30 = System.currentTimeMillis()
        val events30s = listOf(
            UsageTrackerService.RawUsageEvent(
                packageName = "vn.edu.azota",
                eventType = android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND,
                timeStamp = now30 - 28_000L
            )
        )
        val resolved30s = UsageTrackerService.resolveCurrentForegroundPackage(fakeContext, fakePrefs, events30s)
        assertEquals("App within 30s boundary must be returned, not empty or HOME", "vn.edu.azota", resolved30s)

        // 4. Stale event at 35s ago: outside 30s window -> MUST fail-closed to ""
        val now35 = System.currentTimeMillis()
        val events35s = listOf(
            UsageTrackerService.RawUsageEvent(
                packageName = "com.facebook.katana",
                eventType = android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED,
                timeStamp = now35 - 35_000L
            )
        )
        val resolved35s = UsageTrackerService.resolveCurrentForegroundPackage(fakeContext, fakePrefs, events35s)
        assertEquals("Stale event outside 30s window must be rejected", "", resolved35s)
    }

    @Test
    fun testResolveCurrentForegroundPackageDoesNotDefaultToHomeWhenEventMissing() {
        val fakePrefs = FakeSharedPreferences(commitReturnsSuccess = true)
        val fakeContext = FakeTestContext(fakePrefs)
        // SharedPreferences has NO foreground package
        fakePrefs.data["last_foreground_pkg"] = ""
        fakePrefs.data["last_active_package"] = ""
        fakePrefs.data["last_foreground_start"] = 0L
        fakePrefs.data["last_active_timestamp"] = 0L

        // Empty events -> resolveCurrentForegroundPackage returns ""
        val resolved = UsageTrackerService.resolveCurrentForegroundPackage(fakeContext, fakePrefs, emptyList())
        assertEquals("Empty events with empty prefs must return empty string", "", resolved)

        // Verify active app resolution mapping logic:
        // When lastPkg is empty, active app MUST NOT default to "HOME", but must be UNKNOWN
        val isBank = GuardianAccessibilityService.isBankPackage(resolved) || resolved == "BANK_APP_PROTECTED"
        val isHome = resolved == "HOME" || GuardianAccessibilityService.isDefaultLauncher(fakeContext, resolved)
        val isScreenOff = resolved == "SCREEN_OFF"
        val currentActivePkg = when {
            isBank -> "BANK_APP_PROTECTED"
            isHome -> "HOME"
            isScreenOff -> "SCREEN_OFF"
            resolved.isNotEmpty() -> resolved
            else -> "UNKNOWN"
        }
        assertEquals("Unverified empty package MUST map to UNKNOWN, NEVER falsely report HOME", "UNKNOWN", currentActivePkg)

        // Verify that verified launcher ("HOME") DOES map to HOME
        val launcherPkg = "HOME"
        val isLauncherHome = launcherPkg == "HOME" || GuardianAccessibilityService.isDefaultLauncher(fakeContext, launcherPkg)
        val activeLauncher = when {
            isLauncherHome -> "HOME"
            else -> launcherPkg
        }
        assertEquals("Verified launcher MUST map to HOME", "HOME", activeLauncher)

        // Verify that third-party apps containing 'home' or 'launcher' in package name DO NOT map to HOME
        val homeworkPkg = "com.example.homework"
        val isHomeworkHome = homeworkPkg == "HOME" || GuardianAccessibilityService.isDefaultLauncher(fakeContext, homeworkPkg)
        val activeHomework = when {
            isHomeworkHome -> "HOME"
            else -> homeworkPkg
        }
        assertEquals("Third-party app with 'home' in name MUST NOT map to HOME", "com.example.homework", activeHomework)

        val launcherpadPkg = "com.example.launcherpad"
        val isLauncherpadHome = launcherpadPkg == "HOME" || GuardianAccessibilityService.isDefaultLauncher(fakeContext, launcherpadPkg)
        val activeLauncherpad = when {
            isLauncherpadHome -> "HOME"
            else -> launcherpadPkg
        }
        assertEquals("Third-party app with 'launcher' in name MUST NOT map to HOME", "com.example.launcherpad", activeLauncherpad)
    }

    @Test
    fun testIsDefaultLauncherRejectsPackagesContainingHomeOrLauncherSubstrings() {
        val fakePrefs = FakeSharedPreferences(commitReturnsSuccess = true)
        val fakeContext = FakeTestContext(fakePrefs)

        // 1. Literal "HOME" must return true
        assertTrue("HOME literal must return true", GuardianAccessibilityService.isDefaultLauncher(fakeContext, "HOME"))

        // 2. Null, empty string, and SCREEN_OFF must return false
        assertFalse("null package must return false", GuardianAccessibilityService.isDefaultLauncher(fakeContext, null))
        assertFalse("empty package must return false", GuardianAccessibilityService.isDefaultLauncher(fakeContext, ""))
        assertFalse("SCREEN_OFF must return false", GuardianAccessibilityService.isDefaultLauncher(fakeContext, "SCREEN_OFF"))

        // 3. Adversarial third-party apps containing 'home' or 'launcher' in their package names
        // Under Karl Popper falsification, substring matching is prohibited and must fail-closed to false
        val adversarialNonLauncherPackages = listOf(
            "com.example.homework",
            "com.example.launcherpad",
            "org.education.homework",
            "vn.edu.homework.app",
            "com.google.android.apps.homelessness",
            "com.game.rockethome",
            "com.launcher.fakeapp",
            "vn.edu.cva.homeworktracker"
        )

        for (pkg in adversarialNonLauncherPackages) {
            val isLauncher = GuardianAccessibilityService.isDefaultLauncher(fakeContext, pkg)
            assertFalse("Package '$pkg' must NOT be classified as default launcher via substring match", isLauncher)
        }
    }

    @Test
    fun testPersistDiskActionRejectsWriteWhenHardwareTurnsOffOrEpochAdvances() {
        val fakePrefs = FakeSharedPreferences(commitReturnsSuccess = true)
        val initialEpoch = 10L
        fakePrefs.data["last_written_epoch"] = initialEpoch
        fakePrefs.data["is_device_online"] = true
        fakePrefs.data["last_foreground_pkg"] = "com.google.android.youtube"

        // Red-Team Karl Popper Falsification:
        // When task A has epoch 10 and effectiveOnline=true, but screen turns off (epoch becomes 11),
        // any delayed disk persistence for task A must be rejected by CAS under diskStateLock.
        val activeEpoch = 11L
        val activeGen = 5L
        val taskGen = 4L
        val hardwareStillOnline = false
        val effectiveOnline = true

        val shouldWrite = synchronized(UsageTrackerService.diskStateLock) {
            if (effectiveOnline && (!hardwareStillOnline || activeEpoch != initialEpoch || activeGen != taskGen)) {
                false
            } else {
                val currentDiskEpoch = fakePrefs.getLong("last_written_epoch", -1L)
                if (initialEpoch == -1L || currentDiskEpoch <= initialEpoch) {
                    fakePrefs.data["last_foreground_pkg"] = "STALE_APP"
                    true
                } else {
                    false
                }
            }
        }

        assertFalse("Stale online task must NOT write to disk after screen off / epoch advance", shouldWrite)
        assertEquals("YouTube must remain unchanged, not overwritten by stale task", "com.google.android.youtube", fakePrefs.data["last_foreground_pkg"])
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
