package dev.placeholder.framework.commands

import java.util.Locale
import java.util.UUID
import net.kyori.adventure.audience.Audience

/** Stable source information safe to retain across command suspension. */
public data class CommandSender(
    public val name: String,
    public val uniqueId: UUID?,
    public val locale: Locale,
    public val audience: Audience,
    private val deliveryCheck: () -> Boolean = { true },
    private val permissionCheck: (String) -> Boolean,
) {
    public fun hasPermission(permission: String): Boolean = permissionCheck(permission)

    public fun canDeliver(): Boolean = deliveryCheck()
}

/** Stable executor identity, distinct from the sender for proxied command sources. */
public data class CommandExecutor(
    public val name: String,
    public val uniqueId: UUID?,
)

/** Invocation-scoped context available through constructor injection. */
public data class CommandInvocation(
    public val sender: CommandSender,
    public val executor: CommandExecutor,
    public val route: String,
)

/** Enforces a dynamic sender permission inside a handler with an explicit denial response. */
public fun CommandInvocation.requirePermission(
    permission: String,
    denial: () -> String = { "You need '$permission' to do that." },
) {
    if (!sender.hasPermission(permission)) reject(denial())
}
