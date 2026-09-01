package pink.alex.ashlar.di.ksp

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
                    FactoryParameterModel(
                        name = "database",
                        type = DependencyTypeModel(
                            packageName = "example",
                            typeNames = listOf("Database"),
                            arguments = listOf(
                                DependencyTypeModel("kotlin", listOf("String")),
                            ),
                        ),
                    ),
                ),
            ),
        ).toString()

        assertContains(generated, "internal class Repository__AshlarFactory")
        assertContains(
            generated,
            "private val databaseKey: DependencyKey<Database<String>> = DependencyKey(",
        )
        assertContains(generated, "rawType = Database::class")
        assertContains(generated, "DependencyType<String>(String::class)")
        assertContains(generated, "override val dependencies: List<DependencyKey<*>> = listOf(")
        assertContains(generated, "databaseKey,")
        assertContains(generated, "Repository(\n    database = resolver.get(databaseKey),")
        assertFalse(generated.contains("ServiceLoader"))
        assertFalse(generated.contains("synchronized"))
    }
}
