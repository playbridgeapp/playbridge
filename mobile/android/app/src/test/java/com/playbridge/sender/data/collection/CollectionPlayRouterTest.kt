package com.playbridge.sender.data.collection

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionPlayRouterTest {

    @Test
    fun localKindRoutesToLocal() {
        assertEquals(CollectionRoute.LOCAL, CollectionPlayRouter.routeOf(CollectionItemKind.LOCAL))
    }

    @Test
    fun webKindRoutesToWeb() {
        assertEquals(CollectionRoute.WEB, CollectionPlayRouter.routeOf(CollectionItemKind.WEB))
    }

    @Test
    fun unknownOrNullDefaultsToWeb() {
        assertEquals(CollectionRoute.WEB, CollectionPlayRouter.routeOf(null))
        assertEquals(CollectionRoute.WEB, CollectionPlayRouter.routeOf("something_else"))
    }
}
