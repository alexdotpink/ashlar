package dev.placeholder.framework.menus.storage

import dev.placeholder.framework.items.ItemSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Creates session-owned storage which commits immediately in memory. */
public fun localMenuStorage(
    id: MenuStorageId,
    initial: List<ItemSnapshot?>,
    rules: MenuStorageRules = MenuStorageRules.uniform(initial.size),
): MenuStorage = LocalMenuStorage(id, initial, rules)

/** Attaches an externally owned snapshot stream and transaction domain. */
public fun externalMenuStorage(
    id: MenuStorageId,
    snapshots: StateFlow<MenuStorageSnapshot>,
    rules: MenuStorageRules,
    transactionDomain: MenuTransactionDomain,
): MenuStorage {
    require(snapshots.value.id == id) { "External storage stream identity does not match $id" }
    require(snapshots.value.size == rules.size) { "External storage rules do not match snapshot size" }
    require(id in transactionDomain.storages) { "Transaction domain ${transactionDomain.id} does not own $id" }
    return ExternalMenuStorage(id, snapshots, rules, transactionDomain)
}

internal interface MutableMenuStorage : MenuStorage {
    fun install(snapshot: MenuStorageSnapshot)
}

private class LocalMenuStorage(
    override val id: MenuStorageId,
    initial: List<ItemSnapshot?>,
    override val rules: MenuStorageRules,
) : MutableMenuStorage {
    private val mutableSnapshots = MutableStateFlow(MenuStorageSnapshot(id, 0, initial.toList()))

    init {
        require(initial.size == rules.size) { "Local storage rules do not match initial size" }
    }

    override val snapshots: StateFlow<MenuStorageSnapshot> = mutableSnapshots.asStateFlow()
    override val transactionDomain: MenuTransactionDomain? = null

    override fun install(snapshot: MenuStorageSnapshot) {
        require(snapshot.id == id) { "Cannot install ${snapshot.id} into $id" }
        require(snapshot.size == rules.size) { "Installed snapshot size does not match storage rules" }
        require(snapshot.revision > mutableSnapshots.value.revision) { "Installed snapshot must advance revision" }
        mutableSnapshots.value = snapshot
    }
}

private data class ExternalMenuStorage(
    override val id: MenuStorageId,
    override val snapshots: StateFlow<MenuStorageSnapshot>,
    override val rules: MenuStorageRules,
    override val transactionDomain: MenuTransactionDomain,
) : MenuStorage
