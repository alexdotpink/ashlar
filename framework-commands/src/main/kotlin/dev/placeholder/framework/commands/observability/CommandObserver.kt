package dev.placeholder.framework.commands.observability

import dev.placeholder.framework.commands.CommandExecutor
import dev.placeholder.framework.commands.CommandSender
import kotlin.time.Duration

/** Metadata-only lifecycle events emitted around command execution. */
public sealed interface CommandEvent {
    public val route: String
    public val sender: CommandSender
    public val executor: CommandExecutor

    public data class Accepted(
        override val route: String,
        override val sender: CommandSender,
        override val executor: CommandExecutor,
    ) : CommandEvent

    public data class Completed(
        override val route: String,
        override val sender: CommandSender,
        override val executor: CommandExecutor,
        public val duration: Duration,
        public val observedArguments: Map<String, String> = emptyMap(),
    ) : CommandEvent

    public data class Rejected(
        override val route: String,
        override val sender: CommandSender,
        override val executor: CommandExecutor,
        public val duration: Duration,
        public val observedArguments: Map<String, String> = emptyMap(),
    ) : CommandEvent

    public data class Failed(
        override val route: String,
        override val sender: CommandSender,
        override val executor: CommandExecutor,
        public val duration: Duration,
        public val errorType: String,
        public val observedArguments: Map<String, String> = emptyMap(),
    ) : CommandEvent
}

/** Receives typed command events; observer failures never alter the invocation outcome. */
public fun interface CommandObserver {
    public suspend fun observe(event: CommandEvent)
}
