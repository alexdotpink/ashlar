package dev.placeholder.framework.events.codegen

import dev.placeholder.framework.events.Events
import dev.placeholder.framework.events.On
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
}

@Events
internal class GeneratedEventFixture {
    var calls: Int = 0

    @On
    internal fun TestEvent.receive() {
        calls++
    }
}

internal class TestEvent : Event() {
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()
    }
}
