package com.playbridge.sender.data.nuvio

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import com.playbridge.sender.FlavorConfig
import org.junit.Test

class NuvioFlavorEngineTest {
    @Test
    fun factoryCreatesEngineForFlavor() {
        val engine = createNuvioScraperEngine()
        assertNotNull(engine)
        assertEquals(FlavorConfig.SCRAPER_PLUGINS_SUPPORTED, engine.canExecute)
    }
}
