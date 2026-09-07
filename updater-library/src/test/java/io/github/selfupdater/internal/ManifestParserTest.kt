package io.github.selfupdater.internal

import io.github.selfupdater.InvalidManifestException
import io.github.selfupdater.MissingApplicationManifestException
import io.github.selfupdater.MissingUpdateChannelException
import io.github.selfupdater.UpdateChannel
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
              "com.example.other": {
                "version": "9.0.0",
                "versionCode": 90,
                "apkUrl": "https://repo.example.com/other.apk",
                "sha256": "${"b".repeat(64)}",
                "changelog": [],
                "mandatory": true
              },
              "com.example.app": {
                "displayName": "Example App",
                "version": "1.2.0",
                "versionCode": 12,
                "apkUrl": "https://repo.example.com/app-v1.2.0.apk",
                "sha256": "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789",
                "changelog": ["Added feature X", "Fixed crash Y"],
                "mandatory": false,
                "futureField": "safe to ignore"
              }
            }
            """.trimIndent(),
            applicationId = "com.example.app",
        )

        assertEquals("1.2.0", result.version)
        assertEquals("com.example.app", result.applicationId)
        assertEquals("Example App", result.displayName)
        assertEquals(UpdateChannel.STABLE, result.channel)
        assertEquals(12, result.versionCode)
        assertEquals(listOf("Added feature X", "Fixed crash Y"), result.changelog)
        assertFalse(result.mandatory)
        assertEquals(result.sha256.lowercase(), result.sha256)
    }

    @Test
    fun `parses stable and beta channels without duplicating top-level stable fields`() {
        val body =
            """
            {
              "com.gidr.priority_game": {
                "displayName": "Priority Game",
                "version": "top-level-fallback",
                "versionCode": 1,
                "apkUrl": "https://repo.example.com/fallback.apk",
                "sha256": "${"f".repeat(64)}",
                "changelog": [],
                "mandatory": false,
                "channels": {
                  "stable": {
                    "version": "1.1", "versionCode": 210971727,
                    "apkUrl": "https://repo.example.com/com.gidr.priority_game-1.1.apk",
                    "sha256": "${"a".repeat(64)}", "changelog": ["Stable"], "mandatory": false
                  },
                  "beta": {
                    "version": "1.1-dev.1", "versionCode": 210971051,
                    "apkUrl": "https://repo.example.com/com.gidr.priority_game-1.1-dev.1.apk",
                    "sha256": "${"b".repeat(64)}", "changelog": ["Beta"], "mandatory": false
                  }
                }
              }
            }
            """.trimIndent()

        val catalog = parser.parseCatalog(body)
        val beta = parser.parse(body, "com.gidr.priority_game", UpdateChannel.BETA)

        assertEquals(listOf(UpdateChannel.STABLE, UpdateChannel.BETA), catalog.map { it.channel })
        assertEquals(listOf("1.1", "1.1-dev.1"), catalog.map { it.version })
        assertEquals("Priority Game", catalog.last().displayName)
        assertEquals("1.1-dev.1", beta.version)
        assertEquals(UpdateChannel.BETA, beta.channel)
    }

    @Test(expected = MissingUpdateChannelException::class)
    fun `reports a missing requested beta channel`() {
        parser.parse(
            """
            {
              "com.example.app": {
                "version":"1.0", "versionCode":1,
                "apkUrl":"https://repo.example.com/app.apk",
                "sha256":"${"a".repeat(64)}", "changelog":[], "mandatory":false
              }
            }
            """.trimIndent(),
            "com.example.app",
            UpdateChannel.BETA,
        )
    }

    @Test
    fun `parses every catalog entry in manifest order`() {
        val result = parser.parseCatalog(
            """
            {
              "com.example.first": {
                "version":"1.0.0", "versionCode":1,
                "apkUrl":"https://repo.example.com/first.apk",
                "sha256":"${"a".repeat(64)}", "changelog":[], "mandatory":false
              },
              "com.example.second": {
                "version":"2.0.0", "versionCode":2,
                "apkUrl":"https://repo.example.com/second.apk",
                "sha256":"${"b".repeat(64)}", "changelog":["New"], "mandatory":true
              }
            }
            """.trimIndent(),
        )

        assertEquals(listOf("com.example.first", "com.example.second"), result.map { it.applicationId })
        assertEquals("2.0.0", result.last().version)
    }

    @Test(expected = InvalidManifestException::class)
    fun `rejects missing required field`() {
        parser.parse(
            """{"com.example.app":{"version":"1.2.0","versionCode":12,"apkUrl":"https://repo.example.com/a.apk"}}""",
            applicationId = "com.example.app",
        )
    }

    @Test(expected = InvalidManifestException::class)
    fun `rejects non-string changelog entries`() {
        parser.parse(
            """
            {
              "com.example.app": {
                "version":"1.2.0",
                "versionCode":12,
                "apkUrl":"https://repo.example.com/a.apk",
                "sha256":"${"a".repeat(64)}",
                "changelog":[42],
                "mandatory":false
              }
            }
            """.trimIndent(),
            applicationId = "com.example.app",
        )
    }

    @Test(expected = MissingApplicationManifestException::class)
    fun `rejects catalog without current application id`() {
        parser.parse(
            body = """{"com.example.other":{}}""",
            applicationId = "com.example.app",
        )
    }
}
