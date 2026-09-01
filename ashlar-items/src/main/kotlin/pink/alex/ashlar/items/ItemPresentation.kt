package pink.alex.ashlar.items

import io.papermc.paper.datacomponent.DataComponentType
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.TooltipDisplay
import org.bukkit.inventory.ItemStack
import java.util.Locale
import java.util.UUID

/** Per-materialization viewer data available to presentation policies. */
public data class ItemPresentationContext(
    public val viewerId: UUID? = null,
    public val locale: Locale = Locale.ROOT,
    public val theme: String? = null,
)

/** A visual policy applied after an item has been materialized. */
public fun interface ItemPresentation {
    /** Applies this policy without changing durable custom-item identity or payload data. */
    public fun apply(stack: ItemStack, context: ItemPresentationContext)

    public companion object {
        /** Preserves ordinary vanilla presentation. */
        public val Neutral: ItemPresentation = ItemPresentation { _, _ -> }

        /** Hides data normally irrelevant on a virtual menu action icon. */
        public val MenuAction: ItemPresentation = hiding(
            DataComponentTypes.ATTRIBUTE_MODIFIERS,
            DataComponentTypes.DAMAGE,
            DataComponentTypes.MAX_DAMAGE,
        )

        /** Creates a policy which hides [components] from the item tooltip. */
        public fun hiding(vararg components: DataComponentType): ItemPresentation = ItemPresentation { stack, _ ->
            val current = stack.getData(DataComponentTypes.TOOLTIP_DISPLAY)
            val hidden = buildSet {
                current?.hiddenComponents()?.let(::addAll)
                addAll(components)
            }
            stack.setData(
                DataComponentTypes.TOOLTIP_DISPLAY,
                TooltipDisplay.tooltipDisplay()
                    .hideTooltip(current?.hideTooltip() == true)
                    .hiddenComponents(hidden)
                    .build(),
            )
        }
    }
}
