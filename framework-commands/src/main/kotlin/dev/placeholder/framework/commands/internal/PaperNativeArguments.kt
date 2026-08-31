package dev.placeholder.framework.commands.internal

import com.google.common.collect.Range
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.context.CommandContext
import dev.placeholder.framework.commands.codegen.CommandParameterDefinition
import dev.placeholder.framework.commands.minecraft.AxisSelection
import dev.placeholder.framework.commands.minecraft.BlockPredicate
import dev.placeholder.framework.commands.minecraft.BlockPredicateResult
import dev.placeholder.framework.commands.minecraft.BlockStateInput
import dev.placeholder.framework.commands.minecraft.BlockColumnRef
import dev.placeholder.framework.commands.minecraft.FineColumnSnapshot
import dev.placeholder.framework.commands.minecraft.FinePositionSnapshot
import dev.placeholder.framework.commands.minecraft.MinecraftAngle
import dev.placeholder.framework.commands.minecraft.MinecraftDoubleRange
import dev.placeholder.framework.commands.minecraft.MinecraftIntegerRange
import dev.placeholder.framework.commands.minecraft.MinecraftRotation
import dev.placeholder.framework.commands.minecraft.MinecraftTime
import dev.placeholder.framework.commands.minecraft.ItemPredicate
import dev.placeholder.framework.commands.minecraft.ItemStackSnapshot
import dev.placeholder.framework.commands.minecraft.SignedMessageInput
import dev.placeholder.framework.commands.minecraft.PlayerProfileSelection
import dev.placeholder.framework.commands.minecraft.PlayerProfileSnapshot
import dev.placeholder.framework.commands.minecraft.ProfilePropertySnapshot
import dev.placeholder.framework.commands.minecraft.ScoreboardCriterion
import dev.placeholder.framework.commands.minecraft.RegistryValueKey
import dev.placeholder.framework.commands.minecraft.RegistryValueRef
import dev.placeholder.framework.commands.reference.BlockRef
import dev.placeholder.framework.commands.reference.EntityRef
import dev.placeholder.framework.commands.reference.EntitySelection
import dev.placeholder.framework.commands.reference.PlayerRef
import dev.placeholder.framework.commands.reference.PlayerSelection
import dev.placeholder.framework.commands.reference.WorldRef
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.AxisSet
import io.papermc.paper.command.brigadier.argument.SignedMessageResolver
import io.papermc.paper.command.brigadier.argument.predicate.BlockInWorldPredicate
import io.papermc.paper.command.brigadier.argument.predicate.ItemStackPredicate
import io.papermc.paper.command.brigadier.argument.position.ColumnBlockPosition
import io.papermc.paper.command.brigadier.argument.position.ColumnFinePosition
import io.papermc.paper.command.brigadier.argument.range.DoubleRangeProvider
import io.papermc.paper.command.brigadier.argument.range.IntegerRangeProvider
import io.papermc.paper.command.brigadier.argument.resolvers.AngleResolver
import io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver
import io.papermc.paper.command.brigadier.argument.resolvers.ColumnBlockPositionResolver
import io.papermc.paper.command.brigadier.argument.resolvers.ColumnFinePositionResolver
import io.papermc.paper.command.brigadier.argument.resolvers.FinePositionResolver
import io.papermc.paper.command.brigadier.argument.resolvers.PlayerProfileListResolver
import io.papermc.paper.command.brigadier.argument.resolvers.RotationResolver
import io.papermc.paper.command.brigadier.argument.resolvers.selector.EntitySelectorArgumentResolver
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import java.util.UUID
import kotlin.reflect.KClass
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextColor
import org.bukkit.GameMode
import org.bukkit.HeightMap
import org.bukkit.NamespacedKey
import org.bukkit.block.structure.Mirror
import org.bukkit.block.structure.StructureRotation
import org.bukkit.block.data.BlockData
import io.papermc.paper.entity.LookAnchor
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.scoreboard.DisplaySlot

internal object PaperNativeArguments {
    fun argumentType(parameter: CommandParameterDefinition): ArgumentType<*>? {
        if (parameter.qualifier != null) return null
        return when (parameter.type) {
        PlayerRef::class -> ArgumentTypes.player()
        PlayerSelection::class -> ArgumentTypes.players()
        EntityRef::class -> ArgumentTypes.entity()
        EntitySelection::class -> ArgumentTypes.entities()
        PlayerProfileSelection::class -> ArgumentTypes.playerProfiles()
        BlockRef::class -> ArgumentTypes.blockPosition()
        BlockColumnRef::class -> ArgumentTypes.columnBlockPosition()
        FinePositionSnapshot::class -> ArgumentTypes.finePosition(parameter.centerIntegers)
        FineColumnSnapshot::class -> ArgumentTypes.columnFinePosition(parameter.centerIntegers)
        MinecraftRotation::class -> ArgumentTypes.rotation()
        MinecraftAngle::class -> ArgumentTypes.angle()
        AxisSelection::class -> ArgumentTypes.axes()
        BlockStateInput::class -> ArgumentTypes.blockState()
        BlockPredicate::class -> ArgumentTypes.blockInWorldPredicate()
        ItemStackSnapshot::class -> ArgumentTypes.itemStack()
        ItemPredicate::class -> ArgumentTypes.itemPredicate()
        NamedTextColor::class -> ArgumentTypes.namedColor()
        TextColor::class -> ArgumentTypes.hexColor()
        Component::class -> ArgumentTypes.component()
        Style::class -> ArgumentTypes.style()
        SignedMessageInput::class -> ArgumentTypes.signedMessage()
        DisplaySlot::class -> ArgumentTypes.scoreboardDisplaySlot()
        NamespacedKey::class -> ArgumentTypes.namespacedKey()
        Key::class -> ArgumentTypes.key()
        MinecraftIntegerRange::class -> ArgumentTypes.integerRange()
        MinecraftDoubleRange::class -> ArgumentTypes.doubleRange()
        WorldRef::class -> ArgumentTypes.world()
        GameMode::class -> ArgumentTypes.gameMode()
        HeightMap::class -> ArgumentTypes.heightMap()
        UUID::class -> ArgumentTypes.uuid()
        ScoreboardCriterion::class -> ArgumentTypes.objectiveCriteria()
        LookAnchor::class -> ArgumentTypes.entityAnchor()
        MinecraftTime::class -> ArgumentTypes.time(parameter.minimumTicks)
        Mirror::class -> ArgumentTypes.templateMirror()
        StructureRotation::class -> ArgumentTypes.templateRotation()
        RegistryValueRef::class -> ArgumentTypes.resource(registryKey(parameter))
        RegistryValueKey::class -> ArgumentTypes.resourceKey(registryKey(parameter))
            else -> null
        }
    }

    fun extract(
        parameter: CommandParameterDefinition,
        context: CommandContext<CommandSourceStack>,
    ): NativeArgument? {
        if (argumentType(parameter) == null) return null
        val raw = context.getArgument(parameter.name, Any::class.java)
        val source = context.source
        return NativeArgument(
            when (parameter.type) {
                PlayerRef::class -> PlayerRef((raw as PlayerSelectorArgumentResolver).resolve(source).single().uniqueId)
                PlayerSelection::class -> PlayerSelection(
                    (raw as PlayerSelectorArgumentResolver).resolve(source).map { PlayerRef(it.uniqueId) },
                )
                EntityRef::class -> EntityRef((raw as EntitySelectorArgumentResolver).resolve(source).single().uniqueId)
                EntitySelection::class -> EntitySelection(
                    (raw as EntitySelectorArgumentResolver).resolve(source).map { EntityRef(it.uniqueId) },
                )
                PlayerProfileSelection::class -> (raw as PlayerProfileListResolver).resolve(source).map { profile ->
                    PlayerProfileSnapshot(
                        profile.id,
                        profile.name,
                        profile.properties.map { property ->
                            ProfilePropertySnapshot(property.name, property.value, property.signature)
                        },
                    )
                }.let(::PlayerProfileSelection)
                BlockRef::class -> (raw as BlockPositionResolver).resolve(source).let { position ->
                    BlockRef(WorldRef(source.location.world.key), position.blockX(), position.blockY(), position.blockZ())
                }
                BlockColumnRef::class -> (raw as ColumnBlockPositionResolver).resolve(source).let { position ->
                    BlockColumnRef(WorldRef(source.location.world.key), position.blockX(), position.blockZ())
                }
                FinePositionSnapshot::class -> (raw as FinePositionResolver).resolve(source).let { position ->
                    FinePositionSnapshot(WorldRef(source.location.world.key), position.x(), position.y(), position.z())
                }
                FineColumnSnapshot::class -> (raw as ColumnFinePositionResolver).resolve(source).let { position ->
                    FineColumnSnapshot(WorldRef(source.location.world.key), position.x(), position.z())
                }
                MinecraftRotation::class -> (raw as RotationResolver).resolve(source).let { rotation ->
                    MinecraftRotation(rotation.yaw(), rotation.pitch())
                }
                MinecraftAngle::class -> MinecraftAngle((raw as AngleResolver).resolve(source))
                AxisSelection::class -> AxisSelection(raw as AxisSet)
                BlockStateInput::class -> NativeBlockStateInput((raw as org.bukkit.block.BlockState).blockData)
                BlockPredicate::class -> NativeBlockPredicate(raw as BlockInWorldPredicate)
                ItemStackSnapshot::class -> ItemStackSnapshot(raw as ItemStack)
                ItemPredicate::class -> NativeItemPredicate(raw as ItemStackPredicate)
                MinecraftIntegerRange::class -> (raw as IntegerRangeProvider).range().toIntegerRange()
                MinecraftDoubleRange::class -> (raw as DoubleRangeProvider).range().toDoubleRange()
                WorldRef::class -> WorldRef((raw as org.bukkit.World).key)
                ScoreboardCriterion::class -> (raw as org.bukkit.scoreboard.Criteria).let { criteria ->
                    ScoreboardCriterion(criteria.name, criteria.isReadOnly, criteria.defaultRenderType)
                }
                MinecraftTime::class -> MinecraftTime(raw as Int)
                SignedMessageInput::class -> (raw as SignedMessageResolver).let { resolver ->
                    SignedMessageInput(
                        resolver.content(),
                        resolver.resolveSignedMessage(parameter.name, context),
                    )
                }
                RegistryValueRef::class -> RegistryValueRef<Any>(
                    Key.key(requireNotNull(parameter.registry)),
                    raw.registryValueKey(),
                )
                RegistryValueKey::class -> (raw as TypedKey<*>).let { key ->
                    RegistryValueKey<Any>(key.registryKey().key(), key.key())
                }
                else -> raw
            },
        )
    }
}

@Suppress("UNCHECKED_CAST")
private fun registryKey(parameter: CommandParameterDefinition): RegistryKey<Any> {
    val requested = requireNotNull(parameter.registry) {
        "${parameter.type.simpleName} requires @FromRegistry"
    }
    return RegistryKey::class.java.fields.asSequence()
        .filter { field -> RegistryKey::class.java.isAssignableFrom(field.type) }
        .map { field -> field.get(null) as RegistryKey<Any> }
        .singleOrNull { key -> key.key().asString() == requested }
        ?: error("Paper exposes no registry '$requested'")
}

private fun Any.registryValueKey(): Key = when (this) {
    is net.kyori.adventure.key.Keyed -> key()
    is org.bukkit.Keyed -> key
    else -> error("Registry value ${this::class.qualifiedName} does not expose a stable key")
}

internal data class NativeArgument(val value: Any)

private class NativeBlockStateInput(data: BlockData) : BlockStateInput {
    private val data = data.clone()

    override fun blockData(): BlockData = data.clone()

    override suspend fun place(
        plugin: Plugin,
        block: BlockRef,
        force: Boolean,
        applyPhysics: Boolean,
    ): Boolean = block.access(plugin) { target ->
        target.setBlockData(data.clone(), applyPhysics)
        true
    } ?: false
}

private class NativeBlockPredicate(
    private val delegate: BlockInWorldPredicate,
) : BlockPredicate {
    override suspend fun test(
        plugin: Plugin,
        block: BlockRef,
        loadChunk: Boolean,
    ): BlockPredicateResult = block.access(plugin) { target ->
        when (delegate.testBlock(target, loadChunk)) {
            BlockInWorldPredicate.Result.TRUE -> BlockPredicateResult.MATCH
            BlockInWorldPredicate.Result.FALSE -> BlockPredicateResult.NO_MATCH
            BlockInWorldPredicate.Result.UNLOADED_CHUNK -> BlockPredicateResult.UNLOADED_CHUNK
        }
    } ?: BlockPredicateResult.UNLOADED_CHUNK
}

private class NativeItemPredicate(
    private val delegate: ItemStackPredicate,
) : ItemPredicate {
    override fun matches(itemStack: ItemStack): Boolean = delegate.test(itemStack)
}

private fun Range<Int>.toIntegerRange(): MinecraftIntegerRange = MinecraftIntegerRange(
    minimum = takeIf(Range<Int>::hasLowerBound)?.lowerEndpoint(),
    maximum = takeIf(Range<Int>::hasUpperBound)?.upperEndpoint(),
)

private fun Range<Double>.toDoubleRange(): MinecraftDoubleRange = MinecraftDoubleRange(
    minimum = takeIf(Range<Double>::hasLowerBound)?.lowerEndpoint(),
    maximum = takeIf(Range<Double>::hasUpperBound)?.upperEndpoint(),
)
