package dev.placeholder.framework.events.codegen

import dev.placeholder.framework.events.Events
import dev.placeholder.framework.events.On
import dev.placeholder.framework.events.Observe
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.junit.jupiter.api.Test

class GeneratedEventBindingTest {
    @Test
    fun `generated binding invokes an internal extension handler directly`() {
        val target = GeneratedEventFixture()
        val binding = GeneratedEventFixtureGeneratedEventBinding()

        binding.invoke(target, 0, TestEvent())

        assertEquals(1, target.calls)
    }

    @Test
    fun `generated binding invokes a suspending observer directly`() = runBlocking {
        val target = GeneratedEventFixture()
        val binding = GeneratedEventFixtureGeneratedEventBinding()

        binding.observe(target, 1, TestEvent())

        assertEquals(1, target.observations)
    }
}

@Events
internal class GeneratedEventFixture {
    var calls: Int = 0
    var observations: Int = 0

    @On
    internal fun TestEvent.receive() {
        calls++
    }

    @Observe
    internal suspend fun TestEvent.observe() {
        observations++
    }
}

internal class TestEvent : Event() {
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()
    }
}
