package io.github.selfupdater.internal

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import io.github.selfupdater.InstallationException
import io.github.selfupdater.InstallationResult
import java.io.File

internal class ApkInstaller(private val context: Context) {
    fun permissionRequired(): InstallationResult.PermissionRequired? {
        if (!context.packageManager.canRequestPackageInstalls()) {
            return InstallationResult.PermissionRequired(permissionIntent())
        }
        return null
    }

    fun launch(apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.updater.fileprovider",
                apkFile,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                clipData = ClipData.newRawUri("APK", uri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            throw InstallationException("No Android package installer is available", error)
        } catch (error: IllegalArgumentException) {
            throw InstallationException("APK is outside the configured FileProvider cache path", error)
        } catch (error: SecurityException) {
            throw InstallationException("Android refused access to the downloaded APK", error)
        }
    }

    private fun permissionIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${context.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
