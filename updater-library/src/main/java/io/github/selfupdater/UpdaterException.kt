package io.github.selfupdater

import android.content.Intent

/** Base class for failures surfaced by [Updater]. */
public sealed class UpdaterException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** A configured or manifest-provided URL is not a valid HTTPS URL. */
public class InvalidUpdateUrlException(
    /** The rejected URL. */
    public val url: String,
) : UpdaterException("Update URLs must use HTTPS: $url")

/** The server could not be reached after retrying. */
public class UpdateNetworkException(
    message: String,
    cause: Throwable,
) : UpdaterException(message, cause)

/** The endpoint returned a non-success HTTP status. */
public class UpdateHttpException(
    /** HTTP status code. */
    public val statusCode: Int,
    /** URL whose request failed. */
    public val requestUrl: String,
) : UpdaterException("HTTP $statusCode returned by $requestUrl")

/** The manifest body is malformed or contains invalid values. */
public class InvalidManifestException(
    message: String,
    cause: Throwable? = null,
) : UpdaterException(message, cause)

/** The catalog does not contain an update manifest for the installed application ID. */
public class MissingApplicationManifestException(
    /** Package/application ID used to look up the catalog entry. */
    public val applicationId: String,
) : UpdaterException("Update catalog has no entry for application ID '$applicationId'")

/** The selected application entry does not publish the requested release channel. */
public class MissingUpdateChannelException(
    /** Package/application ID whose channel was requested. */
    public val applicationId: String,
    /** Requested release channel. */
    public val channel: UpdateChannel,
) : UpdaterException(
    "Update catalog entry '$applicationId' has no ${channel.manifestKey} channel",
)

/** The APK could not be read from or written to app cache. */
public class UpdateStorageException(
    message: String,
    cause: Throwable,
) : UpdaterException(message, cause)

/** The server response could not be used to complete the APK download. */
public class UpdateDownloadException(
    message: String,
    cause: Throwable? = null,
) : UpdaterException(message, cause)

/** The downloaded APK does not match the manifest SHA-256 checksum. */
public class ChecksumMismatchException(
    /** Expected lowercase hexadecimal SHA-256 value. */
    public val expected: String,
    /** Actual lowercase hexadecimal SHA-256 value. */
    public val actual: String,
) : UpdaterException("APK checksum mismatch. Expected $expected but found $actual")

/** The app is not currently allowed to request APK installation. */
public class InstallPermissionRequiredException(
    /** Intent that opens this app's unknown-sources settings page. */
    public val settingsIntent: Intent,
) : UpdaterException("Permission to install unknown apps is required")

/** The package installer could not be launched. */
public class InstallationException(
    message: String,
    cause: Throwable,
) : UpdaterException(message, cause)

/** An operation was requested after [Updater.close]. */
public class UpdaterClosedException : UpdaterException("Updater has been closed")

/** An unexpected failure occurred inside the updater. */
public class UnexpectedUpdaterException(
    cause: Throwable,
) : UpdaterException("Unexpected updater failure: ${cause.message ?: cause::class.java.simpleName}", cause)
