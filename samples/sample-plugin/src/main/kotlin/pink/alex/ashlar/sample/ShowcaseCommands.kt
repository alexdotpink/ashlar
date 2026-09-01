package pink.alex.ashlar.sample

import pink.alex.ashlar.commands.Command
import pink.alex.ashlar.commands.CommandFragment
import pink.alex.ashlar.commands.CommandInvocation
import pink.alex.ashlar.commands.CommandRenamed
import pink.alex.ashlar.commands.Commands
import pink.alex.ashlar.commands.ConfigureCommandGraph
import pink.alex.ashlar.commands.GreedyText
import pink.alex.ashlar.commands.Group
import pink.alex.ashlar.commands.Observed
import pink.alex.ashlar.commands.Option
import pink.alex.ashlar.commands.OptionValue
import pink.alex.ashlar.commands.Options
import pink.alex.ashlar.commands.Repeated
import pink.alex.ashlar.commands.Restricted
import pink.alex.ashlar.commands.Root
import pink.alex.ashlar.commands.Scope
import pink.alex.ashlar.commands.Sensitive
import pink.alex.ashlar.commands.graph.CommandGraph
import pink.alex.ashlar.commands.graph.CommandRefresh
import pink.alex.ashlar.commands.reference.PlayerRef
import pink.alex.ashlar.commands.reject
import pink.alex.ashlar.commands.requirePermission
import pink.alex.ashlar.commands.responses
import pink.alex.ashlar.commands.route.CommandDispatcher
import pink.alex.ashlar.commands.route.sensitive
import pink.alex.ashlar.commands.policy.CancelOnExecutorRetire
import pink.alex.ashlar.commands.policy.Confirm
import pink.alex.ashlar.commands.policy.Cooldown
import pink.alex.ashlar.commands.policy.CooldownMode
import pink.alex.ashlar.commands.policy.RateLimit
import pink.alex.ashlar.commands.policy.RateLimitMode
import pink.alex.ashlar.commands.policy.SingleFlight
import kotlinx.coroutines.delay
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor

@Commands(
    name = "showcase",
    aliases = ["sc"],
    optionalAliases = ["demo"],
    schemaVersion = 2,
)
internal class ShowcaseCommands(
    private val routes: ShowcaseCommandsRoutes,
    private val dispatcher: CommandDispatcher,
    private val refresh: CommandRefresh,
    private val state: ShowcaseState,
) {
    @ConfigureCommandGraph
    fun configureGraph(graph: CommandGraph) {
        graph.redirect(routes.redirectSource(), routes.graphTarget("redirect"))
        graph.fork(routes.forkSource()) {
            listOf(routes.graphTarget("alpha"), routes.graphTarget("beta"))
        }
        graph.external(routes.externalSource(), "version")
        graph.external(routes.optionalSource(), "definitely-not-installed", optional = true)
    }

    /** Opens the interactive starting point. */
    @Root
    fun home(): Component = menu(
        "Ashlar command showcase",
        "Basics" to routes.basicHello(),
        "Arguments" to routes.argumentLandmark(LandmarkName("lighthouse")),
        "Options" to routes.optionBundle("market square", ShowcaseSearchOptions(limit = 3, verbose = true)),
        "Graph redirect" to routes.redirectSource(),
        "Typed dispatcher" to routes.typedDispatch(),
        "Generated help" to null,
    )

    /** Shows a short playable path through the module. */
    fun guide(): Component = lines(
        "Suggested path",
        "/sc basics basic-hello Alex",
        "/sc arguments argument-landmark <tab>",
        "/sc options option-bundle market square --limit 3 --verbose --tag red --tag blue",
        "/sc responses response-multi",
        "/sc policies policy-cooldown  (run twice)",
        "/sc graph redirect-source",
        "/sc minecraft minecraft-overview",
        "/sc help 2",
    )

    /** Retains a temporary legacy spelling and a permanent short alias. */
    @Command(name = "modern-name", aliases = ["mn"])
    @CommandRenamed(from = "old-name", untilVersion = 3)
    fun migratedName(): String = "The canonical, aliased, and migrated spellings reached one handler."

    @Group(name = "basics", aliases = ["b"])
    inner class Basics {
        /** Optional positional values use Kotlin defaults. */
        fun basicHello(name: String = "world"): Component =
            Component.text("Hello, $name!", NamedTextColor.GREEN)

        /** Every built-in primitive codec uses native Brigadier syntax. */
        fun primitiveTypes(
            integer: Int,
            long: Long,
            float: Float,
            double: Double,
            boolean: Boolean,
            word: String,
        ): String = "int=$integer long=$long float=$float double=$double boolean=$boolean word=$word"

        /** Greedy text consumes the decoded terminal remainder. */
        fun greedyText(message: GreedyText): String = "Greedy text: $message"

        /** A repeated terminal parameter accepts as many words as supplied. */
        fun repeatedWords(@Repeated words: List<String>): String =
            "Repeated ${words.size}: ${words.joinToString()}"

        /** Unit is a valid response and deliberately sends nothing. */
        fun unitResponse() {
            state.record("unit-response")
        }
    }

    @Group(name = "arguments", aliases = ["args"])
    inner class Arguments {
        /** A custom codec validates values and a separate provider supplies tab completions. */
        fun argumentLandmark(landmark: LandmarkName): String = "Resolved landmark '${landmark.value}'."

        /** Type-use qualifiers select two codecs for the same Kotlin value class. */
        fun qualifiedWords(
            @Uppercase loud: StyledWord,
            @Lowercase quiet: StyledWord,
        ): String = "loud=${loud.value}, quiet=${quiet.value}"

        /** Observed values reach metadata while sensitive values are always redacted. */
        fun observedSecret(
            @Observed label: String,
            @Sensitive secret: String,
        ): String = "Recorded label '$label'; the ${secret.length}-character secret was not observed."

        /** Builds a sensitive typed route whose semantic identity hashes the secret. */
        fun sensitiveLink(): Component {
            val route = routes.observedSecret("from-link", sensitive("hidden-value"))
            return Component.text("Click to run a generated sensitive route", NamedTextColor.GOLD)
                .clickEvent(route.runLink())
                .append(Component.newline())
                .append(
                    Component.text("Click to suggest the same typed route", NamedTextColor.YELLOW)
                        .clickEvent(route.suggestLink()),
                )
        }
    }

    @Group(name = "options", aliases = ["o"])
    inner class OptionCommands {
        /** Direct options support short names, booleans, nullability, and explicit presence. */
        fun directOptions(
            @Option(short = 'c') count: Int?,
            @Option(short = 'v') verbose: Boolean,
            @Option note: OptionValue<String>,
        ): String = "count=$count verbose=$verbose note=$note"

        /** An options data class keeps Kotlin defaults and supports interleaved greedy input. */
        fun optionBundle(query: GreedyText, options: ShowcaseSearchOptions): String =
            "query='$query' limit=${options.limit} verbose=${options.verbose} exact=${options.exact} tags=${options.tags}"
    }

    /** A group constructor can resolve a shared native argument once for every child route. */
    @Group(name = "player", aliases = ["p"])
    inner class PlayerCommands(
        private val player: PlayerRef,
    ) {
        /** Displays the stable player identity captured before coroutine execution. */
        fun playerIdentity(): String = "Stable player reference: ${player.uniqueId}"

        /** Sends a targeted command-tree refresh through entity-safe access. */
        suspend fun refreshTree(): String {
            refresh.refresh(player)
            return "Refreshed that player's command tree."
        }
    }

    /** Scopes add invocation dependencies without adding a command literal. */
    @Scope
    inner class InvocationScope(
        private val invocation: CommandInvocation,
    ) {
        /** Displays invocation-scoped sender, executor, and route metadata. */
        fun whoAmI(): String =
            "sender=${invocation.sender.name}, executor=${invocation.executor.name}, route=${invocation.route}"

        /** Dynamic permission checks can provide a domain-specific denial. */
        @Restricted
        fun dynamicPermission(): String {
            invocation.requirePermission("showcase.dynamic") { "Ask an operator for showcase.dynamic." }
            return "Dynamic permission accepted."
        }

        /** Explicit dispatch submits a generated typed route through the same runtime. */
        suspend fun typedDispatch(): String {
            dispatcher.invoke(routes.basicHello("typed dispatcher"), invocation.sender)
            return "The generated route was dispatched; its greeting appears above."
        }
    }

    @Group(name = "graph", aliases = ["g"])
    inner class GraphCommands {
        /** Redirects to a typed target before this body can run. */
        fun redirectSource(): String = error("redirect edge was not followed")

        /** Forks into two supervised typed targets before this body can run. */
        fun forkSource(): String = error("fork edge was not followed")

        /** Dispatches Paper's external version command before this body can run. */
        fun externalSource(): String = error("external edge was not followed")

        /** Runs normally after an unavailable optional edge is removed at startup. */
        fun optionalSource(): String = "Missing optional edge was removed; fallback handler ran."

        /** Receives redirect and fork targets through one typed route. */
        fun graphTarget(value: String): String = "Graph target: $value"
    }

    /** Administrator operations inherit this permission once. */
    @Group(name = "admin", aliases = ["a"], permission = "showcase.admin")
    inner class Admin {
        /** Proves a permission inherited from a structural group. */
        fun restrictedRoute(): String = "Inherited administrator permission accepted."
    }

    /** Displays recent observer events so observed and sensitive metadata can be checked. */
    fun diagnostics(): Component = lines(
        "Recent command observer events",
        *state.recent().ifEmpty { listOf("No events yet") }.toTypedArray(),
    )

    private fun menu(title: String, vararg entries: Pair<String, pink.alex.ashlar.commands.route.CommandRoute?>): Component {
        val builder = Component.text().append(Component.text(title, NamedTextColor.GOLD))
        entries.forEach { (label, route) ->
            builder.append(Component.newline()).append(
                Component.text(" • $label", NamedTextColor.YELLOW).let { item ->
                    if (route == null) item.clickEvent(ClickEvent.runCommand("/showcase help"))
                    else item.clickEvent(route.runLink())
                },
            )
        }
        return builder.build()
    }
}

@Commands(name = "excluded-showcase")
internal class ExcludedShowcaseCommands {
    /** This route proves plug-in-level contribution exclusion when it remains unregistered. */
    fun shouldNeverRegister(): String = error("Excluded command contribution was registered")
}

@Options
internal data class ShowcaseSearchOptions(
    @Option(short = 'l') val limit: Int = 10,
    @Option(short = 'v') val verbose: Boolean = false,
    @Option val exact: Boolean = false,
    @Option(name = "tag") @Repeated val tags: List<String> = emptyList(),
)

@CommandFragment(ShowcaseCommands::class)
internal class ShowcaseResponseCommands(
    private val state: ShowcaseState,
) {
    @Group(name = "responses", aliases = ["r"])
    inner class Responses {
        /** Returns a plain String response. */
        fun responseString(): String = "Plain String response"

        /** Returns an Adventure component without flattening its formatting. */
        fun responseComponent(): Component = Component.text("Adventure Component response", NamedTextColor.LIGHT_PURPLE)

        /** Returns multiple ordered responses. */
        fun responseMulti() = responses {
            reply(Component.text("First response", NamedTextColor.GREEN))
            reply("Second response")
        }

        /** Returns a domain value handled by a contributed response codec. */
        fun responseCustom(): ShowcaseReceipt = state.nextReceipt("custom response codec")

        /** Throws the framework's expected stackless rejection. */
        fun responseReject(): Nothing = reject("Expected stackless rejection")

        /** Throws a domain error handled by the most-specific contributed handler. */
        fun responseException(): Nothing = throw ShowcaseDomainException("the typed handler selected this message")
    }
}

@CommandFragment(ShowcaseCommands::class)
internal class ShowcasePolicyCommands {
    @Group(name = "policies", aliases = ["policy"])
    inner class Policies {
        /** Charges a successful three-second cooldown. */
        @Cooldown(value = 3, mode = CooldownMode.SUCCESS)
        fun policyCooldown(): String = "Cooldown accepted. Try again within three seconds."

        /** Charges a cooldown as soon as the command is attempted. */
        @Cooldown(value = 3, mode = CooldownMode.ATTEMPT)
        fun policyAttemptCooldown(): String = "Attempt-charged cooldown accepted."

        /** Charges a cooldown as soon as the framework accepts the invocation. */
        @Cooldown(value = 3, mode = CooldownMode.ACCEPTED)
        fun policyAcceptedCooldown(): String = "Acceptance-charged cooldown accepted."

        /** Allows two token-bucket invocations every ten seconds. */
        @RateLimit(permits = 2, per = 10, mode = RateLimitMode.TOKEN_BUCKET)
        fun policyRateLimit(): String = "Token accepted. The third quick call is rejected."

        /** Allows two sliding-window invocations every ten seconds. */
        @RateLimit(permits = 2, per = 10, mode = RateLimitMode.SLIDING_WINDOW)
        fun policySlidingRate(): String = "Sliding-window permit accepted."

        /** Rejects overlapping work from the same sender and route. */
        @SingleFlight
        suspend fun policySingleFlight(): String {
            policyDelay()
            return "Single-flight work finished."
        }

        /** Requires the exact semantic invocation twice within twenty seconds. */
        @Confirm(expiresAfterSeconds = 20)
        fun policyConfirm(@Observed value: String): String = "Confirmed '$value' on the second identical invocation."

        /** Runs an injected custom interceptor before the handler. */
        @ShowcaseAudit("custom-policy")
        fun policyCustom(): String = "Custom injected policy ran before this handler."

        /** Cancels unfinished work if its player executor disconnects. */
        @CancelOnExecutorRetire
        suspend fun policyRetire(seconds: Int): String {
            delay(seconds.coerceIn(1, 30) * 1_000L)
            return "The executor stayed online until completion."
        }
    }
}

private fun lines(title: String, vararg values: String): Component {
    val builder = Component.text().append(Component.text(title, NamedTextColor.GOLD))
    values.forEach { value ->
        builder.append(Component.newline()).append(Component.text(" • $value", NamedTextColor.YELLOW))
    }
    return builder.build()
}
