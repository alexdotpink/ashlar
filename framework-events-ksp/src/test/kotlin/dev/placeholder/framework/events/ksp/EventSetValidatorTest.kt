package dev.placeholder.framework.events.ksp

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class EventSetValidatorTest {
    private val validator = EventSetValidator()

    @Test
    fun `accepts a final synchronous unit event extension`() {
        assertTrue(validator.validate(validModel()).isEmpty())
    }

    @Test
    fun `rejects shapes that cannot be direct server handlers`() {
        val invalid = validModel().copy(
            open = true,
            handlers = listOf(
                validModel().handlers.single().copy(
                    event = false,
                    cancellable = false,
                    suspending = true,
                    returnType = "kotlin.String",
                    parameterCount = 1,
                    private = true,
                    generic = true,
                ),
            ),
        )

        assertEquals(
            listOf(
                "A concrete @Events class must be final",
                "Event handler 'protect' receiver must extend Event",
                "Server event handler 'protect' cannot declare value parameters",
                "@On server event handler 'protect' cannot suspend",
                "Server event handler 'protect' must return Unit",
                "Server event handler 'protect' must be public or internal",
                "Server event handler 'protect' cannot declare type parameters",
                "Server event handler 'protect' can ignore cancellation only for a Cancellable event",
            ),
            validator.validate(invalid),
        )
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
