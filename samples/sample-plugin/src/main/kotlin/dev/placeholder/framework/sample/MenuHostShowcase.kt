package dev.placeholder.framework.sample

import dev.placeholder.framework.items.ItemSpec
import dev.placeholder.framework.items.item
import dev.placeholder.framework.menus.AnvilSlot
import dev.placeholder.framework.menus.BeaconSlot
import dev.placeholder.framework.menus.BrewingSlot
import dev.placeholder.framework.menus.CartographySlot
import dev.placeholder.framework.menus.CrafterSlot
import dev.placeholder.framework.menus.CraftingSlot
import dev.placeholder.framework.menus.EnchantmentSlot
import dev.placeholder.framework.menus.FurnaceSlot
import dev.placeholder.framework.menus.GrindstoneSlot
import dev.placeholder.framework.menus.LecternSlot
import dev.placeholder.framework.menus.LoomSlot
import dev.placeholder.framework.menus.MenuFeedback
import dev.placeholder.framework.menus.MenuFeedbackSeverity
import dev.placeholder.framework.menus.MenuScope
import dev.placeholder.framework.menus.MerchantOfferSnapshot
import dev.placeholder.framework.menus.MerchantSlot
import dev.placeholder.framework.menus.SmithingSlot
import dev.placeholder.framework.menus.StonecutterSlot
import dev.placeholder.framework.menus.anvil
import dev.placeholder.framework.menus.beacon
import dev.placeholder.framework.menus.blastFurnace
import dev.placeholder.framework.menus.brewing
import dev.placeholder.framework.menus.cartography
import dev.placeholder.framework.menus.chest
import dev.placeholder.framework.menus.crafter
import dev.placeholder.framework.menus.crafting
import dev.placeholder.framework.menus.enchantment
import dev.placeholder.framework.menus.furnace
import dev.placeholder.framework.menus.generic3x3
import dev.placeholder.framework.menus.grindstone
import dev.placeholder.framework.menus.hopper
import dev.placeholder.framework.menus.lectern
import dev.placeholder.framework.menus.loom
import dev.placeholder.framework.menus.merchant
import dev.placeholder.framework.menus.onBeaconEffectsSelected
import dev.placeholder.framework.menus.onEnchantmentButton
import dev.placeholder.framework.menus.onPageChanged
import dev.placeholder.framework.menus.onPatternSelected
import dev.placeholder.framework.menus.onRecipeSelected
import dev.placeholder.framework.menus.onTradeSelected
import dev.placeholder.framework.menus.renameText
import dev.placeholder.framework.menus.shulker
import dev.placeholder.framework.menus.slot
import dev.placeholder.framework.menus.smithing
import dev.placeholder.framework.menus.smoker
import dev.placeholder.framework.menus.state
import dev.placeholder.framework.menus.stonecutter
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material

private enum class NativeHostPage {
    INDEX,
    HOPPER,
    GENERIC_3X3,
    SHULKER,
    ANVIL,
    MERCHANT,
    FURNACE,
    BLAST_FURNACE,
    SMOKER,
    BREWING,
    CRAFTING,
    CRAFTER,
    ENCHANTMENT,
    GRINDSTONE,
    SMITHING,
    LOOM,
    CARTOGRAPHY,
    STONECUTTER,
    BEACON,
    LECTERN,
}

private val hostEntries: List<Pair<NativeHostPage, Material>> = listOf(
    NativeHostPage.HOPPER to Material.HOPPER,
    NativeHostPage.GENERIC_3X3 to Material.DROPPER,
    NativeHostPage.SHULKER to Material.SHULKER_BOX,
    NativeHostPage.ANVIL to Material.ANVIL,
    NativeHostPage.MERCHANT to Material.EMERALD,
    NativeHostPage.FURNACE to Material.FURNACE,
    NativeHostPage.BLAST_FURNACE to Material.BLAST_FURNACE,
    NativeHostPage.SMOKER to Material.SMOKER,
    NativeHostPage.BREWING to Material.BREWING_STAND,
    NativeHostPage.CRAFTING to Material.CRAFTING_TABLE,
    NativeHostPage.CRAFTER to Material.CRAFTER,
    NativeHostPage.ENCHANTMENT to Material.ENCHANTING_TABLE,
    NativeHostPage.GRINDSTONE to Material.GRINDSTONE,
    NativeHostPage.SMITHING to Material.SMITHING_TABLE,
    NativeHostPage.LOOM to Material.LOOM,
    NativeHostPage.CARTOGRAPHY to Material.CARTOGRAPHY_TABLE,
    NativeHostPage.STONECUTTER to Material.STONECUTTER,
    NativeHostPage.BEACON to Material.BEACON,
    NativeHostPage.LECTERN to Material.LECTERN,
)

context(menu: MenuScope)
internal fun NativeHostShowcase() {
    var page by state(NativeHostPage.INDEX)
    fun back() { page = NativeHostPage.INDEX }
    fun report(name: String, value: Any?) = MenuFeedback(
        Component.text("$name: $value", NamedTextColor.GOLD),
        MenuFeedbackSeverity.INFO,
    )

    when (page) {
        NativeHostPage.INDEX -> chest("Every native host", rows = 3) {
            hostEntries.forEachIndexed { index, (target, material) ->
                slot(index) {
                    item = hostIcon(material, target.name.lowercase().replace('_', ' '))
                    onPrimary { page = target }
                }
            }
            slot(26) {
                item = hostIcon(Material.BARRIER, "close")
                onPrimary { close() }
            }
        }
        NativeHostPage.HOPPER -> hopper(Component.text("Hopper host")) {
            slot(0) { item = backIcon; onPrimary { back() } }
        }
        NativeHostPage.GENERIC_3X3 -> generic3x3(Component.text("Generic 3x3 host")) {
            slot(0) { item = backIcon; onPrimary { back() } }
        }
        NativeHostPage.SHULKER -> shulker(Component.text("Shulker host")) {
            slot(0) { item = backIcon; onPrimary { back() } }
        }
        NativeHostPage.ANVIL -> anvil(Component.text("Anvil host"), repairCost = 3) {
            slot(AnvilSlot.LEFT) { item = backIcon; onPrimary { back() } }
            renameText { feedback(report("rename", it.text)) }
        }
        NativeHostPage.MERCHANT -> merchant(
            Component.text("Merchant host"),
            offers = listOf(MerchantOfferSnapshot(hostIcon(Material.DIAMOND, "cost"), hostIcon(Material.EMERALD, "result"))),
        ) {
            slot(MerchantSlot.FIRST_COST) { item = backIcon; onPrimary { back() } }
            onTradeSelected { feedback(report("trade", it.index)) }
        }
        NativeHostPage.FURNACE -> furnace(Component.text("Furnace host")) {
            slot(FurnaceSlot.INPUT) { item = backIcon; onPrimary { back() } }
        }
        NativeHostPage.BLAST_FURNACE -> blastFurnace(Component.text("Blast furnace host")) {
            slot(FurnaceSlot.INPUT) { item = backIcon; onPrimary { back() } }
        }
        NativeHostPage.SMOKER -> smoker(Component.text("Smoker host")) {
            slot(FurnaceSlot.INPUT) { item = backIcon; onPrimary { back() } }
        }
        NativeHostPage.BREWING -> brewing(Component.text("Brewing host"), fuelLevel = 10) {
            slot(BrewingSlot.INGREDIENT) { item = backIcon; onPrimary { back() } }
        }
        NativeHostPage.CRAFTING -> crafting(Component.text("Crafting host")) {
            slot(CraftingSlot.GRID_ONE) { item = backIcon; onPrimary { back() } }
        }
        NativeHostPage.CRAFTER -> crafter(Component.text("Crafter host"), disabledSlots = setOf(CrafterSlot.GRID_NINE)) {
            slot(CrafterSlot.GRID_ONE) { item = backIcon; onPrimary { back() } }
        }
        NativeHostPage.ENCHANTMENT -> enchantment(Component.text("Enchantment host"), seed = 42) {
            slot(EnchantmentSlot.ITEM) { item = backIcon; onPrimary { back() } }
            onEnchantmentButton { feedback(report("enchantment button", it.button)) }
        }
        NativeHostPage.GRINDSTONE -> grindstone(Component.text("Grindstone host")) {
            slot(GrindstoneSlot.TOP) { item = backIcon; onPrimary { back() } }
        }
        NativeHostPage.SMITHING -> smithing(Component.text("Smithing host")) {
            slot(SmithingSlot.TEMPLATE) { item = backIcon; onPrimary { back() } }
        }
        NativeHostPage.LOOM -> loom(Component.text("Loom host")) {
            slot(LoomSlot.BANNER) { item = backIcon; onPrimary { back() } }
            onPatternSelected { feedback(report("pattern", it.pattern)) }
        }
        NativeHostPage.CARTOGRAPHY -> cartography(Component.text("Cartography host")) {
            slot(CartographySlot.MAP) { item = backIcon; onPrimary { back() } }
        }
        NativeHostPage.STONECUTTER -> stonecutter(Component.text("Stonecutter host")) {
            slot(StonecutterSlot.INPUT) { item = backIcon; onPrimary { back() } }
            onRecipeSelected { feedback(report("recipe", it.recipe)) }
        }
        NativeHostPage.BEACON -> beacon(Component.text("Beacon host")) {
            slot(BeaconSlot.PAYMENT) { item = backIcon; onPrimary { back() } }
            onBeaconEffectsSelected { feedback(report("beacon", "${it.primary}/${it.secondary}")) }
        }
        NativeHostPage.LECTERN -> lectern(Component.text("Lectern host")) {
            slot(LecternSlot.BOOK) { item = backIcon; onPrimary { back() } }
            onPageChanged { feedback(report("page", it.page)) }
        }
    }
}

private val backIcon: ItemSpec = hostIcon(Material.ARROW, "back to host index")

private fun hostIcon(material: Material, label: String): ItemSpec = item(material) {
    name = Component.text(label, NamedTextColor.AQUA)
}
