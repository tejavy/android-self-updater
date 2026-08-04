package io.github.selfupdater.internal

import io.github.selfupdater.InvalidManifestException
import io.github.selfupdater.UnexpectedUpdaterException
import io.github.selfupdater.UpdateNetworkException
import java.io.IOException
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorHandlingTest {
    @Test
    fun `preserves already typed updater errors`() {
        val original = InvalidManifestException("bad checksum")

        assertSame(original, ErrorMapper.map(original, "testing"))
    }

    @Test
    fun `maps IO errors to network errors with cause`() {
        val cause = IOException("offline")
        val mapped = ErrorMapper.map(cause, "checking")

        assertTrue(mapped is UpdateNetworkException)
        assertSame(cause, mapped.cause)
    }

    @Test
    fun `maps unknown failures without losing cause`() {
        val cause = IllegalStateException("boom")
        val mapped = ErrorMapper.map(cause, "testing")

        assertTrue(mapped is UnexpectedUpdaterException)
        assertSame(cause, mapped.cause)
    }
}

