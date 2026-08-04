package io.github.selfupdater.internal

import io.github.selfupdater.InvalidManifestException
import io.github.selfupdater.MissingApplicationManifestException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

internal data class UpdateManifest(
    val version: String,
    val versionCode: Long,
    val apkUrl: String,
    val sha256: String,
    val changelog: List<String>,
    val mandatory: Boolean,
)

internal class ManifestParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun parse(body: String, applicationId: String): UpdateManifest {
        try {
            val catalog = json.parseToJsonElement(body).jsonObject
            val root = catalog[applicationId]?.jsonObject
                ?: throw MissingApplicationManifestException(applicationId)
            val version = root.requiredString("version").trim()
            val versionCode = root.required("versionCode").jsonPrimitive.long
            val apkUrl = root.requiredString("apkUrl").trim()
            val sha256 = root.requiredString("sha256").trim().lowercase()
            val changelog = root.required("changelog").jsonArray.mapIndexed { index, item ->
                val primitive = item.jsonPrimitive
                if (!primitive.isString) {
                    throw InvalidManifestException("changelog[$index] must be a string")
                }
                primitive.content
            }
            val mandatory = root.required("mandatory").jsonPrimitive.boolean

            if (version.isBlank()) throw InvalidManifestException("version cannot be blank")
            if (versionCode < 1) throw InvalidManifestException("versionCode must be positive")
            if (!SHA_256.matches(sha256)) {
                throw InvalidManifestException("sha256 must contain exactly 64 hexadecimal characters")
            }

            return UpdateManifest(version, versionCode, apkUrl, sha256, changelog, mandatory)
        } catch (error: InvalidManifestException) {
            throw error
        } catch (error: MissingApplicationManifestException) {
            throw error
        } catch (error: SerializationException) {
            throw InvalidManifestException("Manifest JSON is malformed: ${error.message}", error)
        } catch (error: IllegalArgumentException) {
            throw InvalidManifestException("Manifest contains an invalid value: ${error.message}", error)
        }
    }

    private fun kotlinx.serialization.json.JsonObject.required(name: String) =
        this[name] ?: throw InvalidManifestException("Manifest field '$name' is required")

    private fun kotlinx.serialization.json.JsonObject.requiredString(name: String): String =
        required(name).jsonPrimitive.let { primitive ->
            if (!primitive.isString) {
                throw InvalidManifestException("Manifest field '$name' must be a string")
            }
            primitive.content
        }

    private companion object {
        val SHA_256 = Regex("^[0-9a-fA-F]{64}$")
    }
}
