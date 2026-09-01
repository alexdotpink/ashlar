package pink.alex.ashlar.sample

import pink.alex.ashlar.commands.codegen.CommandSetDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import net.kyori.adventure.text.Component

internal class ShowcaseHelpRendererTest {
    private val renderer = ShowcaseHelpRenderer()
    private val rendered = Component.text("generated help")

    @Test
    fun `event help is not decorated with command showcase copy`() {
        assertEquals(
            rendered,
            renderer.decorate(CommandSetDefinition("events", emptyList(), null, emptyList()), rendered),
        )
    }

    @Test
    fun `showcase help retains its guide header`() {
        assertNotEquals(
            rendered,
            renderer.decorate(CommandSetDefinition("showcase", emptyList(), null, emptyList()), rendered),
        )
    }
}
