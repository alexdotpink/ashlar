package pink.alex.ashlar.commands.internal

import pink.alex.ashlar.commands.OptionValue
import pink.alex.ashlar.commands.codegen.CommandOptionDefinition
import pink.alex.ashlar.commands.codegen.CommandOptionMemberDefinition
import pink.alex.ashlar.commands.codegen.CommandOptionsDefinition
import pink.alex.ashlar.commands.codegen.CommandParameterDefinition
import pink.alex.ashlar.commands.codegen.CommandRouteDefinition
import pink.alex.ashlar.commands.codegen.CommandSetContribution
import pink.alex.ashlar.commands.codegen.CommandSetDefinition
import pink.alex.ashlar.commands.codegen.MissingCommandArgument
import pink.alex.ashlar.di.DependencyResolver
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ScannedCommandArgumentsTest {
    @Test
    fun `binds options interleaved with a decoded greedy positional`() = runBlocking {
        val route = route(
            CommandParameterDefinition("query", false, String::class, greedy = true),
            CommandParameterDefinition(
                "owner",
                false,
                String::class,
                nullable = true,
                option = option("owner", shortName = 'o', nullable = true),
            ),
            CommandParameterDefinition(
                "verbose",
                false,
                Boolean::class,
                option = option("verbose", type = Boolean::class, presenceAware = true),
            ),
            CommandParameterDefinition(
                "filters",
                false,
                FilterOptions::class,
                options = CommandOptionsDefinition(
                    listOf(
                        CommandOptionMemberDefinition("tags", option("tag", repeated = true)),
                        CommandOptionMemberDefinition("limit", option("limit", type = Int::class)),
                    ),
                ),
            ),
        )
        val binding = TestBinding(route)

        val arguments = scanner(route, binding).scan(
            "market --owner Alex square --tag first --verbose --limit=3 --tag second",
            0,
        )

        assertEquals("market square", arguments[0])
        assertEquals("Alex", arguments[1])
        assertEquals(OptionValue.Present(true), arguments[2])
        assertEquals(FilterOptions(listOf("first", "second"), 3), arguments[3])
    }

    @Test
    fun `uses generated option defaults and preserves explicit absence`() = runBlocking {
        val route = route(
            CommandParameterDefinition(
                "owner",
                false,
                String::class,
                nullable = true,
                option = option("owner", nullable = true),
            ),
            CommandParameterDefinition(
                "verbose",
                false,
                Boolean::class,
                option = option("verbose", type = Boolean::class, presenceAware = true),
            ),
            CommandParameterDefinition(
                "filters",
                false,
                FilterOptions::class,
                options = CommandOptionsDefinition(
                    listOf(
                        CommandOptionMemberDefinition("tags", option("tag", repeated = true)),
                        CommandOptionMemberDefinition("limit", option("limit", type = Int::class)),
                    ),
                ),
            ),
        )

        val arguments = scanner(route, TestBinding(route)).scan("", 0)

        assertEquals(null, arguments[0])
        assertSame(OptionValue.Absent, arguments[1])
        assertEquals(FilterOptions(), arguments[2])
    }

    @Test
    fun `collects a repeated terminal positional and marks an omitted Kotlin default`() = runBlocking {
        val repeated = route(CommandParameterDefinition("names", false, String::class, repeated = true))
        assertEquals<List<Any?>>(
            listOf(listOf("one", "two", "three")),
            scanner(repeated, TestBinding(repeated)).scan("one two three", 0),
        )

        val optional = route(CommandParameterDefinition("page", true, Int::class))
        assertSame(
            MissingCommandArgument,
            scanner(optional, TestBinding(optional)).scan("", 0).single(),
        )
    }

    @Test
    fun `requires a non-null direct option`() {
        val route = route(
            CommandParameterDefinition(
                "owner",
                false,
                String::class,
                option = option("owner"),
            ),
        )

        val failure = assertFailsWith<pink.alex.ashlar.commands.codec.CommandArgumentException> {
            runBlocking { scanner(route, TestBinding(route)).scan("", 0) }
        }

        assertEquals("missing required option '--owner'", failure.reason)
    }

    private fun scanner(
        route: CommandRouteDefinition,
        binding: CommandSetContribution,
    ): ScannedCommandArguments = ScannedCommandArguments(0, route, binding) { type, _, raw ->
        when (type) {
            String::class -> raw
            Int::class -> raw.toInt()
            Boolean::class -> raw.toBooleanStrict()
            else -> error("Unexpected type $type")
        }
    }

    private fun route(vararg parameters: CommandParameterDefinition): CommandRouteDefinition =
        CommandRouteDefinition("test", parameters.toList())

    private fun option(
        name: String,
        shortName: Char? = null,
        type: KClass<*> = String::class,
        nullable: Boolean = false,
        repeated: Boolean = false,
        presenceAware: Boolean = false,
    ): CommandOptionDefinition = CommandOptionDefinition(
        name,
        shortName,
        type,
        nullable,
        repeated,
        presenceAware,
    )

    private data class FilterOptions(
        val tags: List<String> = emptyList(),
        val limit: Int = 10,
    )

    private class TestBinding(route: CommandRouteDefinition) : CommandSetContribution {
        override val targetType: KClass<*> = Any::class
        override val definition: CommandSetDefinition =
            CommandSetDefinition("test", emptyList(), null, listOf(route))
        private val defaults = FilterOptions()

        override suspend fun invoke(
            target: Any,
            route: Int,
            arguments: List<Any?>,
            dependencies: DependencyResolver,
        ): Any? = error("Not used")

        override fun optionDefaults(route: Int, parameter: Int): List<Any?> =
            listOf(defaults.tags, defaults.limit)

        @Suppress("UNCHECKED_CAST")
        override fun constructOptions(route: Int, parameter: Int, values: List<Any?>): Any =
            FilterOptions(
                tags = values[0] as List<String>,
                limit = values[1] as Int,
            )
    }
}
