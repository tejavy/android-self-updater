package io.github.selfupdater.sample

import android.content.Context
import androidx.core.content.edit

internal class CatalogUiStateStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun lastCheckedAt(): Long = preferences.getLong(LAST_CHECKED_AT, 0L)

    fun markChecked(now: Long = System.currentTimeMillis()) {
        preferences.edit { putLong(LAST_CHECKED_AT, now) }
    }

    private companion object {
        const val PREFERENCES_NAME = "catalog_ui_state"
        const val LAST_CHECKED_AT = "last_checked_at"
    }
}
