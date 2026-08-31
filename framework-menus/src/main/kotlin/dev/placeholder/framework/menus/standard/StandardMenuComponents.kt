package dev.placeholder.framework.menus.standard

import dev.placeholder.framework.items.ItemSpec
import dev.placeholder.framework.menus.ChestScope
import dev.placeholder.framework.menus.MenuScope
import dev.placeholder.framework.menus.MenuNavigator
import dev.placeholder.framework.menus.SlotRegion
import dev.placeholder.framework.menus.component
import dev.placeholder.framework.menus.state

/** Four ordinary states used by asynchronous content components. */
public sealed interface ContentState<out T> {
    /** Content has not produced a value yet. */
    public data object Loading : ContentState<Nothing>
    /** Content completed without a displayable value. */
    public data object Empty : ContentState<Nothing>
    /** Content production failed with [cause]. */
    public data class Failed(public val cause: Throwable) : ContentState<Nothing>
    /** Content produced [value]. */
    public data class Ready<T>(public val value: T) : ContentState<T>
}

/** Renders one branch of an asynchronous content state. */
context(menu: MenuScope)
public fun <T> contentState(
    state: ContentState<T>,
    loading: context(MenuScope) () -> Unit,
    empty: context(MenuScope) () -> Unit,
    failed: context(MenuScope) (Throwable) -> Unit,
    ready: context(MenuScope) (T) -> Unit,
) {
    when (state) {
        ContentState.Loading -> loading()
        ContentState.Empty -> empty()
        is ContentState.Failed -> failed(state.cause)
        is ContentState.Ready -> ready(state.value)
    }
}

/** Stateful page over keyed values, built only from the public menu API. */
public class PagedItems<T, K : Any> internal constructor(
    private val values: List<T>,
    private val keys: List<K>,
    private val componentKey: Any,
    public val page: Int,
    public val pageCount: Int,
    private val pageSize: Int,
    private val setPage: (Int) -> Unit,
) {
    /** Values visible on the current page. */
    public val visible: List<T> = values.drop(page * pageSize).take(pageSize)

    /** Moves to the previous page when one exists. */
    public fun previous(): Boolean {
        if (page == 0) return false
        setPage(page - 1)
        return true
    }

    /** Moves to the next page when one exists. */
    public fun next(): Boolean {
        if (page + 1 >= pageCount) return false
        setPage(page + 1)
        return true
    }

    /** Places current values into [region] with their domain keys. */
    context(chest: ChestScope)
    public fun items(
        region: SlotRegion,
        content: context(MenuScope) (T, Int) -> Unit,
    ) {
        require(region.size >= visible.size) {
            "Page has ${visible.size} items for a ${region.size}-slot region"
        }
        visible.forEachIndexed { offset, item ->
            val originalIndex = page * pageSize + offset
            val slotIndex = region.slots[offset]
            chest.builder.component(chest, StandardItemIdentity(componentKey, keys[originalIndex])) {
                content(item, slotIndex)
            }
        }
    }

    /** Declares a previous-page action control. */
    context(chest: ChestScope)
    public fun previous(
        slot: Int,
        item: ItemSpec,
    ) {
        chest.slot(slot) {
            this.item = item
            onPrimary { previous() }
        }
    }

    /** Declares a next-page action control. */
    context(chest: ChestScope)
    public fun next(
        slot: Int,
        item: ItemSpec,
    ) {
        chest.slot(slot) {
            this.item = item
            onPrimary { next() }
        }
    }
}

/** A keyed fixed-size window that moves one item at a time. */
public class ScrollingItems<T, K : Any> internal constructor(
    private val values: List<T>,
    private val keys: List<K>,
    private val componentKey: Any,
    public val offset: Int,
    public val windowSize: Int,
    private val setOffset: (Int) -> Unit,
) {
    /** Values visible at the current offset. */
    public val visible: List<T> = values.drop(offset).take(windowSize)

    /** Whether the window may move toward the first item. */
    public val canPrevious: Boolean
        get() = offset > 0

    /** Whether the window may move toward the last item. */
    public val canNext: Boolean
        get() = offset + windowSize < values.size

    /** Moves the window one item toward the start. */
    public fun previous(): Boolean {
        if (!canPrevious) return false
        setOffset(offset - 1)
        return true
    }

    /** Moves the window one item toward the end. */
    public fun next(): Boolean {
        if (!canNext) return false
        setOffset(offset + 1)
        return true
    }

    /** Places visible keyed values into [region]. */
    context(chest: ChestScope)
    public fun items(
        region: SlotRegion,
        content: context(MenuScope) (T, Int) -> Unit,
    ) {
        require(region.size >= visible.size) {
            "Scroll window has ${visible.size} items for a ${region.size}-slot region"
        }
        visible.forEachIndexed { index, item ->
            chest.builder.component(chest, StandardItemIdentity(componentKey, keys[offset + index])) {
                content(item, region.slots[index])
            }
        }
    }

    /** Declares a previous-item control. */
    context(chest: ChestScope)
    public fun previous(slot: Int, item: ItemSpec) {
        chest.slot(slot) {
            this.item = item
            onPrimary { previous() }
        }
    }

    /** Declares a next-item control. */
    context(chest: ChestScope)
    public fun next(slot: Int, item: ItemSpec) {
        chest.slot(slot) {
            this.item = item
            onPrimary { next() }
        }
    }
}

/** Creates retained pagination state keyed below the current component. */
context(menu: MenuScope)
public fun <T, K : Any> paged(
    items: Iterable<T>,
    key: (T) -> K,
    pageSize: Int,
    componentKey: Any = "pagination",
): PagedItems<T, K> {
    require(pageSize > 0) { "Page size must be positive" }
    val values = items.toList()
    val keys = values.map(key)
    require(keys.distinct().size == keys.size) { "Paged item keys must be unique" }
    var result: PagedItems<T, K>? = null
    component(componentKey) {
        var page by state(0)
        val pageCount = ((values.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        val visiblePage = page.coerceIn(0, pageCount - 1)
        result = PagedItems(values, keys, componentKey, visiblePage, pageCount, pageSize) { page = it }
    }
    return requireNotNull(result)
}

/** Creates retained one-item scrolling over keyed values. */
context(menu: MenuScope)
public fun <T, K : Any> scrolling(
    items: Iterable<T>,
    key: (T) -> K,
    windowSize: Int,
    componentKey: Any = "scrolling",
): ScrollingItems<T, K> {
    require(windowSize > 0) { "Scroll window size must be positive" }
    val values = items.toList()
    val keys = values.map(key)
    require(keys.distinct().size == keys.size) { "Scrolling item keys must be unique" }
    var result: ScrollingItems<T, K>? = null
    component(componentKey) {
        var offset by state(0)
        val maximum = (values.size - windowSize).coerceAtLeast(0)
        val visibleOffset = offset.coerceIn(0, maximum)
        result = ScrollingItems(values, keys, componentKey, visibleOffset, windowSize) { offset = it }
    }
    return requireNotNull(result)
}

/** Declares a binary toggle action with caller-authored presentation. */
context(chest: ChestScope)
public fun toggle(
    slot: Int,
    value: Boolean,
    item: ItemSpec,
    onChange: suspend (Boolean) -> Unit,
) {
    chest.slot(slot) {
        this.item = item
        onPrimary { onChange(!value) }
    }
}

/** Declares decrement and increment controls around a bounded numeric value. */
context(chest: ChestScope)
public fun numberStepper(
    value: Int,
    range: IntRange,
    decrementSlot: Int,
    decrementItem: ItemSpec,
    incrementSlot: Int,
    incrementItem: ItemSpec,
    onChange: suspend (Int) -> Unit,
) {
    require(!range.isEmpty()) { "A number-stepper range cannot be empty" }
    chest.slot(decrementSlot) {
        item = decrementItem
        onPrimary { if (value > range.first) onChange(value - 1) }
    }
    chest.slot(incrementSlot) {
        item = incrementItem
        onPrimary { if (value < range.last) onChange(value + 1) }
    }
}

/** Declares explicit confirm and cancel action controls. */
context(chest: ChestScope)
public fun confirmation(
    confirmSlot: Int,
    confirmItem: ItemSpec,
    cancelSlot: Int,
    cancelItem: ItemSpec,
    onConfirm: suspend () -> Unit,
    onCancel: suspend () -> Unit,
) {
    chest.slot(confirmSlot) {
        item = confirmItem
        onPrimary { onConfirm() }
    }
    chest.slot(cancelSlot) {
        item = cancelItem
        onPrimary { onCancel() }
    }
}

/** Fills each unclaimed slot in [region] with one caller-authored item. */
context(chest: ChestScope)
public fun filler(
    region: SlotRegion,
    item: ItemSpec,
) {
    region.slots.forEach { index -> chest.slot(index) { this.item = item } }
}

/** Declares one keyed selection action. */
context(chest: ChestScope)
public fun <T : Any> selection(
    slot: Int,
    value: T,
    item: ItemSpec,
    onSelect: suspend (T) -> Unit,
) {
    selectionImpl(chest, slot, value, item, onSelect)
}

/** Declares a caller-authored static slot with no action. */
context(chest: ChestScope)
public fun staticItem(slot: Int, item: ItemSpec) {
    chest.slot(slot) { this.item = item }
}

/** Declares a control that ends the current logical session. */
context(chest: ChestScope)
public fun closeControl(slot: Int, item: ItemSpec) {
    chest.slot(slot) {
        this.item = item
        onPrimary { close() }
    }
}

/** Declares a typed navigation back control. */
context(chest: ChestScope)
public fun <R : Any> backControl(
    slot: Int,
    item: ItemSpec,
    navigator: MenuNavigator<R>,
) {
    chest.slot(slot) {
        this.item = item
        onPrimary { if (!navigator.back()) navigator.close() }
    }
}

/** Declares one tab using the same typed selection mechanics as other controls. */
context(chest: ChestScope)
public fun <T : Any> tab(
    slot: Int,
    value: T,
    item: ItemSpec,
    onSelect: suspend (T) -> Unit,
) {
    selectionImpl(chest, slot, value, item, onSelect)
}

/** One caller-presented entry in a typed tab row. */
public data class MenuTab<T : Any>(
    public val value: T,
    public val slot: Int,
    public val item: ItemSpec,
)

/** Declares a keyed row of typed tabs. */
context(chest: ChestScope)
public fun <T : Any> tabs(
    tabs: Iterable<MenuTab<T>>,
    onSelect: suspend (T) -> Unit,
) {
    val values = tabs.toList()
    require(values.map(MenuTab<T>::value).distinct().size == values.size) { "Tab values must be unique" }
    require(values.map(MenuTab<T>::slot).distinct().size == values.size) { "Tab slots must be unique" }
    values.forEach { tab -> selectionImpl(chest, tab.slot, tab.value, tab.item, onSelect) }
}

/** Declares a loading presentation slot. */
context(chest: ChestScope)
public fun loading(slot: Int, item: ItemSpec) {
    chest.slot(slot) { this.item = item }
}

/** Declares an empty-content presentation slot. */
context(chest: ChestScope)
public fun emptyContent(slot: Int, item: ItemSpec) {
    chest.slot(slot) { this.item = item }
}

/** Declares a failed-content slot with an optional retry action. */
context(chest: ChestScope)
public fun failedContent(
    slot: Int,
    item: ItemSpec,
    retry: (suspend () -> Unit)? = null,
) {
    chest.slot(slot) {
        this.item = item
        if (retry != null) onPrimary { retry() }
    }
}

/** Fills an explicit border region with one authored item. */
context(chest: ChestScope)
public fun border(region: SlotRegion, item: ItemSpec) {
    region.slots.forEach { index -> chest.slot(index) { this.item = item } }
}

private fun <T : Any> selectionImpl(
    chest: ChestScope,
    slot: Int,
    value: T,
    item: ItemSpec,
    onSelect: suspend (T) -> Unit,
) {
    chest.builder.component(chest, value) {
        chest.slot(slot) {
            this.item = item
            onPrimary { onSelect(value) }
        }
    }
}

private data class StandardItemIdentity(
    val componentKey: Any,
    val itemKey: Any,
) {
    override fun toString(): String = "$componentKey[$itemKey]"
}
