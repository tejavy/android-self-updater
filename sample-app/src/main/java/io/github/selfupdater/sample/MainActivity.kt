package io.github.selfupdater.sample

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.selfupdater.InstallPermissionRequiredException
import io.github.selfupdater.UpdateAvailable
import io.github.selfupdater.Updater
import io.github.selfupdater.UpdaterException
import io.github.selfupdater.UpdaterListener
import java.io.File
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), UpdaterListener {
    private lateinit var credentialsStore: CloudflareCredentialsStore
    private lateinit var installedAppResolver: InstalledAppResolver
    private lateinit var adapter: CatalogAdapter
    private lateinit var signedOutGroup: View
    private lateinit var contentGroup: View
    private lateinit var loading: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var retryButton: Button

    private var updater: Updater? = null
    private var activeUpdate: UpdateAvailable? = null
    private var downloadedApk: File? = null
    private var installerLaunched = false

    private val settings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { renderSession() }

    private val installPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val update = activeUpdate
        val apk = downloadedApk
        if (update != null && apk != null) updater?.installAsync(update, apk)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)

        credentialsStore = CloudflareCredentialsStore(applicationContext)
        installedAppResolver = InstalledAppResolver(applicationContext)
        signedOutGroup = findViewById(R.id.signedOutGroup)
        contentGroup = findViewById(R.id.contentGroup)
        loading = findViewById(R.id.catalogLoading)
        errorText = findViewById(R.id.catalogError)
        retryButton = findViewById(R.id.retryButton)
        adapter = CatalogAdapter(::startInstallFlow)
        findViewById<RecyclerView>(R.id.appList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        findViewById<Button>(R.id.settingsButton).setOnClickListener { openSettings() }
        findViewById<Button>(R.id.configureAccessButton).setOnClickListener { openSettings() }
        retryButton.setOnClickListener { loadCatalog() }
        renderSession()
    }

    private fun openSettings() {
        settings.launch(Intent(this, SettingsActivity::class.java))
    }

    private fun renderSession() {
        if (credentialsStore.load() == null) {
            updater?.close()
            updater = null
            adapter.submitList(emptyList())
            signedOutGroup.visibility = View.VISIBLE
            contentGroup.visibility = View.GONE
        } else {
            signedOutGroup.visibility = View.GONE
            contentGroup.visibility = View.VISIBLE
            loadCatalog()
        }
    }

    private fun loadCatalog() {
        val credentials = credentialsStore.load() ?: return renderSession()
        updater?.close()
        activeUpdate = null
        downloadedApk = null
        adapter.submitList(emptyList())
        showLoading()

        val instance = UpdateConfiguration.updater(applicationContext, credentials) {
            listener(this@MainActivity)
        }
        updater = instance
        lifecycleScope.launch {
            runCatching { instance.fetchCatalog() }
                .onSuccess { entries ->
                    if (updater !== instance) return@onSuccess
                    loading.visibility = View.GONE
                    adapter.submitList(installedAppResolver.resolve(entries))
                    findViewById<TextView>(R.id.appCount).text = resources.getQuantityString(
                        R.plurals.app_count,
                        entries.size,
                        entries.size,
                    )
                }
                .onFailure { error ->
                    if (updater !== instance) return@onFailure
                    loading.visibility = View.GONE
                    errorText.visibility = View.VISIBLE
                    errorText.text = error.message ?: getString(R.string.catalog_load_failed)
                    retryButton.visibility = View.VISIBLE
                }
        }
    }

    private fun showLoading() {
        loading.visibility = View.VISIBLE
        errorText.visibility = View.GONE
        retryButton.visibility = View.GONE
        findViewById<TextView>(R.id.appCount).setText(R.string.loading_catalog)
    }

    private fun startInstallFlow(update: UpdateAvailable) {
        if (activeUpdate != null) return
        activeUpdate = update
        downloadedApk = null
        adapter.setOperationsEnabled(false)
        adapter.markDownloading(update.applicationId, -1)
        updater?.download(update)
    }

    override fun onChecking() = Unit
    override fun onNoUpdate() = Unit
    override fun onUpdateAvailable(update: UpdateAvailable) = Unit

    override fun onDownloadProgress(percent: Int) {
        activeUpdate?.let { adapter.markDownloading(it.applicationId, percent) }
    }

    override fun onDownloadComplete(apkFile: File) {
        val update = activeUpdate ?: return
        downloadedApk = apkFile
        adapter.markInstalling(update.applicationId)
        updater?.installAsync(update, apkFile)
    }

    override fun onInstalling() {
        installerLaunched = true
        activeUpdate?.let { adapter.markInstalling(it.applicationId) }
    }

    override fun onFailure(error: UpdaterException) {
        val update = activeUpdate ?: return
        if (error is InstallPermissionRequiredException) {
            adapter.markWaitingForPermission(update.applicationId)
            installPermission.launch(error.settingsIntent)
        } else {
            adapter.markFailed(update.applicationId, error.message ?: getString(R.string.install_failed))
            activeUpdate = null
            downloadedApk = null
            adapter.setOperationsEnabled(true)
        }
    }

    override fun onResume() {
        super.onResume()
        if (installerLaunched) {
            installerLaunched = false
            renderSession()
        }
    }

    override fun onDestroy() {
        updater?.close()
        super.onDestroy()
    }
}
