package pink.alex.ashlar.events.codegen

import pink.alex.ashlar.events.Events
import pink.alex.ashlar.events.ConfigureLifecycleEvents
import pink.alex.ashlar.events.DisableEvents
import pink.alex.ashlar.events.LifecycleEventRegistry
import pink.alex.ashlar.events.On
import pink.alex.ashlar.events.Observe
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import org.bukkit.plugin.Plugin
import pink.alex.ashlar.di.DependencyGraph
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents

class GeneratedEventBindingTest {
    @Test
    fun `generated binding invokes an internal extension handler directly`() {
        val target = GeneratedEventFixture()
        val binding = GeneratedEventFixtureGeneratedEventBinding()

        binding.invoke(target, 0, TestEvent())

        assertEquals(1, target.calls)
    }

    @Test
    fun `generated binding invokes a suspending observer directly`() = runBlocking {
        val target = GeneratedEventFixture()
        val binding = GeneratedEventFixtureGeneratedEventBinding()

        binding.observe(target, 1, TestEvent())

        assertEquals(1, target.observations)
    }

    @Test
    fun `generated binding invokes lifecycle configuration directly`() {
        val target = GeneratedEventFixture()
        val binding = GeneratedEventFixtureGeneratedEventBinding()
        @Suppress("UNCHECKED_CAST")
        val manager = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(LifecycleEventManager::class.java),
        ) { _, _, _ -> null } as LifecycleEventManager<Plugin>
        val plugin = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Plugin::class.java),
        ) { _, method, _ -> if (method.name == "getLifecycleManager") manager else null } as Plugin

        binding.configureLifecycle(target, LifecycleEventRegistry(plugin))

        assertEquals(1, target.lifecycleConfigurations)
    }

    @Test
    fun `concrete descendant inherits metadata and dispatches its override`() {
        val target = InheritedGeneratedEventFixture()
        val binding = InheritedGeneratedEventFixtureGeneratedEventBinding()

        binding.invoke(target, 0, TestEvent())

        assertEquals(2, target.inheritedCalls)
    }

    @Test
    fun `disabled event branch contributes no generated binding`() {
        val graph = DependencyGraph(javaClass.classLoader)

        val contributed = graph.contributions(EventSetContribution::class)
            .map(EventSetContribution::targetType)

        assertEquals(false, DisabledGeneratedEventFixture::class in contributed)
        graph.close()
    }
}

@Events
internal class GeneratedEventFixture {
    var calls: Int = 0
    var observations: Int = 0
    var lifecycleConfigurations: Int = 0

    @On
    internal fun TestEvent.receive() {
        calls++
    }

    @Observe
    internal suspend fun TestEvent.observe() {
        observations++
    }

    @ConfigureLifecycleEvents
    internal fun LifecycleEventRegistry.configureFixture() {
        lifecycleConfigurations++
    }

    @Suppress("unused")
    internal fun LifecycleEventRegistry.compileNativeKeys() {
        on(LifecycleEvents.COMMANDS) {}
        monitor(LifecycleEvents.COMMANDS) {}
    }
}

@Events
internal abstract class BaseGeneratedEventFixture {
    var inheritedCalls: Int = 0

    @On
    internal open fun TestEvent.inherited() {
        inheritedCalls++
    }
}

internal class InheritedGeneratedEventFixture : BaseGeneratedEventFixture() {
    override fun TestEvent.inherited() {
        inheritedCalls += 2
    }
}

@DisableEvents
internal class DisabledGeneratedEventFixture : BaseGeneratedEventFixture()

internal class TestEvent : Event() {
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()
    }
}
