package dev.placeholder.framework.input.testing

import dev.placeholder.framework.input.accept
import dev.placeholder.framework.input.pass
import dev.placeholder.framework.input.retry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
internal class InputTestHarnessTest {
    @Test
    fun `fixture drives production prompt and captures delivery`() = runTest {
        InputTestHarness().use { fixture ->
            val alex = fixture.player("Alex")
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.playerInput.chat(alex, "Yes or no?") {
                    if (text == "yes") accept(true) else retry("Try again.")
                }
            }

            assertTrue(fixture.answer(alex, "maybe").consumed)
            runCurrent()
            assertEquals(listOf("Yes or no?", "Try again."), fixture.plainMessages(alex))
            assertTrue(fixture.answer(alex, "yes").consumed)
            assertTrue(pending.await())
        }
    }

    @Test
    fun `fixture records passed chat and advances virtual time`() = runTest {
        InputTestHarness().use { fixture ->
            val alex = fixture.player("Alex")
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.playerInput.chat(
                    alex,
                    "Use answer prefix",
                    idleTimeout = 5.seconds,
                ) {
                    if (text.startsWith("answer:")) accept(text.removePrefix("answer:")) else pass()
                }
            }

            assertFalse(fixture.answer(alex, "public").consumed)
            assertEquals(1, fixture.passed(alex).size)
            advanceTimeBy(4.seconds)
            assertFalse(pending.isCompleted)
            assertTrue(fixture.answer(alex, "answer:done").consumed)
            assertEquals("done", pending.await())
        }
    }
}
