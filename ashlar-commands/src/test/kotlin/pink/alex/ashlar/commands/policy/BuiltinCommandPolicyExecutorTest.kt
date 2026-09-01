package pink.alex.ashlar.commands.policy

import pink.alex.ashlar.commands.CommandExecutor
import pink.alex.ashlar.commands.CommandInvocation
import pink.alex.ashlar.commands.CommandRejectedException
import pink.alex.ashlar.commands.CommandSender
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.audience.Audience
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BuiltinCommandPolicyExecutorTest {
    private val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val state = InMemoryCommandPolicyState(clock)
    private val policies = BuiltinCommandPolicyExecutor(clock, state)
    private val invocation = invocation()

    @Test
    fun `success cooldown is charged only after a successful handler`() = runBlocking {
        val definition = CommandPolicyDefinition.Cooldown(10, CooldownMode.SUCCESS)

        policies.beforeResolution(listOf(definition), invocation, ROUTE)
        assertFailsWith<IllegalStateException> {
            policies.invokeHandler(listOf(definition), invocation, ROUTE) {
                error("handler failed")
            }
        }
        policies.beforeResolution(listOf(definition), invocation, ROUTE)
        policies.invokeHandler(listOf(definition), invocation, ROUTE) { "done" }

        assertFailsWith<CommandRejectedException> {
            policies.beforeResolution(listOf(definition), invocation, ROUTE)
        }
        clock.advance(Duration.ofSeconds(10))
        policies.beforeResolution(listOf(definition), invocation, ROUTE)
    }

    @Test
    fun `attempt and accepted cooldowns charge in their declared phase`() {
        val attempt = CommandPolicyDefinition.Cooldown(10, CooldownMode.ATTEMPT)
        policies.beforeResolution(listOf(attempt), invocation, ROUTE)
        assertFailsWith<CommandRejectedException> {
            policies.beforeResolution(listOf(attempt), invocation, ROUTE)
        }

        val acceptedRoute = "$ROUTE.accepted"
        val accepted = CommandPolicyDefinition.Cooldown(10, CooldownMode.ACCEPTED)
        policies.beforeResolution(listOf(accepted), invocation, acceptedRoute)
        policies.afterResolution(listOf(accepted), invocation, acceptedRoute, emptyList())
        assertFailsWith<CommandRejectedException> {
            policies.beforeResolution(listOf(accepted), invocation, acceptedRoute)
        }
    }

    @Test
    fun `token bucket replenishes continuously`() {
        val definition = CommandPolicyDefinition.RateLimit(2, 10, RateLimitMode.TOKEN_BUCKET)

        repeat(2) { policies.beforeResolution(listOf(definition), invocation, ROUTE) }
        assertFailsWith<CommandRejectedException> {
            policies.beforeResolution(listOf(definition), invocation, ROUTE)
        }
        clock.advance(Duration.ofSeconds(5))
        policies.beforeResolution(listOf(definition), invocation, ROUTE)
        assertFailsWith<CommandRejectedException> {
            policies.beforeResolution(listOf(definition), invocation, ROUTE)
        }
    }

    @Test
    fun `sliding window retains only recent attempts`() {
        val definition = CommandPolicyDefinition.RateLimit(2, 10, RateLimitMode.SLIDING_WINDOW)

        repeat(2) { policies.beforeResolution(listOf(definition), invocation, ROUTE) }
        assertFailsWith<CommandRejectedException> {
            policies.beforeResolution(listOf(definition), invocation, ROUTE)
        }
        clock.advance(Duration.ofSeconds(11))
        policies.beforeResolution(listOf(definition), invocation, ROUTE)
    }

    @Test
    fun `confirmation keys the canonical semantic invocation`() {
        val definition = CommandPolicyDefinition.Confirm(30)

        assertFailsWith<CommandRejectedException> {
            policies.afterResolution(listOf(definition), invocation, ROUTE, listOf("market square", "2"))
        }
        assertFailsWith<CommandRejectedException> {
            policies.afterResolution(listOf(definition), invocation, ROUTE, listOf("other", "2"))
        }
        policies.afterResolution(listOf(definition), invocation, ROUTE, listOf("market square", "2"))
    }

    @Test
    fun `single flight rejects overlap and always releases`() = runBlocking {
        val definition = CommandPolicyDefinition.SingleFlight
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            policies.invokeHandler(listOf(definition), invocation, ROUTE) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        assertFailsWith<CommandRejectedException> {
            policies.invokeHandler(listOf(definition), invocation, ROUTE) { Unit }
        }
        release.complete(Unit)
        first.await()
        policies.invokeHandler(listOf(definition), invocation, ROUTE) { Unit }
    }

    @Test
    fun `stored policy keys are hashed`() {
        val recording = RecordingState(InMemoryCommandPolicyState(clock))
        val executor = BuiltinCommandPolicyExecutor(clock, recording)
        val definition = CommandPolicyDefinition.RateLimit(1, 10, RateLimitMode.TOKEN_BUCKET)

        executor.beforeResolution(listOf(definition), invocation, ROUTE)

        assertEquals(1, recording.keys.size)
        assertTrue(recording.keys.single().matches(Regex("[0-9a-f]{64}")))
        assertTrue("Alex" !in recording.keys.single())
        assertTrue(ROUTE !in recording.keys.single())
    }

    private class RecordingState(
        private val delegate: CommandPolicyState,
    ) : CommandPolicyState {
        val keys = mutableListOf<String>()

        override fun update(
            key: String,
            transform: (CommandPolicyRecord?) -> CommandPolicyRecord?,
        ): CommandPolicyRecord? {
            keys += key
            return delegate.update(key, transform)
        }
    }

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }

    private companion object {
        const val ROUTE = "example.WaypointCommands#teleport"

        fun invocation(): CommandInvocation {
            val id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
            return CommandInvocation(
                sender = CommandSender(
                    name = "Alex",
                    uniqueId = id,
                    locale = java.util.Locale.ENGLISH,
                    audience = Audience.empty(),
                    permissionCheck = { true },
                ),
                executor = CommandExecutor("Alex", id),
                route = "waypoint teleport",
            )
        }
    }
}
