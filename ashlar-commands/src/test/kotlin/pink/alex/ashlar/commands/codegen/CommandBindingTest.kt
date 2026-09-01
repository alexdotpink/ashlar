package pink.alex.ashlar.commands.codegen

import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CommandBindingTest {
    @Test
    fun `plain strings become literal Adventure components`() {
        val response = commandResponse("Hello") as CommandResponse.Message

        assertEquals(Component.text("Hello"), response.value)
    }

    @Test
    fun `Adventure components retain their structure`() {
        val component = Component.text("Hello").append(Component.text(" there"))

        val response = commandResponse(component) as CommandResponse.Message

        assertEquals(component, response.value)
    }
}
