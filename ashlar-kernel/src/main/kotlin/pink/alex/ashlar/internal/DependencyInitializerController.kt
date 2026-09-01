package pink.alex.ashlar.internal

import pink.alex.ashlar.di.DependencyGraph
import pink.alex.ashlar.di.DependencyGraphInitializer

internal class DependencyInitializerController(
    private val initializers: List<DependencyGraphInitializer>,
) {
    private val resources: ArrayDeque<AutoCloseable> = ArrayDeque()

    fun initialize(graph: DependencyGraph) {
        initializers.forEach { initializer ->
            initializer.initialize(graph)?.let(resources::addLast)
        }
    }

    fun close(): List<Throwable> = buildList {
        while (resources.isNotEmpty()) {
            runCatching { resources.removeLast().close() }
                .exceptionOrNull()
                ?.let(::add)
        }
    }
}
