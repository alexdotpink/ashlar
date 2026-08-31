package dev.placeholder.framework.sample

import dev.placeholder.framework.commands.Commands
import dev.placeholder.framework.di.Inject
import dev.placeholder.framework.di.PluginScoped
import dev.placeholder.framework.execution.PlayerRef
import dev.placeholder.framework.items.ItemSpec
import dev.placeholder.framework.items.Items
import dev.placeholder.framework.items.item
import dev.placeholder.framework.input.PlayerInput
import dev.placeholder.framework.menus.MenuActionConcurrency
import dev.placeholder.framework.menus.MenuChoice
import dev.placeholder.framework.menus.MenuClose
import dev.placeholder.framework.menus.MenuFeedback
import dev.placeholder.framework.menus.MenuFeedbackSeverity
import dev.placeholder.framework.menus.MenuGestureKind
import dev.placeholder.framework.menus.MenuLocal
import dev.placeholder.framework.menus.MenuOpen
import dev.placeholder.framework.menus.MenuOpenConflict
import dev.placeholder.framework.menus.MenuScope
import dev.placeholder.framework.menus.NativeClose
import dev.placeholder.framework.menus.PlayerMenus
import dev.placeholder.framework.menus.SlotRegion
import dev.placeholder.framework.menus.chest
import dev.placeholder.framework.menus.collectAsState
import dev.placeholder.framework.menus.component
import dev.placeholder.framework.menus.current
import dev.placeholder.framework.menus.effect
import dev.placeholder.framework.menus.errorBoundary
import dev.placeholder.framework.menus.launchedEffect
import dev.placeholder.framework.menus.menuLocal
import dev.placeholder.framework.menus.navigator
import dev.placeholder.framework.menus.playerInventory
import dev.placeholder.framework.menus.provide
import dev.placeholder.framework.menus.region
import dev.placeholder.framework.menus.rows
import dev.placeholder.framework.menus.screen
import dev.placeholder.framework.menus.slot
import dev.placeholder.framework.menus.state
import dev.placeholder.framework.menus.storage.MenuSlotRule
import dev.placeholder.framework.menus.storage.MenuStorage
import dev.placeholder.framework.menus.storage.MenuStorageId
import dev.placeholder.framework.menus.storage.MenuStorageRules
import dev.placeholder.framework.menus.storage.MenuStorageSnapshot
import dev.placeholder.framework.menus.storage.MenuTransactionDecision
import dev.placeholder.framework.menus.storage.MenuTransactionDomain
import dev.placeholder.framework.menus.storage.MenuTransactionId
import dev.placeholder.framework.menus.storage.MenuTransactionProposal
import dev.placeholder.framework.menus.storage.MenuTransactionResolution
import dev.placeholder.framework.menus.storage.PlayerInventorySection
import dev.placeholder.framework.menus.storage.externalMenuStorage
import dev.placeholder.framework.menus.storage.reference
import dev.placeholder.framework.menus.storage.transferRoute
import dev.placeholder.framework.menus.storage
import dev.placeholder.framework.menus.transfers
import dev.placeholder.framework.menus.standard.ContentState
import dev.placeholder.framework.menus.standard.backControl
import dev.placeholder.framework.menus.standard.border
import dev.placeholder.framework.menus.standard.closeControl
import dev.placeholder.framework.menus.standard.confirmation
import dev.placeholder.framework.menus.standard.contentState
import dev.placeholder.framework.menus.standard.filler
import dev.placeholder.framework.menus.standard.numberStepper
import dev.placeholder.framework.menus.standard.paged
import dev.placeholder.framework.menus.standard.selection
import dev.placeholder.framework.menus.standard.scrolling
import dev.placeholder.framework.menus.standard.searchControl
import dev.placeholder.framework.menus.standard.staticItem
import dev.placeholder.framework.menus.standard.tab
import dev.placeholder.framework.menus.standard.toggle
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.inventory.ItemRarity

@Commands(name = "menus", aliases = ["menu"])
internal class MenuShowcaseCommands(
    private val menus: PlayerMenus,
    private val model: MenuShowcaseModel,
    private val input: PlayerInput,
) {
    /** Opens the complete declarative menu showcase and waits for its typed close reason. */
    suspend fun open(player: PlayerRef): String = when (
        val result = menus.open(player) { MenuShowcase(model, input, player) }
    ) {
        is MenuOpen.Closed -> "Menu closed: ${result.reason.label()}."
        MenuOpen.Rejected -> "The player already has a menu open."
    }

    /** Opens a small typed choice independent of ordinary menu close outcomes. */
    suspend fun choose(player: PlayerRef): String = when (
        val result = menus.choose<String>(player) { ChoiceMenu() }
    ) {
        is MenuChoice.Selected -> "Selected '${result.value}'."
        is MenuChoice.Closed -> "Choice closed: ${result.reason.label()}."
    }

    /** Demonstrates conflict rejection without disturbing the player's current session. */
    suspend fun reject(player: PlayerRef): String = when (
        val result = menus.open(player, conflict = MenuOpenConflict.REJECT) { ChoiceMenu() }
    ) {
        is MenuOpen.Closed -> "Temporary menu closed: ${result.reason.label()}."
        MenuOpen.Rejected -> "Rejected as expected; the existing menu stayed open."
    }

    /** Atomically closes the selected player's active framework menu. */
    fun close(player: PlayerRef): String =
        if (menus.close(player)) "Closed the active menu." else "No framework menu is active."

    /** Summarizes the redacted semantic tree and bounded runtime trace. */
    fun inspect(player: PlayerRef): String = menus.inspect(player)?.let { inspection ->
        "revision=${inspection.snapshot.revision}, " +
            "slots=${inspection.snapshot.host.capacity}, " +
            "actions=${inspection.pendingActions.size}, " +
            "effects=${inspection.activeEffects.size}, trace=${inspection.trace.size}"
    } ?: "No framework menu is active."
}

@Inject
@PluginScoped
internal class MenuShowcaseModel {
    val tick: MutableStateFlow<Int> = MutableStateFlow(0)
    val audit: MutableList<String> = mutableListOf()
    val vaultId: MenuStorageId = MenuStorageId("sample", "shared-vault")

    private val snapshots = MutableStateFlow(
        MenuStorageSnapshot(
            vaultId,
            revision = 0,
            slots = listOf(
                nativeSnapshot(Material.DIAMOND, 8, "Diamonds may enter slot one"),
                nativeSnapshot(Material.BARRIER, 1, "This slot is locked"),
                null,
                nativeSnapshot(Material.DIRT, 32, "Ordinary storage"),
                null,
                null,
                null,
                null,
                null,
            ),
        ),
    )

    private val domain = SampleVaultDomain(vaultId, snapshots)
    val vault: MenuStorage = externalMenuStorage(
        id = vaultId,
        snapshots = snapshots,
        rules = MenuStorageRules.of(
            listOf(
                MenuSlotRule(accepts = { it.material == Material.DIAMOND }),
                MenuSlotRule.Locked,
            ) + List(7) { MenuSlotRule.Vanilla },
        ),
        transactionDomain = domain,
    )

    fun record(value: String) {
        audit += value
        while (audit.size > 20) audit.removeAt(0)
    }
}

private class SampleVaultDomain(
    private val storageId: MenuStorageId,
    private val snapshots: MutableStateFlow<MenuStorageSnapshot>,
) : MenuTransactionDomain {
    override val id: String = "sample-vault"
    override val storages: Set<MenuStorageId> = setOf(storageId)
    private val resolutions: MutableMap<MenuTransactionId, MenuTransactionResolution> = ConcurrentHashMap()

    override suspend fun commit(proposal: MenuTransactionProposal): MenuTransactionDecision {
        when (val existing = resolutions[proposal.id]) {
            is MenuTransactionResolution.Committed -> return MenuTransactionDecision.Commit(existing.snapshots)
            is MenuTransactionResolution.Rejected -> return MenuTransactionDecision.Reject(
                existing.message ?: Component.text("The sample vault rejected this transaction."),
            )
            MenuTransactionResolution.NotCommitted,
            MenuTransactionResolution.Pending,
            null,
            -> Unit
        }
        val change = proposal.changes[storageId]
            ?: return MenuTransactionDecision.Commit(emptyMap())
        if (snapshots.value.revision != change.before.revision) {
            val message = Component.text("The sample vault changed; try the gesture again.", NamedTextColor.RED)
            resolutions[proposal.id] = MenuTransactionResolution.Rejected(message)
            return MenuTransactionDecision.Reject(message)
        }
        snapshots.value = change.after
        val committed = mapOf(storageId to change.after)
        resolutions[proposal.id] = MenuTransactionResolution.Committed(committed)
        return MenuTransactionDecision.Commit(committed)
    }

    override suspend fun resolve(id: MenuTransactionId): MenuTransactionResolution =
        resolutions[id] ?: MenuTransactionResolution.NotCommitted
}

private sealed interface ShowcaseRoute {
    data object Home : ShowcaseRoute
    data object Components : ShowcaseRoute
    data object Storage : ShowcaseRoute
    data object Confirm : ShowcaseRoute
}

private data class ShowcaseTheme(
    val accent: NamedTextColor,
    val label: String,
)

private val Theme: MenuLocal<ShowcaseTheme> = menuLocal("sample-theme") {
    ShowcaseTheme(NamedTextColor.AQUA, "default")
}

context(menu: MenuScope)
private fun MenuShowcase(
    model: MenuShowcaseModel,
    input: PlayerInput,
    player: PlayerRef,
) {
    component("showcase") {
        var deliberateFailure by state(false)
        errorBoundary(
            fallback = { failure, retry ->
                chest("Recovered error", rows = 1) {
                    slot(4) {
                        item = icon(Material.REDSTONE, "Retry ${failure.path}", NamedTextColor.RED)
                        onPrimary {
                            deliberateFailure = false
                            retry.retry()
                        }
                    }
                }
            },
        ) {
            if (deliberateFailure) error("Deliberate sample render failure")
            provide(Theme, ShowcaseTheme(NamedTextColor.GOLD, "gold")) {
                ShowcaseNavigation(model, input, player) { deliberateFailure = true }
            }
        }
    }
}

context(menu: MenuScope)
private fun ShowcaseNavigation(
    model: MenuShowcaseModel,
    input: PlayerInput,
    player: PlayerRef,
    fail: () -> Unit,
) {
    navigator<ShowcaseRoute>(ShowcaseRoute.Home) {
        screen<ShowcaseRoute, ShowcaseRoute.Home> {
            HomeScreen(model, navigator, fail)
        }
        screen<ShowcaseRoute, ShowcaseRoute.Components>(nativeClose = NativeClose.BACK) {
            ComponentsScreen(navigator, input, player)
        }
        screen<ShowcaseRoute, ShowcaseRoute.Storage>(nativeClose = NativeClose.BACK) {
            StorageScreen(model, navigator)
        }
        screen<ShowcaseRoute, ShowcaseRoute.Confirm>(nativeClose = NativeClose.BACK) {
            ConfirmScreen(navigator)
        }
    }
}

context(menu: MenuScope)
private fun HomeScreen(
    model: MenuShowcaseModel,
    navigator: dev.placeholder.framework.menus.MenuNavigator<ShowcaseRoute>,
    fail: () -> Unit,
) {
    component("home") {
        var clicks by state(0)
        val tick by collectAsState(model.tick, initial = 0)
        effect("audit") {
            model.record("home:mounted")
            onDispose { model.record("home:disposed") }
        }
        launchedEffect("ticker") {
            while (true) {
                delay(1_000)
                model.tick.value++
            }
        }
        val theme = Theme.current()
        chest(Component.text("Menus: ${theme.label} · tick $tick", theme.accent), rows = 4) {
            border(SlotRegion.of(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8)), icon(Material.GRAY_STAINED_GLASS_PANE, " "))
            tab(10, ShowcaseRoute.Components, icon(Material.COMPARATOR, "Components")) { navigator.push(it) }
            tab(12, ShowcaseRoute.Storage, icon(Material.CHEST, "Transactional storage")) { navigator.push(it) }
            tab(14, ShowcaseRoute.Confirm, icon(Material.LIME_DYE, "Confirmation")) { navigator.push(it) }
            slot(16) {
                item = icon(Material.REDSTONE_BLOCK, "Trigger error boundary", NamedTextColor.RED)
                onPrimary { fail() }
            }
            slot(19) {
                item = icon(Material.EMERALD, "Clicks: $clicks", NamedTextColor.GREEN)
                onPrimary {
                    clicks++
                    feedback(MenuFeedback(Component.text("Primary click $clicks"), MenuFeedbackSeverity.SUCCESS, 19))
                }
                onSecondary {
                    feedback(MenuFeedback(Component.text("Secondary click"), MenuFeedbackSeverity.INFO, 19))
                }
                on(MenuGestureKind.NUMBER_KEY) {
                    feedback(MenuFeedback(Component.text("Number-key gesture"), MenuFeedbackSeverity.WARNING, 19))
                }
                onGesture {
                    feedback(MenuFeedback(Component.text("Gesture: ${it.gesture.kind}"), MenuFeedbackSeverity.REJECTION, 19))
                }
            }
            slot(21) {
                item = icon(Material.CLOCK, "Single-flight action")
                onPrimary(concurrency = MenuActionConcurrency.SINGLE_FLIGHT) {
                    delay(900.milliseconds)
                    feedback(MenuFeedback(Component.text("Single-flight completed"), MenuFeedbackSeverity.SUCCESS, 21))
                }
            }
            slot(23) {
                item = icon(Material.FEATHER, "Restart-latest action")
                onPrimary(concurrency = MenuActionConcurrency.RESTART_LATEST) {
                    delay(900.milliseconds)
                    feedback(MenuFeedback(Component.text("Latest action completed"), MenuFeedbackSeverity.SUCCESS, 23))
                }
            }
            slot(25) {
                item = icon(Material.FIREWORK_ROCKET, "Parallel action")
                onPrimary(concurrency = MenuActionConcurrency.PARALLEL) {
                    delay(500.milliseconds)
                    feedback(MenuFeedback(Component.text("Parallel action completed"), MenuFeedbackSeverity.SUCCESS, 25))
                }
            }
            closeControl(31, icon(Material.BARRIER, "Close"))
        }
    }
}

context(menu: MenuScope)
private fun ComponentsScreen(
    navigator: dev.placeholder.framework.menus.MenuNavigator<ShowcaseRoute>,
    input: PlayerInput,
    player: PlayerRef,
) {
    component("components") {
        var enabled by state(false)
        var number by state(2)
        var query by state("none")
        val values = (1..18).toList()
        val pages = paged(values, key = { it }, pageSize = 7)
        val scroller = scrolling(values, key = { it }, windowSize = 7)
        chest("Standard components · page ${pages.page + 1}/${pages.pageCount}", rows = 4) {
            contentState(
                state = ContentState.Ready(pages.visible),
                loading = { staticItem(13, icon(Material.CLOCK, "Loading")) },
                empty = { staticItem(13, icon(Material.BARRIER, "Empty")) },
                failed = { staticItem(13, icon(Material.REDSTONE, "Failed: ${it.message}")) },
                ready = {
                    pages.items(region(0..0, 1..7)) { value, index ->
                        selection(index, value, icon(Material.PAPER, "Value $value")) { number = it }
                    }
                },
            )
            pages.previous(9, icon(Material.ARROW, "Previous page"))
            pages.next(17, icon(Material.ARROW, "Next page"))
            scroller.items(region(1..1, 1..7)) { value, index ->
                staticItem(index, icon(Material.MAP, "Scroll value $value"))
            }
            scroller.previous(18, icon(Material.SPECTRAL_ARROW, "Scroll previous"))
            scroller.next(26, icon(Material.SPECTRAL_ARROW, "Scroll next"))
            toggle(20, enabled, icon(if (enabled) Material.LIME_DYE else Material.GRAY_DYE, "Toggle: $enabled")) {
                enabled = it
            }
            numberStepper(
                value = number,
                range = 1..18,
                decrementSlot = 22,
                decrementItem = icon(Material.RED_DYE, "Decrease ($number)"),
                incrementSlot = 24,
                incrementItem = icon(Material.LIME_DYE, "Increase ($number)"),
                onChange = { number = it },
            )
            staticItem(23, icon(Material.NAME_TAG, "Selected: $number"))
            backControl(27, icon(Material.OAK_DOOR, "Back"), navigator)
            searchControl(
                slot = 28,
                item = icon(Material.OAK_SIGN, "Search chat · current: $query"),
                input = input,
                player = player,
                prompt = Component.text("Type a non-blank menu search query.", NamedTextColor.GOLD),
                blankFeedback = Component.text("The search cannot be blank.", NamedTextColor.RED),
                onSearch = { query = it },
            )
            filler(SlotRegion.of(listOf(29, 30, 31, 32, 33, 34, 35)), icon(Material.BLACK_STAINED_GLASS_PANE, " "))
        }
    }
}

context(menu: MenuScope)
private fun StorageScreen(
    model: MenuShowcaseModel,
    navigator: dev.placeholder.framework.menus.MenuNavigator<ShowcaseRoute>,
) {
    chest("Pessimistic shared vault", rows = 3) {
        storage(model.vault, rows(0..0))
        playerInventory(PlayerInventorySection.MAIN, PlayerInventorySection.HOTBAR, PlayerInventorySection.OFFHAND)
        transfers(
            transferRoute(
                model.vault.reference(),
                PlayerInventorySection.HOTBAR.reference(),
                PlayerInventorySection.MAIN.reference(),
            ),
            transferRoute(PlayerInventorySection.MAIN.reference(), model.vault.reference()),
            transferRoute(PlayerInventorySection.HOTBAR.reference(), model.vault.reference()),
            transferRoute(PlayerInventorySection.OFFHAND.reference(), model.vault.reference()),
        )
        staticItem(13, icon(Material.BOOK, "Slot 1 accepts diamonds; slot 2 is locked"))
        backControl(18, icon(Material.OAK_DOOR, "Back"), navigator)
        closeControl(26, icon(Material.BARRIER, "Close"))
    }
}

context(menu: MenuScope)
private fun ConfirmScreen(navigator: dev.placeholder.framework.menus.MenuNavigator<ShowcaseRoute>) {
    chest("Confirmation component", rows = 3) {
        confirmation(
            confirmSlot = 11,
            confirmItem = icon(Material.LIME_CONCRETE, "Confirm"),
            cancelSlot = 15,
            cancelItem = icon(Material.RED_CONCRETE, "Cancel"),
            onConfirm = { navigator.replace(ShowcaseRoute.Home) },
            onCancel = { navigator.back() },
        )
        backControl(18, icon(Material.OAK_DOOR, "Back"), navigator)
    }
}

context(menu: MenuScope)
private fun ChoiceMenu() {
    chest("Typed choice", rows = 1) {
        slot(2) {
            item = icon(Material.RED_WOOL, "Red")
            onPrimary { finish("red") }
        }
        slot(4) {
            item = icon(Material.GREEN_WOOL, "Green")
            onPrimary { finish("green") }
        }
        slot(6) {
            item = icon(Material.BLUE_WOOL, "Blue")
            onPrimary { finish("blue") }
        }
        closeControl(8, icon(Material.BARRIER, "Cancel"))
    }
}

private fun nativeSnapshot(material: Material, amount: Int, name: String) =
    Items.capture(Items.materialize(item(material) {
        this.amount = amount
        this.name = Component.text(name)
    }))

private fun icon(
    material: Material,
    name: String,
    color: NamedTextColor = NamedTextColor.AQUA,
): ItemSpec = item(material) {
    this.name = Component.text(name, color)
    rarity(ItemRarity.COMMON)
}

private fun MenuClose.label(): String = when (this) {
    MenuClose.PlayerClosed -> "player closed"
    MenuClose.ExternalInventory -> "external inventory"
    MenuClose.Explicit -> "explicit"
    MenuClose.Replaced -> "replaced"
    MenuClose.Disconnected -> "disconnected"
    MenuClose.Died -> "death"
    MenuClose.Kicked -> "kick"
    MenuClose.CallerCancelled -> "caller cancelled"
    MenuClose.PluginStopped -> "plugin stopped"
    is MenuClose.Failed -> "failed: ${cause::class.simpleName}"
}
