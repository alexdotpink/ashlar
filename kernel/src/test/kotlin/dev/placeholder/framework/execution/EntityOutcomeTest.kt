package dev.placeholder.framework.execution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class EntityOutcomeTest {
    @Test
    fun `onRetired runs only for retirement and preserves the outcome`() {
        var calls = 0
        val completed = EntityOutcome.Completed(7)

        assertSame(completed, completed.onRetired { calls += 1 })
        assertSame(EntityOutcome.Retired, EntityOutcome.Retired.onRetired { calls += 1 })
        assertEquals(1, calls)
    }
}
