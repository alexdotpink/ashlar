package dev.placeholder.framework.commands.help

import dev.placeholder.framework.commands.CommandSender
import dev.placeholder.framework.commands.codegen.CommandRouteDefinition
import dev.placeholder.framework.commands.codegen.CommandSegmentDefinition
import dev.placeholder.framework.commands.codegen.CommandSetDefinition
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

/** Renders permission-filtered command help without exposing Brigadier nodes. */
public fun interface CommandHelpRenderer {
    public fun render(
        definition: CommandSetDefinition,
        sender: CommandSender,
    ): Component

    public fun render(
        definition: CommandSetDefinition,
        sender: CommandSender,
        page: Int,
    ): Component = render(definition, sender)
}

/** Compact built-in help renderer. */
public object DefaultCommandHelpRenderer : CommandHelpRenderer {
    override fun render(
        definition: CommandSetDefinition,
        sender: CommandSender,
    ): Component = render(definition, sender, 1)

    override fun render(
        definition: CommandSetDefinition,
        sender: CommandSender,
        page: Int,
    ): Component {
        val visible = definition.routes.filter { route -> route.isVisibleTo(sender) }
        if (visible.isEmpty()) return Component.text("No commands are available.", NamedTextColor.GRAY)
        val pageCount = ((visible.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
        val selectedPage = page.coerceIn(1, pageCount)
        val selected = visible.drop((selectedPage - 1) * PAGE_SIZE).take(PAGE_SIZE)
        return Component.text()
            .append(Component.text("/${definition.name}", NamedTextColor.GOLD))
            .append(Component.text(" — page $selectedPage/$pageCount", NamedTextColor.GRAY))
            .append(Component.newline())
            .also { builder ->
                selected.forEachIndexed { index, route ->
                    if (index > 0) builder.append(Component.newline())
                    builder.append(Component.text("  ${route.usage(definition.name)}", NamedTextColor.YELLOW))
                    route.documentation.summary.takeIf(String::isNotBlank)?.let { summary ->
                        builder.append(Component.text(" — $summary", NamedTextColor.GRAY))
                    }
                }
            }
            .build()
    }

    private const val PAGE_SIZE: Int = 8
}

private fun CommandRouteDefinition.isVisibleTo(sender: CommandSender): Boolean =
    segments.filterIsInstance<CommandSegmentDefinition.Literal>()
        .flatMap(CommandSegmentDefinition.Literal::permissions)
        .all(sender::hasPermission)

private fun CommandRouteDefinition.usage(root: String): String = buildString {
    append('/').append(root)
    segments.forEach { segment ->
        append(' ')
        when (segment) {
            is CommandSegmentDefinition.Literal -> append(segment.names.first())
            is CommandSegmentDefinition.Argument -> {
                val parameter = parameters[segment.parameterIndex]
                append(if (parameter.optional) '[' else '<')
                append(parameter.name)
                append(if (parameter.optional) ']' else '>')
            }
            is CommandSegmentDefinition.ScannedArguments -> {
                parameters.drop(segment.firstParameterIndex).forEach { parameter ->
                    append(if (parameter.optional || parameter.nullable) '[' else '<')
                    append(if (parameter.option != null || parameter.options != null) "--" else "")
                    append(parameter.option?.name ?: parameter.name)
                    append(if (parameter.optional || parameter.nullable) ']' else '>')
                    append(' ')
                }
                if (isNotEmpty() && last() == ' ') deleteCharAt(lastIndex)
            }
        }
    }
}
