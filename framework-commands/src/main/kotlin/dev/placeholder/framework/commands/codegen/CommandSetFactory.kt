package dev.placeholder.framework.commands.codegen

import dev.placeholder.framework.PluginComponent
import dev.placeholder.framework.commands.internal.CommandSetComponent

/** Connects a command-set instance to its generated binding. Intended for generated code. */
public fun <T : Any> commandSet(
    target: T,
    binding: CommandSetBinding<T>,
): PluginComponent = CommandSetComponent(target, binding)
