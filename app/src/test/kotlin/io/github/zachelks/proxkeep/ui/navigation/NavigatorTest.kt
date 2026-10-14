package io.github.zachelks.proxkeep.ui.navigation

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class NavigatorTest {

    private val backStack = mutableListOf<Route>(Route.Main)
    private val navigator = Navigator(backStack)

    @Test
    fun openingAScreenPutsItOnTop() {
        navigator.open(Route.Settings)
        navigator.open(Route.SignIn("a"))

        assertEquals(listOf(Route.Main, Route.Settings, Route.SignIn("a")), backStack)
    }

    @Test
    fun openingTheScreenOnTopAgainDoesNothing() {
        navigator.open(Route.Terminal)
        navigator.open(Route.Terminal)

        assertEquals(listOf(Route.Main, Route.Terminal), backStack)
    }

    @Test
    fun aSignInForAnotherServerIsAnotherScreen() {
        navigator.open(Route.SignIn("a"))
        navigator.open(Route.SignIn("b"))

        assertEquals(listOf(Route.Main, Route.SignIn("a"), Route.SignIn("b")), backStack)
    }

    @Test
    fun backNeverLeavesTheMainScreen() {
        navigator.open(Route.Settings)

        navigator.back()
        navigator.back()

        assertEquals(listOf<Route>(Route.Main), backStack)
    }

    @Test
    fun closingRemovesTheScreenWhereverItIs() {
        navigator.open(Route.Settings)
        navigator.open(Route.SignIn())

        navigator.close(Route.Settings)

        assertEquals(listOf(Route.Main, Route.SignIn()), backStack)
    }

    @Test
    fun closingTwiceOrClosingMainIsHarmless() {
        navigator.open(Route.Terminal)

        navigator.close(Route.Terminal)
        navigator.close(Route.Terminal)
        navigator.close(Route.Main)

        assertEquals(listOf<Route>(Route.Main), backStack)
    }

    @Test
    fun everyRouteSurvivesBeingSaved() {
        val routes = listOf(Route.Main, Route.Settings, Route.SignIn(), Route.SignIn("a"), Route.Terminal)

        val restored = routes.map { Json.decodeFromString(Route.serializer(), Json.encodeToString(Route.serializer(), it)) }

        assertEquals(routes, restored)
    }
}
