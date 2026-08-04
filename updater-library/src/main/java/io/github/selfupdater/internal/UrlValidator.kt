package io.github.selfupdater.internal

import io.github.selfupdater.InvalidUpdateUrlException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object UrlValidator {
    fun requireHttps(url: String): HttpUrl {
        val parsed = url.toHttpUrlOrNull() ?: throw InvalidUpdateUrlException(url)
        if (!parsed.isHttps) throw InvalidUpdateUrlException(url)
        return parsed
    }

    fun requireHttps(url: HttpUrl): HttpUrl {
        if (!url.isHttps) throw InvalidUpdateUrlException(url.toString())
        return url
    }
}

