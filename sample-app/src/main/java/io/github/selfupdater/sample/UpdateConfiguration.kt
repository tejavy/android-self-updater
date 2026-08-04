package io.github.selfupdater.sample

import android.content.Context
import io.github.selfupdater.Updater

internal object UpdateConfiguration {
    const val MANIFEST_URL = "https://app-updates.tejaswi.space/latest.json"
    private const val CF_ACCESS_CLIENT_ID = "CF-Access-Client-Id"
    private const val CF_ACCESS_CLIENT_SECRET = "CF-Access-Client-Secret"

    fun updater(
        context: Context,
        credentials: CloudflareAccessCredentials,
        configure: Updater.Builder.() -> Unit = {},
    ): Updater = Updater.Builder(context)
        .manifestUrl(MANIFEST_URL)
        .header(CF_ACCESS_CLIENT_ID, credentials.clientId)
        .header(CF_ACCESS_CLIENT_SECRET, credentials.clientSecret)
        .requestTimeoutSeconds(30)
        .apply(configure)
        .build()
}
