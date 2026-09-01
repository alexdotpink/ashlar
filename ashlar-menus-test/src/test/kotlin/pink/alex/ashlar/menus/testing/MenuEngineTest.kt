package pink.alex.ashlar.menus.testing

import pink.alex.ashlar.items.item
import pink.alex.ashlar.menus.MenuActionConcurrency
import pink.alex.ashlar.menus.MenuClose
import pink.alex.ashlar.menus.MenuDispatch
import pink.alex.ashlar.menus.MenuGesture
import pink.alex.ashlar.menus.MenuNativeClose
import pink.alex.ashlar.menus.MenuScope
import pink.alex.ashlar.menus.MenuValidationException
import pink.alex.ashlar.menus.NativeClose
import pink.alex.ashlar.menus.chest
import pink.alex.ashlar.menus.collectAsState
import pink.alex.ashlar.menus.component
import pink.alex.ashlar.menus.effect
import pink.alex.ashlar.menus.errorBoundary
import pink.alex.ashlar.menus.navigator
import pink.alex.ashlar.menus.current
import pink.alex.ashlar.menus.menuLocal
import pink.alex.ashlar.menus.provide
import pink.alex.ashlar.menus.screen
import pink.alex.ashlar.menus.slot
import pink.alex.ashlar.menus.state
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import net.kyori.adventure.text.Component
import org.bukkit.Material

class MenuEngineTest {
    private val icon = item(Material.COMPASS)

    @Test
    fun `state mutations in one action conflate to one render`() = menuTest {
        val menu = open {
            component("counter") {
                var count by state(0)
                chest("Counter", rows = 1) {
                    slot(0) {
                        item = icon
                        onPrimary {
                            count++
                            count++
                        }
                    }
                }
            }
        }

        menu.assertRevision(1)
        assertIs<MenuDispatch.Accepted>(menu.primaryClick(0))
        menu.assertRevision(2)
        assertEquals("2", menu.render.stateCells["/root/counter:count"])
    }

    @Test
    fun `flow bursts conflate to the newest pending render`() = menuTest {
        val values = MutableStateFlow(0)
        val menu = open {
            component("flow") {
                val value by collectAsState(values, initial = 0)
                chest("Flow", rows = 1) { slot(0) { item = icon } }
                check(value >= 0)
            }
        }

        values.value = 1
        values.value = 2
        values.value = 3
        runCurrent()

        menu.assertRevision(2)
        assertEquals("3", menu.render.stateCells["/root/flow:value"])
        menu.close()
    }

    @Test
    fun `component keys retain state when repeated children reorder`() = menuTest {
        val menu = open {
            component("list") {
                var reversed by state(false)
                val values = if (reversed) listOf("beta", "alpha") else listOf("alpha", "beta")
                chest("Keys", rows = 1) {
                    values.forEachIndexed { index, value ->
                        component(value) {
                            var clicks by state(0)
                            slot(index) {
                                item = icon
                                onPrimary { clicks++ }
                            }
                        }
                    }
                    slot(8) {
                        item = icon
                        onPrimary { reversed = !reversed }
                    }
                }
            }
        }

        menu.primaryClick(0)
        menu.primaryClick(8)

        assertEquals("1", menu.render.stateCells["/root/list/alpha:clicks"])
        assertEquals("0", menu.render.stateCells["/root/list/beta:clicks"])
        assertEquals("/root/list/beta", menu.chest[0]?.owner.toString())
    }

    @Test
    fun `single flight rejects overlap while restart latest cancels old work`() = menuTest {
        var completions = 0
        val menu = open {
            chest("Concurrency", rows = 1) {
                slot(0) {
                    item = icon
                    onPrimary(concurrency = MenuActionConcurrency.SINGLE_FLIGHT) {
                        delay(1.seconds)
                        completions++
                    }
                }
            }
        }

        assertIs<MenuDispatch.Accepted>(menu.primaryClick(0))
        assertIs<MenuDispatch.AlreadyRunning>(menu.primaryClick(0))
        advanceTimeBy(1.seconds)
        assertEquals(1, completions)
    }

    @Test
    fun `error boundary keeps session alive and retries descendant render`() = menuTest {
        var fail = true
        val menu = open {
            errorBoundary(
                fallback = { _, retry ->
                    chest("Fallback", rows = 1) {
                        slot(0) {
                            item = icon
                            onPrimary {
                                fail = false
                                retry.retry()
                            }
                        }
                    }
                },
            ) {
                if (fail) error("broken")
                chest("Recovered", rows = 1) { slot(0) { item = icon } }
            }
        }

        assertEquals(Component.text("Fallback"), menu.chest.title)
        menu.primaryClick(0)
        assertTrue(menu.render.revision >= 2)
    }

    @Test
    fun `effect key change disposes old work after commit`() = menuTest {
        var disposed = 0
        val menu = open {
            component("effect-owner") {
                var key by state(0)
                effect(key) { onDispose { disposed++ } }
                chest("Effects", rows = 1) {
                    slot(0) {
                        item = icon
                        onPrimary { key++ }
                    }
                }
            }
        }

        menu.primaryClick(0)
        assertEquals(1, disposed)
        menu.close()
        assertEquals(2, disposed)
    }

    @Test
    fun `native player close goes back only on an opted in screen`() = menuTest {
        val menu = open { NavigationMenu() }

        menu.primaryClick(0)
        assertEquals(listOf("Root", "Details"), menu.render.navigation)
        menu.nativeClose(MenuNativeClose.PLAYER)
        assertEquals(listOf("Root"), menu.render.navigation)
        assertEquals(0, menu.nativeCloseCalls())

        menu.nativeClose(MenuNativeClose.PLAYER)
        assertEquals(1, menu.nativeCloseCalls())
    }

    @Test
    fun `typed choice completes separately from ordinary close`() = menuTest {
        val choice = choose<String> {
            chest("Choose", rows = 1) {
                slot(0) {
                    item = icon
                    onPrimary { finish("selected") }
                }
            }
        }

        choice.menu.primaryClick(0)
        assertEquals("selected", choice.awaitSelected())
        assertEquals(1, choice.menu.nativeCloseCalls())
    }

    @Test
    fun `typed locals are captured in semantic slot output`() = menuTest {
        val theme = menuLocal("theme") { "default" }
        val menu = open {
            provide(theme, "supporter") {
                chest("Local", rows = 1) {
                    slot(0) {
                        item = icon
                        check(theme.current() == "supporter")
                    }
                }
            }
        }

        assertEquals("String(length=9)", menu.chest[0]?.locals?.get("theme"))
    }

    @Test
    fun `duplicate physical slots fail before any host mount`() = menuTest {
        val menu = open {
            chest("Collision", rows = 1) {
                slot(0) { item = icon }
                component("other") { slot(0) { item = icon } }
            }
        }

        val failure = assertIs<MenuClose.Failed>(menu.awaitClose())
        assertIs<MenuValidationException>(failure.cause)
        assertEquals(1, menu.nativeCloseCalls())
    }

    private sealed interface Route {
        data object Root : Route
        data object Details : Route
    }

    context(menu: MenuScope)
    private fun NavigationMenu() {
        navigator<Route>(Route.Root) {
            screen<Route, Route.Root> {
                chest("Root", rows = 1) {
                    slot(0) {
                        item = icon
                        onPrimary { navigator.push(Route.Details) }
                    }
                }
            }
            screen<Route, Route.Details>(nativeClose = NativeClose.BACK) {
                chest("Details", rows = 1) { slot(0) { item = icon } }
            }
        }
    }
}
