package dev.placeholder.framework.commands.parsing

/** One decoded command-line token and its location in the original input. */
public data class CommandToken(
    public val value: String,
    public val startIndex: Int,
    /** Exclusive source index immediately after this token. */
    public val endIndex: Int,
    /** Whether quoting or escaping makes option-looking text literal. */
    public val wasQuotedOrEscaped: Boolean,
)

/** A command-line syntax error suitable for rendering without a stack trace. */
public class CommandLineSyntaxException(
    public val reason: String,
    public val position: Int? = null,
) : RuntimeException(reason, null, false, false)

/** Decodes whitespace-separated tokens with single/double quotes and backslash escapes. */
public object CommandLineTokenizer {
    public fun tokenize(input: String): List<CommandToken> {
        val tokens = mutableListOf<CommandToken>()
        var cursor = 0

        while (cursor < input.length) {
            while (cursor < input.length && input[cursor].isWhitespace()) cursor++
            if (cursor == input.length) break

            val start = cursor
            val value = StringBuilder()
            var quote: Char? = null
            var quoteStart = -1
            var decorated = false

            while (cursor < input.length && (quote != null || !input[cursor].isWhitespace())) {
                val character = input[cursor]
                when {
                    character == '\\' -> {
                        decorated = true
                        if (cursor + 1 == input.length) {
                            throw CommandLineSyntaxException("trailing escape", cursor)
                        }
                        value.append(input[cursor + 1])
                        cursor += 2
                    }
                    quote != null && character == quote -> {
                        decorated = true
                        quote = null
                        cursor++
                    }
                    quote == null && (character == '\'' || character == '"') -> {
                        decorated = true
                        quote = character
                        quoteStart = cursor
                        cursor++
                    }
                    else -> {
                        value.append(character)
                        cursor++
                    }
                }
            }

            if (quote != null) {
                throw CommandLineSyntaxException("unterminated $quote quote", quoteStart)
            }
            tokens += CommandToken(value.toString(), start, cursor, decorated)
        }

        return tokens
    }
}
