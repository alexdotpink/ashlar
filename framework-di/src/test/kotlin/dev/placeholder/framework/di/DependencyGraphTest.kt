package dev.placeholder.framework.di

import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

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
    fun `reports missing generated factories`() {
        val graph = DependencyGraph(javaClass.classLoader)

        val failure = assertFailsWith<IllegalStateException> {
            graph.get(ExampleDependency::class)
        }

        assertEquals(
            "No generated dependency factory for dev.placeholder.framework.di.ExampleDependency; " +
                "annotate its constructor with @Inject and enable framework DI processing",
            failure.message,
        )
    }

    @Test
    fun `derives stable factory names for nested classes`() {
        assertEquals(
            "dev.placeholder.framework.di.DependencyGraphTest_Nested__FrameworkFactory",
            generatedFactoryName(Nested::class),
        )
    }

    private class Nested
}

private data class ExampleDependency(val value: String)

private interface ExampleContract

private class ExampleImplementation : ExampleContract
