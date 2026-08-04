package io.github.selfupdater.internal

import io.github.selfupdater.ChecksumMismatchException
import io.github.selfupdater.RetryPolicy
import io.github.selfupdater.UpdateAvailable
import io.github.selfupdater.UpdateDownloadException
import io.github.selfupdater.UpdateHttpException
import io.github.selfupdater.UpdateNetworkException
import io.github.selfupdater.UpdateStorageException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.delay
import okhttp3.Response

internal class ApkDownloader(
    private val cacheDirectory: File,
    private val network: NetworkExecutor,
    private val requests: RequestFactory,
    private val retryPolicy: RetryPolicy,
) {
    suspend fun download(
        update: UpdateAvailable,
        onProgress: suspend (Int) -> Unit,
    ): File {
        val directory = File(cacheDirectory, CACHE_FOLDER)
        ensureDirectory(directory)
        val cacheKey = "${update.versionCode}-${update.sha256.take(12)}"
        val finalFile = File(directory, "update-$cacheKey.apk")
        val partialFile = File(directory, "update-$cacheKey.apk.part")
        cleanOldFiles(directory, finalFile, partialFile)

        if (finalFile.exists()) {
            val verification = verifyFile(finalFile, update.sha256)
            if (verification.matches) {
                onProgress(100)
                return finalFile
            }
            finalFile.delete()
        }

        var completed = false
        for (attempt in 0 until retryPolicy.maxAttempts) {
            if (attempt > 0) delay(retryPolicy.delayBeforeAttempt(attempt))
            try {
                downloadAttempt(update, partialFile, onProgress)
                completed = true
                break
            } catch (error: UpdateNetworkException) {
                if (attempt + 1 == retryPolicy.maxAttempts) throw error
            } catch (error: RetryableHttpStatus) {
                if (attempt + 1 == retryPolicy.maxAttempts) {
                    throw UpdateHttpException(error.statusCode, error.requestUrl)
                }
            }
        }

        if (!completed || !partialFile.exists()) {
            throw UpdateDownloadException("APK download did not produce a complete file")
        }
        val verification = verifyFile(partialFile, update.sha256)
        if (!verification.matches) {
            partialFile.delete()
            throw ChecksumMismatchException(update.sha256, verification.actual)
        }
        if (finalFile.exists() && !finalFile.delete()) {
            partialFile.delete()
            throw UpdateStorageException("Could not replace cached APK", IOException(finalFile.path))
        }
        if (!partialFile.renameTo(finalFile)) {
            throw UpdateStorageException("Could not finalize cached APK", IOException(partialFile.path))
        }
        onProgress(100)
        return finalFile
    }

    private suspend fun downloadAttempt(
        update: UpdateAvailable,
        partialFile: File,
        onProgress: suspend (Int) -> Unit,
    ) {
        val existingBytes = partialFile.takeIf(File::exists)?.length() ?: 0L
        val url = UrlValidator.requireHttps(update.apkUrl)
        val response = network.executeOnce(requests.create(url, existingBytes.takeIf { it > 0 }))
        response.use {
            if (response.code.isRetryable) {
                throw RetryableHttpStatus(response.code, response.request.url.toString())
            }
            if (response.code == 416 && existingBytes > 0) {
                if (!partialFile.delete()) {
                    throw UpdateStorageException(
                        "Server rejected resume and the partial APK could not be removed",
                        IOException(partialFile.path),
                    )
                }
                return downloadAttempt(update, partialFile, onProgress)
            }
            if (response.code !in listOf(200, 206)) {
                throw UpdateHttpException(response.code, response.request.url.toString())
            }

            val append = response.code == 206 && existingBytes > 0
            if (append && response.rangeStart() != existingBytes) {
                throw UpdateDownloadException(
                    "Server resumed at ${response.rangeStart()} instead of byte $existingBytes",
                )
            }
            val baseBytes = if (append) existingBytes else 0L
            val totalBytes = response.totalLength(baseBytes)
            val body = response.body ?: throw UpdateDownloadException("APK response body is empty")

            val output = try {
                FileOutputStream(partialFile, append)
            } catch (error: IOException) {
                throw UpdateStorageException("Could not open APK cache file", error)
            }
            body.byteStream().use { input ->
                output.use { fileOutput ->
                    var downloaded = baseBytes
                    var lastPercent = if (totalBytes > 0) {
                        ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 99)
                    } else {
                        -1
                    }
                    onProgress(lastPercent)
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    while (true) {
                        val count = try {
                            input.read(buffer)
                        } catch (error: IOException) {
                            throw UpdateNetworkException("APK download was interrupted", error)
                        }
                        if (count == -1) break
                        try {
                            fileOutput.write(buffer, 0, count)
                        } catch (error: IOException) {
                            throw UpdateStorageException("Could not write APK to cache", error)
                        }
                        downloaded += count
                        val percent = if (totalBytes > 0) {
                            ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 99)
                        } else {
                            -1
                        }
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
        }
    }

    private fun verifyFile(file: File, expected: String): Sha256Verifier.Verification = try {
        Sha256Verifier.verify(file, expected)
    } catch (error: IOException) {
        throw UpdateStorageException("Could not read cached APK", error)
    }

    private fun ensureDirectory(directory: File) {
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
            throw UpdateStorageException("Could not create updater cache directory", IOException(directory.path))
        }
    }

    private fun cleanOldFiles(directory: File, vararg keep: File) {
        val keepPaths = keep.mapTo(mutableSetOf()) { it.absolutePath }
        directory.listFiles()?.forEach { file ->
            if (file.absolutePath !in keepPaths && (file.name.endsWith(".apk") || file.name.endsWith(".part"))) {
                file.delete()
            }
        }
    }

    private fun Response.rangeStart(): Long? =
        header("Content-Range")
            ?.substringBefore('-')
            ?.substringAfter("bytes ")
            ?.toLongOrNull()

    private fun Response.totalLength(baseBytes: Long): Long {
        val contentRangeTotal = header("Content-Range")
            ?.substringAfterLast('/')
            ?.takeUnless { it == "*" }
            ?.toLongOrNull()
        if (contentRangeTotal != null) return contentRangeTotal
        val responseLength = body?.contentLength() ?: -1L
        return if (responseLength >= 0) baseBytes + responseLength else -1L
    }

    private val Int.isRetryable: Boolean
        get() = this == 408 || this == 429 || this in 500..599

    private class RetryableHttpStatus(
        val statusCode: Int,
        val requestUrl: String,
    ) : Exception()

    private companion object {
        const val CACHE_FOLDER = "updater"
        const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
    }
}
