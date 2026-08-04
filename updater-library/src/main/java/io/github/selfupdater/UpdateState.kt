package io.github.selfupdater

import android.content.Intent
import java.io.File

/** Observable state of one [Updater] instance. */
public sealed interface UpdateState {
    /** No operation has started. */
    public data object Idle : UpdateState

    /** The remote manifest is being checked. */
    public data object Checking : UpdateState

    /** The installed app is already current. */
    public data object NoUpdate : UpdateState

    /** A newer version is available. */
    public data class Available(public val update: UpdateAvailable) : UpdateState

    /** The APK is downloading. A value of `-1` means its total size is unknown. */
    public data class Downloading(
        public val update: UpdateAvailable,
        public val percent: Int,
    ) : UpdateState

    /** The APK is downloaded and its SHA-256 checksum has been verified. */
    public data class Downloaded(
        public val update: UpdateAvailable,
        public val apkFile: File,
    ) : UpdateState

    /** The user must allow this app to install unknown apps before installation can continue. */
    public data class InstallPermissionRequired(
        public val update: UpdateAvailable,
        public val settingsIntent: Intent,
    ) : UpdateState

    /** The Android package installer is being launched. */
    public data class Installing(public val update: UpdateAvailable) : UpdateState

    /** An operation failed. */
    public data class Failed(public val error: UpdaterException) : UpdateState
}

/** Result returned by [Updater.checkForUpdate]. */
public sealed interface UpdateCheckResult {
    /** The installed app is current. */
    public data object NoUpdate : UpdateCheckResult

    /** A newer update was found. */
    public data class Available(public val update: UpdateAvailable) : UpdateCheckResult
}

/** Result returned when [Updater.install] is requested. */
public sealed interface InstallationResult {
    /** Android's package installer was launched. */
    public data object Launched : InstallationResult

    /** The consumer should launch [settingsIntent], then call [Updater.install] again. */
    public data class PermissionRequired(public val settingsIntent: Intent) : InstallationResult
}

