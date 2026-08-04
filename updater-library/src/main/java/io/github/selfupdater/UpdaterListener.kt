package io.github.selfupdater

import java.io.File

/**
 * Optional callbacks for consumers that prefer an event API.
 *
 * Callbacks are dispatched on the main thread. Every method has a default no-op
 * implementation so consumers only override events they need.
 */
public interface UpdaterListener {
    /** Called immediately before the manifest request. */
    public fun onChecking(): Unit = Unit

    /** Called when the installed version is current. */
    public fun onNoUpdate(): Unit = Unit

    /** Called when [update] has a higher version code than the installed app. */
    public fun onUpdateAvailable(update: UpdateAvailable): Unit = Unit

    /** Called when the integer download percentage changes; `-1` means unknown. */
    public fun onDownloadProgress(percent: Int): Unit = Unit

    /** Called after checksum verification succeeds. */
    public fun onDownloadComplete(apkFile: File): Unit = Unit

    /** Called immediately before the Android package installer is launched. */
    public fun onInstalling(): Unit = Unit

    /** Called when an operation fails or install permission is required. */
    public fun onFailure(error: UpdaterException): Unit = Unit
}

