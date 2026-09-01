package pink.alex.ashlar.di

import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DependencyGraphTest {
    @Test
    fun `framework defaults do not replace explicit bindings`() {
        val graph = DependencyGraph(javaClass.classLoader)
        val explicit = ExampleImplementation()
        graph.bind(explicit, listOf(ExampleContract::class))

        val installed = graph.bindDefault(ExampleContract::class, ExampleImplementation())

        assertFalse(installed)
        assertSame(explicit, graph.get(ExampleContract::class))
    }

    @Test
    fun `returns an explicitly bound instance`() {
        val graph = DependencyGraph(javaClass.classLoader)
        val value = ExampleDependency("bound")

        graph.bind(value)

        assertSame(value, graph.get(ExampleDependency::class))
    }

    @Test
    fun `binds explicit interface views`() {
        val graph = DependencyGraph(javaClass.classLoader)
        val value = ExampleImplementation()

        graph.bind(value, listOf(ExampleContract::class))

        assertSame(value, graph.get(ExampleContract::class))
    }

    @Test
    fun `invocation bindings are isolated and discarded`() {
        val graph = DependencyGraph(javaClass.classLoader)
        val first = ExampleDependency("first")
        val second = ExampleDependency("second")

        graph.invocation(first).use { invocation ->
            assertSame(first, invocation.get(ExampleDependency::class))
        }
        graph.invocation(second).use { invocation ->
            assertSame(second, invocation.get(ExampleDependency::class))
        }
    }

    @Test
    fun `closed generic bindings keep their complete type identity`() {
        val graph = DependencyGraph(javaClass.classLoader)
        val strings = ExampleRepository<String>("strings")
        val numbers = ExampleRepository<Int>("numbers")
        val stringKey = repositoryKey<String>()
        val numberKey = repositoryKey<Int>()

        graph.bind(stringKey, strings)
        graph.bind(numberKey, numbers)

        assertSame(strings, graph.get(stringKey))
        assertSame(numbers, graph.get(numberKey))
    }

    @Test
    fun `qualifiers and defaults apply to complete generic keys`() {
        val graph = DependencyGraph(javaClass.classLoader)
        val unqualified = ExampleRepository<String>("default")
        val qualified = ExampleRepository<String>("qualified")
        val unqualifiedKey = repositoryKey<String>()
        val qualifiedKey = DependencyKey(unqualifiedKey.dependencyType, ExampleQualifier::class)

        assertTrue(graph.bindDefault(unqualifiedKey, unqualified))
        graph.bind(qualifiedKey, qualified)

        assertSame(unqualified, graph.get(unqualifiedKey))
        assertSame(qualified, graph.get(qualifiedKey))
    }

    @Test
    fun `invocation resolvers delegate the exact generic key to their parent`() {
        val graph = DependencyGraph(javaClass.classLoader)
        val strings = ExampleRepository<String>("strings")
        val numbers = ExampleRepository<Int>("numbers")
        val stringKey = repositoryKey<String>()
        val numberKey = repositoryKey<Int>()
        graph.bind(stringKey, strings)
        graph.bind(numberKey, numbers)

        graph.invocation().use { invocation ->
            assertSame(strings, invocation.get(stringKey))
            assertSame(numbers, invocation.get(numberKey))
        }
    }

    @Test
    fun `structural keys render complete diagnostics`() {
        val key = DependencyKey(
            dependencyType = DependencyType<ExampleRepository<List<String>>>(
                rawType = ExampleRepository::class,
                arguments = listOf(
                    DependencyType<List<String>>(
                        rawType = List::class,
                        arguments = listOf(DependencyType<String>(String::class)),
                    ),
                ),
            ),
            qualifier = ExampleQualifier::class,
        )

        assertEquals(
            "@pink.alex.ashlar.di.ExampleQualifier " +
                "pink.alex.ashlar.di.ExampleRepository<kotlin.collections.List<kotlin.String>>",
            key.toString(),
        )
    }

    @Test
    fun `missing generic factories identify the complete requested type`() {
        val graph = DependencyGraph(javaClass.classLoader)

        val failure = assertFailsWith<IllegalStateException> {
            graph.get(repositoryKey<String>())
        }

        assertEquals(
            "No generated dependency factory for " +
                "pink.alex.ashlar.di.ExampleRepository<kotlin.String>; " +
                "annotate its constructor with @Inject and enable framework DI processing",
            failure.message,
        )
    }

    @Test
    fun `reports missing generated factories`() {
        val graph = DependencyGraph(javaClass.classLoader)

        val failure = assertFailsWith<IllegalStateException> {
            graph.get(ExampleDependency::class)
        }

        assertEquals(
            "No generated dependency factory for pink.alex.ashlar.di.ExampleDependency; " +
                "annotate its constructor with @Inject and enable framework DI processing",
            failure.message,
        )
    }

    @Test
    fun `derives stable factory names for nested classes`() {
        assertEquals(
            "pink.alex.ashlar.di.DependencyGraphTest_Nested__AshlarFactory",
            generatedFactoryName(Nested::class),
        )
    }

    private class Nested
}

private data class ExampleDependency(val value: String)

private interface ExampleContract

private class ExampleImplementation : ExampleContract

private class ExampleRepository<T>(val name: String)

@DependencyQualifier
private annotation class ExampleQualifier

private inline fun <reified T : Any> repositoryKey(): DependencyKey<ExampleRepository<T>> =
    DependencyKey(
        DependencyType<ExampleRepository<T>>(
            rawType = ExampleRepository::class,
            arguments = listOf(DependencyType<T>(T::class)),
        ),
    )
