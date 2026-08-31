package dev.placeholder.framework.menus.internal.paper

import dev.placeholder.framework.menus.*
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaperHostCatalogueTest {
    private val title = Component.text("Host")

    @Test
    fun `every semantic host has one pinned Paper menu kind`() {
        val mappings = mapOf<MenuHostSnapshot, PaperMenuKind>(
            MenuHostSnapshot.Chest(ChestHostSnapshot(title, 6, emptyList())) to PaperMenuKind.GENERIC_9X6,
            HopperHostSnapshot(title, emptyList()) to PaperMenuKind.HOPPER,
            Generic3x3HostSnapshot(title, emptyList()) to PaperMenuKind.GENERIC_3X3,
            ShulkerHostSnapshot(title, emptyList()) to PaperMenuKind.SHULKER_BOX,
            AnvilHostSnapshot(title, emptyList()) to PaperMenuKind.ANVIL,
            MerchantHostSnapshot(title, emptyList(), emptyList()) to PaperMenuKind.MERCHANT,
            FurnaceHostSnapshot(title, emptyList()) to PaperMenuKind.FURNACE,
            FurnaceHostSnapshot(title, emptyList(), FurnaceHostKind.BLAST_FURNACE) to PaperMenuKind.BLAST_FURNACE,
            FurnaceHostSnapshot(title, emptyList(), FurnaceHostKind.SMOKER) to PaperMenuKind.SMOKER,
            BrewingHostSnapshot(title, emptyList()) to PaperMenuKind.BREWING_STAND,
            CraftingHostSnapshot(title, emptyList()) to PaperMenuKind.CRAFTING,
            CrafterHostSnapshot(title, emptyList()) to PaperMenuKind.CRAFTER_3X3,
            EnchantmentHostSnapshot(title, emptyList(), seed = 1) to PaperMenuKind.ENCHANTMENT,
            GrindstoneHostSnapshot(title, emptyList()) to PaperMenuKind.GRINDSTONE,
            SmithingHostSnapshot(title, emptyList()) to PaperMenuKind.SMITHING,
            LoomHostSnapshot(title, emptyList()) to PaperMenuKind.LOOM,
            CartographyHostSnapshot(title, emptyList()) to PaperMenuKind.CARTOGRAPHY_TABLE,
            StonecutterHostSnapshot(title, emptyList()) to PaperMenuKind.STONECUTTER,
            BeaconHostSnapshot(title, emptyList()) to PaperMenuKind.BEACON,
            LecternHostSnapshot(title, emptyList()) to PaperMenuKind.LECTERN,
        )

        mappings.forEach { (host, expected) ->
            assertEquals(expected, PaperChestPresentation.nativeKind(host))
        }
    }

    @Test
    fun `kind and title changes remount while property changes update in place`() {
        val furnace = FurnaceHostSnapshot(title, emptyList(), cooking = MenuProgress(1, 10))
        val progressed = furnace.copy(cooking = MenuProgress(2, 10))
        val renamed = furnace.copy(title = Component.text("Renamed"))
        val smoker = furnace.copy(kind = FurnaceHostKind.SMOKER)

        assertTrue(!PaperChestPresentation.requiresRemount(furnace, progressed))
        assertTrue(PaperChestPresentation.requiresRemount(furnace, renamed))
        assertTrue(PaperChestPresentation.requiresRemount(furnace, smoker))
    }
}
