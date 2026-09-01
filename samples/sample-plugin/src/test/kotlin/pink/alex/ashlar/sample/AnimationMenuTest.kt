package pink.alex.ashlar.sample

import pink.alex.ashlar.menus.MenuReconciliation
import pink.alex.ashlar.menus.testing.menuTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

class AnimationMenuTest {
    @Test
    fun `animation advances only changed canvas slots and can pause`() = menuTest {
        val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val menu = open { AnimationMenu(ticks) }

        assertEquals(Component.text("Menu animations · Comet", NamedTextColor.AQUA), menu.chest.title)
        assertEquals(54, menu.chest.slots.size)
        ticks.tryEmit(Unit)
        runCurrent()

        menu.assertRevision(2)
        val update = assertIs<MenuReconciliation.Update>(menu.reconciliations().single())
        assertTrue(update.changedSlots.isNotEmpty())
        assertTrue(update.changedSlots.size < 45)
        assertTrue(update.changedSlots.all { it < 45 })

        menu.primaryClick(49)
        assertEquals(Component.text("Menu animations · Wave", NamedTextColor.AQUA), menu.chest.title)
        menu.primaryClick(47)
        val pausedRevision = menu.render.revision
        ticks.tryEmit(Unit)
        runCurrent()
        assertEquals(pausedRevision, menu.render.revision)

        menu.close()
    }
}
