package dev.placeholder.framework.commands.parsing

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class NamedOptionScannerTest {
    private val scanner = NamedOptionScanner(
        listOf(
            NamedOptionDefinition("owner", 'o'),
            NamedOptionDefinition("world", 'w'),
            NamedOptionDefinition("description", 'd'),
            NamedOptionDefinition("verbose", 'v', NamedOptionKind.BOOLEAN),
            NamedOptionDefinition("tag", 't', repeated = true),
        ),
    )

    @Test
    fun `extracts long short and attached options from anywhere`() {
        val parsed = scanner.scan("market --owner Alex square -w world_nether --description=shops")

        assertEquals(listOf("market", "square"), parsed.positionals)
        assertEquals("Alex", parsed.value("owner"))
        assertEquals("world_nether", parsed.value("world"))
        assertEquals("shops", parsed.value("description"))
        assertEquals("market square", parsed.remainder())
    }

    @Test
    fun `supports every boolean spelling`() {
        assertEquals(true, scanner.scan("--verbose").boolean("verbose"))
        assertEquals(false, scanner.scan("--no-verbose").boolean("verbose"))
        assertEquals(false, scanner.scan("--verbose=false").boolean("verbose"))
        assertEquals(true, scanner.scan("-v").boolean("verbose"))
        assertNull(scanner.scan("market").boolean("verbose"))
    }

    @Test
    fun `standalone delimiter preserves every remaining token as positional`() {
        val parsed = scanner.scan("--owner Alex -- --verbose -w world")

        assertEquals(listOf("--verbose", "-w", "world"), parsed.positionals)
        assertEquals("Alex", parsed.value("owner"))
    }

    @Test
    fun `quoted and escaped option text remains positional`() {
        val parsed = scanner.scan("\"--verbose\" \\--owner")

        assertEquals(listOf("--verbose", "--owner"), parsed.positionals)
        assertNull(parsed.boolean("verbose"))
    }

    @Test
    fun `repeated collection values retain encounter order`() {
        val parsed = scanner.scan("--tag first middle -t second --tag=third")

        assertEquals(listOf("first", "second", "third"), parsed.values("tag"))
        assertEquals(listOf("middle"), parsed.positionals)
    }

    @Test
    fun `repeating scalar options is rejected`() {
        val failure = assertFailsWith<CommandLineSyntaxException> {
            scanner.scan("--owner Alex -o Steve")
        }

        assertEquals("option '--owner' cannot be repeated", failure.reason)
    }

    @Test
    fun `unknown bundled missing and invalid boolean options are rejected`() {
        assertFailure("unknown option '--missing'") { scanner.scan("--missing value") }
        assertFailure("bundled short options are not supported") { scanner.scan("-vw world") }
        assertFailure("option '--owner' requires a value") { scanner.scan("--owner --") }
        assertFailure("option '--owner' requires a value") { scanner.scan("--owner --world nether") }
        assertFailure("option '--verbose' expects true or false") { scanner.scan("--verbose=maybe") }
    }

    @Test
    fun `negative numbers remain positional and empty attached values are retained`() {
        val parsed = scanner.scan("-42 --description= --owner=-Alex --world \"--literal\"")

        assertEquals(listOf("-42"), parsed.positionals)
        assertEquals("", parsed.value("description"))
        assertEquals("-Alex", parsed.value("owner"))
        assertEquals("--literal", parsed.value("world"))
    }

    @Test
    fun `schema rejects duplicate long and short names`() {
        assertFailsWith<IllegalArgumentException> {
            NamedOptionScanner(listOf(NamedOptionDefinition("one"), NamedOptionDefinition("one")))
        }
        assertFailsWith<IllegalArgumentException> {
            NamedOptionScanner(listOf(NamedOptionDefinition("one", 'x'), NamedOptionDefinition("two", 'x')))
        }
    }

    private fun assertFailure(expected: String, block: () -> Unit) {
        assertEquals(expected, assertFailsWith<CommandLineSyntaxException>(block = block).reason)
    }
}
