package io.github.selfupdater.sample

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import io.github.selfupdater.InstallPermissionRequiredException
import io.github.selfupdater.MissingApplicationManifestException
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
    private lateinit var credentialInputGroup: View
    private lateinit var credentialConnectedGroup: View
    private lateinit var clientIdInput: EditText
    private lateinit var clientSecretInput: EditText
    private lateinit var saveCredentialsButton: Button
    private lateinit var logoutButton: Button
    private lateinit var credentialStatusText: TextView
    private lateinit var credentialsStore: CloudflareCredentialsStore

    private var updater: Updater? = null
    private var availableUpdate: UpdateAvailable? = null
    private var downloadedApk: File? = null
    private var pendingCredentials: CloudflareAccessCredentials? = null

    private val installPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val update = availableUpdate
        val apk = downloadedApk
        if (update != null && apk != null) updater?.installAsync(update, apk)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)

        manifestUrl = findViewById(R.id.manifestUrl)
        checkButton = findViewById(R.id.checkButton)
        downloadButton = findViewById(R.id.downloadButton)
        installButton = findViewById(R.id.installButton)
        statusText = findViewById(R.id.statusText)
        releaseNotes = findViewById(R.id.releaseNotes)
        progress = findViewById(R.id.downloadProgress)
        credentialInputGroup = findViewById(R.id.credentialInputGroup)
        credentialConnectedGroup = findViewById(R.id.credentialConnectedGroup)
        clientIdInput = findViewById(R.id.clientIdInput)
        clientSecretInput = findViewById(R.id.clientSecretInput)
        saveCredentialsButton = findViewById(R.id.saveCredentialsButton)
        logoutButton = findViewById(R.id.logoutButton)
        credentialStatusText = findViewById(R.id.credentialStatusText)
        credentialsStore = CloudflareCredentialsStore(applicationContext)

        renderCredentialState(credentialsStore.load() != null)
        checkButton.setOnClickListener {
            pendingCredentials = null
            val credentials = credentialsStore.load()
            if (credentials == null) renderCredentialState(configured = false)
            startCheck(credentials)
        }
        saveCredentialsButton.setOnClickListener { saveAndConnect() }
        logoutButton.setOnClickListener { logout() }
        downloadButton.setOnClickListener {
            availableUpdate?.let { update -> updater?.download(update) }
        }
        installButton.setOnClickListener {
            val update = availableUpdate
            val apk = downloadedApk
            if (update != null && apk != null) updater?.installAsync(update, apk)
        }
    }

    private fun saveAndConnect() {
        val clientId = clientIdInput.text.toString().trim()
        val clientSecret = clientSecretInput.text.toString().trim()
        if (clientId.isEmpty() || clientSecret.isEmpty()) {
            credentialStatusText.setText(R.string.cloudflare_credentials_required)
            return
        }
        val credentials = CloudflareAccessCredentials(clientId, clientSecret)
        pendingCredentials = credentials
        startCheck(credentials)
    }

    private fun startCheck(credentials: CloudflareAccessCredentials?) {
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
                .apply {
                    if (credentials != null) {
                        header(CF_ACCESS_CLIENT_ID, credentials.clientId)
                        header(CF_ACCESS_CLIENT_SECRET, credentials.clientSecret)
                    }
                }
                .build()
        }.getOrElse { error ->
            pendingCredentials = null
            saveCredentialsButton.isEnabled = true
            statusText.text = error.message ?: getString(R.string.status_invalid_configuration)
            return
        }
        updater?.check()
    }

    override fun onChecking() {
        checkButton.isEnabled = false
        saveCredentialsButton.isEnabled = false
        statusText.setText(R.string.status_checking)
    }

    override fun onNoUpdate() {
        checkButton.isEnabled = true
        completePendingCredentialSave()
        statusText.setText(R.string.status_current)
    }

    override fun onUpdateAvailable(update: UpdateAvailable) {
        availableUpdate = update
        checkButton.isEnabled = true
        completePendingCredentialSave()
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
        saveCredentialsButton.isEnabled = true
        if (error is MissingApplicationManifestException && pendingCredentials != null) {
            // The authenticated catalog was downloaded and parsed; only this sample package is absent.
            completePendingCredentialSave()
        } else {
            pendingCredentials = null
        }
        if (error is InstallPermissionRequiredException) {
            statusText.setText(R.string.status_install_permission)
            installPermission.launch(error.settingsIntent)
        } else {
            statusText.text = error.message ?: getString(R.string.status_update_failed)
        }
    }

    private fun completePendingCredentialSave() {
        val credentials = pendingCredentials ?: return
        pendingCredentials = null
        runCatching { credentialsStore.save(credentials) }
            .onSuccess { renderCredentialState(configured = true) }
            .onFailure {
                saveCredentialsButton.isEnabled = true
                credentialStatusText.setText(R.string.cloudflare_credentials_save_failed)
            }
    }

    private fun renderCredentialState(configured: Boolean) {
        clientIdInput.text.clear()
        clientSecretInput.text.clear()
        credentialInputGroup.visibility = if (configured) View.GONE else View.VISIBLE
        credentialConnectedGroup.visibility = if (configured) View.VISIBLE else View.GONE
        credentialStatusText.setText(
            if (configured) R.string.cloudflare_connected else R.string.cloudflare_not_configured,
        )
        saveCredentialsButton.isEnabled = true
    }

    private fun logout() {
        updater?.close()
        updater = null
        pendingCredentials = null
        credentialsStore.clear()
        renderCredentialState(configured = false)
        credentialStatusText.setText(R.string.cloudflare_logged_out)
        availableUpdate = null
        downloadedApk = null
        releaseNotes.text = ""
        progress.visibility = View.GONE
        checkButton.isEnabled = true
        downloadButton.isEnabled = false
        installButton.isEnabled = false
    }

    override fun onDestroy() {
        updater?.close()
        super.onDestroy()
    }

    private companion object {
        const val CF_ACCESS_CLIENT_ID = "CF-Access-Client-Id"
        const val CF_ACCESS_CLIENT_SECRET = "CF-Access-Client-Secret"
    }
}
