package pink.alex.ashlar.sample

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import pink.alex.ashlar.di.Inject
import pink.alex.ashlar.di.PluginScoped
import pink.alex.ashlar.execution.EntityOutcome
import pink.alex.ashlar.execution.PlayerRef
import pink.alex.ashlar.items.ItemSpec
import pink.alex.ashlar.items.item
import pink.alex.ashlar.menus.ChestScope
import pink.alex.ashlar.menus.MenuScope
import pink.alex.ashlar.menus.chest
import pink.alex.ashlar.menus.component
import pink.alex.ashlar.menus.launchedEffect
import pink.alex.ashlar.menus.slot
import pink.alex.ashlar.menus.state
import pink.alex.ashlar.menus.standard.closeControl
import kotlin.math.abs
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.plugin.Plugin

private const val CANVAS_SIZE = 45
private const val CANVAS_WIDTH = 9

private enum class AnimationScene(val label: String) {
    COMET("Comet"),
    WAVE("Wave"),
    PULSE("Pulse"),
    RAIN("Rain"),
    ;

    fun previous(): AnimationScene = entries[(ordinal - 1 + entries.size) % entries.size]

    fun next(): AnimationScene = entries[(ordinal + 1) % entries.size]
}

private enum class AnimationSpeed(val label: String, val ticksPerFrame: Int) {
    FAST("20 FPS", 1),
    MEDIUM("10 FPS", 2),
    SLOW("5 FPS", 4),
    ;

    fun next(): AnimationSpeed = entries[(ordinal + 1) % entries.size]
}

@Inject
@PluginScoped
internal class MenuAnimationClock(private val plugin: Plugin) {
    fun ticks(player: PlayerRef): Flow<Unit> = callbackFlow {
        var task: ScheduledTask? = null
        when (
            player.access(plugin) { livePlayer ->
                task = livePlayer.scheduler.runAtFixedRate(
                    plugin,
                    { _ -> trySend(Unit) },
                    { close() },
                    1L,
                    1L,
                )
            }
        ) {
            is EntityOutcome.Completed -> Unit
            EntityOutcome.Retired -> close()
        }
        awaitClose { task?.cancel() }
    }
}

context(menu: MenuScope)
internal fun AnimationMenu(ticks: Flow<Unit>) {
    component("animation-reel") {
        var scene by state(AnimationScene.COMET)
        var speed by state(AnimationSpeed.FAST)
        var paused by state(false)
        var frame by state(0L)

        if (!paused) {
            launchedEffect(speed) {
                var elapsedTicks = 0
                ticks.collect {
                    elapsedTicks++
                    if (elapsedTicks == speed.ticksPerFrame) {
                        elapsedTicks = 0
                        frame++
                    }
                }
            }
        }

        chest(Component.text("Menu animations · ${scene.label}", NamedTextColor.AQUA), rows = 6) {
            repeat(CANVAS_SIZE) { index ->
                slot(index) { item = scene.item(frame, index) }
            }
            slot(45) {
                item = animationItem(Material.ARROW, "Previous scene", NamedTextColor.YELLOW)
                onPrimary {
                    scene = scene.previous()
                    frame = 0
                }
            }
            animationSpacer(46)
            slot(47) {
                item = animationItem(
                    if (paused) Material.LIME_DYE else Material.RED_DYE,
                    if (paused) "Resume" else "Pause",
                    if (paused) NamedTextColor.GREEN else NamedTextColor.RED,
                )
                onPrimary { paused = !paused }
            }
            animationSpacer(48)
            slot(49) {
                item = animationItem(Material.SPECTRAL_ARROW, "Next scene", NamedTextColor.YELLOW)
                onPrimary {
                    scene = scene.next()
                    frame = 0
                }
            }
            animationSpacer(50)
            slot(51) {
                item = animationItem(Material.COMPARATOR, "Speed: ${speed.label}", NamedTextColor.GOLD)
                onPrimary { speed = speed.next() }
            }
            animationSpacer(52)
            closeControl(53, animationItem(Material.BARRIER, "Close", NamedTextColor.RED))
        }
    }
}

context(chest: ChestScope)
private fun animationSpacer(index: Int) {
    slot(index) { item = CONTROL_BACKGROUND }
}

private fun AnimationScene.item(frame: Long, index: Int): ItemSpec = when (this) {
    AnimationScene.COMET -> cometItem(frame, index)
    AnimationScene.WAVE -> waveItem(frame, index)
    AnimationScene.PULSE -> pulseItem(frame, index)
    AnimationScene.RAIN -> rainItem(frame, index)
}

private fun cometItem(frame: Long, index: Int): ItemSpec {
    val pathIndex = COMET_PATH_INDEX[index] ?: return SPACE_BACKGROUND
    val head = (frame % COMET_PATH.size).toInt()
    return when ((head - pathIndex + COMET_PATH.size) % COMET_PATH.size) {
        0 -> COMET_HEAD
        1 -> COMET_TRAIL_BRIGHT
        2 -> COMET_TRAIL_MEDIUM
        3 -> COMET_TRAIL_DIM
        else -> SPACE_BACKGROUND
    }
}

private fun waveItem(frame: Long, index: Int): ItemSpec {
    val x = index % CANVAS_WIDTH
    val y = index / CANVAS_WIDTH
    val waveY = WAVE_HEIGHTS[((frame / 2 + x) % WAVE_HEIGHTS.size).toInt()]
    return when {
        y == waveY -> WAVE_CREST
        y > waveY -> WAVE_WATER
        else -> WAVE_SKY
    }
}

private fun pulseItem(frame: Long, index: Int): ItemSpec {
    val x = index % CANVAS_WIDTH
    val y = index / CANVAS_WIDTH
    val distance = maxOf(abs(x - 4), abs(y - 2))
    val radius = ((frame / 2) % 5).toInt()
    return when {
        distance == radius -> PULSE_BRIGHT
        distance == (radius + 4) % 5 -> PULSE_TRAIL
        else -> SPACE_BACKGROUND
    }
}

private fun rainItem(frame: Long, index: Int): ItemSpec {
    val x = index % CANVAS_WIDTH
    val y = index / CANVAS_WIDTH
    val drop = ((frame / 2 + RAIN_OFFSETS[x]) % 8).toInt() - 2
    return when (y) {
        drop -> RAIN_DROP
        drop - 1 -> RAIN_TRAIL
        else -> RAIN_BACKGROUND
    }
}

private fun animationItem(material: Material, name: String, color: NamedTextColor): ItemSpec = item(material) {
    this.name = Component.text(name, color)
}

private val SPACE_BACKGROUND = animationItem(Material.BLACK_STAINED_GLASS_PANE, " ", NamedTextColor.BLACK)
private val CONTROL_BACKGROUND = animationItem(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.DARK_GRAY)
private val COMET_HEAD = animationItem(Material.NETHER_STAR, "Comet", NamedTextColor.YELLOW).edit { glint() }
private val COMET_TRAIL_BRIGHT = animationItem(Material.YELLOW_STAINED_GLASS_PANE, "Bright trail", NamedTextColor.YELLOW)
private val COMET_TRAIL_MEDIUM = animationItem(Material.ORANGE_STAINED_GLASS_PANE, "Warm trail", NamedTextColor.GOLD)
private val COMET_TRAIL_DIM = animationItem(Material.RED_STAINED_GLASS_PANE, "Fading trail", NamedTextColor.RED)
private val WAVE_SKY = animationItem(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "Sky", NamedTextColor.AQUA)
private val WAVE_CREST = animationItem(Material.WHITE_STAINED_GLASS_PANE, "Wave crest", NamedTextColor.WHITE)
private val WAVE_WATER = animationItem(Material.BLUE_STAINED_GLASS_PANE, "Water", NamedTextColor.BLUE)
private val PULSE_BRIGHT = animationItem(Material.MAGENTA_STAINED_GLASS_PANE, "Pulse", NamedTextColor.LIGHT_PURPLE)
private val PULSE_TRAIL = animationItem(Material.PURPLE_STAINED_GLASS_PANE, "Pulse trail", NamedTextColor.DARK_PURPLE)
private val RAIN_BACKGROUND = animationItem(Material.BLUE_STAINED_GLASS_PANE, "Night", NamedTextColor.DARK_BLUE)
private val RAIN_DROP = animationItem(Material.AMETHYST_SHARD, "Drop", NamedTextColor.LIGHT_PURPLE).edit { glint() }
private val RAIN_TRAIL = animationItem(Material.PRISMARINE_CRYSTALS, "Rain trail", NamedTextColor.AQUA)

private val COMET_PATH: List<Int> = buildList {
    addAll(0..8)
    addAll(listOf(17, 26, 35, 44))
    addAll(43 downTo 36)
    addAll(listOf(27, 18, 9))
}
private val COMET_PATH_INDEX: Map<Int, Int> = COMET_PATH.withIndex().associate { (pathIndex, slot) -> slot to pathIndex }
private val WAVE_HEIGHTS: List<Int> = listOf(2, 1, 0, 0, 1, 2, 3, 4, 4, 3)
private val RAIN_OFFSETS: List<Int> = listOf(0, 5, 2, 7, 3, 1, 6, 4, 2)
