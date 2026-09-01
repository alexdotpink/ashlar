package pink.alex.ashlar.menus.storage

import pink.alex.ashlar.items.ItemSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

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

private class ExternalMenuStorage(
    override val id: MenuStorageId,
    source: StateFlow<MenuStorageSnapshot>,
    override val rules: MenuStorageRules,
    override val transactionDomain: MenuTransactionDomain,
) : MutableMenuStorage {
    private val authoritative: MutableStateFlow<MenuStorageSnapshot?> = MutableStateFlow(null)
    override val snapshots: StateFlow<MenuStorageSnapshot> = MergedStorageState(source, authoritative)

    override fun install(snapshot: MenuStorageSnapshot) {
        require(snapshot.id == id) { "Cannot install ${snapshot.id} into $id" }
        require(snapshot.size == rules.size) { "Installed snapshot size does not match storage rules" }
        val current = snapshots.value
        require(snapshot.revision >= current.revision) { "Installed snapshot cannot move storage backward" }
        if (snapshot.revision == current.revision) {
            require(snapshot == current) { "Storage $id has conflicting values for revision ${snapshot.revision}" }
            return
        }
        authoritative.value = snapshot
    }
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class MergedStorageState(
    private val source: StateFlow<MenuStorageSnapshot>,
    private val authoritative: StateFlow<MenuStorageSnapshot?>,
) : StateFlow<MenuStorageSnapshot> {
    override val replayCache: List<MenuStorageSnapshot>
        get() = listOf(value)

    override val value: MenuStorageSnapshot
        get() = newest(source.value, authoritative.value)

    override suspend fun collect(collector: FlowCollector<MenuStorageSnapshot>): Nothing {
        combine(source, authoritative, ::newest).distinctUntilChanged().collect(collector)
        error("A merged storage StateFlow completed unexpectedly")
    }
}

private fun newest(source: MenuStorageSnapshot, authoritative: MenuStorageSnapshot?): MenuStorageSnapshot = when {
    authoritative == null -> source
    source.revision > authoritative.revision -> source
    source.revision < authoritative.revision -> authoritative
    source == authoritative -> source
    else -> error("Storage ${source.id} published conflicting values for revision ${source.revision}")
}
