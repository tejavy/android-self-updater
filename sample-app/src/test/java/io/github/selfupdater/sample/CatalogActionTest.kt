package io.github.selfupdater.sample

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogActionTest {
    @Test
    fun `offers update only when available version code is higher`() {
        assertEquals(CatalogAction.Update, classifyCatalogAction(11, 12))
    }

    @Test
    fun `offers reinstall when version codes are equal`() {
        assertEquals(CatalogAction.Reinstall, classifyCatalogAction(12, 12))
    }

    @Test
    fun `blocks reinstall when installed version is newer`() {
        assertEquals(CatalogAction.NewerInstalled, classifyCatalogAction(13, 12))
    }

    @Test
    fun `offers install when package is not installed`() {
        assertEquals(CatalogAction.Install, classifyCatalogAction(null, 12))
    }
}
