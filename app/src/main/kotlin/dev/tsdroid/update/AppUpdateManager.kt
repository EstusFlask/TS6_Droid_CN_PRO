package dev.tsdroid.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class UpdateRelease(
    val versionName: String,
    val tagName: String,
    val releasePageUrl: String,
    val apkName: String?,
    val apkDownloadUrl: String?,
)

sealed interface UpdateCheckResult {
    data class Available(val release: UpdateRelease) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data object NetworkError : UpdateCheckResult
}

object StartupUpdateCheckGate {
    private var consumed = false

    @Synchronized
    fun consume(): Boolean {
        if (consumed) return false
        consumed = true
        return true
    }
}

object AppUpdateManager {
    const val RELEASES_URL = "https://github.com/EstusFlask/TS6_Droid_CN_PRO/releases"

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/EstusFlask/TS6_Droid_CN_PRO/releases/latest"
    private const val USER_AGENT = "TS6-Droid-Pro"

    suspend fun checkForUpdate(
        currentVersionName: String,
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val release = fetchLatestRelease()
            if (isNewerVersion(release.versionName, currentVersionName)) {
                UpdateCheckResult.Available(release)
            } else {
                UpdateCheckResult.UpToDate
            }
        } catch (_: Exception) {
            UpdateCheckResult.NetworkError
        }
    }

    suspend fun downloadAndInstall(context: Context, release: UpdateRelease) {
        val downloadUrl = release.apkDownloadUrl ?: throw IOException("No APK asset in release")
        val apkFile = withContext(Dispatchers.IO) {
            downloadApk(context.applicationContext, downloadUrl, release.apkName, release.tagName)
        }
        withContext(Dispatchers.Main) {
            installApk(context, apkFile)
        }
    }

    fun openReleasePage(context: Context, url: String = RELEASES_URL) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    private fun fetchLatestRelease(): UpdateRelease {
        val connection = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("GitHub API returned $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tagName = json.optString("tag_name")
            val versionName = releaseVersion(tagName.ifBlank { json.optString("name") })
            val assets = json.optJSONArray("assets")
            var apkName: String? = null
            var apkDownloadUrl: String? = null

            if (assets != null) {
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    val name = asset.optString("name")
                    if (!name.endsWith(".apk", ignoreCase = true)) continue
                    apkName = name
                    apkDownloadUrl = asset.optString("browser_download_url").ifBlank { null }
                    break
                }
            }

            return UpdateRelease(
                versionName = versionName,
                tagName = tagName.ifBlank { versionName },
                releasePageUrl = json.optString("html_url", RELEASES_URL).ifBlank { RELEASES_URL },
                apkName = apkName,
                apkDownloadUrl = apkDownloadUrl,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadApk(
        context: Context,
        downloadUrl: String,
        apkName: String?,
        tagName: String,
    ): File {
        val safeName = (apkName ?: "TS6_Droid_Pro-$tagName.apk")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(updatesDir, safeName)
        val temp = File(updatesDir, "$safeName.tmp")
        val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("APK download returned $code")
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (target.exists() && !target.delete()) {
                throw IOException("Unable to replace old APK")
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            return target
        } finally {
            connection.disconnect()
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        }
        context.startActivity(intent)
    }

    private fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
        val remoteParts = versionParts(remoteVersion)
        val currentParts = versionParts(currentVersion)
        val maxParts = maxOf(remoteParts.size, currentParts.size)

        for (index in 0 until maxParts) {
            val remote = remoteParts.getOrElse(index) { 0 }
            val current = currentParts.getOrElse(index) { 0 }
            if (remote != current) return remote > current
        }
        return false
    }

    private fun versionParts(version: String): List<Int> {
        return Regex("\\d+")
            .findAll(version)
            .mapNotNull { it.value.toIntOrNull() }
            .toList()
    }

    private fun releaseVersion(source: String): String {
        val version = Regex("[vV]?\\d+(?:\\.\\d+)+(?:[-._A-Za-z0-9]*)?")
            .find(source)
            ?.value
            ?: source
        return version.trimStart('v', 'V')
    }
}
