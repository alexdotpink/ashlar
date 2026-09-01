package pink.alex.ashlar.commands.ksp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandSetValidatorTest {
    private val validator = CommandSetValidator()

    @Test
    fun `accepts the first command slice`() {
        assertTrue(validator.validate(validModel()).isEmpty())
    }

    @Test
    fun `rejects required parameters after optional parameters`() {
        val model = validModel().copy(
            routes = listOf(
                validModel().routes.single().copy(
                    parameters = listOf(
                        ParameterModel("first", "kotlin.String", optional = true),
                        ParameterModel("second", "kotlin.String", optional = false),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf("Required command parameter 'greet.second' cannot follow an optional parameter"),
            validator.validate(model),
        )
    }

    @Test
    fun `rejects invalid built-in policy configuration`() {
        val model = validModel().copy(
            routes = listOf(
                validModel().routes.single().copy(
                    policies = listOf(
                        PolicyModel.Cooldown(0, "SUCCESS"),
                        PolicyModel.RateLimit(0, 0, "TOKEN_BUCKET"),
                        PolicyModel.Confirm(0),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                "Cooldown on 'greet' must be positive",
                "Rate limit permits on 'greet' must be positive",
                "Rate limit duration on 'greet' must be positive",
                "Confirmation expiry on 'greet' must be positive",
            ),
            validator.validate(model),
        )
    }

    private fun validModel(): CommandSetModel =
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
                ),
            ),
        )
}
