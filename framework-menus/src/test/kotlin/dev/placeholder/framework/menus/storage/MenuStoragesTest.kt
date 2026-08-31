package dev.placeholder.framework.menus.storage

import dev.placeholder.framework.items.ItemSnapshot
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MenuStoragesTest {
    @Test
    fun `accepted authoritative snapshot is visible before repository flow catches up`() = runTest {
        val id = MenuStorageId("test", "shared")
        val before = MenuStorageSnapshot(id, 0, listOf(null))
        val emeralds = ItemSnapshot.detached(Material.EMERALD, 4, 64)
        val after = MenuStorageSnapshot(id, 1, listOf(emeralds))
        val source = MutableStateFlow(before)
        val domain = object : MenuTransactionDomain {
            override val id: String = "shared-domain"
            override val storages: Set<MenuStorageId> = setOf(id)
            override suspend fun commit(proposal: MenuTransactionProposal): MenuTransactionDecision =
                MenuTransactionDecision.Commit(mapOf(id to after))
            override suspend fun resolve(id: MenuTransactionId): MenuTransactionResolution =
                MenuTransactionResolution.Rejected(Component.empty())
        }
        val storage = externalMenuStorage(id, source, MenuStorageRules.uniform(1), domain)
        val proposal = MenuTransactionProposal(
            MenuTransactionId(UUID.randomUUID()),
            changes = mapOf(id to MenuStorageChange(before, after)),
            cursorBefore = emeralds,
            cursorAfter = null,
        )

        val result = MenuTransactionCoordinator().submit(proposal, mapOf(id to storage), session = Any())

        assertIs<MenuTransactionSubmission.Committed>(result)
        assertEquals(after, storage.snapshots.value)
        assertEquals(before, source.value)

        val newer = MenuStorageSnapshot(id, 2, listOf(null))
        source.value = newer
        assertEquals(newer, storage.snapshots.value)
    }
}
