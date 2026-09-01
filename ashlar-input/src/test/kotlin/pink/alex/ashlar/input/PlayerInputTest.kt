package pink.alex.ashlar.input

import pink.alex.ashlar.execution.PlayerRef
import java.util.Locale
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

@OptIn(ExperimentalCoroutinesApi::class)
internal class PlayerInputTest {
    private val alex = PlayerRef(UUID.fromString("1ed2dfa7-07df-4d36-8bc0-436b500bc3f2"))

    @Test
    fun `accepted answer is consumed and returned`() = runTest {
        val fixture = fixture()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.input.chat(alex, "Yes or no?") {
                if (text == "yes") accept(true) else retry("Try again.")
            }
        }

        assertEquals(listOf("Yes or no?"), fixture.delivery.plainMessages())
        assertTrue(fixture.input.dispatch(alex.uniqueId, Component.text("yes")))
        assertTrue(pending.await())
    }

    @Test
    fun `retry sends feedback and resets idle timeout`() = runTest {
        val fixture = fixture()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.input.chat(alex, "Answer", idleTimeout = 30.seconds) {
                if (text == "done") accept(text) else retry("Again")
            }
        }

        advanceTimeBy(25.seconds)
        assertTrue(fixture.input.dispatch(alex.uniqueId, Component.text("wrong")))
        runCurrent()
        assertEquals(listOf("Answer", "Again"), fixture.delivery.plainMessages())

        advanceTimeBy(25.seconds)
        assertFalse(pending.isCompleted)
        assertTrue(fixture.input.dispatch(alex.uniqueId, Component.text("done")))
        assertEquals("done", pending.await())
    }

    @Test
    fun `pass leaves chat public and does not complete`() = runTest {
        val fixture = fixture()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.input.chat(alex, "Use answer prefix") {
                if (text.startsWith("answer:")) accept(text.removePrefix("answer:")) else pass()
            }
        }

        assertFalse(fixture.input.dispatch(alex.uniqueId, Component.text("public chat")))
        assertFalse(pending.isCompleted)
        assertTrue(fixture.input.dispatch(alex.uniqueId, Component.text("answer:value")))
        assertEquals("value", pending.await())
    }

    @Test
    fun `parser cancel has a typed reason`() = runTest {
        val fixture = fixture()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.input.chat<Unit>(alex, "Type cancel") { cancel() }
        }

        assertTrue(fixture.input.dispatch(alex.uniqueId, Component.text("cancel")))
        val failure = assertFailsWith<InputCancellationException> { pending.await() }
        assertEquals(InputCancellationReason.USER_CANCELLED, failure.reason)
    }

    @Test
    fun `reject preserves the active prompt`() = runTest {
        val fixture = fixture()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.input.chat(alex, "First") { accept(text) }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.input.chat(alex, "Second") { accept(text) }
        }

        assertFailsWith<InputConflictException> { second.await() }
        assertEquals(listOf("First", "Finish your current prompt first."), fixture.delivery.plainMessages())
        assertTrue(fixture.input.dispatch(alex.uniqueId, Component.text("kept")))
        assertEquals("kept", first.await())
    }

    @Test
    fun `replace cancels the old prompt and activates the new one`() = runTest {
        val fixture = fixture()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.input.chat(alex, "First") { accept(text) }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.input.chat(alex, "Second", conflict = InputConflict.REPLACE) { accept(text) }
        }

        val replaced = assertFailsWith<InputCancellationException> { first.await() }
        assertEquals(InputCancellationReason.REPLACED, replaced.reason)
        assertTrue(fixture.input.dispatch(alex.uniqueId, Component.text("new")))
        assertEquals("new", second.await())
    }

    @Test
    fun `external cancellation is atomic`() = runTest {
        val fixture = fixture()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.input.chat(alex, "Waiting") { accept(text) }
        }

        assertTrue(fixture.input.cancel(alex))
        assertFalse(fixture.input.cancel(alex))
        val failure = assertFailsWith<InputCancellationException> { pending.await() }
        assertEquals(InputCancellationReason.EXTERNALLY_CANCELLED, failure.reason)
    }

    @Test
    fun `disconnect cancels without another message`() = runTest {
        val fixture = fixture()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.input.chat(alex, "Waiting") { accept(text) }
        }

        fixture.input.disconnect(alex.uniqueId)
        val failure = assertFailsWith<InputCancellationException> { pending.await() }
        assertEquals(InputCancellationReason.PLAYER_DISCONNECTED, failure.reason)
        assertEquals(listOf("Waiting"), fixture.delivery.plainMessages())
    }

    @Test
    fun `idle timeout sends the configured message`() = runTest {
        val fixture = fixture()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.input.chat(
                alex,
                "Waiting",
                idleTimeout = 30.seconds,
                expiredMessage = "Custom expiry",
            ) { accept(text) }
        }

        advanceTimeBy(30.seconds)
        runCurrent()
        val failure = assertFailsWith<InputCancellationException> { pending.await() }
        assertEquals(InputCancellationReason.IDLE_TIMEOUT, failure.reason)
        assertEquals(listOf("Waiting", "Custom expiry"), fixture.delivery.plainMessages())
    }

    @Test
    fun `parser failure consumes chat and reaches the caller`() = runTest {
        supervisorScope {
            val fixture = fixture()
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.input.chat<Unit>(alex, "Break") { error("parser failed") }
            }

            assertTrue(fixture.input.dispatch(alex.uniqueId, Component.text("private answer")))
            val failure = assertFailsWith<IllegalStateException> { pending.await() }
            assertEquals("parser failed", failure.message)
        }
    }

    @Test
    fun `plugin close cancels every active prompt`() = runTest {
        val fixture = fixture()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.input.chat(alex, "Waiting") { accept(text) }
        }

        fixture.input.close()
        val failure = assertFailsWith<InputCancellationException> { pending.await() }
        assertEquals(InputCancellationReason.PLUGIN_STOPPED, failure.reason)
    }

    @Test
    fun `retired player cancels prompt before waiting`() = runTest {
        val fixture = fixture().also { it.delivery.online = false }

        val failure = assertFailsWith<InputCancellationException> {
            fixture.input.chat(alex, "Unreachable") { accept(text) }
        }

        assertEquals(InputCancellationReason.PLAYER_DISCONNECTED, failure.reason)
    }

    private fun fixture(): Fixture {
        val delivery = RecordingDelivery()
        return Fixture(PlayerInput.testing(delivery), delivery)
    }

    private data class Fixture(
        val input: PlayerInput,
        val delivery: RecordingDelivery,
    )

    private class RecordingDelivery : InputDelivery {
        private val messages = mutableListOf<Component>()
        var online: Boolean = true

        override suspend fun send(
            player: PlayerRef,
            message: (Locale) -> Component,
        ): Boolean {
            if (!online) return false
            messages += message(Locale.US)
            return true
        }

        fun plainMessages(): List<String> = messages.map(PlainTextComponentSerializer.plainText()::serialize)
    }
}
