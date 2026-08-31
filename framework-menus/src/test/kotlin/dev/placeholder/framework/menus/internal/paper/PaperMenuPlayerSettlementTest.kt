package dev.placeholder.framework.menus.internal.paper

import dev.placeholder.framework.items.ItemSnapshot
import dev.placeholder.framework.menus.storage.CommittedMenuTransaction
import dev.placeholder.framework.menus.storage.MenuPlayerDelivery
import dev.placeholder.framework.menus.storage.MenuStorageChange
import dev.placeholder.framework.menus.storage.MenuStorageId
import dev.placeholder.framework.menus.storage.MenuStorageSnapshot
import dev.placeholder.framework.menus.storage.MenuTransactionId
import dev.placeholder.framework.menus.storage.MenuTransactionProposal
import dev.placeholder.framework.menus.storage.PlayerInventorySection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.bukkit.Material

internal class PaperMenuPlayerSettlementTest {
    @Test
    fun `restart settlement computes exact net player holdings across inventory and cursor`() {
        val storage = MenuStorageId("framework-player", "$PLAYER.main")
        val before = MenuStorageSnapshot(storage, 0, listOf(diamonds(5), null))
        val after = MenuStorageSnapshot(storage, 1, listOf(diamonds(2), dirt(1)))
        val proposal = MenuTransactionProposal(
            id = MenuTransactionId.create(),
            playerId = PLAYER,
            changes = mapOf(storage to MenuStorageChange(before, after)),
            cursorBefore = diamonds(2),
            cursorAfter = diamonds(1),
            playerStorages = mapOf(PlayerInventorySection.MAIN to storage),
        )
        val delivery = MenuPlayerDelivery(
            proposal,
            CommittedMenuTransaction(
                proposal.id,
                mapOf(storage to after),
                proposal.cursorAfter,
                emptyList(),
                requiresAcknowledgement = true,
            ),
        )

        val delta = delivery.playerDelta()

        assertEquals(4, delta.removals.filter { it.material == Material.DIAMOND }.sumOf(ItemSnapshot::amount))
        assertEquals(1, delta.additions.filter { it.material == Material.DIRT }.sumOf(ItemSnapshot::amount))
    }

    private fun diamonds(amount: Int): ItemSnapshot =
        ItemSnapshot.detached(Material.DIAMOND, amount, 64, "diamond")

    private fun dirt(amount: Int): ItemSnapshot =
        ItemSnapshot.detached(Material.DIRT, amount, 64, "dirt")

    private companion object {
        val PLAYER: UUID = UUID.fromString("1ed2dfa7-07df-4d36-8bc0-436b500bc3f2")
    }
}
