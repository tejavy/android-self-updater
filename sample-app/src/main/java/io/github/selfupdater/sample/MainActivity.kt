package io.github.selfupdater.sample

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import io.github.selfupdater.InstallPermissionRequiredException
import io.github.selfupdater.UpdateAvailable
import io.github.selfupdater.Updater
import io.github.selfupdater.UpdaterException
import io.github.selfupdater.UpdaterListener
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), UpdaterListener {
    private lateinit var credentialsStore: CloudflareCredentialsStore
    private lateinit var installedAppResolver: InstalledAppResolver
    private lateinit var syncClient: CatalogSyncClient
    private lateinit var uiStateStore: CatalogUiStateStore
    private lateinit var adapter: CatalogAdapter
    private lateinit var signedOutGroup: View
    private lateinit var contentGroup: View
    private lateinit var loading: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var retryButton: Button
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var mainRoot: View
    private lateinit var lastCheckedText: TextView

    private var updater: Updater? = null
    private var catalogJob: Job? = null
    private var activeUpdate: UpdateAvailable? = null
    private var downloadedApk: File? = null
    private var installerLaunched = false
    private var retryWithSync = false

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
        mainRoot = findViewById(R.id.mainRoot)
        applyEdgeToEdgeInsets(mainRoot)

        credentialsStore = CloudflareCredentialsStore(applicationContext)
        installedAppResolver = InstalledAppResolver(applicationContext)
        syncClient = CatalogSyncClient(UpdateConfiguration.SYNC_URL)
        uiStateStore = CatalogUiStateStore(applicationContext)
        signedOutGroup = findViewById(R.id.signedOutGroup)
        contentGroup = findViewById(R.id.contentGroup)
        loading = findViewById(R.id.catalogLoading)
        errorText = findViewById(R.id.catalogError)
        retryButton = findViewById(R.id.retryButton)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        toolbar = findViewById(R.id.mainToolbar)
        lastCheckedText = findViewById(R.id.lastCheckedText)
        adapter = CatalogAdapter(::startInstallFlow)
        val appList = findViewById<RecyclerView>(R.id.appList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        swipeRefresh.setColorSchemeResources(R.color.update_green)
        swipeRefresh.setOnRefreshListener { syncAndRefresh() }
        ViewCompat.addAccessibilityAction(
            appList,
            getString(R.string.refresh_catalog),
        ) { _, _ ->
            syncAndRefresh()
            true
        }

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> {
                    syncAndRefresh()
                    true
                }
                R.id.action_settings -> {
                    openSettings()
                    true
                }
                else -> false
            }
        }
        findViewById<Button>(R.id.configureAccessButton).setOnClickListener { openSettings() }
        retryButton.setOnClickListener {
            if (retryWithSync) syncAndRefresh() else loadCatalog(forceRefresh = true)
        }
        renderLastChecked()
        renderSession()
    }

    private fun openSettings() {
        settings.launch(Intent(this, SettingsActivity::class.java))
    }

    private fun renderSession() {
        if (credentialsStore.load() == null) {
            catalogJob?.cancel()
            updater?.close()
            updater = null
            swipeRefresh.isRefreshing = false
            swipeRefresh.isEnabled = false
            toolbar.menu.findItem(R.id.action_refresh).isVisible = false
            adapter.submitList(emptyList())
            signedOutGroup.visibility = View.VISIBLE
            contentGroup.visibility = View.GONE
        } else {
            signedOutGroup.visibility = View.GONE
            contentGroup.visibility = View.VISIBLE
            swipeRefresh.isEnabled = true
            toolbar.menu.findItem(R.id.action_refresh).isVisible = true
            loadCatalog()
        }
    }

    private fun syncAndRefresh() {
        loadCatalog(forceRefresh = true, synchronize = true)
    }

    private fun loadCatalog(
        forceRefresh: Boolean = false,
        synchronize: Boolean = false,
    ) {
        if (activeUpdate != null) {
            swipeRefresh.isRefreshing = false
            return
        }
        val credentials = credentialsStore.load() ?: return renderSession()
        catalogJob?.cancel()
        updater?.close()
        updater = null
        activeUpdate = null
        downloadedApk = null
        if (!forceRefresh) adapter.submitList(emptyList())
        showLoading(forceRefresh, synchronize)

        catalogJob = lifecycleScope.launch {
            var syncCompleted = !synchronize
            try {
                if (synchronize) {
                    syncClient.synchronize(credentials) { stage ->
                        findViewById<TextView>(R.id.appCount).setText(
                            when (stage) {
                                CatalogSyncStage.Triggering -> R.string.triggering_sync
                                CatalogSyncStage.Running -> R.string.sync_in_progress
                            },
                        )
                    }
                    syncCompleted = true
                }
                if (synchronize) {
                    findViewById<TextView>(R.id.appCount).setText(R.string.refreshing_catalog)
                }
                val instance = UpdateConfiguration.updater(
                    applicationContext,
                    credentials,
                    forceRefresh,
                ) {
                    listener(this@MainActivity)
                }
                updater = instance
                val entries = instance.fetchCatalog()
                if (updater !== instance) return@launch
                loading.visibility = View.GONE
                swipeRefresh.isRefreshing = false
                swipeRefresh.isEnabled = true
                toolbar.menu.findItem(R.id.action_refresh).isEnabled = true
                val catalogApps = installedAppResolver.resolve(entries)
                adapter.submitList(catalogApps)
                retryWithSync = false
                renderCatalogSummary(catalogApps)
                uiStateStore.markChecked()
                renderLastChecked()
                if (synchronize) showRefreshSuccess(catalogApps.count { it.action == CatalogAction.Update })
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                retryWithSync = synchronize && !syncCompleted
                loading.visibility = View.GONE
                swipeRefresh.isRefreshing = false
                swipeRefresh.isEnabled = true
                toolbar.menu.findItem(R.id.action_refresh).isEnabled = true
                if (forceRefresh && adapter.hasItems) {
                    renderCatalogSummaryFromAdapter()
                    Snackbar.make(mainRoot, R.string.refresh_failed_message, Snackbar.LENGTH_LONG)
                        .setAction(R.string.retry) {
                            if (retryWithSync) syncAndRefresh() else loadCatalog(forceRefresh = true)
                        }
                        .show()
                } else {
                    errorText.visibility = View.VISIBLE
                    errorText.text = error.message ?: getString(R.string.catalog_load_failed)
                    findViewById<TextView>(R.id.appCount).setText(R.string.catalog_unavailable)
                    retryButton.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showLoading(forceRefresh: Boolean, synchronize: Boolean) {
        loading.visibility = if (forceRefresh) View.GONE else View.VISIBLE
        toolbar.menu.findItem(R.id.action_refresh).isEnabled = false
        swipeRefresh.isRefreshing = forceRefresh
        errorText.visibility = View.GONE
        retryButton.visibility = View.GONE
        when {
            synchronize -> findViewById<TextView>(R.id.appCount).setText(R.string.triggering_sync)
            !forceRefresh -> findViewById<TextView>(R.id.appCount).setText(R.string.loading_catalog)
        }
    }

    private fun startInstallFlow(update: UpdateAvailable) {
        if (activeUpdate != null) return
        activeUpdate = update
        downloadedApk = null
        adapter.setOperationsEnabled(false)
        swipeRefresh.isEnabled = false
        toolbar.menu.findItem(R.id.action_refresh).isEnabled = false
        adapter.markDownloading(update, -1)
        updater?.download(update)
    }

    override fun onChecking() = Unit
    override fun onNoUpdate() = Unit
    override fun onUpdateAvailable(update: UpdateAvailable) = Unit

    override fun onDownloadProgress(percent: Int) {
        activeUpdate?.let { adapter.markDownloading(it, percent) }
    }

    override fun onDownloadComplete(apkFile: File) {
        val update = activeUpdate ?: return
        downloadedApk = apkFile
        adapter.markInstalling(update)
        updater?.installAsync(update, apkFile)
    }

    override fun onInstalling() {
        installerLaunched = true
        activeUpdate?.let { adapter.markInstalling(it) }
    }

    override fun onFailure(error: UpdaterException) {
        val update = activeUpdate ?: return
        if (error is InstallPermissionRequiredException) {
            adapter.markWaitingForPermission(update)
            installPermission.launch(error.settingsIntent)
        } else {
            adapter.markFailed(update, error.message ?: getString(R.string.install_failed))
            activeUpdate = null
            downloadedApk = null
            adapter.setOperationsEnabled(true)
            swipeRefresh.isEnabled = true
            toolbar.menu.findItem(R.id.action_refresh).isEnabled = true
        }
    }

    override fun onResume() {
        super.onResume()
        renderLastChecked()
        if (installerLaunched) {
            installerLaunched = false
            activeUpdate = null
            downloadedApk = null
            adapter.setOperationsEnabled(true)
            swipeRefresh.isEnabled = true
            toolbar.menu.findItem(R.id.action_refresh).isEnabled = true
            renderSession()
        }
    }

    override fun onDestroy() {
        catalogJob?.cancel()
        updater?.close()
        super.onDestroy()
    }

    private fun renderCatalogSummary(catalogApps: List<CatalogApp>) {
        val updates = catalogApps.count { it.action == CatalogAction.Update }
        val updateText = resources.getQuantityString(R.plurals.update_count, updates, updates)
        val applicationCount = catalogApps.asSequence().map { it.release.applicationId }.distinct().count()
        val appText = resources.getQuantityString(R.plurals.app_count, applicationCount, applicationCount)
        findViewById<TextView>(R.id.appCount).text = getString(R.string.catalog_summary, updateText, appText)
    }

    private fun renderCatalogSummaryFromAdapter() {
        val updateText = resources.getQuantityString(R.plurals.update_count, adapter.updateCount, adapter.updateCount)
        val appText = resources.getQuantityString(
            R.plurals.app_count,
            adapter.applicationCount,
            adapter.applicationCount,
        )
        findViewById<TextView>(R.id.appCount).text = getString(R.string.catalog_summary, updateText, appText)
    }

    private fun renderLastChecked() {
        val lastCheckedAt = uiStateStore.lastCheckedAt()
        lastCheckedText.text = if (lastCheckedAt <= 0L) {
            getString(R.string.never_checked)
        } else if (System.currentTimeMillis() - lastCheckedAt < DateUtils.MINUTE_IN_MILLIS) {
            getString(R.string.last_checked_just_now)
        } else {
            val relativeTime = DateUtils.getRelativeTimeSpanString(
                lastCheckedAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE,
            )
            getString(R.string.last_checked, relativeTime)
        }
    }

    private fun showRefreshSuccess(updateCount: Int) {
        val message = if (updateCount == 0) {
            getString(R.string.no_updates_found)
        } else {
            resources.getQuantityString(R.plurals.updates_found, updateCount, updateCount)
        }
        Snackbar.make(mainRoot, message, Snackbar.LENGTH_SHORT).show()
    }
}
