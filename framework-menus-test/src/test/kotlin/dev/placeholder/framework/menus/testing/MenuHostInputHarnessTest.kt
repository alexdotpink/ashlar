package dev.placeholder.framework.menus.testing

import dev.placeholder.framework.menus.AnvilHostSnapshot
import dev.placeholder.framework.menus.MenuDispatch
import dev.placeholder.framework.menus.MenuHostInput
import dev.placeholder.framework.menus.MenuHostInputKind
import dev.placeholder.framework.menus.anvil
import dev.placeholder.framework.menus.renameText
import dev.placeholder.framework.menus.state
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MenuHostInputHarnessTest {
    @Test
    fun `typed host input executes its declared action and rerenders`() = menuTest {
        val menu = open {
            var name by state("Unnamed")
            anvil(Component.text(name)) {
                renameText { input -> name = input.text }
            }
        }

        assertEquals(setOf(MenuHostInputKind.ANVIL_RENAME_TEXT), menu.render.hostInputs)
        assertEquals(
            MenuDispatch.Accepted,
            menu.hostInput(MenuHostInput.AnvilRenameText(menu.player, menu.render.revision, "Market")),
        )
        assertEquals(Component.text("Market"), assertIs<AnvilHostSnapshot>(menu.render.host).title)
    }

    @Test
    fun `stale and undeclared host inputs are rejected before action launch`() = menuTest {
        val menu = open {
            anvil(Component.text("Rename")) {
                renameText {}
            }
        }
        val revision = menu.render.revision

        assertEquals(
            MenuDispatch.StaleRevision,
            menu.hostInput(MenuHostInput.AnvilRenameText(menu.player, revision - 1, "Old")),
        )
        assertEquals(
            MenuDispatch.UnsupportedHostInput,
            menu.hostInput(MenuHostInput.MerchantTradeSelected(menu.player, revision, 0)),
        )
    }
}
