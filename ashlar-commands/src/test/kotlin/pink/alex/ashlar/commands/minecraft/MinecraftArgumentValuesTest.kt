package pink.alex.ashlar.commands.minecraft

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kyori.adventure.key.Key
import org.bukkit.Axis

class MinecraftArgumentValuesTest {
    @Test
    fun `selections copy mutable source collections`() {
        val axes = mutableSetOf(Axis.X)
        val selection = AxisSelection(axes)
        axes += Axis.Z

        assertEquals(setOf(Axis.X), selection.axes)

        val properties = mutableListOf(ProfilePropertySnapshot("textures", "value"))
        val profile = PlayerProfileSnapshot(UUID.randomUUID(), "Alex", properties)
        properties.clear()

        assertEquals(1, profile.properties.size)
    }

    @Test
    fun `ranges are inclusive and validate their bounds`() {
        val integers = MinecraftIntegerRange(minimum = 2, maximum = 4)
        assertTrue(2 in integers)
        assertTrue(4 in integers)
        assertFalse(5 in integers)

        val doubles = MinecraftDoubleRange(maximum = 1.5)
        assertTrue(-100.0 in doubles)
        assertFalse(Double.NaN in doubles)
        assertFailsWith<IllegalArgumentException> { MinecraftIntegerRange(2, 1) }
        assertFailsWith<IllegalArgumentException> { MinecraftDoubleRange(Double.NaN, null) }
    }

    @Test
    fun `registry references retain registry and value identities`() {
        val value = RegistryValueRef<Any>(
            registry = Key.key("minecraft", "worldgen/biome"),
            value = Key.key("minecraft", "plains"),
        )

        assertEquals("minecraft:worldgen/biome", value.registry.asString())
        assertEquals("minecraft:plains", value.value.asString())
    }

    @Test
    fun `semantic API does not expose Paper argument providers`() {
        val forbiddenPackages = listOf(
            "io.papermc.paper.command.brigadier.argument.resolvers",
            "io.papermc.paper.command.brigadier.argument.range",
            "io.papermc.paper.command.brigadier.argument.predicate",
        )
        val semanticTypes = listOf(
            MinecraftAngle::class.java,
            MinecraftTime::class.java,
            MinecraftRotation::class.java,
            AxisSelection::class.java,
            BlockColumnRef::class.java,
            FinePositionSnapshot::class.java,
            FineColumnSnapshot::class.java,
            PlayerProfileSelection::class.java,
            MinecraftIntegerRange::class.java,
            MinecraftDoubleRange::class.java,
            ScoreboardCriterion::class.java,
            RegistryValueRef::class.java,
            RegistryValueKey::class.java,
            BlockPredicate::class.java,
            BlockStateInput::class.java,
            ItemStackSnapshot::class.java,
            ItemPredicate::class.java,
        )

        val signatures = semanticTypes.flatMap { type ->
            type.declaredMethods.map { method -> method.toGenericString() } +
                type.declaredConstructors.map { constructor -> constructor.toGenericString() }
        }
        assertTrue(signatures.none { signature -> forbiddenPackages.any(signature::contains) })
    }
}
