package io.github.selfupdater

import android.content.Context
import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import io.github.selfupdater.internal.AndroidVersionCodeProvider
import io.github.selfupdater.internal.ApkDownloader
import io.github.selfupdater.internal.ApkInstaller
import io.github.selfupdater.internal.ErrorMapper
import io.github.selfupdater.internal.ManifestParser
import io.github.selfupdater.internal.ManifestRepository
import io.github.selfupdater.internal.NetworkExecutor
import io.github.selfupdater.internal.RequestFactory
import io.github.selfupdater.internal.Sha256Verifier
import io.github.selfupdater.internal.UrlValidator
import io.github.selfupdater.internal.VersionComparator

/**
 * Checks a self-hosted JSON manifest, downloads its APK, verifies SHA-256, and opens the
 * Android package installer. This class has no UI and never displays dialogs.
 *
 * Create one instance per update flow. Call [close] when a long-lived instance is no longer
 * needed; closing cancels callback-style work started by [check], [download], or [installAsync].
 *
 * @param context any context; only its application context is retained.
 * @param manifestUrl HTTPS URL of the update manifest.
 * @param listener optional callback receiver. Callbacks run on the main thread.
 * @param requestHeaders headers attached to both manifest and APK requests.
 * @param requestTimeoutSeconds connect, read, and write timeout applied to HTTP requests.
 * @param okHttpClient optional client whose interceptors, authenticator, TLS, and cache are reused.
 * @param retryPolicy exponential-backoff policy for retryable network failures.
 */
public class Updater @JvmOverloads constructor(
    context: Context,
    manifestUrl: String,
    private val listener: UpdaterListener? = null,
    requestHeaders: Map<String, String> = emptyMap(),
    requestTimeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    okHttpClient: OkHttpClient? = null,
    retryPolicy: RetryPolicy = RetryPolicy(),
) : Closeable {
    private val applicationContext = context.applicationContext
    private val manifestHttpUrl = UrlValidator.requireHttps(manifestUrl)
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val callbackDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val operationMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)

    /** Current state of this updater instance. */
    public val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    private val requests = RequestFactory(requestHeaders.toMap())
    private val network: NetworkExecutor
    private val manifestRepository: ManifestRepository
    private val downloader: ApkDownloader
    private val installer = ApkInstaller(applicationContext)
    private val versionCodeProvider = AndroidVersionCodeProvider(applicationContext)

    init {
        require(requestTimeoutSeconds > 0) { "requestTimeoutSeconds must be positive" }
        val client = (okHttpClient ?: OkHttpClient()).newBuilder()
            .connectTimeout(requestTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(requestTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(requestTimeoutSeconds, TimeUnit.SECONDS)
            .build()
        network = NetworkExecutor(client, retryPolicy)
        manifestRepository = ManifestRepository(network, requests, ManifestParser())
        downloader = ApkDownloader(applicationContext.cacheDir, network, requests, retryPolicy)
    }

    /**
     * Starts an update check and returns its cancellable [Job]. Failures are delivered to
     * [UpdaterListener.onFailure] and [state]. For structured concurrency, prefer
     * [checkForUpdate].
     */
    public fun check(): Job = launchReporting { checkForUpdate() }

    /**
     * Checks the manifest and compares its version code with the installed app.
     *
     * @return [UpdateCheckResult.Available] only when the manifest version code is higher.
     * @throws UpdaterException when validation, parsing, or networking fails.
     */
    public suspend fun checkForUpdate(): UpdateCheckResult = operationMutex.withLock {
        ensureOpen()
        try {
            publish(UpdateState.Checking) { it.onChecking() }
            val manifest = withContext(ioDispatcher) {
                manifestRepository.fetch(manifestHttpUrl, applicationContext.packageName)
            }
            UrlValidator.requireHttps(manifest.apkUrl)
            val currentVersionCode = withContext(ioDispatcher) {
                versionCodeProvider.currentVersionCode()
            }
            if (!VersionComparator.isUpdateAvailable(currentVersionCode, manifest.versionCode)) {
                publish(UpdateState.NoUpdate) { it.onNoUpdate() }
                UpdateCheckResult.NoUpdate
            } else {
                val update = UpdateAvailable(
                    applicationId = manifest.applicationId,
                    version = manifest.version,
                    versionCode = manifest.versionCode,
                    changelog = manifest.changelog.toList(),
                    mandatory = manifest.mandatory,
                    apkUrl = manifest.apkUrl,
                    sha256 = manifest.sha256,
                )
                publish(UpdateState.Available(update)) { it.onUpdateAvailable(update) }
                UpdateCheckResult.Available(update)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw reportFailure(ErrorMapper.map(error, "checking for updates"))
        }
    }

    /**
     * Downloads and validates the complete manifest catalog without comparing entries to the
     * host app. This is intended for update dashboards that manage multiple application IDs.
     * Entries retain the manifest order and may be passed directly to [downloadApk] and [install].
     *
     * Unlike [checkForUpdate], this method does not publish listener callbacks or update [state].
     *
     * @throws UpdaterException when validation, parsing, or networking fails.
     */
    public suspend fun fetchCatalog(): List<UpdateAvailable> = operationMutex.withLock {
        ensureOpen()
        try {
            withContext(ioDispatcher) {
                manifestRepository.fetchCatalog(manifestHttpUrl).map { manifest ->
                    UrlValidator.requireHttps(manifest.apkUrl)
                    manifest.toUpdateAvailable()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw ErrorMapper.map(error, "loading the update catalog")
        }
    }

    /**
     * Starts a resumable APK download and returns its cancellable [Job]. Progress and completion
     * are delivered through [state] and [UpdaterListener].
     */
    public fun download(update: UpdateAvailable): Job = launchReporting { downloadApk(update) }

    /**
     * Downloads [update] into app cache, resuming a partial file when the server supports byte
     * ranges. The returned file has already passed SHA-256 verification.
     *
     * @throws ChecksumMismatchException after deleting a corrupt APK.
     * @throws UpdaterException for network, HTTP, protocol, or storage failures.
     */
    public suspend fun downloadApk(update: UpdateAvailable): File = operationMutex.withLock {
        ensureOpen()
        try {
            val file = withContext(ioDispatcher) {
                downloader.download(update) { percent ->
                    publish(UpdateState.Downloading(update, percent)) {
                        it.onDownloadProgress(percent)
                    }
                }
            }
            publish(UpdateState.Downloaded(update, file)) { it.onDownloadComplete(file) }
            file
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw reportFailure(ErrorMapper.map(error, "downloading the APK"))
        }
    }

    /** Starts [install] in this updater's cancellable callback scope. */
    public fun installAsync(update: UpdateAvailable, apkFile: File): Job =
        launchReporting { install(update, apkFile) }

    /**
     * Verifies [apkFile] again and launches Android's package installer.
     *
     * If unknown-app installation is disabled, this returns
     * [InstallationResult.PermissionRequired], publishes
     * [UpdateState.InstallPermissionRequired], and reports an
     * [InstallPermissionRequiredException] to the listener. Launch the returned settings intent
     * and call this method again after the user returns.
     *
     * @throws ChecksumMismatchException after deleting a corrupt APK.
     * @throws UpdaterException if the APK cannot be read or the installer cannot be launched.
     */
    public suspend fun install(
        update: UpdateAvailable,
        apkFile: File,
    ): InstallationResult = operationMutex.withLock {
        ensureOpen()
        try {
            val verification = withContext(ioDispatcher) {
                if (!apkFile.isFile) {
                    throw UpdateStorageException(
                        "Downloaded APK no longer exists",
                        java.io.FileNotFoundException(apkFile.path),
                    )
                }
                try {
                    Sha256Verifier.verify(apkFile, update.sha256)
                } catch (error: java.io.IOException) {
                    throw UpdateStorageException("Could not read downloaded APK", error)
                }
            }
            if (!verification.matches) {
                apkFile.delete()
                throw ChecksumMismatchException(update.sha256, verification.actual)
            }

            val permission = withContext(callbackDispatcher) { installer.permissionRequired() }
            if (permission != null) {
                val error = InstallPermissionRequiredException(permission.settingsIntent)
                mutableState.value = UpdateState.InstallPermissionRequired(update, permission.settingsIntent)
                notifyListener { it.onFailure(error) }
                return@withLock permission
            }
            publish(UpdateState.Installing(update)) { it.onInstalling() }
            withContext(callbackDispatcher) { installer.launch(apkFile) }
            InstallationResult.Launched
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw reportFailure(ErrorMapper.map(error, "installing the APK"))
        }
    }

    /** Cancels callback-style operations. A closed instance cannot be reused. */
    override fun close() {
        if (closed.compareAndSet(false, true)) scope.cancel()
    }

    private fun launchReporting(block: suspend () -> Unit): Job {
        if (closed.get()) {
            return scope.launch { reportFailure(UpdaterClosedException()) }
        }
        return scope.launch {
            try {
                block()
            } catch (_: UpdaterException) {
                // Suspending APIs throw; callback APIs report through state/listener only.
            }
        }
    }

    private suspend fun publish(state: UpdateState, callback: (UpdaterListener) -> Unit) {
        mutableState.value = state
        notifyListener(callback)
    }

    private suspend fun notifyListener(callback: (UpdaterListener) -> Unit) {
        val target = listener ?: return
        withContext(callbackDispatcher) { runCatching { callback(target) } }
    }

    private suspend fun reportFailure(error: UpdaterException): UpdaterException {
        mutableState.value = UpdateState.Failed(error)
        notifyListener { it.onFailure(error) }
        return error
    }

    private fun ensureOpen() {
        if (closed.get()) throw UpdaterClosedException()
    }

    private fun io.github.selfupdater.internal.UpdateManifest.toUpdateAvailable(): UpdateAvailable =
        UpdateAvailable(
            applicationId = applicationId,
            version = version,
            versionCode = versionCode,
            changelog = changelog.toList(),
            mandatory = mandatory,
            apkUrl = apkUrl,
            sha256 = sha256,
        )

    /** Fluent alternative to the primary constructor. */
    public class Builder(context: Context) {
        private val context = context.applicationContext
        private var manifestUrl: String? = null
        private var listener: UpdaterListener? = null
        private val headers = linkedMapOf<String, String>()
        private var requestTimeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
        private var client: OkHttpClient? = null
        private var retryPolicy: RetryPolicy = RetryPolicy()

        /** Sets the required HTTPS manifest URL. */
        public fun manifestUrl(url: String): Builder = apply { manifestUrl = url }

        /** Sets the optional callback receiver. */
        public fun listener(listener: UpdaterListener?): Builder = apply { this.listener = listener }

        /** Adds or replaces one header for both manifest and APK requests. */
        public fun header(name: String, value: String): Builder = apply { headers[name] = value }

        /** Replaces all custom request headers. */
        public fun headers(headers: Map<String, String>): Builder = apply {
            this.headers.clear()
            this.headers.putAll(headers)
        }

        /** Sets the connect, read, and write timeout applied to HTTP requests. */
        public fun requestTimeoutSeconds(seconds: Long): Builder = apply {
            require(seconds > 0) { "seconds must be positive" }
            requestTimeoutSeconds = seconds
        }

        /** Injects an OkHttp client; its interceptors, TLS, authenticator, and cache are retained. */
        public fun okHttpClient(client: OkHttpClient): Builder = apply { this.client = client }

        /** Sets exponential-backoff behavior. */
        public fun retryPolicy(policy: RetryPolicy): Builder = apply { retryPolicy = policy }

        /** Builds a new, independent updater instance. */
        public fun build(): Updater = Updater(
            context = context,
            manifestUrl = requireNotNull(manifestUrl) { "manifestUrl is required" },
            listener = listener,
            requestHeaders = headers.toMap(),
            requestTimeoutSeconds = requestTimeoutSeconds,
            okHttpClient = client,
            retryPolicy = retryPolicy,
        )
    }

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 30L
    }
}
