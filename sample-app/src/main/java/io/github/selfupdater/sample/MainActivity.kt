package io.github.selfupdater.sample

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import io.github.selfupdater.InstallPermissionRequiredException
import io.github.selfupdater.UpdateAvailable
import io.github.selfupdater.Updater
import io.github.selfupdater.UpdaterException
import io.github.selfupdater.UpdaterListener
import java.io.File

class MainActivity : AppCompatActivity(), UpdaterListener {
    private lateinit var manifestUrl: EditText
    private lateinit var checkButton: Button
    private lateinit var downloadButton: Button
    private lateinit var installButton: Button
    private lateinit var statusText: TextView
    private lateinit var releaseNotes: TextView
    private lateinit var progress: ProgressBar

    private var updater: Updater? = null
    private var availableUpdate: UpdateAvailable? = null
    private var downloadedApk: File? = null

    private val installPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val update = availableUpdate
        val apk = downloadedApk
        if (update != null && apk != null) updater?.installAsync(update, apk)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        manifestUrl = findViewById(R.id.manifestUrl)
        checkButton = findViewById(R.id.checkButton)
        downloadButton = findViewById(R.id.downloadButton)
        installButton = findViewById(R.id.installButton)
        statusText = findViewById(R.id.statusText)
        releaseNotes = findViewById(R.id.releaseNotes)
        progress = findViewById(R.id.downloadProgress)

        checkButton.setOnClickListener { startCheck() }
        downloadButton.setOnClickListener {
            availableUpdate?.let { update -> updater?.download(update) }
        }
        installButton.setOnClickListener {
            val update = availableUpdate
            val apk = downloadedApk
            if (update != null && apk != null) updater?.installAsync(update, apk)
        }
    }

    private fun startCheck() {
        updater?.close()
        availableUpdate = null
        downloadedApk = null
        releaseNotes.text = ""
        downloadButton.isEnabled = false
        installButton.isEnabled = false
        progress.visibility = View.GONE

        updater = runCatching {
            Updater.Builder(applicationContext)
                .manifestUrl(manifestUrl.text.toString().trim())
                .listener(this)
                .requestTimeoutSeconds(30)
                .build()
        }.getOrElse { error ->
            statusText.text = error.message ?: getString(R.string.status_invalid_configuration)
            return
        }
        updater?.check()
    }

    override fun onChecking() {
        checkButton.isEnabled = false
        statusText.setText(R.string.status_checking)
    }

    override fun onNoUpdate() {
        checkButton.isEnabled = true
        statusText.setText(R.string.status_current)
    }

    override fun onUpdateAvailable(update: UpdateAvailable) {
        availableUpdate = update
        checkButton.isEnabled = true
        downloadButton.isEnabled = true
        statusText.text = getString(
            if (update.mandatory) R.string.status_available_mandatory else R.string.status_available,
            update.version,
            update.versionCode,
        )
        releaseNotes.text = update.changelog.joinToString(separator = "\n") {
            getString(R.string.release_note_bullet, it)
        }
    }

    override fun onDownloadProgress(percent: Int) {
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = percent < 0
        if (percent >= 0) {
            progress.progress = percent
            statusText.text = getString(R.string.status_downloading_progress, percent)
        } else {
            statusText.setText(R.string.status_downloading)
        }
    }

    override fun onDownloadComplete(apkFile: File) {
        downloadedApk = apkFile
        downloadButton.isEnabled = false
        installButton.isEnabled = true
        progress.isIndeterminate = false
        progress.progress = 100
        statusText.setText(R.string.status_download_verified)
    }

    override fun onInstalling() {
        statusText.setText(R.string.status_installing)
    }

    override fun onFailure(error: UpdaterException) {
        checkButton.isEnabled = true
        if (error is InstallPermissionRequiredException) {
            statusText.setText(R.string.status_install_permission)
            installPermission.launch(error.settingsIntent)
        } else {
            statusText.text = error.message ?: getString(R.string.status_update_failed)
        }
    }

    override fun onDestroy() {
        updater?.close()
        super.onDestroy()
    }
}
