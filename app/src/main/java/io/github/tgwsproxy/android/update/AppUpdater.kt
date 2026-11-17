package io.github.tgwsproxy.android.update

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import java.io.IOException
import java.net.URI
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

    fun isTrustedGithubHttpsUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return false
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return false
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.rawPath.orEmpty()
        return when (host) {
            "github.com" -> path.startsWith("/$GITHUB_REPO/", ignoreCase = true)
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com" -> true
            else -> false
        }
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
            val rawHtmlUrl = obj.optString("html_url", RELEASES_PAGE_URL).trim()
            val htmlUrl = if (isTrustedGithubHttpsUrl(rawHtmlUrl)) rawHtmlUrl else RELEASES_PAGE_URL

            var apkUrl: String? = null
            var apkName: String? = null
            var apkSize = 0L

            val assets = obj.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "").trim()
                    if (name.endsWith(".apk", ignoreCase = true) && !name.contains("debug", ignoreCase = true)) {
                        val rawUrl = asset.optString("browser_download_url", "").trim()
                        if (isTrustedGithubHttpsUrl(rawUrl)) {
                            apkUrl = rawUrl
                            apkName = name
                            apkSize = asset.optLong("size", 0L)
                            break
                        }
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

    fun openUpdateDownload(context: Context, release: ReleaseInfo) {
        val targetUrl = listOfNotNull(release.apkDownloadUrl, release.htmlUrl)
            .firstOrNull { isTrustedGithubHttpsUrl(it) }
            ?: RELEASES_PAGE_URL
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            val safeHtml = if (isTrustedGithubHttpsUrl(release.htmlUrl)) release.htmlUrl else RELEASES_PAGE_URL
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(safeHtml)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(fallback) }
        }
        dismissDialog()
    }
}
