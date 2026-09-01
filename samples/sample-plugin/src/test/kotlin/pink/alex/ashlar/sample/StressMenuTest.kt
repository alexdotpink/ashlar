package pink.alex.ashlar.sample

import pink.alex.ashlar.menus.MenuReconciliation
import pink.alex.ashlar.menus.testing.menuTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.milliseconds

class StressMenuTest {
    @Test
    fun `every tick changes every slot`() = menuTest {
        val menu = open { StressMenu() }
        val firstFrame = menu.chest.slots.associate { it.index to it.item?.material }

        assertEquals(54, firstFrame.size)
        advanceTimeBy(50.milliseconds)

        menu.assertRevision(2)
        val update = assertIs<MenuReconciliation.Update>(menu.reconciliations().single())
        assertEquals((0 until 54).toSet(), update.changedSlots)
        menu.chest.slots.forEach { slot ->
            assertNotEquals(firstFrame.getValue(slot.index), slot.item?.material)
        }

        menu.close()
    }
}
