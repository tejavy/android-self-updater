package io.github.selfupdater.internal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Sha256VerifierTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `calculates and verifies sha256`() {
        val file = temporaryFolder.newFile("app.apk").apply { writeText("test APK bytes") }
        val expected = "c46e831a25a1dc1f3bf4e4c2d7bea9b9137cc09b59bda10a1adf896604f8069c"

        assertEquals(expected, Sha256Verifier.digest(file))
        assertTrue(Sha256Verifier.verify(file, expected.uppercase()).matches)
    }

    @Test
    fun `reports actual checksum when verification fails`() {
        val file: File = temporaryFolder.newFile("corrupt.apk").apply { writeText("corrupt") }

        val result = Sha256Verifier.verify(file, "0".repeat(64))

        assertFalse(result.matches)
        assertEquals(64, result.actual.length)
    }
}
