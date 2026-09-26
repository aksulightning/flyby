package io.github.aksulightning.flyby.vm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class VmManagerTest {
    @get:Rule val temporary = TemporaryFolder()

    private class FakeController : VmController {
        var starts = 0
        var stops = 0
        var forceStops = 0
        var failStart = false
        var hangOnStop = false
        var failCleanup = false
        var spawnGate: CompletableDeferred<Unit>? = null
        var exit = CompletableDeferred<Int>()
        var lastInput: ByteArray? = null
        override suspend fun start(config: VmConfig, files: GuestFiles, onOutput: (ByteArray) -> Unit) {
            starts++
            if (failStart) error("spawn failed")
            spawnGate?.await()
            onOutput("guest output".toByteArray())
        }
        override suspend fun awaitExit() = exit.await()
        override suspend fun stop() { stops++; if (!hangOnStop) exit.complete(0) }
        override suspend fun forceStop() {
            forceStops++
            if (failCleanup) error("reap failed")
            exit.complete(137)
        }
        override suspend fun sendInput(bytes: ByteArray) { lastInput = bytes }
    }

    @Test fun startRunStopAndOutput() = runTest {
        val fake = FakeController()
        val manager = VmManager(backgroundScope, fake, ioDispatcher = StandardTestDispatcher(testScheduler))
        assertEquals(VmState.STOPPED, manager.status.value.state)
        assertTrue(manager.start(VmConfig(), temporary.guestFiles()))
        assertEquals(VmState.STARTING, manager.status.value.state)
        runCurrent()
        assertEquals(VmState.RUNNING, manager.status.value.state)
        assertEquals("guest output", manager.transcript.value)
        val stop = async { manager.stop() }
        runCurrent()
        stop.await()
        assertEquals(VmState.STOPPED, manager.status.value.state)
        assertEquals(1, fake.stops)
    }

    @Test fun duplicateStartRejectedWhileStartingAndRunning() = runTest {
        val fake = FakeController()
        val manager = VmManager(backgroundScope, fake, ioDispatcher = StandardTestDispatcher(testScheduler))
        val files = temporary.guestFiles()
        manager.start(VmConfig(), files)
        assertFalse(manager.start(VmConfig(), files))
        runCurrent()
        assertFalse(manager.start(VmConfig(), files))
        assertEquals(1, fake.starts)
        manager.forceStop()
    }

    @Test fun immediateStopDoesNotLeaveStartingStateStuck() = runTest {
        val fake = FakeController()
        val manager = VmManager(backgroundScope, fake, ioDispatcher = StandardTestDispatcher(testScheduler))
        manager.start(VmConfig(), temporary.guestFiles())
        manager.stop()
        assertEquals(VmState.STOPPED, manager.status.value.state)
        assertEquals(0, fake.starts)
    }

    @Test fun stopDuringSpawnCleansUp() = runTest {
        val fake = FakeController().apply { spawnGate = CompletableDeferred() }
        val manager = VmManager(backgroundScope, fake, ioDispatcher = StandardTestDispatcher(testScheduler))
        manager.start(VmConfig(), temporary.guestFiles())
        runCurrent()
        manager.stop()
        assertEquals(VmState.STOPPED, manager.status.value.state)
        assertEquals(1, fake.forceStops)
    }

    @Test fun nonzeroExitAndRetry() = runTest {
        val fake = FakeController()
        val manager = VmManager(backgroundScope, fake, ioDispatcher = StandardTestDispatcher(testScheduler))
        val files = temporary.guestFiles()
        manager.start(VmConfig(), files)
        runCurrent()
        fake.exit.complete(42)
        runCurrent()
        assertEquals(VmState.ERROR, manager.status.value.state)
        assertEquals(42, manager.status.value.exitCode)
        fake.exit = CompletableDeferred()
        assertTrue(manager.start(VmConfig(), files))
        runCurrent()
        assertEquals(VmState.RUNNING, manager.status.value.state)
        manager.forceStop()
    }

    @Test fun spawnFailureIsReportedAndCleanedUp() = runTest {
        val fake = FakeController().apply { failStart = true }
        val manager = VmManager(backgroundScope, fake, ioDispatcher = StandardTestDispatcher(testScheduler))
        manager.start(VmConfig(), temporary.guestFiles())
        runCurrent()
        assertEquals(VmState.ERROR, manager.status.value.state)
        assertEquals("spawn failed", manager.status.value.error)
        assertEquals(1, fake.forceStops)
    }

    @Test fun invalidConfigNeverSpawns() = runTest {
        val fake = FakeController()
        val manager = VmManager(backgroundScope, fake, ioDispatcher = StandardTestDispatcher(testScheduler))
        manager.start(VmConfig(memoryMiB = 0), temporary.guestFiles())
        runCurrent()
        assertEquals(VmState.ERROR, manager.status.value.state)
        assertEquals(0, fake.starts)
    }

    @Test fun provisioningFailureIsReportedWithoutStartingNative() = runTest {
        val fake = FakeController()
        val manager = VmManager(backgroundScope, fake, ioDispatcher = StandardTestDispatcher(testScheduler))
        manager.start(VmConfig()) { error("Guest checksum mismatch") }
        runCurrent()
        assertEquals(VmState.ERROR, manager.status.value.state)
        assertEquals("Guest checksum mismatch", manager.status.value.error)
        assertEquals(0, fake.starts)
    }

    @Test fun unavailableProductionControllerNeverPretendsToRun() = runTest {
        val manager = VmManager(backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))
        manager.start(VmConfig(), temporary.guestFiles())
        runCurrent()
        assertEquals(VmState.ERROR, manager.status.value.state)
        assertTrue(manager.status.value.error!!.contains("Native VM runtime"))
    }

    @Test fun timeoutFallsBackToForceStopAndRejectsRestartWhileStopping() = runTest {
        val fake = FakeController().apply { hangOnStop = true }
        val manager = VmManager(backgroundScope, fake, shutdownTimeoutMs = 100,
            ioDispatcher = StandardTestDispatcher(testScheduler))
        val files = temporary.guestFiles()
        manager.start(VmConfig(), files)
        runCurrent()
        val stopping = async { manager.stop() }
        runCurrent()
        assertEquals(VmState.STOPPING, manager.status.value.state)
        assertFalse(manager.start(VmConfig(), files))
        advanceTimeBy(101)
        runCurrent()
        stopping.await()
        assertEquals(VmState.STOPPED, manager.status.value.state)
        assertEquals(1, fake.forceStops)
    }

    @Test fun inputOnlyGoesToRunningGuestAndIsNotLogged() = runTest {
        val fake = FakeController()
        val logs = mutableListOf<String>()
        val manager = VmManager(backgroundScope, fake, logs::add,
            ioDispatcher = StandardTestDispatcher(testScheduler))
        try { manager.sendInput("secret".toByteArray()); fail("must reject input while stopped") }
        catch (_: IllegalStateException) { }
        manager.start(VmConfig(), temporary.guestFiles())
        runCurrent()
        manager.sendInput("secret".toByteArray())
        assertArrayEquals("secret".toByteArray(), fake.lastInput)
        assertFalse(logs.any { "secret" in it })
        manager.forceStop()
    }

    @Test fun failedCleanupBlocksRestartUntilForceStopSucceeds() = runTest {
        val fake = FakeController().apply { failCleanup = true }
        val manager = VmManager(backgroundScope, fake, ioDispatcher = StandardTestDispatcher(testScheduler))
        val files = temporary.guestFiles()
        manager.start(VmConfig(), files)
        runCurrent()
        manager.forceStop()
        assertEquals(VmState.ERROR, manager.status.value.state)
        assertFalse(manager.start(VmConfig(), files))
        fake.failCleanup = false
        manager.forceStop()
        assertEquals(VmState.STOPPED, manager.status.value.state)
        fake.exit = CompletableDeferred()
        assertTrue(manager.start(VmConfig(), files))
        runCurrent()
        manager.forceStop()
    }
}
