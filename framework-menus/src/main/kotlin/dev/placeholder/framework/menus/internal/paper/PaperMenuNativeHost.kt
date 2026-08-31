package dev.placeholder.framework.menus.internal.paper

import dev.placeholder.framework.execution.EntityOutcome
import dev.placeholder.framework.execution.PlayerRef
import dev.placeholder.framework.menus.ChestHostSnapshot
import dev.placeholder.framework.menus.MenuFeedback
import dev.placeholder.framework.menus.MenuHostSnapshot
import dev.placeholder.framework.menus.MenuInteraction
import dev.placeholder.framework.menus.MenuNativeCallbacks
import dev.placeholder.framework.menus.MenuNativeClose
import dev.placeholder.framework.menus.MenuNativeHost
import dev.placeholder.framework.menus.MenuNativeHostFactory
import dev.placeholder.framework.menus.MenuReconciliation
import dev.placeholder.framework.menus.MenuRenderSnapshot
import dev.placeholder.framework.menus.storage.MenuStorageReference
import dev.placeholder.framework.menus.storage.MenuNativeTransaction
import dev.placeholder.framework.menus.storage.MenuTransactionEmission
import dev.placeholder.framework.menus.storage.PlayerInventorySection
import dev.placeholder.framework.items.Items
import org.bukkit.inventory.InventoryView
import org.bukkit.plugin.Plugin

/** Plug-in-owned factory sharing one synchronous native event boundary across menu sessions. */
internal class PaperMenuNativeHostFactory(private val plugin: Plugin) : MenuNativeHostFactory, AutoCloseable {
    private val events = PaperMenuEvents(plugin)

    override fun create(player: PlayerRef): MenuNativeHost = PaperMenuNativeHost(plugin, player, events)

    override fun close() {
        events.close()
    }
}

private class PaperMenuNativeHost(
    private val plugin: Plugin,
    private val player: PlayerRef,
    private val events: PaperMenuEvents,
) : MenuNativeHost {
    private var render: MenuRenderSnapshot? = null
    private var callbacks: MenuNativeCallbacks? = null
    private var view: InventoryView? = null
    private var binding: PaperMenuViewBinding? = null

    override suspend fun mount(render: MenuRenderSnapshot, callbacks: MenuNativeCallbacks) {
        check(view == null) { "The native menu host is already mounted" }
        this.callbacks = callbacks
        val chest = render.chest()
        when (
            player.access(plugin) { livePlayer ->
                val created = PaperChestPresentation.create(livePlayer, chest)
                val createdBinding = bind(created, render)
                try {
                    PaperChestPresentation.open(livePlayer, created)
                } catch (failure: Throwable) {
                    createdBinding.suppressClose()
                    createdBinding.unbind()
                    throw failure
                }
                view = created
                binding = createdBinding
                this@PaperMenuNativeHost.render = render
            }
        ) {
            is EntityOutcome.Completed -> Unit
            EntityOutcome.Retired -> callbacks.closed(MenuNativeClose.DISCONNECT)
        }
    }

    override suspend fun reconcile(render: MenuRenderSnapshot, change: MenuReconciliation) {
        val currentView = checkNotNull(view) { "The native menu host is not mounted" }
        val currentBinding = checkNotNull(binding) { "The native menu host has no event binding" }
        val chest = render.chest()
        val remount = change is MenuReconciliation.Remount ||
            change is MenuReconciliation.Update && change.titleChanged
        when (
            player.access(plugin) { livePlayer ->
                if (remount) {
                    currentBinding.suppressClose()
                    val replacement = PaperChestPresentation.create(livePlayer, chest)
                    val replacementBinding = bind(replacement, render)
                    try {
                        PaperChestPresentation.open(livePlayer, replacement)
                    } catch (failure: Throwable) {
                        replacementBinding.suppressClose()
                        replacementBinding.unbind()
                        throw failure
                    } finally {
                        currentBinding.unbind()
                    }
                    view = replacement
                    binding = replacementBinding
                } else {
                    val update = change as MenuReconciliation.Update
                    PaperChestPresentation.update(livePlayer, currentView, chest, update.changedSlots)
                }
                this@PaperMenuNativeHost.render = render
            }
        ) {
            is EntityOutcome.Completed -> Unit
            EntityOutcome.Retired -> callbacks?.closed(MenuNativeClose.DISCONNECT)
        }
    }

    override suspend fun close() {
        val currentView = view ?: return
        binding?.suppressClose()
        when (
            player.access(plugin) { livePlayer ->
                PaperChestPresentation.close(livePlayer, currentView)
            }
        ) {
            is EntityOutcome.Completed,
            EntityOutcome.Retired,
            -> Unit
        }
        binding?.unbind()
        binding = null
        view = null
        render = null
        callbacks = null
    }

    override suspend fun feedback(value: MenuFeedback) {
        player.access(plugin) { livePlayer -> livePlayer.sendActionBar(value.message) }
    }

    override suspend fun commitTransaction(transaction: MenuNativeTransaction) {
        val currentView = checkNotNull(view) { "Cannot commit a transaction without a mounted menu view" }
        when (
            player.access(plugin) { livePlayer ->
                check(livePlayer.openInventory.topInventory === currentView.topInventory) {
                    "The player's native inventory changed before the menu transaction committed"
                }
                transaction.playerStorages.forEach { (section, storageId) ->
                    val snapshot = transaction.committed.snapshots[storageId] ?: return@forEach
                    val indexes = section.nativeIndexes()
                    check(snapshot.slots.size == indexes.count()) {
                        "Player section $section has ${snapshot.slots.size} items, expected ${indexes.count()}"
                    }
                    indexes.zip(snapshot.slots).forEach { (index, item) ->
                        livePlayer.inventory.setItem(index, item?.let(Items::materialize))
                    }
                }
                currentView.setCursor(transaction.committed.cursor?.let(Items::materialize))
                transaction.committed.emissions.forEach { emission ->
                    when (emission) {
                        is MenuTransactionEmission.Drop -> livePlayer.world.dropItem(
                            livePlayer.location,
                            Items.materialize(emission.item),
                        )
                    }
                }
                if (transaction.committed.requiresAcknowledgement) livePlayer.updateInventory()
            }
        ) {
            is EntityOutcome.Completed -> Unit
            EntityOutcome.Retired -> callbacks?.closed(MenuNativeClose.DISCONNECT)
        }
    }

    private fun bind(view: InventoryView, initialRender: MenuRenderSnapshot): PaperMenuViewBinding = events.bind(
        view = view,
        player = player,
        revision = { render?.revision ?: initialRender.revision },
        playerInventorySections = {
            (render ?: initialRender).storageParticipants
                .filterIsInstance<MenuStorageReference.Player>()
                .mapTo(linkedSetOf(), MenuStorageReference.Player::section)
        },
        interaction = { interaction: MenuInteraction -> callbacks?.dispatch(interaction) },
        nativeClose = { reason -> callbacks?.closed(reason.semantic()) },
    )
}

private fun PlayerInventorySection.nativeIndexes(): IntRange = when (this) {
    PlayerInventorySection.HOTBAR -> 0..8
    PlayerInventorySection.MAIN -> 9..35
    PlayerInventorySection.ARMOR -> 36..39
    PlayerInventorySection.OFFHAND -> 40..40
}

private fun MenuRenderSnapshot.chest(): ChestHostSnapshot =
    (host as? MenuHostSnapshot.Chest)?.chest
        ?: error("Paper menu adapter does not support ${host::class.simpleName}")

private fun PaperMenuCloseReason.semantic(): MenuNativeClose = when (this) {
    PaperMenuCloseReason.PLAYER_CLOSED -> MenuNativeClose.PLAYER
    PaperMenuCloseReason.EXTERNAL_REPLACEMENT,
    PaperMenuCloseReason.NATIVE_UNAVAILABLE,
    -> MenuNativeClose.EXTERNAL_INVENTORY
    PaperMenuCloseReason.DISCONNECTED -> MenuNativeClose.DISCONNECT
    PaperMenuCloseReason.KICKED -> MenuNativeClose.KICK
    PaperMenuCloseReason.DIED -> MenuNativeClose.DEATH
}
