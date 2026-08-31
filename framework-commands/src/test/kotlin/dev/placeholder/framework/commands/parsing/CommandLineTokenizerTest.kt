package dev.placeholder.framework.commands.parsing

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class CommandLineTokenizerTest {
    @Test
    fun `decodes whitespace quotes escapes and adjacent quoted segments`() {
        val tokens = CommandLineTokenizer.tokenize("  alpha  \"market square\" pre'fixed value' escaped\\ value \"\" ")

        assertEquals(
            listOf("alpha", "market square", "prefixed value", "escaped value", ""),
            tokens.map(CommandToken::value),
        )
        assertEquals(false, tokens.first().wasQuotedOrEscaped)
        assertEquals(true, tokens.drop(1).all(CommandToken::wasQuotedOrEscaped))
        assertEquals("alpha", "  alpha  \"market square\"".substring(tokens.first().startIndex, tokens.first().endIndex))
    }

    @Test
    fun `backslash escapes quote and backslash characters`() {
        val tokens = CommandLineTokenizer.tokenize("\"say \\\"hello\\\"\" path\\\\name")

        assertEquals(listOf("say \"hello\"", "path\\name"), tokens.map(CommandToken::value))
    }

    @Test
    fun `reports unterminated quotes at their source position`() {
        val failure = assertFailsWith<CommandLineSyntaxException> {
            CommandLineTokenizer.tokenize("ok \"unfinished")
        }

        assertEquals("unterminated \" quote", failure.reason)
        assertEquals(3, failure.position)
    }

    @Test
    fun `reports a trailing escape`() {
        val failure = assertFailsWith<CommandLineSyntaxException> {
            CommandLineTokenizer.tokenize("hello\\")
        }

        assertEquals("trailing escape", failure.reason)
        assertEquals(5, failure.position)
    }
}
