# Android Self Updater

A small, UI-free Kotlin library for self-hosted Android APK updates. It checks a JSON manifest,
compares Android version codes, downloads with resume support, verifies SHA-256, and hands the
verified APK to Android's package installer.

- Min SDK: 26
- Compile/target SDK: 37 (Android 17)
- Kotlin, coroutines, OkHttp, and Kotlin Serialization
- No Play Store, Firebase, background service, or proprietary backend
- `updater-library/` is the publishable AAR; `sample-app/` is an XML-view demonstration

## Manifest

Both the manifest URL and every selected `apkUrl` must use HTTPS. Redirects are checked as well.
The root object is keyed by Android application ID. The library automatically selects the entry
matching `context.packageName`; a missing entry raises `MissingApplicationManifestException`.
Build variants that add an `applicationIdSuffix` need their suffixed ID represented separately.

Catalog/dashboard apps can load every entry without applying the host-app version comparison:

```kotlin
val releases: List<UpdateAvailable> = updater.fetchCatalog()
```

Each result exposes its `applicationId` and can be passed directly to `downloadApk()` and
`install()`. Normal app integrations should continue to use `check()` or `checkForUpdate()`, which
only evaluates the installed app's entry.

```json
{
  "com.example.app": {
    "version": "1.2.0",
    "versionCode": 12,
    "apkUrl": "https://repo.example.com/releases/com.example.app-1.2.0.apk",
    "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    "changelog": [
      "Added feature X",
      "Fixed crash Y"
    ],
    "mandatory": false
  }
}
```

`versionCode` is authoritative. A version is available only when the remote value is greater than
the installed app's version code. `version` is display text and is not semantically compared.

## Basic usage

The exact one-line check uses listener callbacks and returns a cancellable `Job`:

```kotlin
val updater = Updater(
    context = applicationContext,
    manifestUrl = "https://repo.example.com/latest.json",
    listener = object : UpdaterListener {
        override fun onUpdateAvailable(update: UpdateAvailable) {
            // Render your own UI, then call updater.download(update).
        }

        override fun onFailure(error: UpdaterException) {
            // Show/log the typed error in the host app.
        }
    },
)

updater.check()
```

For lifecycle-owned structured concurrency, use the suspending API:

```kotlin
lifecycleScope.launch {
    when (val result = updater.checkForUpdate()) {
        UpdateCheckResult.NoUpdate -> Unit
        is UpdateCheckResult.Available -> {
            val apk = updater.downloadApk(result.update)
            when (val install = updater.install(result.update, apk)) {
                InstallationResult.Launched -> Unit
                is InstallationResult.PermissionRequired -> {
                    unknownSourcesLauncher.launch(install.settingsIntent)
                }
            }
        }
    }
}
```

`Updater.state` is a `StateFlow<UpdateState>` for reactive consumers. Listener callbacks are sent
on the Android main thread. Suspending functions throw typed `UpdaterException` subclasses.

## Builder, authentication, and client injection

```kotlin
val updater = Updater.Builder(applicationContext)
    .manifestUrl("https://repo.example.com/latest.json")
    .header("Authorization", "Bearer $token")
    .requestTimeoutSeconds(45)
    .retryPolicy(RetryPolicy(maxAttempts = 4))
    .okHttpClient(companyOkHttpClient)
    .listener(listener)
    .build()
```

Custom headers are attached to manifest and APK requests. When injecting a client, its TLS setup,
interceptors, authenticator, cookie jar, cache, and dispatcher are retained; configured connect,
read, and write timeouts are applied to a derived client. Do not put credentials in URLs or point a protected
manifest at an untrusted APK host.

## Download and install behavior

- Files live under `cacheDir/updater/`; old APK and partial files are removed automatically.
- A `.part` download is resumed with `Range` when the server supports it. A server that ignores
  `Range` causes a safe restart from byte zero.
- Retryable failures use exponential backoff. Cancellation leaves the partial file for the next run.
- A checksum mismatch deletes the corrupt file and raises `ChecksumMismatchException`.
- SHA-256 is checked after download and again immediately before installation.
- The AAR contributes `INTERNET`, `REQUEST_INSTALL_PACKAGES`, and a non-exported `FileProvider`.
- If unknown-source permission is absent, installation returns `PermissionRequired` and the
  listener receives `InstallPermissionRequiredException` containing the same settings intent.
- The library never presents UI. Android's system package installer is the only UI it launches.

Your distribution model and use of `REQUEST_INSTALL_PACKAGES` must comply with the policies of any
store through which the host app is distributed.

## Build and publish locally

Install Android SDK Platform 37, use JDK 17, then run:

```shell
./gradlew test lint assemble
./gradlew :updater-library:publishToMavenLocal
```

The default Maven coordinates are `io.github.selfupdater:android-self-updater:1.2.0`. Adjust the
root `group` and `version` before publishing to your own Maven repository.

### Release signing

The sample app signs release builds when an ignored `keystore.properties` file is present at the
repository root. Start from `keystore.properties.example`; keep the real keystore and passwords
outside Git and back them up securely. Every future update for the same application ID must be
signed by the same key.

The sample is a small multi-app update dashboard. Its home screen lists every catalog entry with
release notes and an install action. When signed out it links to a separate settings screen for
Cloudflare Access service-token headers. Credentials are saved only after the complete catalog
responds successfully, encrypted with an Android Keystore AES-GCM key, and removed from both
storage and memory on logout. Saved token values are never rendered again; the sample disables
Android backup and screen capture to reduce accidental credential exposure.
