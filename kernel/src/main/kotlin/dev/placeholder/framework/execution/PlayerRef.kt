package dev.placeholder.framework.execution

import java.util.UUID
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/** Stable player identity safe to retain outside server-owned execution. */
@JvmInline
public value class PlayerRef(public val uniqueId: UUID) {
    /** Resolves and accesses the current player inside its entity ownership context. */
    public suspend fun <T> access(
        plugin: Plugin,
        block: context(EntityContext) (Player) -> T,
    ): EntityOutcome<T> {
        val player = plugin.withGlobal { plugin.server.getPlayer(uniqueId) }
            ?: return EntityOutcome.Retired
        return plugin.withEntity(player) { block(player) }
    }
}
