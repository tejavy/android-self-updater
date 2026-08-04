package io.github.selfupdater.internal

import io.github.selfupdater.RetryPolicy
import io.github.selfupdater.UpdateNetworkException
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal class NetworkExecutor(
    private val client: OkHttpClient,
    private val retryPolicy: RetryPolicy,
) {
    suspend fun execute(request: Request): Response {
        var lastFailure: IOException? = null
        for (attempt in 0 until retryPolicy.maxAttempts) {
            if (attempt > 0) delay(retryPolicy.delayBeforeAttempt(attempt))
            try {
                val response = client.newCall(request).awaitResponse()
                UrlValidator.requireHttps(response.request.url)
                if (response.code.isRetryable && attempt + 1 < retryPolicy.maxAttempts) {
                    response.close()
                    continue
                }
                return response
            } catch (error: IOException) {
                lastFailure = error
            }
        }
        throw UpdateNetworkException(
            "Network request failed after ${retryPolicy.maxAttempts} attempt(s): ${request.url}",
            checkNotNull(lastFailure),
        )
    }

    suspend fun executeOnce(request: Request): Response {
        try {
            return client.newCall(request).awaitResponse().also { response ->
                try {
                    UrlValidator.requireHttps(response.request.url)
                } catch (error: Throwable) {
                    response.close()
                    throw error
                }
            }
        } catch (error: IOException) {
            throw UpdateNetworkException("Network request failed: ${request.url}", error)
        }
    }

    private val Int.isRetryable: Boolean
        get() = this == 408 || this == 429 || this in 500..599
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) {
                continuation.resume(response)
            } else {
                response.close()
            }
        }
    })
}
