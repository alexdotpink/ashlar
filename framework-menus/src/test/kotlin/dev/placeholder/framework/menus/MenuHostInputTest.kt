package dev.placeholder.framework.menus

import dev.placeholder.framework.execution.PlayerRef
import java.util.UUID
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MenuHostInputTest {
    private val title = Component.text("Host input")

    @Test
    fun `specialized host callbacks retain typed render declarations`() {
        assertHandler(MenuHostInputKind.ANVIL_RENAME_TEXT, MenuActionConcurrency.RESTART_LATEST) {
            anvil(title) { renameText {} }
        }
        assertHandler(MenuHostInputKind.MERCHANT_TRADE_SELECTED, MenuActionConcurrency.RESTART_LATEST) {
            merchant(title, emptyList()) { onTradeSelected {} }
        }
        assertHandler(MenuHostInputKind.LOOM_PATTERN_SELECTED, MenuActionConcurrency.RESTART_LATEST) {
            loom(title) { onPatternSelected {} }
        }
        assertHandler(MenuHostInputKind.STONECUTTER_RECIPE_SELECTED, MenuActionConcurrency.RESTART_LATEST) {
            stonecutter(title) { onRecipeSelected {} }
        }
        assertHandler(MenuHostInputKind.ENCHANTMENT_BUTTON, MenuActionConcurrency.SINGLE_FLIGHT) {
            enchantment(title, seed = 7) { onEnchantmentButton {} }
        }
        assertHandler(MenuHostInputKind.BEACON_EFFECTS_SELECTED, MenuActionConcurrency.SINGLE_FLIGHT) {
            beacon(title) { onBeaconEffectsSelected {} }
        }
        assertHandler(MenuHostInputKind.LECTERN_PAGE_CHANGED, MenuActionConcurrency.RESTART_LATEST) {
            lectern(title) { onPageChanged {} }
        }
    }

    @Test
    fun `duplicate callback category fails during render`() {
        assertFailsWith<MenuValidationException> {
            tree {
                anvil(title) {
                    renameText {}
                    renameText {}
                }
            }
        }
    }

    @Test
    fun `host inputs validate semantic indexes and retain stable keys`() {
        val player = PlayerRef(UUID.randomUUID())
        assertEquals(EnchantmentButton.THIRD, EnchantmentButton.fromIndex(2))
        assertFailsWith<IllegalArgumentException> { EnchantmentButton.fromIndex(3) }
        assertFailsWith<IllegalArgumentException> {
            MenuHostInput.MerchantTradeSelected(player, revision = 1, index = -1)
        }

        val recipe = Key.key("minecraft", "stone_stairs")
        assertEquals(
            recipe,
            MenuHostInput.StonecutterRecipeSelected(player, revision = 2, recipe = recipe).recipe,
        )
    }

    private fun assertHandler(
        kind: MenuHostInputKind,
        concurrency: MenuActionConcurrency,
        content: context(MenuScope) () -> Unit,
    ) {
        val declaration = tree(content).hostActions.getValue(kind)
        assertEquals(concurrency, declaration.concurrency)
        assertEquals(kind, declaration.identity.kind)
    }

    private fun tree(content: context(MenuScope) () -> Unit): RenderTree {
        val builder = MenuTreeBuilder(
            stateStore = MenuStateStore {},
            boundaryFailures = linkedMapOf(),
            clearBoundary = {},
            invalidate = {},
            navigationStates = linkedMapOf(),
            closeSession = {},
        )
        val root = builder.root()
        context(root) { content() }
        return builder.build()
    }
}
