package com.playbridge.sender.library

import com.playbridge.sender.data.library.InstalledAddonEntity
import com.playbridge.sender.data.library.searchableCatalogs
import org.junit.Assert.assertEquals
import org.junit.Test

class AddonCatalogSearchTest {
    @Test
    fun searchesOnlyEnabledCatalogsInLibraryOrder() {
        val searchable = """[{"id":"top","type":"movie","extra":[{"name":"search"}]}]"""
        val noSearch = """[{"id":"top","type":"movie","extra":[]}]"""
        fun addon(name: String, catalogs: String = searchable) = InstalledAddonEntity(
            manifestUrl = "https://$name.example/manifest.json",
            name = name,
            baseUrl = "https://$name.example",
            catalogsJson = catalogs,
        )

        val catalogs = searchableCatalogs(listOf(
            addon("Cinemeta"),
            addon("Off").copy(isEnabled = false),
            addon("CatalogOff").copy(disabledFeatures = "catalog"),
            addon("NoSearch", noSearch),
            addon("Backup"),
        ))

        assertEquals(listOf("Cinemeta", "Backup"), catalogs.map { it.first.name })
    }
}
