package io.github.selfupdater.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {
    @Test
    fun `newer remote version code is available`() {
        assertTrue(VersionComparator.isUpdateAvailable(currentVersionCode = 11, latestVersionCode = 12))
    }

    @Test
    fun `equal version code is ignored`() {
        assertFalse(VersionComparator.isUpdateAvailable(currentVersionCode = 12, latestVersionCode = 12))
    }

    @Test
    fun `older remote version code is ignored`() {
        assertFalse(VersionComparator.isUpdateAvailable(currentVersionCode = 13, latestVersionCode = 12))
    }
}

