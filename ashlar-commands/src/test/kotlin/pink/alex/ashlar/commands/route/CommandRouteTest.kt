package pink.alex.ashlar.commands.route

import pink.alex.ashlar.commands.CommandInvocation
import pink.alex.ashlar.commands.codec.CommandArgumentCodec
import pink.alex.ashlar.di.DependencyResolver
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import net.kyori.adventure.text.event.ClickEvent

class CommandRouteTest {
    @Test
    fun `route command uses brigadier quoting and escaping`() {
        val route = commandRoute(
            route = "waypoint.search",
            segments = listOf(
                routeLiteral("waypoint"),
                routeLiteral("search"),
                routeArgument("market-1.2+shops_main"),
                routeArgument("market square"),
                routeArgument("say \"hello\" \\ home"),
                routeArgument(""),
                routeArgument("München"),
            ),
        )

        assertEquals(
            "/waypoint search market-1.2+shops_main \"market square\" " +
                "\"say \\\"hello\\\" \\\\ home\" \"\" \"München\"",
            route.command,
        )
    }

    @Test
    fun `run and suggest links use the canonical command`() {
        val route = commandRoute(
            route = "waypoint.show",
            segments = listOf(
                routeLiteral("waypoint"),
                routeLiteral("show"),
                routeArgument("market square"),
            ),
        )

        assertEquals(ClickEvent.runCommand("/waypoint show \"market square\""), route.runLink())
        assertEquals(ClickEvent.suggestCommand("/waypoint show \"market square\""), route.suggestLink())
    }

    @Test
    fun `semantic identity is immutable and includes canonical arguments`() {
        val mutableSegments = mutableListOf(
            routeLiteral("waypoint"),
            routeLiteral("show"),
            routeArgument("market square"),
        )
        val first = commandRoute("waypoint.show", mutableSegments)
        mutableSegments += routeArgument("unexpected")
        val same = commandRoute(
            "waypoint.show",
            listOf(routeLiteral("waypoint"), routeLiteral("show"), routeArgument("market square")),
        )
        val differentArgument = commandRoute(
            "waypoint.show",
            listOf(routeLiteral("waypoint"), routeLiteral("show"), routeArgument("spawn")),
        )

        assertEquals("/waypoint show \"market square\"", first.command)
        assertEquals(first.identity, same.identity)
        assertEquals(first, same)
        assertNotEquals(first.identity, differentArgument.identity)
    }

    @Test
    fun `sensitive values require a wrapper and stay out of identity text`() {
        val wrapped = sensitive("winter palace")
        val first = commandRoute(
            "account.login",
            listOf(
                routeLiteral("account"),
                routeLiteral("login"),
                StringCodec.sensitiveRouteArgument(wrapped),
            ),
        )
        val same = commandRoute(
            "account.login",
            listOf(
                routeLiteral("account"),
                routeLiteral("login"),
                StringCodec.sensitiveRouteArgument(sensitive("winter palace")),
            ),
        )
        val different = commandRoute(
            "account.login",
            listOf(
                routeLiteral("account"),
                routeLiteral("login"),
                StringCodec.sensitiveRouteArgument(sensitive("summer palace")),
            ),
        )

        assertEquals("[sensitive]", wrapped.toString())
        assertTrue(first.command.contains("winter palace"))
        assertFalse(first.identity.toString().contains("winter palace"))
        assertFalse(first.toString().contains("winter palace"))
        assertEquals(first.identity, same.identity)
        assertNotEquals(first.identity, different.identity)
    }

    @Test
    fun `invalid literals and control characters fail before a link is built`() {
        assertFailsWith<IllegalArgumentException> { routeLiteral("not a literal") }
        assertFailsWith<IllegalArgumentException> { routeArgument("line\nbreak") }
        assertFailsWith<IllegalArgumentException> {
            commandRoute("blank\nidentity", listOf(routeLiteral("root")))
        }
    }

    private object StringCodec : CommandArgumentCodec<String> {
        override val type: KClass<String> = String::class

        override suspend fun resolve(
            raw: String,
            invocation: CommandInvocation,
            dependencies: DependencyResolver,
        ): String = raw

        override fun encode(value: String): String = value
    }
}
