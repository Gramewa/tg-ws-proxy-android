package io.github.tgwsproxy.android.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.tgwsproxy.android.BuildConfig
import io.github.tgwsproxy.android.proxy.ProxyLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val version: String,
    val tagName: String,
    val title: String,
    val notes: String,
    val htmlUrl: String,
    val apkDownloadUrl: String?,
    val apkFileName: String?,
    val apkSizeBytes: Long,
)

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Progress(val downloadedBytes: Long, val totalBytes: Long) : DownloadState()
    data class ReadyToInstall(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

object AppUpdater {
    const val GITHUB_REPO = "Gramewa/tg-ws-proxy-android"
    const val LATEST_RELEASE_API = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    const val RELEASES_PAGE_URL = "https://github.com/$GITHUB_REPO/releases/latest"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val _availableRelease = MutableStateFlow<ReleaseInfo?>(null)
    val availableRelease: StateFlow<ReleaseInfo?> = _availableRelease.asStateFlow()

    private val _showDialog = MutableStateFlow(false)
    val showDialog: StateFlow<Boolean> = _showDialog.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val _lastCheckStatus = MutableStateFlow<String?>(null)
    val lastCheckStatus: StateFlow<String?> = _lastCheckStatus.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    @Volatile
    var pendingInstallFile: File? = null
        private set

    fun parseVersionParts(raw: String): List<Int> {
        val cleaned = raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
        if (cleaned.isEmpty()) return listOf(0)
        return cleaned.split('.').map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
    }

    fun isNewerVersion(currentVersion: String, remoteVersion: String): Boolean {
        val cur = parseVersionParts(currentVersion)
        val rem = parseVersionParts(remoteVersion)
        val maxLen = maxOf(cur.size, rem.size)
        for (i in 0 until maxLen) {
            val c = cur.getOrElse(i) { 0 }
            val r = rem.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    fun parseReleaseJson(jsonText: String): ReleaseInfo? {
        return try {
            val obj = JSONObject(jsonText)
            if (obj.optBoolean("draft", false) || obj.optBoolean("prerelease", false)) {
                return null
            }
            val tagName = obj.optString("tag_name", "").trim()
            if (tagName.isEmpty()) return null
            val cleanVersion = tagName
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore('+')
            val title = obj.optString("name", "").trim().ifEmpty { tagName }
            val notes = obj.optString("body", "").trim()
            val htmlUrl = obj.optString("html_url", RELEASES_PAGE_URL).trim().ifEmpty { RELEASES_PAGE_URL }

            var apkUrl: String? = null
            var apkName: String? = null
            var apkSize = 0L

            val assets = obj.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "").trim()
                    if (name.endsWith(".apk", ignoreCase = true) && !name.contains("debug", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url", "").trim().ifEmpty { null }
                        apkName = name
                        apkSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }

            ReleaseInfo(
                version = cleanVersion,
                tagName = tagName,
                title = title,
                notes = notes,
                htmlUrl = htmlUrl,
                apkDownloadUrl = apkUrl,
                apkFileName = apkName,
                apkSizeBytes = apkSize,
            )
        } catch (exc: Exception) {
            ProxyLogger.d("Update JSON parse error: ${exc.message}")
            null
        }
    }

    suspend fun checkForUpdates(
        currentVersion: String = BuildConfig.VERSION_NAME,
        manual: Boolean = false,
        postponedVersion: String = "",
        postponedUntilMs: Long = 0L,
    ): ReleaseInfo? {
        if (_isChecking.value) return _availableRelease.value
        _isChecking.value = true
        if (manual) {
            _lastCheckStatus.value = null
        }

        return try {
            val info = withContext(Dispatchers.IO) {
                val req = Request.Builder()
                    .url(LATEST_RELEASE_API)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "tg-ws-proxy-android/$currentVersion")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("GitHub API HTTP ${resp.code}")
                    }
                    val body = resp.body?.string().orEmpty()
                    parseReleaseJson(body)
                }
            }

            if (info != null && isNewerVersion(currentVersion, info.version)) {
                _availableRelease.value = info
                _lastCheckStatus.value = "available"
                val snoozed = info.version == postponedVersion &&
                    System.currentTimeMillis() < postponedUntilMs
                if (manual || !snoozed) {
                    _showDialog.value = true
                }
                info
            } else {
                _availableRelease.value = null
                _lastCheckStatus.value = "up_to_date"
                null
            }
        } catch (exc: Exception) {
            ProxyLogger.d("Update check failed: ${exc.javaClass.simpleName}: ${exc.message}")
            if (manual) {
                _lastCheckStatus.value = "error"
            }
            null
        } finally {
            _isChecking.value = false
        }
    }

    fun openDialog() {
        if (_availableRelease.value != null) {
            _showDialog.value = true
        }
    }

    fun dismissDialog() {
        _showDialog.value = false
    }

    fun clearLastCheckStatus() {
        _lastCheckStatus.value = null
    }

    suspend fun downloadAndInstall(context: Context, release: ReleaseInfo) {
        val url = release.apkDownloadUrl ?: return
        val appContext = context.applicationContext
        _downloadState.value = DownloadState.Progress(0L, release.apkSizeBytes)

        try {
            val targetFile = withContext(Dispatchers.IO) {
                val updatesDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
                updatesDir.listFiles()?.forEach { old -> runCatching { old.delete() } }

                val fileName = release.apkFileName ?: "tg-ws-proxy-android-v${release.version}.apk"
                val tempFile = File(updatesDir, "$fileName.part")
                val finalFile = File(updatesDir, fileName)

                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "tg-ws-proxy-android/${BuildConfig.VERSION_NAME}")
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("HTTP ${resp.code}")
                    }
                    val body = resp.body ?: throw IOException("Empty APK response")
                    val total = body.contentLength().takeIf { it > 0L } ?: release.apkSizeBytes
                    var downloaded = 0L
                    var lastReport = 0L
                    val buf = ByteArray(16 * 1024)

                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            while (true) {
                                val n = input.read(buf)
                                if (n == -1) break
                                output.write(buf, 0, n)
                                downloaded += n
                                if (downloaded - lastReport >= 64 * 1024 || downloaded == total) {
                                    lastReport = downloaded
                                    _downloadState.value = DownloadState.Progress(downloaded, total)
                                }
                            }
                        }
                    }
                }

                if (!tempFile.renameTo(finalFile)) {
                    tempFile.copyTo(finalFile, overwrite = true)
                    tempFile.delete()
                }
                finalFile
            }

            _downloadState.value = DownloadState.ReadyToInstall(targetFile)
            withContext(Dispatchers.Main) {
                triggerInstall(appContext, targetFile)
            }
        } catch (exc: Exception) {
            ProxyLogger.w("APK download failed: ${exc.javaClass.simpleName}: ${exc.message}")
            _downloadState.value = DownloadState.Error(exc.message ?: exc.javaClass.simpleName)
        }
    }

    fun triggerInstall(context: Context, apkFile: File) {
        if (!apkFile.exists()) {
            _downloadState.value = DownloadState.Error("APK file missing")
            return
        }
        val appContext = context.applicationContext
        if (!appContext.packageManager.canRequestPackageInstalls()) {
            pendingInstallFile = apkFile
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { appContext.startActivity(permIntent) }
            return
        }

        pendingInstallFile = null
        val uri = FileProvider.getUriForFile(
            appContext,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apkFile,
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(installIntent)
    }

    fun retryPendingInstallIfPermitted(context: Context) {
        val pending = pendingInstallFile ?: return
        if (!pending.exists()) {
            pendingInstallFile = null
            return
        }
        if (context.packageManager.canRequestPackageInstalls()) {
            triggerInstall(context, pending)
        }
    }
}
