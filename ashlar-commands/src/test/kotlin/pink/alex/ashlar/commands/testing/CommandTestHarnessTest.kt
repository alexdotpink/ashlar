package pink.alex.ashlar.commands.testing

import pink.alex.ashlar.commands.CommandExecutor
import pink.alex.ashlar.commands.CommandInvocation
import pink.alex.ashlar.commands.CommandSender
import pink.alex.ashlar.commands.codegen.CommandParameterDefinition
import pink.alex.ashlar.commands.codegen.CommandRouteDefinition
import pink.alex.ashlar.commands.codegen.CommandSegmentDefinition
import pink.alex.ashlar.commands.codegen.CommandSetBinding
import pink.alex.ashlar.commands.codegen.CommandSetDefinition
import pink.alex.ashlar.di.DependencyGraph
import pink.alex.ashlar.di.DependencyResolver
import java.util.Locale
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component

class CommandTestHarnessTest {
    @Test
    fun `executes optional and greedy generated plans without a server`() = runTest {
        val target = Target()
        val harness = CommandTestHarness(Binding, target, DependencyGraph(javaClass.classLoader))

        assertEquals(
            Component.text("Hello, there"),
            harness.execute("/example greet", invocation).responses.single(),
        )
        harness.execute("example say market square", invocation)

        assertEquals("market square", target.lastMessage)
    }

    private class Target {
        var lastMessage: String? = null

        fun greet(name: String = "there"): String = "Hello, $name"

        fun say(message: String): Unit {
            lastMessage = message
        }
    }

    private object Binding : CommandSetBinding<Target> {
        override val targetType: KClass<Target> = Target::class
        override val definition: CommandSetDefinition = CommandSetDefinition(
            name = "example",
            aliases = emptyList(),
            permission = null,
            routes = listOf(
                CommandRouteDefinition(
                    name = "greet",
                    parameters = listOf(CommandParameterDefinition("name", optional = true)),
                ),
                CommandRouteDefinition(
                    name = "say",
                    parameters = listOf(CommandParameterDefinition("message", optional = false, greedy = true)),
                    segments = listOf(
                        CommandSegmentDefinition.Literal(listOf("say")),
                        CommandSegmentDefinition.Argument(0),
                    ),
                ),
            ),
        )

        override suspend fun invokeTyped(
            target: Target,
            route: Int,
            arguments: List<Any?>,
            dependencies: DependencyResolver,
        ): Any? = when (route) {
            0 -> if (arguments.isEmpty()) target.greet() else target.greet(arguments.single() as String)
            1 -> target.say(arguments.single() as String)
            else -> error("unknown route")
        }
    }

    private companion object {
        val invocation = CommandInvocation(
            sender = CommandSender("test", null, Locale.ENGLISH, Audience.empty()) { true },
            executor = CommandExecutor("test", null),
            route = "test",
        )
    }
}
