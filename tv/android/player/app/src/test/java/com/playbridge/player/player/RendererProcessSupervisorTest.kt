package com.playbridge.player.player

import org.junit.Assert.assertEquals
import org.junit.Test

class RendererProcessSupervisorTest {
    @Test
    fun buildsPrivateProcessNames() {
        assertEquals(
            "com.playbridge.player:mpv",
            RendererProcessSupervisor.processName(
                "com.playbridge.player",
                RendererProcessSupervisor.Kind.MPV,
            ),
        )
        assertEquals(
            "com.playbridge.player:exo",
            RendererProcessSupervisor.processName(
                "com.playbridge.player",
                RendererProcessSupervisor.Kind.EXO,
            ),
        )
    }

    @Test
    fun newerGenerationInvalidatesOlderGeneration() {
        val first = RendererProcessSupervisor.nextGeneration(RendererProcessSupervisor.Kind.MPV)
        val second = RendererProcessSupervisor.nextGeneration(RendererProcessSupervisor.Kind.MPV)

        assertEquals(false, RendererProcessSupervisor.isCurrentGeneration(
            RendererProcessSupervisor.Kind.MPV,
            first,
        ))
        assertEquals(true, RendererProcessSupervisor.isCurrentGeneration(
            RendererProcessSupervisor.Kind.MPV,
            second,
        ))
    }
}
