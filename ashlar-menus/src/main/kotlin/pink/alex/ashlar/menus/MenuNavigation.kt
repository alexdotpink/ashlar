package pink.alex.ashlar.menus

/** Player-native close behavior for one typed navigation screen. */
public enum class NativeClose {
    END_SESSION,
    BACK,
}

/** Typed operations over one retained navigation stack. */
public interface MenuNavigator<R : Any> {
    /** The currently visible route. */
    public val current: R

    /** The retained route history including [current]. */
    public val routes: List<R>

    /** Covers the current screen with [route]. */
    public fun push(route: R)

    /** Replaces the current route without adding history. */
    public fun replace(route: R)

    /** Reveals the previous route, or returns false at the root. */
    public fun back(): Boolean

    /** Ends the owning logical menu session. */
    public fun close()
}

/** Declaration scope for screens belonging to one typed navigator. */
public abstract class MenuNavigationScope<R : Any> internal constructor() {
    /** Operations for the navigator currently being rendered. */
    public abstract val navigator: MenuNavigator<R>

    /** Renders [content] when [select] accepts the active route. */
    public abstract fun <S : R> screen(
        select: (R) -> S?,
        nativeClose: NativeClose = NativeClose.END_SESSION,
        content: context(MenuScope) (S) -> Unit,
    )
}

/** Declares typed retained navigation owned by the current component. */
context(menu: MenuScope)
public fun <R : Any> navigator(
    initial: R,
    content: MenuNavigationScope<R>.() -> Unit,
) {
    menu.builder.navigator(menu, initial, content)
}

/** Declares a screen selected by its reified sealed-route subtype. */
public inline fun <R : Any, reified S : R> MenuNavigationScope<R>.screen(
    nativeClose: NativeClose = NativeClose.END_SESSION,
    noinline content: context(MenuScope) (S) -> Unit,
) {
    screen(select = { route -> route as? S }, nativeClose = nativeClose, content = content)
}

internal class NavigationState<R : Any>(initial: R) : MenuNavigator<R> {
    private data class Entry<R>(val id: Long, val route: R)

    private val stack: MutableList<Entry<R>> = mutableListOf(Entry(0, initial))
    private var nextId: Long = 1
    lateinit var invalidate: () -> Unit
    lateinit var closeSession: () -> Unit
    lateinit var discard: (Long) -> Unit
    @Volatile
    var nativeClose: NativeClose = NativeClose.END_SESSION

    override val current: R
        @Synchronized
        get() = stack.last().route

    internal val currentEntryId: Long
        @Synchronized
        get() = stack.last().id

    override val routes: List<R>
        @Synchronized
        get() = stack.map(Entry<R>::route)

    @Synchronized
    override fun push(route: R) {
        stack += Entry(nextId++, route)
        invalidate()
    }

    @Synchronized
    override fun replace(route: R) {
        discard(stack.last().id)
        stack[stack.lastIndex] = Entry(nextId++, route)
        invalidate()
    }

    @Synchronized
    override fun back(): Boolean {
        if (stack.size == 1) return false
        discard(stack.removeAt(stack.lastIndex).id)
        invalidate()
        return true
    }

    override fun close() {
        closeSession()
    }
}

internal class DefaultNavigationScope<R : Any>(
    private val menu: MenuScope,
    override val navigator: NavigationState<R>,
) : MenuNavigationScope<R>() {
    var matched: Boolean = false

    override fun <S : R> screen(
        select: (R) -> S?,
        nativeClose: NativeClose,
        content: context(MenuScope) (S) -> Unit,
    ) {
        val route = select(navigator.current) ?: return
        if (matched) {
            throw MenuValidationException("More than one screen matched route ${navigator.current}")
        }
        navigator.nativeClose = nativeClose
        matched = true
        menu.builder.component(menu, ScreenIdentity(navigator.currentEntryId)) {
            content(route)
        }
    }
}

internal data class ScreenIdentity(val entry: Long)
