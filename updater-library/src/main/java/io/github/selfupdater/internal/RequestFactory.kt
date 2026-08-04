package io.github.selfupdater.internal

import okhttp3.HttpUrl
import okhttp3.Request

internal class RequestFactory(private val headers: Map<String, String>) {
    init {
        // Let OkHttp perform strict validation once during Updater construction.
        Request.Builder().url("https://localhost/").also { builder ->
            headers.forEach(builder::addHeader)
        }.build()
    }

    fun create(url: HttpUrl, rangeStart: Long? = null): Request =
        Request.Builder()
            .url(url)
            .apply {
                headers.forEach(::addHeader)
                header("Accept-Encoding", "identity")
                header(
                    "Accept",
                    if (rangeStart == null) {
                        "application/json"
                    } else {
                        "application/vnd.android.package-archive"
                    },
                )
                if (rangeStart != null) header("Range", "bytes=$rangeStart-")
            }
            .build()
}
