package dev.placeholder.framework.menus

/** A typed presentation value inherited by descendant menu declarations. */
public class MenuLocal<T> internal constructor(
    public val name: String,
    internal val default: () -> T,
) {
    override fun toString(): String = "MenuLocal($name)"
}

/** Creates a typed presentation local with a lazy default. */
public fun <T> menuLocal(
    name: String,
    default: () -> T,
): MenuLocal<T> {
    require(name.isNotBlank()) { "A menu local name cannot be blank" }
    return MenuLocal(name, default)
}

/** Reads this local from the current immutable render context. */
context(menu: MenuScope)
public fun <T> MenuLocal<T>.current(): T = menu.local(this)

/** Provides [value] to declarations emitted by [content]. */
context(menu: MenuScope)
public fun <T> provide(
    local: MenuLocal<T>,
    value: T,
    content: context(MenuScope) () -> Unit,
) {
    menu.provide(local, value, content)
}
