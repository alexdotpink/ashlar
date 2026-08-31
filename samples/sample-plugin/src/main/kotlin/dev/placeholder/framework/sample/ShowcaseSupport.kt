package dev.placeholder.framework.sample

import dev.placeholder.framework.commands.CommandExceptionHandler
import dev.placeholder.framework.commands.CommandInvocation
import dev.placeholder.framework.commands.CommandMessages
import dev.placeholder.framework.commands.CommandResponseCodec
import dev.placeholder.framework.commands.CommandResult
import dev.placeholder.framework.commands.codec.CommandArgumentCodec
import dev.placeholder.framework.commands.codec.CommandArgumentQualifier
import dev.placeholder.framework.commands.codec.CommandSuggestionProvider
import dev.placeholder.framework.commands.codec.CommandSyntax
import dev.placeholder.framework.commands.codec.invalidArgument
import dev.placeholder.framework.commands.help.CommandHelpRenderer
import dev.placeholder.framework.commands.help.DefaultCommandHelpRenderer
import dev.placeholder.framework.commands.observability.CommandEvent
import dev.placeholder.framework.commands.observability.CommandObserver
import dev.placeholder.framework.commands.policy.CommandPolicy
import dev.placeholder.framework.commands.policy.CommandPolicyContext
import dev.placeholder.framework.commands.policy.CommandPolicyInterceptor
import dev.placeholder.framework.commands.policy.CommandPolicyPhase
import dev.placeholder.framework.di.Contributes
import dev.placeholder.framework.di.Inject
import dev.placeholder.framework.di.PluginScoped
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass
import kotlinx.coroutines.delay
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

@JvmInline
internal value class LandmarkName(val value: String)

@Target(AnnotationTarget.TYPE, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
@CommandArgumentQualifier
internal annotation class Uppercase

@Target(AnnotationTarget.TYPE, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
@CommandArgumentQualifier
internal annotation class Lowercase

@JvmInline
internal value class StyledWord(val value: String)

@Contributes
@Inject
internal class LandmarkCodec : CommandArgumentCodec<LandmarkName> {
    override val type: KClass<LandmarkName> = LandmarkName::class
    override val syntax: CommandSyntax = CommandSyntax.STRING

    override suspend fun resolve(
        raw: String,
        invocation: CommandInvocation,
        dependencies: dev.placeholder.framework.di.DependencyResolver,
    ): LandmarkName = raw.trim().takeIf { it.length in 2..24 }
        ?.let(::LandmarkName)
        ?: invalidArgument("use between 2 and 24 characters")

    override suspend fun suggest(
        input: String,
        invocation: CommandInvocation,
        dependencies: dev.placeholder.framework.di.DependencyResolver,
    ): List<String> = listOf("spawn", "market", "castle")

    override fun encode(value: LandmarkName): String = value.value
}

/** Overrides the codec suggestions to prove suggestion providers stay independent. */
@Contributes
@Inject
internal class LandmarkSuggestions : CommandSuggestionProvider<LandmarkName> {
    override val type: KClass<LandmarkName> = LandmarkName::class

    override suspend fun suggest(
        input: String,
        invocation: CommandInvocation,
        dependencies: dev.placeholder.framework.di.DependencyResolver,
    ): List<String> = listOf("lighthouse", "windmill", "observatory")
        .filter { it.startsWith(input, ignoreCase = true) }
}

internal abstract class StyledWordCodec(
    override val qualifier: KClass<out Annotation>,
) : CommandArgumentCodec<StyledWord> {
    override val type: KClass<StyledWord> = StyledWord::class
    override val syntax: CommandSyntax = CommandSyntax.WORD
    abstract fun transform(raw: String): String

    override suspend fun resolve(
        raw: String,
        invocation: CommandInvocation,
        dependencies: dev.placeholder.framework.di.DependencyResolver,
    ): StyledWord = StyledWord(transform(raw))

    override fun encode(value: StyledWord): String = value.value
}

@Contributes
@Inject
internal class UppercaseWordCodec : StyledWordCodec(Uppercase::class) {
    override fun transform(raw: String): String = raw.uppercase()
}

@Contributes
@Inject
internal class LowercaseWordCodec : StyledWordCodec(Lowercase::class) {
    override fun transform(raw: String): String = raw.lowercase()
}

internal data class ShowcaseReceipt(val operation: String, val sequence: Int)

@Contributes
@Inject
internal class ShowcaseReceiptCodec : CommandResponseCodec<ShowcaseReceipt> {
    override val type: KClass<ShowcaseReceipt> = ShowcaseReceipt::class

    override suspend fun encode(value: ShowcaseReceipt): CommandResult = CommandResult.of(
        Component.text("Receipt #${value.sequence}: ${value.operation}", NamedTextColor.AQUA),
    )
}

internal class ShowcaseDomainException(message: String) : RuntimeException(message)

@Contributes
@Inject
internal class ShowcaseExceptionHandler : CommandExceptionHandler<ShowcaseDomainException> {
    override val type: KClass<ShowcaseDomainException> = ShowcaseDomainException::class

    override suspend fun handle(
        error: ShowcaseDomainException,
        invocation: CommandInvocation,
    ): CommandResult = CommandResult.of(
        Component.text("Handled domain failure: ${error.message}", NamedTextColor.RED),
    )
}

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@CommandPolicy(
    interceptor = ShowcaseAuditInterceptor::class,
    phase = CommandPolicyPhase.BEFORE_HANDLER,
    order = -10,
)
internal annotation class ShowcaseAudit(val label: String)

@Inject
internal class ShowcaseAuditInterceptor(
    private val state: ShowcaseState,
) : CommandPolicyInterceptor<ShowcaseAudit> {
    override suspend fun intercept(
        annotation: ShowcaseAudit,
        context: CommandPolicyContext,
        next: suspend () -> Any?,
    ): Any? {
        state.record("policy:${annotation.label}")
        return next()
    }
}

@Inject
@PluginScoped
internal class ShowcaseState {
    private val events = CopyOnWriteArrayList<String>()
    private var receiptSequence: Int = 0

    fun record(event: String) {
        events += event
        while (events.size > 20) events.removeAt(0)
    }

    @Synchronized
    fun nextReceipt(operation: String): ShowcaseReceipt =
        ShowcaseReceipt(operation, ++receiptSequence)

    fun recent(): List<String> = events.toList()
}

@Contributes
@Inject
internal class ShowcaseObserver(
    private val state: ShowcaseState,
) : CommandObserver {
    override suspend fun observe(event: CommandEvent) {
        val suffix = when (event) {
            is CommandEvent.Completed -> event.observedArguments.entries.joinToString(prefix = " [", postfix = "]")
            is CommandEvent.Rejected -> " rejected"
            is CommandEvent.Failed -> " failed:${event.errorType.substringAfterLast('.')}"
            is CommandEvent.Accepted -> " accepted"
        }
        state.record("${event.route.substringAfterLast('#')}$suffix")
    }
}

@Contributes
@Inject
internal class ShowcaseMessages : CommandMessages {
    override fun invalidArgument(locale: Locale, name: String, reason: String): Component =
        error("Invalid '$name': $reason")

    override fun missingArgument(locale: Locale, name: String): Component = error("Missing '$name'.")
    override fun noPermission(locale: Locale): Component = error("That showcase route needs operator permission.")
    override fun unexpectedFailure(locale: Locale): Component = error("The showcase caught an unexpected failure.")

    private fun error(message: String): Component = Component.text("Showcase: $message", NamedTextColor.RED)
}

@Contributes
@Inject
internal class ShowcaseHelpRenderer : CommandHelpRenderer {
    override fun render(
        definition: dev.placeholder.framework.commands.codegen.CommandSetDefinition,
        sender: dev.placeholder.framework.commands.CommandSender,
        page: Int,
    ): Component = Component.text("Command showcase — click or type /sc guide", NamedTextColor.AQUA)
        .append(Component.newline())
        .append(DefaultCommandHelpRenderer.render(definition, sender, page))

    override fun render(
        definition: dev.placeholder.framework.commands.codegen.CommandSetDefinition,
        sender: dev.placeholder.framework.commands.CommandSender,
    ): Component = render(definition, sender, 1)
}

internal suspend fun policyDelay() {
    delay(2_000)
}
