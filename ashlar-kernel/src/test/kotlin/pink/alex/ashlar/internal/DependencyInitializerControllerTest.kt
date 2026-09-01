package pink.alex.ashlar.internal

import org.junit.jupiter.api.Test
import pink.alex.ashlar.di.DependencyGraph
import pink.alex.ashlar.di.DependencyGraphInitializer
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DependencyInitializerControllerTest {
    @Test
    fun `initializers run before their resources close in reverse order`() {
        val actions = mutableListOf<String>()
        val graph = DependencyGraph(javaClass.classLoader)
        val controller = DependencyInitializerController(
            listOf(
                initializer("first", actions),
                initializer("second", actions),
            ),
        )

        controller.initialize(graph)
        val failures = controller.close()

        assertEquals(emptyList(), failures)
        assertEquals(listOf("start:first", "start:second", "close:second", "close:first"), actions)
    }

    @Test
    fun `close continues after one resource fails`() {
        val expected = IllegalStateException("broken close")
        val controller = DependencyInitializerController(
            listOf(
                DependencyGraphInitializer { AutoCloseable { throw expected } },
                DependencyGraphInitializer { AutoCloseable {} },
            ),
        )
        controller.initialize(DependencyGraph(javaClass.classLoader))

        val failures = controller.close()

        assertEquals(1, failures.size)
        assertIs<IllegalStateException>(failures.single())
    }

    private fun initializer(
        name: String,
        actions: MutableList<String>,
    ): DependencyGraphInitializer = DependencyGraphInitializer {
        actions += "start:$name"
        AutoCloseable { actions += "close:$name" }
    }
}
