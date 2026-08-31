package dev.placeholder.framework.menus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/** Cleanup registration available while a committed synchronous effect starts. */
public interface MenuEffectScope {
    /** Registers cleanup run exactly once when this effect leaves. */
    public fun onDispose(dispose: () -> Unit)
}

internal data class BoundaryIdentity(val component: ComponentIdentity, val key: Any)

internal data class EffectIdentity(
    val component: ComponentIdentity,
    val kind: String,
    val key: Any,
)

internal sealed interface EffectDeclaration {
    val identity: EffectIdentity
    val boundary: BoundaryIdentity?

    data class Synchronous(
        override val identity: EffectIdentity,
        override val boundary: BoundaryIdentity?,
        val block: MenuEffectScope.() -> Unit,
    ) : EffectDeclaration

    data class Launched(
        override val identity: EffectIdentity,
        override val boundary: BoundaryIdentity?,
        val block: suspend CoroutineScope.() -> Unit,
    ) : EffectDeclaration

    data class Collection<T>(
        override val identity: EffectIdentity,
        override val boundary: BoundaryIdentity?,
        val flow: Flow<T>,
        val emit: (T) -> Unit,
    ) : EffectDeclaration
}

/** Declares closeable work that starts only after a successful commit. */
context(menu: MenuScope)
public fun effect(
    key: Any,
    block: MenuEffectScope.() -> Unit,
) {
    menu.effect(key, block)
}

/** Declares suspending work owned by the current keyed component. */
context(menu: MenuScope)
public fun launchedEffect(
    key: Any,
    block: suspend CoroutineScope.() -> Unit,
) {
    menu.launchedEffect(key, block)
}
