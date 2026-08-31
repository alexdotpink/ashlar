package dev.placeholder.framework.events.ksp

import kotlin.test.assertContains
import kotlin.test.assertFalse
import org.junit.jupiter.api.Test

class EventSetWriterTest {
    @Test
    fun `generates metadata and one direct extension call`() {
        val generated = EventSetWriter().file(validModel()).toString()

        assertContains(generated, "internal class ProtectionEventsGeneratedEventBinding")
        assertContains(generated, "eventType = BlockBreakEvent::class")
        assertContains(generated, "priority = EventPriority.HIGH")
        assertContains(generated, "with(typedTarget) { (event as BlockBreakEvent).protect() }")
        assertFalse(generated.contains("registerEvent"))
        assertFalse(generated.contains("coroutine", ignoreCase = true))
    }

    private fun validModel(): EventSetModel = EventSetModel(
        packageName = "example",
        typeNames = listOf("ProtectionEvents"),
        abstract = false,
        open = false,
        handlers = listOf(
            ServerHandlerModel(
                functionName = "protect",
                eventPackageName = "org.bukkit.event.block",
                eventTypeNames = listOf("BlockBreakEvent"),
                eventQualifiedName = "org.bukkit.event.block.BlockBreakEvent",
                event = true,
                priority = "HIGH",
                ignoreCancelled = true,
                cancellable = true,
                suspending = false,
                returnType = "kotlin.Unit",
                parameterCount = 0,
                private = false,
                protected = false,
                generic = false,
            ),
        ),
    )
}
