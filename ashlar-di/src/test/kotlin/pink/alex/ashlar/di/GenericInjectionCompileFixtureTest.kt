package pink.alex.ashlar.di

import org.junit.jupiter.api.Test
import kotlin.test.assertSame

class GenericInjectionCompileFixtureTest {
    @Test
    fun `generated factory resolves nested and qualified generic dependencies`() {
        val graph = DependencyGraph(javaClass.classLoader)
        val nested = CompileRepository<List<String>>(listOf("nested"))
        val qualified = CompileRepository(42)
        graph.bind(compileRepositoryKey<List<String>>(), nested)
        graph.bind(
            DependencyKey(compileRepositoryKey<Int>().dependencyType, CompileQualifier::class),
            qualified,
        )

        val fixture = graph.get(GenericInjectionCompileFixture::class)

        assertSame(nested, fixture.nested)
        assertSame(qualified, fixture.qualified)
    }
}

@Inject
internal class GenericInjectionCompileFixture(
    val nested: CompileRepository<List<String>>,
    @CompileQualifier val qualified: CompileRepository<Int>,
)

internal class CompileRepository<T>(val value: T)

@DependencyQualifier
internal annotation class CompileQualifier

private inline fun <reified T : Any> compileRepositoryKey(): DependencyKey<CompileRepository<T>> =
    DependencyKey(
        DependencyType<CompileRepository<T>>(
            rawType = CompileRepository::class,
            arguments = listOf(dependencyType<T>()),
        ),
    )

private inline fun <reified T : Any> dependencyType(): DependencyType<T> = when (T::class) {
    List::class -> {
        @Suppress("UNCHECKED_CAST")
        DependencyType<List<String>>(
            rawType = List::class,
            arguments = listOf(DependencyType<String>(String::class)),
        ) as DependencyType<T>
    }

    else -> DependencyType(T::class)
}
