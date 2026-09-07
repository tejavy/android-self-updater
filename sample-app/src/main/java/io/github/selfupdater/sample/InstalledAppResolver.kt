package io.github.selfupdater.sample

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import io.github.selfupdater.UpdateAvailable

internal val UpdateAvailable.catalogEntryId: String
    get() = "$applicationId:${channel.name}"

internal data class InstalledAppVersion(
    val displayName: String,
    val icon: Drawable,
    val versionName: String?,
    val versionCode: Long,
)

internal data class CatalogApp(
    val release: UpdateAvailable,
    val installedVersion: InstalledAppVersion?,
) {
    val id: String = release.catalogEntryId

    val displayName: String = installedVersion?.displayName
        ?: release.displayName
        ?: release.applicationId

    val action: CatalogAction = classifyCatalogAction(
        installedVersionCode = installedVersion?.versionCode,
        availableVersionCode = release.versionCode,
    )
}

internal enum class CatalogAction {
    Install,
    Reinstall,
    Update,
    NewerInstalled,
}

internal fun classifyCatalogAction(
    installedVersionCode: Long?,
    availableVersionCode: Long,
): CatalogAction = when {
    installedVersionCode == null -> CatalogAction.Install
    availableVersionCode > installedVersionCode -> CatalogAction.Update
    availableVersionCode == installedVersionCode -> CatalogAction.Reinstall
    else -> CatalogAction.NewerInstalled
}

/** Reads versions for catalog packages without starting or modifying those applications. */
internal class InstalledAppResolver(context: Context) {
    private val packageManager = context.packageManager

    fun resolve(releases: List<UpdateAvailable>): List<CatalogApp> = releases
        .map { release ->
            CatalogApp(
                release = release,
                installedVersion = packageInfo(release.applicationId)?.let { info ->
                    info.applicationInfo?.let { applicationInfo ->
                        InstalledAppVersion(
                            displayName = applicationInfo.loadLabel(packageManager)
                                .toString()
                                .takeIf(String::isNotBlank)
                                ?: release.displayName
                                ?: release.applicationId,
                            icon = applicationInfo.loadIcon(packageManager),
                            versionName = info.versionName,
                            versionCode = info.compatLongVersionCode,
                        )
                    }
                },
            )
        }
        .sortedWith(
            compareBy<CatalogApp> { app ->
                when (app.action) {
                    CatalogAction.Update -> 0
                    CatalogAction.Reinstall, CatalogAction.NewerInstalled -> 1
                    CatalogAction.Install -> 2
                }
            }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
                .thenBy { it.release.channel.ordinal },
        )

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
