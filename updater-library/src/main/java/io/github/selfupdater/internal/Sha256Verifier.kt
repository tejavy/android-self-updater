package io.github.selfupdater.internal

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

internal object Sha256Verifier {
    fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    fun verify(file: File, expected: String): Verification {
        val actual = digest(file)
        return Verification(matches = actual.equals(expected, ignoreCase = true), actual = actual)
    }

    data class Verification(val matches: Boolean, val actual: String)
}

