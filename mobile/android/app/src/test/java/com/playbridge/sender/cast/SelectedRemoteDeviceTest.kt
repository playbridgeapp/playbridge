package com.playbridge.sender.cast

import com.playbridge.sender.model.TvDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectedRemoteDeviceTest {
    @Test fun nativeSelectionRemainsAvailableWithoutSocketReadinessAndClearsOnRouteChange() {
        val tv = TvDevice("192.168.1.22", 8000, "", "TV", uuid = "tv")
        assertEquals(tv, selectedRemoteDevice(CastSessionManager.Route.NativeTv, tv, null))
        assertNull(selectedRemoteDevice(CastSessionManager.Route.ThisDevice, tv, null))
    }
    @Test fun externalSelectionRequiresMatchingEndpoint() {
        val tv = TvDevice("192.168.1.22", 8000, "", "TV", uuid = "tv")
        val other = tv.copy(uuid = "other")
        val route = CastSessionManager.Route.External(tv.endpointKey, tv.name)
        assertEquals(tv, selectedRemoteDevice(route, null, tv))
        assertNull(selectedRemoteDevice(route, null, other))
    }
}
