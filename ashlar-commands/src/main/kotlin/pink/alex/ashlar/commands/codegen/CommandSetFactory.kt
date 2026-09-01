package pink.alex.ashlar.commands.codegen

import pink.alex.ashlar.PluginComponent
import pink.alex.ashlar.commands.internal.CommandSetComponent

/** Connects a command-set instance to its generated binding. Intended for generated code. */
public fun <T : Any> commandSet(
    target: T,
    binding: CommandSetBinding<T>,
): PluginComponent = CommandSetComponent(target, binding)
