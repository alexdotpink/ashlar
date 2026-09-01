package pink.alex.ashlar.commands.ksp

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class CommandSetWriterTest {
    @Test
    fun `typed routes preserve repeated and generic argument shapes`() {
        val generated = CommandSetWriter().file(
            CommandSetModel(
                packageName = "example",
                typeName = "ShapeCommands",
                rootName = "shape",
                aliases = emptyList(),
                permission = null,
                routes = listOf(
                    RouteModel(
                        name = "repeat",
                        functionName = "repeat",
                        returnType = "kotlin.Unit",
                        suspending = false,
                        parameters = listOf(
                            ParameterModel(
                                name = "values",
                                type = "kotlin.collections.List",
                                optional = false,
                                repeated = true,
                                valueType = "kotlin.String",
                                collection = true,
                            ),
                        ),
                    ),
                    RouteModel(
                        name = "registry",
                        functionName = "registry",
                        returnType = "kotlin.Unit",
                        suspending = false,
                        parameters = listOf(
                            ParameterModel(
                                name = "value",
                                type = "example.RegistryValue",
                                optional = false,
                                typeArguments = listOf("kotlin.Any"),
                            ),
                        ),
                    ),
                ),
            ),
        ).toString()

        assertContains(generated, "for (argumentValue in values)")
        assertContains(generated, "RegistryValue<Any>")
        assertContains(generated, "@Suppress(\"UNCHECKED_CAST\")")
    }

    @Test
    fun `generates one small binding with direct calls`() {
        val generated = CommandSetWriter().file(
            CommandSetModel(
                packageName = "example",
                typeName = "GreetingCommands",
                rootName = "ashlar",
                aliases = listOf("fw"),
                permission = "framework.greet",
                routes = listOf(
                    RouteModel(
                        name = "greet",
                        functionName = "greet",
                        returnType = "kotlin.String",
                        suspending = false,
                        parameters = listOf(
                            ParameterModel("name", "kotlin.String", optional = true),
                        ),
                        policies = listOf(
                            PolicyModel.Cooldown(3, "SUCCESS"),
                            PolicyModel.SingleFlight,
                        ),
                    ),
                ),
            ),
        ).toString()

        assertContains(generated, "internal class GreetingCommandsGeneratedBinding")
        assertContains(generated, "override val targetType: KClass<GreetingCommands>")
        assertContains(generated, "target.greet(")
        assertContains(generated, "name = arguments[0] as String,")
        assertContains(generated, "CommandPolicyDefinition.Cooldown(seconds = 3L, mode = CooldownMode.SUCCESS)")
        assertContains(generated, "CommandPolicyDefinition.SingleFlight")
        assertContains(generated, "internal fun commands(target: GreetingCommands): PluginComponent")
        assertFalse(generated.contains("brigadier", ignoreCase = true))
        assertFalse(generated.contains("permission", ignoreCase = true) && generated.contains("hasPermission"))
    }

    @Test
    fun `generates option metadata and one direct defaults factory`() {
        val options = OptionsModel(
            "example.SearchOptions",
            listOf(
                OptionMemberModel(
                    propertyName = "tags",
                    declaredType = "kotlin.collections.List",
                    option = OptionModel("tag", 't', "kotlin.String", repeated = true),
                    hasDefault = true,
                    collection = true,
                ),
            ),
        )
        val generated = CommandSetWriter().file(
            CommandSetModel(
                packageName = "example",
                typeName = "SearchCommands",
                rootName = "search",
                aliases = emptyList(),
                permission = null,
                routes = listOf(
                    RouteModel(
                        name = "find",
                        functionName = "find",
                        returnType = "kotlin.Unit",
                        suspending = false,
                        parameters = listOf(
                            ParameterModel("query", "kotlin.String", false),
                            ParameterModel("options", "example.SearchOptions", false, options = options),
                        ),
                        segments = listOf(
                            SegmentModel.Literal(listOf("find"), emptyList()),
                            SegmentModel.ScannedArguments(0),
                        ),
                    ),
                ),
            ),
        ).toString()

        assertContains(generated, "private val route0Parameter1Defaults: SearchOptions = SearchOptions()")
        assertContains(generated, "override fun optionDefaults(route: Int, parameter: Int): List<Any?>")
        assertContains(generated, "override fun constructOptions(")
        assertContains(generated, "CommandOptionDefinition(")
        assertContains(generated, "ScannedArguments(0)")
    }
}
