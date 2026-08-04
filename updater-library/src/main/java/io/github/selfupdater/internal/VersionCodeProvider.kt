package io.github.selfupdater.internal

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

internal fun interface VersionCodeProvider {
    fun currentVersionCode(): Long
}

internal class AndroidVersionCodeProvider(private val context: Context) : VersionCodeProvider {
    override fun currentVersionCode(): Long {
        val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }
}

internal object VersionComparator {
    fun isUpdateAvailable(currentVersionCode: Long, latestVersionCode: Long): Boolean =
        latestVersionCode > currentVersionCode
}

