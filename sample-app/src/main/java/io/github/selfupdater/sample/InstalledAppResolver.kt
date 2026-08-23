package io.github.selfupdater.sample

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import io.github.selfupdater.UpdateAvailable

internal data class InstalledAppVersion(
    val versionName: String?,
    val versionCode: Long,
)

internal data class CatalogApp(
    val release: UpdateAvailable,
    val installedVersion: InstalledAppVersion?,
) {
    val action: CatalogAction = classifyCatalogAction(
        installedVersionCode = installedVersion?.versionCode,
        availableVersionCode = release.versionCode,
    )
}

internal enum class CatalogAction {
    Install,
    Reinstall,
    Update,
}

internal fun classifyCatalogAction(
    installedVersionCode: Long?,
    availableVersionCode: Long,
): CatalogAction = when {
    installedVersionCode == null -> CatalogAction.Install
    availableVersionCode > installedVersionCode -> CatalogAction.Update
    else -> CatalogAction.Reinstall
}

/** Reads versions for catalog packages without starting or modifying those applications. */
internal class InstalledAppResolver(context: Context) {
    private val packageManager = context.packageManager

    fun resolve(releases: List<UpdateAvailable>): List<CatalogApp> = releases.map { release ->
        CatalogApp(
            release = release,
            installedVersion = packageInfo(release.applicationId)?.let { info ->
                InstalledAppVersion(
                    versionName = info.versionName,
                    versionCode = info.compatLongVersionCode,
                )
            },
        )
    }

    private fun packageInfo(applicationId: String): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(applicationId, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(applicationId, 0)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private val PackageInfo.compatLongVersionCode: Long
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }
}
