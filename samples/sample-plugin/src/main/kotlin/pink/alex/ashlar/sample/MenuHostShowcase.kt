package pink.alex.ashlar.sample

import pink.alex.ashlar.items.ItemSpec
import pink.alex.ashlar.items.item
import pink.alex.ashlar.menus.AnvilSlot
import pink.alex.ashlar.menus.BeaconSlot
import pink.alex.ashlar.menus.BrewingSlot
import pink.alex.ashlar.menus.CartographySlot
import pink.alex.ashlar.menus.CrafterSlot
import pink.alex.ashlar.menus.CraftingSlot
import pink.alex.ashlar.menus.EnchantmentSlot
import pink.alex.ashlar.menus.FurnaceSlot
import pink.alex.ashlar.menus.GrindstoneSlot
import pink.alex.ashlar.menus.LecternSlot
import pink.alex.ashlar.menus.LoomSlot
import pink.alex.ashlar.menus.MenuFeedback
import pink.alex.ashlar.menus.MenuFeedbackSeverity
import pink.alex.ashlar.menus.MenuScope
import pink.alex.ashlar.menus.MerchantOfferSnapshot
import pink.alex.ashlar.menus.MerchantSlot
import pink.alex.ashlar.menus.SmithingSlot
import pink.alex.ashlar.menus.StonecutterSlot
import pink.alex.ashlar.menus.anvil
import pink.alex.ashlar.menus.beacon
import pink.alex.ashlar.menus.blastFurnace
import pink.alex.ashlar.menus.brewing
import pink.alex.ashlar.menus.cartography
import pink.alex.ashlar.menus.chest
import pink.alex.ashlar.menus.crafter
import pink.alex.ashlar.menus.crafting
import pink.alex.ashlar.menus.enchantment
import pink.alex.ashlar.menus.furnace
import pink.alex.ashlar.menus.generic3x3
import pink.alex.ashlar.menus.grindstone
import pink.alex.ashlar.menus.hopper
import pink.alex.ashlar.menus.lectern
import pink.alex.ashlar.menus.loom
import pink.alex.ashlar.menus.merchant
import pink.alex.ashlar.menus.onBeaconEffectsSelected
import pink.alex.ashlar.menus.onEnchantmentButton
import pink.alex.ashlar.menus.onPageChanged
import pink.alex.ashlar.menus.onPatternSelected
import pink.alex.ashlar.menus.onRecipeSelected
import pink.alex.ashlar.menus.onTradeSelected
import pink.alex.ashlar.menus.renameText
import pink.alex.ashlar.menus.shulker
import pink.alex.ashlar.menus.slot
import pink.alex.ashlar.menus.smithing
import pink.alex.ashlar.menus.smoker
import pink.alex.ashlar.menus.state
import pink.alex.ashlar.menus.stonecutter
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.inventory.meta.BookMeta

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
            slot(LecternSlot.BOOK) { item = lecternBook }
            onPageChanged { feedback(report("page", it.page)) }
        }
    }
}

private val backIcon: ItemSpec = hostIcon(Material.ARROW, "back to host index")

private val lecternBook: ItemSpec = item(Material.WRITTEN_BOOK) {
    name = Component.text("Lectern host", NamedTextColor.AQUA)
    paper("menu-host-showcase-book-v1") { stack ->
        stack.editMeta(BookMeta::class.java) { book ->
            book.title(Component.text("Ashlar menus"))
            book.author(Component.text("Ashlar"))
            book.addPages(
                Component.text("This is a real lectern host."),
                Component.text("Turn the page to test typed page input."),
            )
        }
    }
}

private fun hostIcon(material: Material, label: String): ItemSpec = item(material) {
    name = Component.text(label, NamedTextColor.AQUA)
}
