package com.termix.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit

private const val PACKAGE_NAME = "com.termix.app"
internal const val UPDATE_METADATA = "com.termix.GITHUB_APP_UPDATES"
private const val LEGACY_CERT = "a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc"
private const val LEGACY_PUBLIC_KEY = "ef57b690165cb561b5026922c00d2d6574e8b184fa7d161e076f06e06e6d35db"
private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
private const val APK_CACHE_TTL_MS = 24 * 60 * 60 * 1000L
private const val MAX_RELEASE_JSON = 1024 * 1024L
private val DOWNLOAD_HOSTS = setOf(
    "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com",
    "github-releases.githubusercontent.com",
)

internal class GitHubAppUpdater private constructor(
    private val context: Context,
    val installedVersion: String,
    private val installedVersionCode: Long,
    private val installedCertificate: ByteArray,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences = context.getSharedPreferences("github-app-updates", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state = mutableState.asStateFlow()
    private var downloadCall: Call? = null
    private val staging = File(context.cacheDir, "github-update-staging")
    private val verified = File(context.cacheDir, "github-updates")
    private val installerApks = mutableMapOf<File, Long>()
    private val releaseClient = OkHttpClient.Builder()
        .followRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val downloadClient = releaseClient.newBuilder()
        .followRedirects(true)
        .followSslRedirects(false)
        .callTimeout(20, TimeUnit.MINUTES)
        .addNetworkInterceptor { chain ->
            val url = chain.request().url
            if (!url.isHttps || url.host !in DOWNLOAD_HOSTS || url.port != 443 ||
                url.username.isNotEmpty() || url.password.isNotEmpty()
            ) throw UpdateFailure(UpdateError.VERIFICATION)
            chain.proceed(chain.request())
        }
        .build()

    fun check(automatic: Boolean = false) {
        val current = state.value
        if (current is AppUpdateState.Checking || current is AppUpdateState.Downloading) return
        val ready = current as? AppUpdateState.Ready
        val now = System.currentTimeMillis()
        val lastCheck = preferences.getLong("last_check", 0)
        if (automatic && now - lastCheck in 0 until CHECK_INTERVAL_MS) return
        if (rateLimited(now)) {
            if (!automatic) mutableState.value = failedUpdateCheck(UpdateError.RATE_LIMIT, ready)
            return
        }
        val installerFiles = activeInstallerApks(installerApks, SystemClock.elapsedRealtime())
        preferences.edit().putLong("last_check", now).apply()
        mutableState.value = AppUpdateState.Checking
        scope.launch {
            mutableState.value = try {
                withContext(Dispatchers.IO) {
                    val release = fetchRelease(installerFiles + listOfNotNull(ready?.apk))
                    val next = checkedUpdate(release, ready, automatic)
                    if (next !is AppUpdateState.Ready && ready != null && ready.apk !in installerFiles) {
                        ready.apk.delete()
                    }
                    next
                }
            } catch (failure: UpdateFailure) {
                failedUpdateCheck(failure.error, ready)
            } catch (_: IOException) {
                failedUpdateCheck(UpdateError.NETWORK, ready)
            }
        }
    }

    fun shouldAnnounce(update: AppUpdateState.Available): Boolean {
        if (!update.automatic || preferences.getString("announced_version", null) == update.release.version) {
            return false
        }
        preferences.edit().putString("announced_version", update.release.version).apply()
        return true
    }

    fun download() {
        val release = when (val current = state.value) {
            is AppUpdateState.Available -> current.release
            is AppUpdateState.Failed -> current.release
            else -> null
        } ?: return
        if (rateLimited(System.currentTimeMillis())) {
            mutableState.value = AppUpdateState.Failed(UpdateError.RATE_LIMIT, release)
            return
        }
        val call = downloadClient.newCall(Request.Builder().url(release.url).build())
        downloadCall = call
        mutableState.value = AppUpdateState.Downloading(0)
        scope.launch {
            val next = try {
                val apk = withContext(Dispatchers.IO) { downloadApk(release, call) }
                AppUpdateState.Ready(release, apk)
            } catch (failure: UpdateFailure) {
                AppUpdateState.Failed(failure.error, release)
            } catch (_: IOException) {
                AppUpdateState.Failed(UpdateError.NETWORK, release)
            }
            downloadCall = null
            mutableState.value = if (userCancelledDownload()) {
                if (next is AppUpdateState.Ready) withContext(Dispatchers.IO) { next.apk.delete() }
                AppUpdateState.Available(release, automatic = false)
            } else {
                next
            }
        }
    }

    fun cancelDownload() {
        val downloading = state.value as? AppUpdateState.Downloading ?: return
        mutableState.value = downloading.copy(cancelling = true)
        downloadCall?.cancel()
    }

    private fun userCancelledDownload(): Boolean =
        (state.value as? AppUpdateState.Downloading)?.cancelling == true

    fun openInstaller(update: AppUpdateState.Ready, launch: (File) -> Unit) {
        val ready = state.value as? AppUpdateState.Ready ?: return
        if (ready.apk != update.apk) return
        if (!launchWithCacheTimestamp(ready.apk, System.currentTimeMillis(), launch)) {
            mutableState.value = AppUpdateState.Failed(UpdateError.STORAGE, ready.release)
            return
        }
        installerApks[ready.apk] = SystemClock.elapsedRealtime() + APK_CACHE_TTL_MS
    }

    fun discardApk(update: AppUpdateState.Ready) {
        val ready = state.value as? AppUpdateState.Ready ?: return
        if (ready.apk != update.apk) return
        mutableState.value = AppUpdateState.Available(ready.release, automatic = false)
        if (ready.apk !in activeInstallerApks(installerApks, SystemClock.elapsedRealtime())) {
            scope.launch(Dispatchers.IO) { ready.apk.delete() }
        }
    }

    private fun fetchRelease(retainedApks: Set<File>): GitHubRelease? {
        staging.listFiles()?.forEach { it.delete() }
        val oldCache = System.currentTimeMillis() - APK_CACHE_TTL_MS
        verified.listFiles()?.filter { it !in retainedApks && it.lastModified() <= oldCache }
            ?.forEach { it.delete() }
        val request = Request.Builder().url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Termix-Android")
            .build()
        return releaseClient.newCall(request).execute().use { response ->
            checkResponse(response)
            val source = response.body?.source() ?: throw UpdateFailure(UpdateError.VERIFICATION)
            if (source.request(MAX_RELEASE_JSON + 1)) throw UpdateFailure(UpdateError.VERIFICATION)
            try {
                latestUpdate(source.readUtf8(), installedVersion)
            } catch (_: Exception) {
                throw UpdateFailure(UpdateError.VERIFICATION)
            }
        }
    }

    private fun downloadApk(release: GitHubRelease, call: Call): File {
        if ((!staging.isDirectory && !staging.mkdirs()) || (!verified.isDirectory && !verified.mkdirs())) {
            throw UpdateFailure(UpdateError.STORAGE)
        }
        val temporary = try {
            File.createTempFile("termix-v${release.version}-", ".apk", staging)
        } catch (_: IOException) {
            throw UpdateFailure(UpdateError.STORAGE)
        }
        try {
            call.execute().use { response ->
                checkResponse(response)
                val body = response.body ?: throw UpdateFailure(UpdateError.VERIFICATION)
                val declaredSize = body.contentLength()
                if (declaredSize >= 0 && declaredSize != release.size) {
                    throw UpdateFailure(UpdateError.VERIFICATION)
                }
                val input = body.byteStream()
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                var size = 0L
                var lastPercent = 0
                val output = try {
                    temporary.outputStream()
                } catch (_: IOException) {
                    throw UpdateFailure(UpdateError.STORAGE)
                }
                output.use {
                    while (true) {
                        val bytes = input.read(buffer)
                        if (bytes < 0) break
                        if (call.isCanceled()) throw IOException("Download cancelled")
                        size += bytes
                        if (size > release.size) throw UpdateFailure(UpdateError.VERIFICATION)
                        try {
                            output.write(buffer, 0, bytes)
                        } catch (_: IOException) {
                            throw UpdateFailure(UpdateError.STORAGE)
                        }
                        digest.update(buffer, 0, bytes)
                        val percent = (100 * size / release.size).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            mutableState.update {
                                if (it is AppUpdateState.Downloading && !it.cancelling) {
                                    it.copy(percent = percent)
                                } else it
                            }
                        }
                    }
                }
                if (size != release.size || digest.digest().hex() != release.sha256) {
                    throw UpdateFailure(UpdateError.VERIFICATION)
                }
            }
            if (call.isCanceled()) throw IOException("Download cancelled")
            verifyApk(temporary, release.version)
            if (call.isCanceled()) throw IOException("Download cancelled")
            val apk = File(verified, temporary.name)
            if (apk.exists() || !temporary.renameTo(apk)) throw UpdateFailure(UpdateError.STORAGE)
            return apk
        } finally {
            temporary.delete()
        }
    }

    private fun verifyApk(file: File, expectedVersion: String) {
        val info = try {
            context.packageManager.readUpdateArchive(file)
        } catch (_: RuntimeException) {
            null
        }
        val signer = info?.let(::officialSigner)
        if (info == null || info.packageName != PACKAGE_NAME || info.versionName != expectedVersion ||
            info.currentVersionCode() <= installedVersionCode || signer == null ||
            !MessageDigest.isEqual(signer, installedCertificate)
        ) throw UpdateFailure(UpdateError.VERIFICATION)
    }

    private fun rateLimited(now: Long): Boolean =
        preferences.getLong("rate_limit_until", 0) - now in 1..CHECK_INTERVAL_MS

    private fun checkResponse(response: Response) {
        if (response.code == 403 || response.code == 429) {
            val now = System.currentTimeMillis()
            val retrySeconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 86400) ?: 0
            val resetSeconds = if (response.header("X-RateLimit-Remaining") == "0") {
                response.header("X-RateLimit-Reset")?.toLongOrNull()
                    ?.minus(now / 1000)?.coerceIn(0, 86400) ?: 0
            } else 0
            val delay = maxOf(60, retrySeconds, resetSeconds) * 1000
            preferences.edit().putLong("rate_limit_until", now + delay).apply()
            throw UpdateFailure(UpdateError.RATE_LIMIT)
        }
        if (!response.isSuccessful) throw UpdateFailure(UpdateError.NETWORK)
        if (response.code != 200) throw UpdateFailure(UpdateError.VERIFICATION)
    }

    companion object {
        private var initialized = false
        private var instance: GitHubAppUpdater? = null

        @Suppress("DEPRECATION")
        fun from(context: Context): GitHubAppUpdater? {
            if (initialized) return instance
            initialized = true
            val app = context.applicationContext
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || app.packageName != PACKAGE_NAME) return null
            val manager = app.packageManager
            try {
                val metadata = manager.getApplicationInfo(PACKAGE_NAME, PackageManager.GET_META_DATA).metaData
                if (metadata?.getBoolean(UPDATE_METADATA) != true) return null
                val info = manager.getPackageInfo(PACKAGE_NAME, signingFlags())
                val version = info.versionName ?: return null
                val signer = officialSigner(info) ?: return null
                instance = GitHubAppUpdater(app, version, info.currentVersionCode(), signer)
            } catch (_: PackageManager.NameNotFoundException) {
                return null
            }
            return instance
        }
    }
}

private class UpdateFailure(val error: UpdateError) : IOException()

internal fun activeInstallerApks(retainedUntil: MutableMap<File, Long>, elapsedNow: Long): Set<File> {
    retainedUntil.entries.removeAll { it.value <= elapsedNow }
    return retainedUntil.keys.toSet()
}

internal fun launchWithCacheTimestamp(apk: File, now: Long, launch: (File) -> Unit): Boolean {
    if (!apk.isFile) return false
    val originalTimestamp = apk.lastModified()
    if (!apk.setLastModified(now)) return false
    try {
        launch(apk)
    } catch (failure: RuntimeException) {
        apk.setLastModified(originalTimestamp)
        throw failure
    }
    return true
}

@Suppress("DEPRECATION")
internal fun PackageManager.readUpdateArchive(file: File): PackageInfo? {
    // Android 9 and 10 gather archive certificates only when GET_SIGNATURES is set.
    return getPackageArchiveInfo(file.absolutePath, signingFlags() or PackageManager.GET_SIGNATURES)
}

@Suppress("DEPRECATION")
private fun signingFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    PackageManager.GET_SIGNING_CERTIFICATES
} else {
    PackageManager.GET_SIGNATURES
}

@Suppress("DEPRECATION")
private fun officialSigner(info: PackageInfo): ByteArray? {
    val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.signingInfo?.apkContentsSigners
    } else {
        info.signatures
    }
    val bytes = signatures?.singleOrNull()?.toByteArray() ?: return null
    val sha256 = MessageDigest.getInstance("SHA-256")
    if (sha256.digest(bytes).hex() == LEGACY_CERT) return null
    val certificate = try {
        CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream())
    } catch (_: java.security.cert.CertificateException) {
        return null
    }
    if (sha256.digest(certificate.publicKey.encoded).hex() == LEGACY_PUBLIC_KEY) return null
    return bytes
}

@Suppress("DEPRECATION")
private fun PackageInfo.currentVersionCode(): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    longVersionCode
} else {
    versionCode.toLong()
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
