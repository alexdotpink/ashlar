package pink.alex.ashlar.execution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import org.bukkit.World
import org.bukkit.entity.Entity

class ExecutionContextTest {
    @Test
    fun `entity access revalidates ownership`() {
        var owned = true
        val entity = interfaceStub<Entity>()
        val context = EntityContext(entity) {
            if (!owned) throw OwnershipViolationException("retired")
        }

        assertSame(entity, context.entity)
        owned = false

        assertFailsWith<OwnershipViolationException> { context.entity }
    }

    @Test
    fun `every region property revalidates ownership`() {
        var checks = 0
        val world = interfaceStub<World>()
        val context = RegionContext(world, 12, -7) { checks += 1 }

        assertSame(world, context.world)
        assertEquals(12, context.chunkX)
        assertEquals(-7, context.chunkZ)

        assertEquals(3, checks)
    }

    @Test
    fun `global capability can be revalidated after capture`() {
        var owned = true
        val context = GlobalContext {
            if (!owned) throw OwnershipViolationException("wrong domain")
        }
        context.checkOwnership()
        owned = false

        assertFailsWith<OwnershipViolationException> { context.checkOwnership() }
    }
}

private inline fun <reified T> interfaceStub(): T =
    java.lang.reflect.Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "${T::class.simpleName}Stub"
            else -> error("Unexpected call to ${method.name}")
        }
    } as T
