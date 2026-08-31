package dev.placeholder.framework.di.ksp

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class DiCodeWriterTest {
    @Test
    fun `factory contains one direct constructor call and no graph behavior`() {
        val generated = DiCodeWriter().factory(
            FactoryModel(
                packageName = "example",
                typeNames = listOf("Repository"),
                qualifiedName = "example.Repository",
                lifetime = LifetimeModel.PLUGIN,
                parameters = listOf(
                    FactoryParameterModel("database", "example", listOf("Database")),
                ),
            ),
        ).toString()

        assertContains(generated, "internal class Repository__FrameworkFactory")
        assertContains(generated, "Repository(\n    database = resolver.get(Database::class),")
        assertFalse(generated.contains("ServiceLoader"))
        assertFalse(generated.contains("synchronized"))
    }
}
