package com.playbridge.player.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginClientRegistryTest {
    private class Client(val name: String)

    @Test fun unboundClientUsesBroadcastFallback() {
        val delivered = mutableListOf<String>()
        val registry = PluginClientRegistry<Client, String> { _, _ -> error("no client") }
        registry.deliver("remote") { delivered += "broadcast:remote" }
        assertEquals(listOf("broadcast:remote"), delivered)
    }

    @Test fun boundClientUsesOnlyThePrimaryChannel() {
        val delivered = mutableListOf<String>()
        val registry = PluginClientRegistry<Client, String> { client, command ->
            delivered += "${client.name}:$command"
            true
        }
        registry.replace(Client("plugin"))
        registry.deliver("mouse") { error("duplicate broadcast") }
        assertEquals(listOf("plugin:mouse"), delivered)
    }

    @Test fun newHelloReplacesOldClient() {
        val delivered = mutableListOf<String>()
        val registry = PluginClientRegistry<Client, String> { client, command ->
            delivered += "${client.name}:$command"
            true
        }
        val old = Client("old")
        val next = Client("new")
        assertNull(registry.replace(old))
        assertSame(old, registry.replace(next))
        registry.deliver("dismiss") { error("unexpected fallback") }
        assertEquals(listOf("new:dismiss"), delivered)
        assertSame(next, registry.current())
    }

    @Test fun deadClientIsDroppedAndCommandFallsBackExactlyOnce() {
        var attempts = 0
        val fallback = mutableListOf<String>()
        val registry = PluginClientRegistry<Client, String> { _, _ -> attempts++; false }
        registry.replace(Client("dead"))
        registry.deliver("first") { fallback += "first" }
        registry.deliver("second") { fallback += "second" }
        assertNull(registry.current())
        assertEquals(1, attempts)
        assertEquals(listOf("first", "second"), fallback)
    }

    @Test fun lateDeathOrGoodbyeCannotClearReplacementClient() {
        val registry = PluginClientRegistry<Client, String> { _, _ -> true }
        val old = Client("old")
        val next = Client("next")
        registry.replace(old)
        registry.replace(next)
        assertFalse(registry.remove(old))
        assertSame(next, registry.current())
        assertTrue(registry.remove(next))
        assertNull(registry.current())
    }

    @Test fun mouseDownMoveUpOrderIsPreservedOnPrimaryChannel() {
        val order = mutableListOf<String>()
        val registry = PluginClientRegistry<Client, String> { _, message -> order += message; true }
        registry.replace(Client("plugin"))
        listOf("down", "move:1", "move:2", "up").forEach { command ->
            registry.deliver(command) { error("unexpected fallback") }
        }
        assertEquals(listOf("down", "move:1", "move:2", "up"), order)
    }

    @Test fun failedDeliveryAndFollowingFallbackPreserveDispatchOrder() {
        val order = mutableListOf<String>()
        val registry = PluginClientRegistry<Client, String> { _, command ->
            if (command == "down") { order += "down"; true } else false
        }
        registry.replace(Client("plugin"))
        listOf("down", "move", "up").forEach { command ->
            registry.deliver(command) { order += command }
        }
        assertEquals(listOf("down", "move", "up"), order)
    }

    @Test fun aFailureFromOldClientCannotDropAReplacement() {
        val next = Client("next")
        lateinit var registry: PluginClientRegistry<Client, String>
        registry = PluginClientRegistry { _, _ -> registry.replace(next); false }
        registry.replace(Client("old"))
        var fallbacks = 0
        registry.deliver("ua") { fallbacks++ }
        assertSame(next, registry.current())
        assertEquals(1, fallbacks)
    }

    @Test fun disconnectedClientFallsBackUntilNextHello() {
        val registry = PluginClientRegistry<Client, String> { _, _ -> true }
        val old = Client("old")
        registry.replace(old)
        registry.remove(old)
        var broadcasts = 0
        registry.deliver("mouse") { broadcasts++ }
        registry.replace(Client("reconnected"))
        registry.deliver("mouse") { broadcasts++ }
        assertEquals(1, broadcasts)
    }
}
