package io.github.selfupdater.internal

import io.github.selfupdater.InvalidManifestException
import io.github.selfupdater.UpdateHttpException
import java.io.ByteArrayOutputStream
import java.io.IOException
import okhttp3.HttpUrl

internal class ManifestRepository(
    private val network: NetworkExecutor,
    private val requests: RequestFactory,
    private val parser: ManifestParser,
) {
    suspend fun fetch(url: HttpUrl): UpdateManifest {
        network.execute(requests.create(url)).use { response ->
            if (!response.isSuccessful) {
                throw UpdateHttpException(response.code, response.request.url.toString())
            }
            val body = response.body ?: throw InvalidManifestException("Manifest response body is empty")
            if (body.contentLength() > MAX_MANIFEST_BYTES) {
                throw InvalidManifestException("Manifest exceeds the ${MAX_MANIFEST_BYTES}-byte limit")
            }
            return parser.parse(body.byteStream().readUtf8Limited(MAX_MANIFEST_BYTES))
        }
    }

    private fun java.io.InputStream.readUtf8Limited(limit: Long): String {
        try {
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = read(buffer)
                    if (count == -1) break
                    total += count
                    if (total > limit) {
                        throw InvalidManifestException("Manifest exceeds the $limit-byte limit")
                    }
                    output.write(buffer, 0, count)
                }
                return output.toString(Charsets.UTF_8.name())
            }
        } catch (error: InvalidManifestException) {
            throw error
        } catch (error: IOException) {
            throw InvalidManifestException("Could not read the manifest response", error)
        }
    }

    private companion object {
        const val MAX_MANIFEST_BYTES = 1_048_576L
    }
}

