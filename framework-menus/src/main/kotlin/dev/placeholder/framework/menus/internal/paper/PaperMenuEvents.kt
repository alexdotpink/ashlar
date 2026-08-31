package dev.placeholder.framework.menus.internal.paper

import dev.placeholder.framework.execution.PlayerRef
import dev.placeholder.framework.items.ItemSnapshot
import dev.placeholder.framework.items.Items
import dev.placeholder.framework.menus.EnchantmentButton
import dev.placeholder.framework.menus.LecternPageDirection
import dev.placeholder.framework.menus.MenuHostInput
import dev.placeholder.framework.menus.MenuInteraction
import io.papermc.paper.event.player.PlayerChangeBeaconEffectEvent
import io.papermc.paper.event.player.PlayerLecternPageChangeEvent
import io.papermc.paper.event.player.PlayerLoomPatternSelectEvent
import io.papermc.paper.event.player.PlayerStonecutterRecipeSelectEvent
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import dev.placeholder.framework.menus.storage.MenuDragMode
import dev.placeholder.framework.menus.storage.PlayerInventorySection
import java.util.IdentityHashMap
import java.util.UUID
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.event.inventory.TradeSelectEvent
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.view.AnvilView
import org.bukkit.plugin.Plugin

/** Native close categories kept private until the logical session chooses its public outcome. */
internal enum class PaperMenuCloseReason {
    PLAYER_CLOSED,
    EXTERNAL_REPLACEMENT,
    DISCONNECTED,
    KICKED,
    DIED,
    NATIVE_UNAVAILABLE,
}

/** One registered native chest view. The logical session remains owned by menu core. */
internal class PaperMenuViewBinding internal constructor(
    private val events: PaperMenuEvents,
    internal val view: InventoryView,
) {
    /** Prevents this view's close event from ending the logical session during remount or teardown. */
    fun suppressClose() {
        events.suppressClose(view)
    }

    /** Removes a view which is no longer capable of receiving native input. */
    fun unbind() {
        events.unbind(view)
    }
}

/** Synchronous Paper event boundary for framework-owned menu views. */
internal class PaperMenuEvents private constructor(
    private val plugin: Plugin?,
    register: Boolean,
) : Listener, AutoCloseable {
    private val lock = Any()
    private val bindings: IdentityHashMap<InventoryView, Binding> = IdentityHashMap()
    private val topBindings: IdentityHashMap<Inventory, Binding> = IdentityHashMap()
    private val kickedPlayers: MutableSet<UUID> = mutableSetOf()
    private var closed: Boolean = false

    init {
        if (register) {
            requireNotNull(plugin).server.pluginManager.registerEvents(this, plugin)
        }
    }

    constructor(plugin: Plugin) : this(plugin, register = true)

    internal constructor() : this(null, register = false)

    fun bind(
        view: InventoryView,
        player: PlayerRef,
        revision: () -> Long,
        playerInventorySections: () -> Set<PlayerInventorySection> = { emptySet() },
        interaction: (MenuInteraction) -> Unit,
        hostInput: (MenuHostInput) -> Unit = {},
        nativeClose: (PaperMenuCloseReason, ItemSnapshot?) -> Unit,
    ): PaperMenuViewBinding = synchronized(lock) {
        check(!closed) { "The Paper menu event adapter is closed" }
        require(view.player.uniqueId == player.uniqueId) { "The menu view belongs to another player" }
        val binding = Binding(
            view,
            player,
            revision,
            playerInventorySections,
            interaction,
            hostInput,
            nativeClose,
            lastAnvilRenameText = (view as? AnvilView)?.renameText,
        )
        check(bindings[view] == null && topBindings[view.topInventory] == null) {
            "The Paper menu view is already bound"
        }
        bindings[view] = binding
        topBindings[view.topInventory] = binding
        PaperMenuViewBinding(this, view)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onClick(event: InventoryClickEvent) {
        val binding = binding(event.view) ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (player.uniqueId != binding.player.uniqueId) return
        val topCapacity = event.view.topInventory.size
        val outside = event.rawSlot == InventoryView.OUTSIDE
        val slot = event.rawSlot.takeIf { raw -> raw in 0 until topCapacity }
        val playerSlot = event.rawSlot
            .takeIf { raw -> raw >= topCapacity }
            ?.let(event.view::convertSlot)
            ?.let(PaperMenuGestureMapper::playerSlot)
        if (slot == null && playerSlot == null && !outside) return
        val gesture = PaperMenuGestureMapper.click(event.click, event.hotbarButton, outside) ?: return
        binding.interaction(
            MenuInteraction(
                player = binding.player,
                revision = binding.revision(),
                slot = slot,
                playerSlot = playerSlot,
                playerInventory = playerInventory(player, binding.playerInventorySections()),
                gesture = gesture,
                clicked = snapshot(event.currentItem),
                cursor = snapshot(event.cursor),
            ),
        )
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDrag(event: InventoryDragEvent) {
        val binding = binding(event.view) ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (player.uniqueId != binding.player.uniqueId) return
        val mode = when (event.type) {
            org.bukkit.event.inventory.DragType.EVEN -> MenuDragMode.EVEN
            org.bukkit.event.inventory.DragType.SINGLE -> MenuDragMode.SINGLE
        }
        val topCapacity = event.view.topInventory.size
        val hostSlots = event.rawSlots.filter { raw -> raw in 0 until topCapacity }.sorted()
        val playerSlots = event.rawSlots.asSequence()
            .filter { raw -> raw >= topCapacity }
            .sorted()
            .map(event.view::convertSlot)
            .mapNotNull(PaperMenuGestureMapper::playerSlot)
            .distinct()
            .toList()
        val gesture = PaperMenuGestureMapper.drag(hostSlots, playerSlots, mode) ?: return
        binding.interaction(
            MenuInteraction(
                player = binding.player,
                revision = binding.revision(),
                slot = null,
                hostSlots = hostSlots,
                playerSlots = playerSlots,
                playerInventory = playerInventory(player, binding.playerInventorySections()),
                gesture = gesture,
                cursor = snapshot(event.oldCursor),
            ),
        )
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPrepareAnvil(event: PrepareAnvilEvent) {
        val text = event.view.renameText.orEmpty()
        val binding = changedAnvilRename(event.view, text) ?: return
        binding.hostInput(MenuHostInput.AnvilRenameText(binding.player, binding.revision(), text))
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onTradeSelected(event: TradeSelectEvent) {
        val binding = binding(event.view) ?: return
        event.isCancelled = true
        binding.hostInput(
            MenuHostInput.MerchantTradeSelected(binding.player, binding.revision(), event.index),
        )
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onLoomPatternSelected(event: PlayerLoomPatternSelectEvent) {
        val binding = binding(event.loomInventory) ?: return
        event.isCancelled = true
        if (event.player.uniqueId != binding.player.uniqueId) return
        binding.hostInput(
            MenuHostInput.LoomPatternSelected(
                binding.player,
                binding.revision(),
                RegistryAccess.registryAccess()
                    .getRegistry(RegistryKey.BANNER_PATTERN)
                    .getKeyOrThrow(event.patternType),
            ),
        )
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onStonecutterRecipeSelected(event: PlayerStonecutterRecipeSelectEvent) {
        val binding = binding(event.stonecutterInventory) ?: return
        event.isCancelled = true
        if (event.player.uniqueId != binding.player.uniqueId) return
        binding.hostInput(
            MenuHostInput.StonecutterRecipeSelected(
                binding.player,
                binding.revision(),
                event.getStonecuttingRecipe().key(),
            ),
        )
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onEnchantmentButton(event: EnchantItemEvent) {
        val binding = binding(event.view) ?: return
        event.isCancelled = true
        if (event.enchanter.uniqueId != binding.player.uniqueId) return
        binding.hostInput(
            MenuHostInput.EnchantmentButtonPressed(
                binding.player,
                binding.revision(),
                EnchantmentButton.fromIndex(event.whichButton()),
            ),
        )
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBeaconEffectsSelected(event: PlayerChangeBeaconEffectEvent) {
        val binding = binding(event.player.openInventory) ?: return
        event.isCancelled = true
        if (event.player.uniqueId != binding.player.uniqueId) return
        binding.hostInput(
            MenuHostInput.BeaconEffectsSelected(
                binding.player,
                binding.revision(),
                event.primary?.key(),
                event.secondary?.key(),
                event.willConsumeItem(),
            ),
        )
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onLecternPageChanged(event: PlayerLecternPageChangeEvent) {
        val binding = binding(event.player.openInventory) ?: return
        event.isCancelled = true
        if (event.player.uniqueId != binding.player.uniqueId) return
        binding.hostInput(
            MenuHostInput.LecternPageChanged(
                binding.player,
                binding.revision(),
                event.oldPage,
                event.newPage,
                when (event.pageChangeDirection) {
                    PlayerLecternPageChangeEvent.PageChangeDirection.LEFT -> LecternPageDirection.PREVIOUS
                    PlayerLecternPageChangeEvent.PageChangeDirection.RIGHT -> LecternPageDirection.NEXT
                },
            ),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(event: InventoryCloseEvent) {
        val binding = remove(event.view) ?: return
        if (binding.suppressClose) return
        val cursor = snapshot(event.view.cursor)
        event.view.setCursor(null)
        binding.nativeClose(closeReason(event.reason, binding.player.uniqueId), cursor)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onKick(event: PlayerKickEvent) {
        synchronized(lock) { kickedPlayers += event.player.uniqueId }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val playerId = event.player.uniqueId
        val reason = synchronized(lock) {
            if (kickedPlayers.remove(playerId)) PaperMenuCloseReason.KICKED else PaperMenuCloseReason.DISCONNECTED
        }
        removePlayer(playerId).forEach { binding ->
            if (!binding.suppressClose) {
                val cursor = snapshot(binding.view.cursor)
                binding.view.setCursor(null)
                binding.nativeClose(reason, cursor)
            }
        }
    }

    override fun close() {
        val remaining = synchronized(lock) {
            if (closed) return
            closed = true
            bindings.values.distinctBy(System::identityHashCode).also {
                bindings.clear()
                topBindings.clear()
                kickedPlayers.clear()
            }
        }
        HandlerList.unregisterAll(this)
        remaining.forEach { binding ->
            if (!binding.suppressClose) {
                val cursor = snapshot(binding.view.cursor)
                binding.view.setCursor(null)
                binding.nativeClose(PaperMenuCloseReason.NATIVE_UNAVAILABLE, cursor)
            }
        }
    }

    internal fun suppressClose(view: InventoryView) {
        synchronized(lock) { findBinding(view)?.suppressClose = true }
    }

    internal fun unbind(view: InventoryView) {
        remove(view)
    }

    private fun binding(view: InventoryView): Binding? = synchronized(lock) { findBinding(view) }

    private fun binding(inventory: Inventory): Binding? = synchronized(lock) { topBindings[inventory] }

    private fun changedAnvilRename(view: InventoryView, text: String): Binding? = synchronized(lock) {
        val binding = findBinding(view) ?: return@synchronized null
        if (binding.lastAnvilRenameText == text) return@synchronized null
        binding.lastAnvilRenameText = text
        binding
    }

    private fun remove(view: InventoryView): Binding? = synchronized(lock) {
        val binding = findBinding(view) ?: return@synchronized null
        bindings.entries.removeIf { entry -> entry.value === binding }
        topBindings.entries.removeIf { entry -> entry.value === binding }
        binding
    }

    private fun findBinding(view: InventoryView): Binding? = bindings[view] ?: topBindings[view.topInventory]

    private fun removePlayer(playerId: UUID): List<Binding> = synchronized(lock) {
        buildList {
            val iterator = bindings.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value.player.uniqueId == playerId) {
                    add(entry.value)
                    topBindings.entries.removeIf { top -> top.value === entry.value }
                    iterator.remove()
                }
            }
        }
    }

    private fun closeReason(reason: InventoryCloseEvent.Reason, playerId: UUID): PaperMenuCloseReason = when (reason) {
        InventoryCloseEvent.Reason.PLAYER -> PaperMenuCloseReason.PLAYER_CLOSED
        InventoryCloseEvent.Reason.OPEN_NEW,
        InventoryCloseEvent.Reason.PLUGIN,
        InventoryCloseEvent.Reason.UNKNOWN,
        -> PaperMenuCloseReason.EXTERNAL_REPLACEMENT
        InventoryCloseEvent.Reason.DISCONNECT -> synchronized(lock) {
            if (kickedPlayers.remove(playerId)) PaperMenuCloseReason.KICKED else PaperMenuCloseReason.DISCONNECTED
        }
        InventoryCloseEvent.Reason.DEATH -> PaperMenuCloseReason.DIED
        InventoryCloseEvent.Reason.CANT_USE,
        InventoryCloseEvent.Reason.UNLOADED,
        -> PaperMenuCloseReason.NATIVE_UNAVAILABLE
        @Suppress("DEPRECATION")
        InventoryCloseEvent.Reason.TELEPORT -> PaperMenuCloseReason.EXTERNAL_REPLACEMENT
    }

    private data class Binding(
        val view: InventoryView,
        val player: PlayerRef,
        val revision: () -> Long,
        val playerInventorySections: () -> Set<PlayerInventorySection>,
        val interaction: (MenuInteraction) -> Unit,
        val hostInput: (MenuHostInput) -> Unit,
        val nativeClose: (PaperMenuCloseReason, ItemSnapshot?) -> Unit,
        var lastAnvilRenameText: String?,
        var suppressClose: Boolean = false,
    )
}

private fun snapshot(stack: ItemStack?): ItemSnapshot? =
    stack?.takeUnless(ItemStack::isEmpty)?.let(Items::capture)

private fun playerInventory(
    player: Player,
    sections: Set<PlayerInventorySection>,
): Map<PlayerInventorySection, List<ItemSnapshot?>> = sections.associateWith { section ->
    val indexes = when (section) {
        PlayerInventorySection.HOTBAR -> 0..8
        PlayerInventorySection.MAIN -> 9..35
        PlayerInventorySection.ARMOR -> 36..39
        PlayerInventorySection.OFFHAND -> 40..40
    }
    indexes.map { index -> snapshot(player.inventory.getItem(index)) }
}
