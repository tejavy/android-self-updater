package io.github.selfupdater.sample

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSyncClientTest {
    @Test
    fun `parses running sync status`() {
        assertTrue(parseSyncRunning("""{"state":"running","syncRunning":true}"""))
    }

    @Test
    fun `parses finished sync status`() {
        assertFalse(parseSyncRunning("""{"state":"idle","syncRunning":false}"""))
    }

    @Test
    fun `rejects status without syncRunning`() {
        assertThrows(IOException::class.java) { parseSyncRunning("""{"state":"idle"}""") }
    }

    @Test
    fun `rejects non boolean syncRunning`() {
        assertThrows(IOException::class.java) { parseSyncRunning("""{"syncRunning":"false"}""") }
    }

    @Test
    fun `rejects malformed status JSON`() {
        assertThrows(IOException::class.java) { parseSyncRunning("{") }
    }
}
