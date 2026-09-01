package pink.alex.ashlar.menus

import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MenuHostCatalogueTest {
    private val title = Component.text("Host")

    @Test
    fun `fixed hosts expose their exact native capacities`() {
        assertEquals(5, HopperHostSnapshot(title, emptyList()).capacity)
        assertEquals(9, Generic3x3HostSnapshot(title, emptyList()).capacity)
        assertEquals(27, ShulkerHostSnapshot(title, emptyList()).capacity)
        assertEquals(3, AnvilHostSnapshot(title, emptyList()).capacity)
        assertEquals(3, MerchantHostSnapshot(title, emptyList(), emptyList()).capacity)
        assertEquals(3, FurnaceHostSnapshot(title, emptyList()).capacity)
        assertEquals(5, BrewingHostSnapshot(title, emptyList()).capacity)
        assertEquals(10, CraftingHostSnapshot(title, emptyList()).capacity)
        assertEquals(9, CrafterHostSnapshot(title, emptyList()).capacity)
        assertEquals(2, EnchantmentHostSnapshot(title, emptyList(), seed = 1).capacity)
        assertEquals(3, GrindstoneHostSnapshot(title, emptyList()).capacity)
        assertEquals(4, SmithingHostSnapshot(title, emptyList()).capacity)
        assertEquals(4, LoomHostSnapshot(title, emptyList()).capacity)
        assertEquals(3, CartographyHostSnapshot(title, emptyList()).capacity)
        assertEquals(2, StonecutterHostSnapshot(title, emptyList()).capacity)
        assertEquals(1, BeaconHostSnapshot(title, emptyList()).capacity)
        assertEquals(1, LecternHostSnapshot(title, emptyList()).capacity)
    }

    @Test
    fun `host slot validation uses the concrete capacity`() {
        val outside = MenuSlotSnapshot(
            index = 5,
            owner = MenuComponentPath("test"),
            item = null,
            actions = emptySet(),
        )

        assertFailsWith<IllegalArgumentException> {
            HopperHostSnapshot(title, listOf(outside))
        }
    }

    @Test
    fun `specialized properties reject invalid native state`() {
        assertFailsWith<IllegalArgumentException> { MenuProgress(-1, 20) }
        assertFailsWith<IllegalArgumentException> { MenuProgress(1, 0) }
        assertFailsWith<IllegalArgumentException> {
            BrewingHostSnapshot(title, emptyList(), recipeBrewTime = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            EnchantmentHostSnapshot(title, emptyList(), seed = 1, offers = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            LecternHostSnapshot(title, emptyList(), page = -1)
        }
    }

    @Test
    fun `typed slot roles match the pinned native layout`() {
        assertEquals(listOf(0, 1, 2), AnvilSlot.entries.map(AnvilSlot::index))
        assertEquals(listOf(0, 1, 2), FurnaceSlot.entries.map(FurnaceSlot::index))
        assertEquals(listOf(0, 1, 2, 3, 4), BrewingSlot.entries.map(BrewingSlot::index))
        assertEquals((0..9).toList(), CraftingSlot.entries.map(CraftingSlot::index))
        assertEquals((0..8).toList(), CrafterSlot.entries.map(CrafterSlot::index))
    }
}
