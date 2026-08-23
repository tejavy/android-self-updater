package io.github.selfupdater.sample

import android.os.SystemClock
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal enum class CatalogSyncStage {
    Triggering,
    Running,
}

/** Triggers the server-side release sync and waits for its rate-limited status endpoint. */
internal class CatalogSyncClient(
    private val endpoint: HttpUrl,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
) {
    init {
        require(endpoint.isHttps) { "Sync endpoint must use HTTPS" }
    }

    suspend fun synchronize(
        credentials: CloudflareAccessCredentials,
        onStage: (CatalogSyncStage) -> Unit,
    ) {
        onStage(CatalogSyncStage.Triggering)
        trigger(credentials)
        onStage(CatalogSyncStage.Running)

        val deadline = SystemClock.elapsedRealtime() + SYNC_TIMEOUT_MILLIS
        delay(POLL_INTERVAL_MILLIS)
        while (SystemClock.elapsedRealtime() < deadline) {
            when (poll(credentials)) {
                PollResult.Finished -> return
                PollResult.Running -> delay(POLL_INTERVAL_MILLIS)
                PollResult.RateLimited -> delay(RATE_LIMIT_DELAY_MILLIS)
            }
        }
        throw IOException("Release sync did not finish within ${SYNC_TIMEOUT_MILLIS / 60_000} minutes")
    }

    private suspend fun trigger(credentials: CloudflareAccessCredentials) {
        val request = requestBuilder(credentials)
            .post(ByteArray(0).toRequestBody())
            .build()
        executeWithRetry(request).use { response ->
            if (response.isSuccessful || response.code == HTTP_TOO_MANY_REQUESTS) return
            throw IOException("Sync trigger returned HTTP ${response.code}")
        }
    }

    private suspend fun poll(credentials: CloudflareAccessCredentials): PollResult {
        val request = requestBuilder(credentials).get().build()
        executeWithRetry(request).use { response ->
            if (response.code == HTTP_TOO_MANY_REQUESTS) return PollResult.RateLimited
            if (!response.isSuccessful) {
                throw IOException("Sync status returned HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("Sync status response was empty")
            return if (parseSyncRunning(body)) PollResult.Running else PollResult.Finished
        }
    }

    private fun requestBuilder(credentials: CloudflareAccessCredentials): Request.Builder =
        Request.Builder()
            .url(endpoint)
            .header(CF_ACCESS_CLIENT_ID, credentials.clientId)
            .header(CF_ACCESS_CLIENT_SECRET, credentials.clientSecret)
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")

    private suspend fun executeWithRetry(request: Request): Response {
        var lastFailure: IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(RETRY_BASE_DELAY_MILLIS shl (attempt - 1))
            try {
                val response = client.newCall(request).awaitResponse()
                if (response.code in 500..599 && attempt + 1 < MAX_ATTEMPTS) {
                    response.close()
                } else {
                    return response
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                lastFailure = error
            }
        }
        throw IOException("Could not reach the sync endpoint", lastFailure)
    }

    private enum class PollResult {
        Running,
        Finished,
        RateLimited,
    }

    private companion object {
        const val CF_ACCESS_CLIENT_ID = "CF-Access-Client-Id"
        const val CF_ACCESS_CLIENT_SECRET = "CF-Access-Client-Secret"
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val MAX_ATTEMPTS = 3
        const val RETRY_BASE_DELAY_MILLIS = 1_000L
        const val POLL_INTERVAL_MILLIS = 7_000L
        const val RATE_LIMIT_DELAY_MILLIS = 12_000L
        const val SYNC_TIMEOUT_MILLIS = 10 * 60_000L
    }
}

internal fun parseSyncRunning(body: String): Boolean = try {
    val primitive = Json.parseToJsonElement(body)
        .jsonObject["syncRunning"]
        ?.jsonPrimitive
        ?: throw IOException("Sync status is missing 'syncRunning'")
    if (primitive.isString) throw IOException("Sync status field 'syncRunning' must be a boolean")
    primitive.boolean
} catch (error: IOException) {
    throw error
} catch (error: SerializationException) {
    throw IOException("Sync status JSON is malformed", error)
} catch (error: IllegalArgumentException) {
    throw IOException("Sync status has an invalid 'syncRunning' value", error)
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}
