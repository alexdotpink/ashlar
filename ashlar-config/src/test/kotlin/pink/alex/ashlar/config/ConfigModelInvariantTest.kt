package pink.alex.ashlar.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigModelInvariantTest {
    @Test
    fun `key paths have a stable dotted representation`() {
        assertEquals("database.pool.maximum", ConfigKeyPath("database", "pool", "maximum").toString())
    }

    @Test
    fun `source locations are one based`() {
        assertFailsWith<IllegalArgumentException> { ConfigSourceLocation(0, 1) }
        assertFailsWith<IllegalArgumentException> { ConfigSourceLocation(1, 0) }
    }

    @Test
    fun `resource limits cannot disable mandatory bounds`() {
        assertFailsWith<IllegalArgumentException> { ConfigLimits(maximumBytes = 0) }
        assertFailsWith<IllegalArgumentException> { ConfigLimits(maximumDepth = 0) }
        assertFailsWith<IllegalArgumentException> { ConfigLimits(maximumScalarCharacters = 0) }
        assertFailsWith<IllegalArgumentException> { ConfigLimits(maximumAliases = -1) }
    }
}
