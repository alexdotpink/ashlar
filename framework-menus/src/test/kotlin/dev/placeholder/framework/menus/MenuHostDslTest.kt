package dev.placeholder.framework.menus

import dev.placeholder.framework.menus.storage.MenuStorageId
import dev.placeholder.framework.menus.storage.localMenuStorage
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MenuHostDslTest {
    private val title = Component.text("Typed host")

    @Test
    fun `container DSLs retain their concrete host identity`() {
        assertIs<HopperHostSnapshot>(hostSnapshot { hopper(title) {} })
        assertIs<Generic3x3HostSnapshot>(hostSnapshot { generic3x3(title) {} })
        assertIs<ShulkerHostSnapshot>(hostSnapshot { shulker(title) {} })
    }

    @Test
    fun `specialized DSLs map roles to native slots`() {
        val anvil = assertIs<AnvilHostSnapshot>(hostSnapshot {
            anvil(title, repairCost = 7) {
                slot(AnvilSlot.RESULT) {}
            }
        })
        assertEquals(7, anvil.repairCost)
        assertEquals(listOf(AnvilSlot.RESULT.index), anvil.slots.map(MenuSlotSnapshot::index))

        val brewing = assertIs<BrewingHostSnapshot>(hostSnapshot {
            brewing(title, fuelLevel = 12, brewingTicks = 30) {
                slot(BrewingSlot.INGREDIENT) {}
            }
        })
        assertEquals(12, brewing.fuelLevel)
        assertEquals(listOf(BrewingSlot.INGREDIENT.index), brewing.slots.map(MenuSlotSnapshot::index))
    }

    @Test
    fun `every specialized DSL produces its typed snapshot`() {
        assertIs<MerchantHostSnapshot>(hostSnapshot { merchant(title, emptyList()) {} })
        assertIs<FurnaceHostSnapshot>(hostSnapshot { furnace(title) {} })
        assertIs<FurnaceHostSnapshot>(hostSnapshot { blastFurnace(title) {} })
        assertIs<FurnaceHostSnapshot>(hostSnapshot { smoker(title) {} })
        assertIs<CraftingHostSnapshot>(hostSnapshot { crafting(title) {} })
        assertIs<CrafterHostSnapshot>(hostSnapshot { crafter(title) {} })
        assertIs<EnchantmentHostSnapshot>(hostSnapshot { enchantment(title, seed = 42) {} })
        assertIs<GrindstoneHostSnapshot>(hostSnapshot { grindstone(title) {} })
        assertIs<SmithingHostSnapshot>(hostSnapshot { smithing(title) {} })
        assertIs<LoomHostSnapshot>(hostSnapshot { loom(title) {} })
        assertIs<CartographyHostSnapshot>(hostSnapshot { cartography(title) {} })
        assertIs<StonecutterHostSnapshot>(hostSnapshot { stonecutter(title) {} })
        assertIs<BeaconHostSnapshot>(hostSnapshot { beacon(title) {} })
        assertIs<LecternHostSnapshot>(hostSnapshot { lectern(title) {} })
    }

    @Test
    fun `storage binds to non-chest hosts through the shared host scope`() {
        val model = localMenuStorage(MenuStorageId("test", "hopper"), List(5) { null })
        val hopper = assertIs<HopperHostSnapshot>(hostSnapshot {
            hopper(title) {
                storage(model, SlotRegion.of(0 until 5))
            }
        })

        assertEquals(5, hopper.slots.size)
        assertEquals(model.id, hopper.slots.first().storage?.storage)
    }

    private fun hostSnapshot(content: context(MenuScope) () -> Unit): MenuHostSnapshot {
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
        val tree = builder.build()
        val slots = tree.host.slots.values.sortedBy(RenderedSlot::index).map { slot ->
            MenuSlotSnapshot(
                index = slot.index,
                owner = slot.owner.semantic(),
                item = slot.item,
                storedItem = slot.storedItem,
                storage = slot.storage,
                actions = slot.actions.keys,
                modifiers = slot.modifiers,
            )
        }
        return tree.host.host.snapshot(slots)
    }
}
