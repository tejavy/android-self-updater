package io.github.selfupdater.internal

import io.github.selfupdater.InvalidManifestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ManifestParserTest {
    private val parser = ManifestParser()

    @Test
    fun `parses valid manifest and ignores additive fields`() {
        val result = parser.parse(
            """
            {
              "version": "1.2.0",
              "versionCode": 12,
              "apkUrl": "https://repo.example.com/app-v1.2.0.apk",
              "sha256": "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789",
              "changelog": ["Added feature X", "Fixed crash Y"],
              "mandatory": false,
              "futureField": "safe to ignore"
            }
            """.trimIndent(),
        )

        assertEquals("1.2.0", result.version)
        assertEquals(12, result.versionCode)
        assertEquals(listOf("Added feature X", "Fixed crash Y"), result.changelog)
        assertFalse(result.mandatory)
        assertEquals(result.sha256.lowercase(), result.sha256)
    }

    @Test(expected = InvalidManifestException::class)
    fun `rejects missing required field`() {
        parser.parse(
            """{"version":"1.2.0","versionCode":12,"apkUrl":"https://repo.example.com/a.apk"}""",
        )
    }

    @Test(expected = InvalidManifestException::class)
    fun `rejects non-string changelog entries`() {
        parser.parse(
            """
            {
              "version":"1.2.0",
              "versionCode":12,
              "apkUrl":"https://repo.example.com/a.apk",
              "sha256":"${"a".repeat(64)}",
              "changelog":[42],
              "mandatory":false
            }
            """.trimIndent(),
        )
    }
}

