package pink.alex.ashlar.di.ksp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DiModelValidatorTest {
    @Test
    fun `generic injectable declarations report an actionable diagnostic`() {
        val problems = DiModelValidator().validate(
            FactoryModel(
                packageName = "example",
                typeNames = listOf("Repository"),
                qualifiedName = "example.Repository",
                lifetime = LifetimeModel.PLUGIN,
                parameters = emptyList(),
                typeParameters = listOf("T"),
            ),
        )

        assertEquals(
            listOf(
                "Injectable dependency 'example.Repository' declares unresolved type parameters <T>; " +
                    "bind closed generic instances explicitly instead",
            ),
            problems,
        )
    }
}
