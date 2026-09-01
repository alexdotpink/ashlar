package pink.alex.ashlar.menus.internal.paper

import pink.alex.ashlar.execution.EntityOutcome
import pink.alex.ashlar.execution.PlayerRef
import pink.alex.ashlar.menus.MenuFeedback
import pink.alex.ashlar.menus.MenuFeedbackPresentation
import pink.alex.ashlar.menus.MenuInteraction
import pink.alex.ashlar.menus.MenuNativeCallbacks
import pink.alex.ashlar.menus.MenuNativeClose
import pink.alex.ashlar.menus.MenuNativeHost
import pink.alex.ashlar.menus.MenuNativeHostFactory
import pink.alex.ashlar.menus.MenuReconciliation
import pink.alex.ashlar.menus.MenuRenderSnapshot
import pink.alex.ashlar.menus.storage.MenuStorageReference
import pink.alex.ashlar.menus.storage.MenuNativeCommit
import pink.alex.ashlar.menus.storage.MenuNativeTransaction
import pink.alex.ashlar.menus.storage.MenuTransactionEmission
import pink.alex.ashlar.menus.storage.PlayerInventorySection
import pink.alex.ashlar.items.Items
import java.util.UUID
import java.util.logging.Level
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/** Plug-in-owned factory sharing one synchronous native event boundary across menu sessions. */
internal class PaperMenuNativeHostFactory(
    private val plugin: Plugin,
    private val settlement: PaperMenuPlayerSettlement,
) : MenuNativeHostFactory, AutoCloseable {
    private val events = PaperMenuEvents(plugin)

    override fun create(player: PlayerRef): MenuNativeHost = PaperMenuNativeHost(plugin, player, events, settlement)

    override fun close() {
        events.close()
    }
}

private class PaperMenuNativeHost(
    private val plugin: Plugin,
    private val player: PlayerRef,
    private val events: PaperMenuEvents,
    private val settlement: PaperMenuPlayerSettlement,
) : MenuNativeHost {
    private val cursorSettlementId: UUID = UUID.randomUUID()
    private var render: MenuRenderSnapshot? = null
    private var callbacks: MenuNativeCallbacks? = null
    private var view: InventoryView? = null
    private var binding: PaperMenuViewBinding? = null
    private var logicalCursor: pink.alex.ashlar.items.ItemSnapshot? = null
    private var nativeEnded: Boolean = false
    private var retainedCursor: Boolean = false

    override suspend fun mount(render: MenuRenderSnapshot, callbacks: MenuNativeCallbacks) {
        check(view == null) { "The native menu host is already mounted" }
        settlement.deliverPending(player)
        this.callbacks = callbacks
        val host = render.host
        when (
            player.access(plugin) { livePlayer ->
                val created = PaperChestPresentation.create(livePlayer, host)
                val createdBinding = bind(created, render)
                try {
                    PaperChestPresentation.open(livePlayer, created)
                } catch (failure: Throwable) {
                    createdBinding.suppressClose()
                    createdBinding.unbind()
                    throw failure
                }
                if (retainedCursor) {
                    created.setCursor(logicalCursor?.let(Items::materialize))
                } else {
                    logicalCursor = created.cursor.takeUnless(ItemStack::isEmpty)?.let(Items::capture)
                }
                view = created
                binding = createdBinding
                nativeEnded = false
                retainedCursor = false
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
        val host = render.host
        val previousHost = checkNotNull(this.render).host
        val remount = change is MenuReconciliation.Remount ||
            PaperChestPresentation.requiresRemount(previousHost, host)
        when (
            player.access(plugin) { livePlayer ->
                if (remount) {
                    currentBinding.suppressClose()
                    val replacement = PaperChestPresentation.create(livePlayer, host)
                    val replacementBinding = bind(replacement, render)
                    try {
                        PaperChestPresentation.clear(livePlayer, currentView)
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
                    PaperChestPresentation.update(livePlayer, currentView, host, update.changedSlots)
                }
                this@PaperMenuNativeHost.render = render
            }
        ) {
            is EntityOutcome.Completed -> Unit
            EntityOutcome.Retired -> callbacks?.closed(MenuNativeClose.DISCONNECT)
        }
    }

    override suspend fun close() {
        if (!plugin.isEnabled) {
            abandonPresentation()
            return
        }
        val currentView = view
        binding?.suppressClose()
        val cursorToSettle = currentView?.cursor
            ?.takeUnless(ItemStack::isEmpty)
            ?.let(Items::capture)
            ?: logicalCursor
        player.access(plugin) { livePlayer ->
            currentView?.setCursor(null)
            if (currentView != null) {
                PaperChestPresentation.clear(livePlayer, currentView)
                PaperChestPresentation.close(livePlayer, currentView)
            }
        }
        settlement.settleCursor(cursorSettlementId, player.uniqueId, cursorToSettle)
        logicalCursor = null
        binding?.unbind()
        binding = null
        view = null
        render = null
        callbacks = null
        nativeEnded = false
        retainedCursor = false
    }

    private fun abandonPresentation() {
        binding?.suppressClose()
        runCatching { view?.topInventory?.clear() }
            .onFailure { failure ->
                plugin.logger.log(Level.WARNING, "Could not clear a menu during plug-in shutdown", failure)
            }
        binding?.unbind()
        binding = null
        view = null
        render = null
        callbacks = null
        logicalCursor = null
        nativeEnded = false
        retainedCursor = false
    }

    override suspend fun suspendPresentation() {
        val currentView = view ?: return
        binding?.suppressClose()
        when (
            player.access(plugin) { livePlayer ->
                logicalCursor = currentView.cursor.takeUnless(ItemStack::isEmpty)?.let(Items::capture)
                currentView.setCursor(null)
                PaperChestPresentation.clear(livePlayer, currentView)
                PaperChestPresentation.close(livePlayer, currentView)
            }
        ) {
            is EntityOutcome.Completed -> Unit
            EntityOutcome.Retired -> callbacks?.closed(MenuNativeClose.DISCONNECT)
        }
        binding?.unbind()
        binding = null
        view = null
        render = null
        callbacks = null
        nativeEnded = false
        retainedCursor = true
    }

    override suspend fun feedback(value: MenuFeedback) {
        player.access(plugin) { livePlayer -> livePlayer.sendActionBar(value.message) }
    }

    override suspend fun feedback(value: MenuFeedback, presentation: MenuFeedbackPresentation) {
        player.access(plugin) { livePlayer ->
            presentation.actionBar?.let(livePlayer::sendActionBar)
            presentation.sound?.let(livePlayer::playSound)
        }
    }

    override suspend fun commitTransaction(transaction: MenuNativeTransaction): MenuNativeCommit {
        val currentView = view ?: return MenuNativeCommit.Unavailable
        return when (
            val outcome = player.access(plugin) { livePlayer ->
                if (livePlayer.openInventory.topInventory !== currentView.topInventory) return@access false
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
                logicalCursor = transaction.committed.cursor
                transaction.committed.emissions.forEach { emission ->
                    when (emission) {
                        is MenuTransactionEmission.Drop -> livePlayer.world.dropItem(
                            livePlayer.location,
                            Items.materialize(emission.item),
                        )
                    }
                }
                if (transaction.committed.requiresAcknowledgement) {
                    settlement.recordApplied(livePlayer, transaction.committed.id.value)
                    livePlayer.updateInventory()
                    livePlayer.saveData()
                }
                true
            }
        ) {
            is EntityOutcome.Completed -> if (outcome.value) MenuNativeCommit.Applied else MenuNativeCommit.Unavailable
            EntityOutcome.Retired -> {
                callbacks?.closed(MenuNativeClose.DISCONNECT)
                MenuNativeCommit.Unavailable
            }
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
        interaction = { interaction: MenuInteraction ->
            logicalCursor = interaction.cursor
            callbacks?.dispatch(interaction)
        },
        hostInput = { input -> callbacks?.dispatch(input) },
        nativeClose = { reason, cursor ->
            if (cursor != null) logicalCursor = cursor
            nativeEnded = true
            callbacks?.closed(reason.semantic())
        },
    )

}

private fun PlayerInventorySection.nativeIndexes(): IntRange = when (this) {
    PlayerInventorySection.HOTBAR -> 0..8
    PlayerInventorySection.MAIN -> 9..35
    PlayerInventorySection.ARMOR -> 36..39
    PlayerInventorySection.OFFHAND -> 40..40
}

private fun PaperMenuCloseReason.semantic(): MenuNativeClose = when (this) {
    PaperMenuCloseReason.PLAYER_CLOSED -> MenuNativeClose.PLAYER
    PaperMenuCloseReason.EXTERNAL_REPLACEMENT,
    PaperMenuCloseReason.NATIVE_UNAVAILABLE,
    -> MenuNativeClose.EXTERNAL_INVENTORY
    PaperMenuCloseReason.DISCONNECTED -> MenuNativeClose.DISCONNECT
    PaperMenuCloseReason.KICKED -> MenuNativeClose.KICK
    PaperMenuCloseReason.DIED -> MenuNativeClose.DEATH
}
