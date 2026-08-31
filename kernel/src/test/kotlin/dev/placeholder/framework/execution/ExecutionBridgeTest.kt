package dev.placeholder.framework.execution

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class ExecutionBridgeTest {
    @Test
    fun `scheduled work completes with its value`() = runTest {
        val scheduled = CompletableDeferred<() -> Unit>()
        val result = async {
            awaitSchedule(
                schedule = { callback ->
                    scheduled.complete(callback)
                    ScheduledCancellation {}
                },
                run = { 42 },
            )
        }

        scheduled.await().invoke()

        assertEquals(42, result.await())
    }

    @Test
    fun `caller resumes in its previous coroutine context`() {
        val callerExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "execution-caller")
        }
        val schedulerExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "server-scheduler")
        }

        callerExecutor.asCoroutineDispatcher().use { callerDispatcher ->
            try {
                runBlocking(callerDispatcher) {
                    val callerThread = Thread.currentThread()
                    val scheduled = AtomicReference<() -> Unit>()
                    val resumedThread = async(start = CoroutineStart.UNDISPATCHED) {
                        awaitSchedule(
                            schedule = { callback ->
                                scheduled.set(callback)
                                ScheduledCancellation {}
                            },
                            run = { Unit },
                        )
                        Thread.currentThread()
                    }

                    schedulerExecutor.submit { requireNotNull(scheduled.get()).invoke() }.get()

                    assertSame(callerThread, resumedThread.await())
                }
            } finally {
                schedulerExecutor.shutdownNow()
            }
        }
    }

    @Test
    fun `cancelling the caller cancels scheduled work`() = runTest {
        val scheduled = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean(false)
        val task = launch {
            awaitSchedule(
                schedule = {
                    scheduled.complete(Unit)
                    ScheduledCancellation { cancelled.set(true) }
                },
                run = {},
            )
        }
        scheduled.await()

        task.cancelAndJoin()

        assertTrue(cancelled.get())
    }

    @Test
    fun `cancelled work does not run even if its callback arrives`() = runTest {
        val scheduled = CompletableDeferred<() -> Unit>()
        val ran = AtomicBoolean(false)
        val task = launch {
            awaitSchedule(
                schedule = { callback ->
                    scheduled.complete(callback)
                    ScheduledCancellation {}
                },
                run = { ran.set(true) },
            )
        }
        val callback = scheduled.await()
        task.cancelAndJoin()

        callback()

        assertFalse(ran.get())
    }

    @Test
    fun `entity scheduler rejection is retirement`() = runTest {
        val outcome = awaitEntitySchedule<Int>(
            schedule = { _, _ -> null },
            run = { error("must not run") },
        )

        assertIs<EntityOutcome.Retired>(outcome)
    }

    @Test
    fun `entity retirement callback is retirement`() = runTest {
        val retired = CompletableDeferred<() -> Unit>()
        val result = async {
            awaitEntitySchedule<Int>(
                schedule = { _, onRetired ->
                    retired.complete(onRetired)
                    ScheduledCancellation {}
                },
                run = { error("must not run") },
            )
        }

        retired.await().invoke()

        assertIs<EntityOutcome.Retired>(result.await())
    }

    @Test
    fun `block failure remains a failure`() = runTest {
        supervisorScope {
            val scheduled = CompletableDeferred<() -> Unit>()
            val result = async {
                awaitSchedule(
                    schedule = { callback ->
                        scheduled.complete(callback)
                        ScheduledCancellation {}
                    },
                    run = { throw ExpectedFailure() },
                )
            }

            scheduled.await().invoke()

            assertFailsWith<ExpectedFailure> { result.await() }
        }
    }

    @Test
    fun `only one entity completion callback wins`() = runTest {
        lateinit var run: () -> Unit
        lateinit var retire: () -> Unit
        var executions = 0
        val result = async {
            awaitEntitySchedule(
                schedule = { scheduledRun, scheduledRetire ->
                    run = scheduledRun
                    retire = scheduledRetire
                    ScheduledCancellation {}
                },
                run = {
                    executions += 1
                    EntityOutcome.Completed(executions)
                },
            )
        }
        yield()

        run()
        retire()

        assertEquals(EntityOutcome.Completed(1), result.await())
        assertEquals(1, executions)
    }

    private class ExpectedFailure : RuntimeException()
}
