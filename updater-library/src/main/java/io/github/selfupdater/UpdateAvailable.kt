package io.github.selfupdater

/**
 * Describes an update advertised by the remote manifest.
 *
 * Instances are produced by [Updater.checkForUpdate]. Pass the same instance to
 * [Updater.download] or [Updater.downloadApk] so the library can verify the manifest checksum.
 */
public class UpdateAvailable internal constructor(
    /** Human-readable version name, for example `1.2.0`. */
    public val version: String,
    /** Monotonically increasing Android version code. */
    public val versionCode: Long,
    /** Release notes in display order. */
    public val changelog: List<String>,
    /** Whether the publisher considers this update mandatory. */
    public val mandatory: Boolean,
    /** HTTPS URL from which the APK will be downloaded. */
    public val apkUrl: String,
    internal val sha256: String,
) {
    override fun equals(other: Any?): Boolean =
        other is UpdateAvailable &&
            version == other.version &&
            versionCode == other.versionCode &&
            changelog == other.changelog &&
            mandatory == other.mandatory &&
            apkUrl == other.apkUrl &&
            sha256 == other.sha256

    override fun hashCode(): Int {
        var result = version.hashCode()
        result = 31 * result + versionCode.hashCode()
        result = 31 * result + changelog.hashCode()
        result = 31 * result + mandatory.hashCode()
        result = 31 * result + apkUrl.hashCode()
        result = 31 * result + sha256.hashCode()
        return result
    }

    override fun toString(): String =
        "UpdateAvailable(version=$version, versionCode=$versionCode, mandatory=$mandatory, apkUrl=$apkUrl)"
}

