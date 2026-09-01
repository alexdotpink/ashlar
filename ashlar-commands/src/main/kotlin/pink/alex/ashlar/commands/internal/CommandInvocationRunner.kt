package pink.alex.ashlar.commands.internal

import com.mojang.brigadier.Command
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import pink.alex.ashlar.ComponentContext
import pink.alex.ashlar.commands.CommandExecutor
import pink.alex.ashlar.commands.CommandInvocation
import pink.alex.ashlar.commands.CommandRejectedException
import pink.alex.ashlar.commands.CommandExceptionHandler
import pink.alex.ashlar.commands.CommandResponseCodec
import pink.alex.ashlar.commands.CommandResult
import pink.alex.ashlar.commands.CommandSender
import pink.alex.ashlar.commands.EnglishCommandMessages
import pink.alex.ashlar.commands.CommandMessages
import pink.alex.ashlar.commands.codec.CommandArgumentCodec
import pink.alex.ashlar.commands.codec.CommandArgumentException
import pink.alex.ashlar.commands.codec.CommandSuggestionProvider
import pink.alex.ashlar.commands.codegen.CommandParameterDefinition
import pink.alex.ashlar.commands.codegen.CommandRouteDefinition
import pink.alex.ashlar.commands.codegen.CommandSetContribution
import pink.alex.ashlar.commands.codegen.commandResult
import pink.alex.ashlar.commands.observability.CommandEvent
import pink.alex.ashlar.commands.observability.CommandObserver
import pink.alex.ashlar.commands.parsing.CommandLineTokenizer
import pink.alex.ashlar.commands.policy.BuiltinCommandPolicyExecutor
import pink.alex.ashlar.commands.policy.CommandPolicyState
import pink.alex.ashlar.commands.policy.CommandPolicyContext
import pink.alex.ashlar.commands.policy.CommandPolicyDefinition
import pink.alex.ashlar.commands.policy.CommandPolicyInterceptor
import pink.alex.ashlar.commands.policy.CommandPolicyPhase
import pink.alex.ashlar.commands.policy.InMemoryCommandPolicyState
import pink.alex.ashlar.commands.graph.CommandGraph
import pink.alex.ashlar.commands.graph.CommandGraphEdge
import pink.alex.ashlar.commands.route.commandRouteIdentity
import pink.alex.ashlar.di.DependencyGraph
import pink.alex.ashlar.di.DependencyResolver
import io.papermc.paper.command.brigadier.CommandSourceStack
import java.util.Locale
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import pink.alex.ashlar.execution.withGlobal
import org.bukkit.command.CommandSender as BukkitCommandSender
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import kotlin.time.TimeSource

/** Platform-neutral asynchronous resolution and handler pipeline after Brigadier accepts a route. */
internal class CommandInvocationRunner(
    private val runtime: ComponentContext,
    private val graph: DependencyGraph,
    private val retirement: CommandRetirement,
) {
    private val suggestionJobs: MutableMap<String, Job> = ConcurrentHashMap()
    private val policies: BuiltinCommandPolicyExecutor
    private val messages: CommandMessages =
        graph.contributions(CommandMessages::class).singleOrNull() ?: EnglishCommandMessages

    init {
        graph.bindDefault(Clock::class, Clock.systemUTC())
        val clock = graph.get(Clock::class)
        graph.bindDefault(CommandPolicyState::class, InMemoryCommandPolicyState(clock))
        policies = BuiltinCommandPolicyExecutor(clock, graph.get(CommandPolicyState::class))
    }

    fun accept(
        target: Any,
        binding: CommandSetContribution,
        routeIndex: Int,
        route: CommandRouteDefinition,
        context: CommandContext<CommandSourceStack>,
        argumentCount: Int = route.parameters.size,
    ): Int {
        val accepted = route.parameters.take(argumentCount).map { parameter ->
            PaperNativeArguments.extract(parameter, context)?.value
                ?: context.getArgument(parameter.name, Any::class.java).toString()
        }
        val raw = accepted.map(Any::toString)
        val invocation = context.source.snapshot("${binding.definition.name} ${route.name}")
        val job = runtime.task("command:${invocation.route}") {
            graph.invocation(invocation, invocation.sender, invocation.executor).use { dependencies ->
                execute(target, binding, routeIndex, route, raw, invocation, dependencies) {
                    val arguments = accepted.mapIndexed { index, value ->
                        if (value is String) {
                            resolve(codec(route.parameters[index]), value, invocation, dependencies)
                        } else {
                            value
                        }
                    }
                    arguments to arguments.mapIndexed { index, value ->
                        val parameter = route.parameters[index]
                        if (PaperNativeArguments.argumentType(parameter) != null) value.toString()
                        else encodeArgument(codec(parameter), value)
                    }
                }
            }
        }
        if (route.cancelOnExecutorRetire) invocation.executor.uniqueId?.let { retirement.track(it, job) }
        return Command.SINGLE_SUCCESS
    }

    fun acceptScanned(
        target: Any,
        binding: CommandSetContribution,
        routeIndex: Int,
        route: CommandRouteDefinition,
        context: CommandContext<CommandSourceStack>,
        firstParameterIndex: Int,
        rawTail: String,
    ): Int {
        val prefixRaw = route.parameters.take(firstParameterIndex).map { parameter ->
            context.getArgument(parameter.name, Any::class.java).toString()
        }
        val invocation = context.source.snapshot("${binding.definition.name} ${route.name}")
        val job = runtime.task("command:${invocation.route}") {
            graph.invocation(invocation, invocation.sender, invocation.executor).use { dependencies ->
                execute(
                    target,
                    binding,
                    routeIndex,
                    route,
                    prefixRaw,
                    invocation,
                    dependencies,
                ) {
                    val prefix = route.parameters.take(firstParameterIndex).mapIndexed { index, parameter ->
                        resolve(codec(parameter), prefixRaw[index], invocation, dependencies)
                    }
                    val scanner = ScannedCommandArguments(
                        routeIndex,
                        route,
                        binding,
                        resolve = { type, qualifier, raw ->
                            resolve(codec(type, qualifier), raw, invocation, dependencies)
                        },
                        encode = { type, qualifier, value -> encodeArgument(codec(type, qualifier), value) },
                    )
                    val suffix = scanner.scan(rawTail, firstParameterIndex)
                    val canonicalPrefix = route.parameters.take(firstParameterIndex).mapIndexed { index, parameter ->
                        "argument:${parameter.name}=${encodeArgument(codec(parameter), prefix[index])}"
                    }
                    prefix + suffix.arguments to (canonicalPrefix + suffix.canonicalArguments)
                }
            }
        }
        if (route.cancelOnExecutorRetire) invocation.executor.uniqueId?.let { retirement.track(it, job) }
        return Command.SINGLE_SUCCESS
    }

    fun codec(parameter: CommandParameterDefinition): CommandArgumentCodec<*> {
        val custom = graph.contributions(CommandArgumentCodec::class)
            .singleOrNull { candidate ->
                candidate.type == parameter.type && candidate.qualifier == parameter.qualifier
            }
        return custom ?: BuiltinCodecs.find(parameter.type)
            ?: error("No command argument codec for ${parameter.type.qualifiedName}")
    }

    private fun codec(
        type: kotlin.reflect.KClass<*>,
        qualifier: kotlin.reflect.KClass<out Annotation>? = null,
    ): CommandArgumentCodec<*> {
        val custom = graph.contributions(CommandArgumentCodec::class)
            .singleOrNull { candidate -> candidate.type == type && candidate.qualifier == qualifier }
        return custom ?: BuiltinCodecs.find(type)
            ?: error("No command argument codec for ${type.qualifiedName}")
    }

    fun suggest(
        route: String,
        parameter: CommandParameterDefinition,
        context: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        val invocation = context.source.snapshot(route)
        val sourceKey = invocation.sender.uniqueId?.toString() ?: invocation.sender.name
        val key = "$sourceKey:$route:${parameter.name}"
        val future = CompletableFuture<Suggestions>()
        suggestionJobs.remove(key)?.cancel()
        val job = runtime.task("suggest:$route:${parameter.name}") {
            graph.invocation(invocation, invocation.sender, invocation.executor).use { dependencies ->
                try {
                    val provider = graph.contributions(CommandSuggestionProvider::class)
                        .singleOrNull { candidate ->
                            candidate.type == parameter.type && candidate.qualifier == parameter.qualifier
                        }
                    val values = provider?.suggest(builder.remaining, invocation, dependencies)
                        ?: codec(parameter).suggest(builder.remaining, invocation, dependencies)
                    values.forEach(builder::suggest)
                    future.complete(builder.build())
                } catch (cancelled: CancellationException) {
                    future.cancel(false)
                    throw cancelled
                } catch (failure: Throwable) {
                    future.completeExceptionally(failure)
                }
            }
        }
        suggestionJobs[key] = job
        job.invokeOnCompletion { suggestionJobs.remove(key, job) }
        return future
    }

    fun hasSuggestionProvider(parameter: CommandParameterDefinition): Boolean =
        graph.contributions(CommandSuggestionProvider::class).any { provider ->
            provider.type == parameter.type && provider.qualifier == parameter.qualifier
        }

    private suspend fun execute(
        target: Any,
        binding: CommandSetContribution,
        routeIndex: Int,
        route: CommandRouteDefinition,
        raw: List<String>,
        invocation: CommandInvocation,
        dependencies: DependencyResolver,
        prepared: (suspend () -> Pair<List<Any?>, List<String>>)? = null,
    ) {
        val started = TimeSource.Monotonic.markNow()
        var observedArguments: Map<String, String> = emptyMap()
        observe(CommandEvent.Accepted(invocation.route, invocation.sender, invocation.executor))
        try {
            val routeIdentity = "${binding.targetType.qualifiedName}#$routeIndex"
            val handlerResult = customPolicies(
                route.policies,
                CommandPolicyPhase.BEFORE_RESOLUTION,
                CommandPolicyContext(invocation, emptyList()),
            ) {
                policies.beforeResolution(route.policies, invocation, routeIdentity)
                val resolved = prepared?.invoke()
                val arguments = resolved?.first ?: route.parameters.take(raw.size).mapIndexed { index, parameter ->
                    if (parameter.repeated) {
                        CommandLineTokenizer.tokenize(raw[index]).map { token ->
                            resolve(codec(parameter), token.value, invocation, dependencies)
                        }
                    } else {
                        resolve(codec(parameter), raw[index], invocation, dependencies)
                    }
                }
                val canonicalArguments = resolved?.second ?: route.parameters.take(arguments.size)
                    .flatMapIndexed { index, parameter ->
                        val value = arguments[index]
                        if (parameter.repeated) {
                            @Suppress("UNCHECKED_CAST")
                            (value as List<Any>).map { item -> encodeArgument(codec(parameter), item) }
                        } else {
                            listOf(encodeArgument(codec(parameter), value))
                        }
                    }
                val policyContext = CommandPolicyContext(invocation, canonicalArguments)
                observedArguments = route.parameters.mapIndexedNotNull { index, parameter ->
                    if (!parameter.observed || parameter.sensitive || index >= arguments.size) null
                    else parameter.name to arguments[index].toString()
                }.toMap()
                customPolicies(route.policies, CommandPolicyPhase.AFTER_RESOLUTION, policyContext) {
                    policies.afterResolution(route.policies, invocation, routeIdentity, canonicalArguments)
                    customPolicies(route.policies, CommandPolicyPhase.BEFORE_HANDLER, policyContext) {
                        val result = policies.invokeHandler(route.policies, invocation, routeIdentity) {
                            when (
                                val edge = graph.get(CommandGraph::class).edge(
                                    commandRouteIdentity(
                                        routeIdentity,
                                        canonicalArguments.map { value -> value.substringAfter('=', value) },
                                    ),
                                )
                            ) {
                                null -> binding.invoke(target, routeIndex, arguments, dependencies)
                                else -> executeGraphEdge(edge, invocation)
                            }
                        }
                        customPolicies(route.policies, CommandPolicyPhase.AFTER_HANDLER, policyContext) { result }
                    }
                }
            }
            val result = encode(handlerResult)
            if (invocation.sender.canDeliver()) {
                result.responses.forEach(invocation.sender.audience::sendMessage)
            }
            observe(
                CommandEvent.Completed(
                    invocation.route,
                    invocation.sender,
                    invocation.executor,
                    started.elapsedNow(),
                    observedArguments,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejection: CommandRejectedException) {
            if (invocation.sender.canDeliver()) invocation.sender.audience.sendMessage(rejection.response)
            observe(
                CommandEvent.Rejected(
                    invocation.route,
                    invocation.sender,
                    invocation.executor,
                    started.elapsedNow(),
                    observedArguments,
                ),
            )
        } catch (failure: CommandArgumentException) {
            val name = route.parameters.getOrNull(raw.size - 1)?.name ?: "argument"
            if (invocation.sender.canDeliver()) {
                invocation.sender.audience.sendMessage(
                    messages.invalidArgument(invocation.sender.locale, name, failure.reason),
                )
            }
            observe(
                CommandEvent.Rejected(
                    invocation.route,
                    invocation.sender,
                    invocation.executor,
                    started.elapsedNow(),
                    observedArguments,
                ),
            )
        } catch (failure: Throwable) {
            val handled = handle(failure, invocation)
            if (handled == null) {
                runtime.logger.error("Command ${invocation.route} failed", failure)
                if (invocation.sender.canDeliver()) {
                    invocation.sender.audience.sendMessage(
                        messages.unexpectedFailure(invocation.sender.locale),
                    )
                }
            } else {
                if (invocation.sender.canDeliver()) {
                    handled.responses.forEach(invocation.sender.audience::sendMessage)
                }
            }
            observe(
                CommandEvent.Failed(
                    invocation.route,
                    invocation.sender,
                    invocation.executor,
                    started.elapsedNow(),
                    failure::class.qualifiedName.orEmpty(),
                    observedArguments,
                ),
            )
        }
    }

    private suspend fun executeGraphEdge(
        edge: CommandGraphEdge,
        invocation: CommandInvocation,
    ): Unit {
        val sender = invocation.sender.audience as? BukkitCommandSender
            ?: error("The command sender is no longer backed by Paper")
        suspend fun dispatch(command: String): Boolean = runtime.plugin.withGlobal {
            runtime.server.dispatchCommand(sender, command.removePrefix("/"))
        }
        when (edge) {
            is CommandGraphEdge.Redirect -> check(dispatch(edge.target.command)) {
                "Redirect target '${edge.target.command}' was rejected"
            }
            is CommandGraphEdge.Fork -> supervisorScope {
                edge.targets().map { route -> async { dispatch(route.command) } }
                    .forEach { result -> check(result.await()) { "Fork target was rejected" } }
            }
            is CommandGraphEdge.External -> {
                val accepted = dispatch(edge.command)
                check(accepted || edge.optional) { "Required external command '${edge.command}' is unavailable" }
            }
        }
    }

    private suspend fun customPolicies(
        definitions: List<CommandPolicyDefinition>,
        phase: CommandPolicyPhase,
        context: CommandPolicyContext,
        action: suspend () -> Any?,
    ): Any? {
        val policies = definitions.filterIsInstance<CommandPolicyDefinition.Custom>()
            .filter { definition -> definition.phase == phase }
            .sortedBy(CommandPolicyDefinition.Custom::order)
        suspend fun proceed(index: Int): Any? {
            if (index == policies.size) return action()
            val definition = policies[index]
            @Suppress("UNCHECKED_CAST")
            val interceptor = graph.get(definition.interceptor) as CommandPolicyInterceptor<Annotation>
            return interceptor.intercept(definition.annotation, context) { proceed(index + 1) }
        }
        return proceed(0)
    }

    private suspend fun encode(value: Any?): CommandResult {
        if (value == null || value is Unit || value is String ||
            value is net.kyori.adventure.text.Component || value is CommandResult
        ) {
            return commandResult(value)
        }
        val codec = graph.contributions(CommandResponseCodec::class)
            .singleOrNull { candidate -> candidate.type == value::class }
            ?: error("No CommandResponseCodec is registered for ${value::class.qualifiedName}")
        @Suppress("UNCHECKED_CAST")
        return (codec as CommandResponseCodec<Any>).encode(value)
    }

    private suspend fun handle(
        failure: Throwable,
        invocation: CommandInvocation,
    ): CommandResult? {
        val matching = graph.contributions(CommandExceptionHandler::class)
            .filter { handler -> handler.type.java.isInstance(failure) }
        val mostSpecific = matching.filter { candidate ->
            matching.none { other ->
                other !== candidate && candidate.type.java.isAssignableFrom(other.type.java)
            }
        }
        if (mostSpecific.isEmpty()) return null
        check(mostSpecific.size == 1) {
            "Multiple equally specific command exception handlers match ${failure::class.qualifiedName}"
        }
        @Suppress("UNCHECKED_CAST")
        return (mostSpecific.single() as CommandExceptionHandler<Throwable>).handle(failure, invocation)
    }

    private suspend fun observe(event: CommandEvent) {
        graph.contributions(CommandObserver::class).forEach { observer ->
            runCatching { observer.observe(event) }
                .onFailure { failure -> runtime.logger.warn("Command observer failed", failure) }
        }
    }
}

private fun encodeArgument(codec: CommandArgumentCodec<*>, value: Any?): String {
    if (value == null) return "<absent>"
    @Suppress("UNCHECKED_CAST")
    return (codec as CommandArgumentCodec<Any>).encode(value)
}

internal fun CommandSourceStack.snapshot(route: String): CommandInvocation {
    val sourceSender = sender
    val senderId = (sourceSender as? Entity)?.uniqueId
    val executorId = executor?.uniqueId
    return CommandInvocation(
        sender = CommandSender(
            name = sourceSender.name,
            uniqueId = senderId,
            locale = (sourceSender as? Player)?.locale() ?: Locale.ENGLISH,
            audience = sourceSender,
            permissionCheck = sourceSender::hasPermission,
            deliveryCheck = { (sourceSender as? Player)?.isOnline != false },
        ),
        executor = CommandExecutor(executor?.name ?: sourceSender.name, executorId ?: senderId),
        route = route,
    )
}

private suspend fun resolve(
    codec: CommandArgumentCodec<*>,
    raw: String,
    invocation: CommandInvocation,
    dependencies: DependencyResolver,
): Any = codec.resolve(raw, invocation, dependencies)
