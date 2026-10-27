package io.github.arnavdugad.ember.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.ember.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
internal data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
)

data class AvailableUpdate(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val sizeBytes: Long,
    val sha256: String?,
    val mandatory: Boolean,
    val pageUrl: String,
)

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(val progress: Float) : DownloadState
    data object Verifying : DownloadState
    data class Ready(val file: File) : DownloadState
    data class Failed(val message: String) : DownloadState
}

/**
 * Self-update from GitHub Releases. The release workflow writes `sha256: <hex>` into the release
 * body; the APK is checked against it, and against this app's own package name and signing
 * certificate, before the system installer ever sees it.
 */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
    private val json: Json,
) {
    val installedVersion: String = BuildConfig.VERSION_NAME.substringBefore('-')

    /** The latest release if it is newer than this build, else null. Throws on network errors. */
    suspend fun latest(): AvailableUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        val release = http.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            check(response.isSuccessful) { "GitHub returned ${response.code}" }
            json.decodeFromString<GitHubRelease>(response.body.string())
        }
        if (release.draft || release.prerelease) return@withContext null
        val version = release.tagName.removePrefix("v")
        if (!isNewer(version, installedVersion)) return@withContext null
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk") } ?: return@withContext null
        val body = release.body.orEmpty()
        AvailableUpdate(
            version = version,
            notes = cleanNotes(body),
            apkUrl = apk.downloadUrl,
            apkName = apk.name,
            sizeBytes = apk.size,
            sha256 = SHA_LINE.find(body)?.groupValues?.get(1)?.lowercase(),
            mandatory = MANDATORY.containsMatchIn(body),
            pageUrl = release.htmlUrl,
        )
    }

    fun download(update: AvailableUpdate): Flow<DownloadState> = flow {
        emit(DownloadState.Downloading(0f))
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Old downloads are dead weight once a newer one starts.
        dir.listFiles()?.filter { it.name != update.apkName }?.forEach { it.delete() }
        val target = File(dir, update.apkName)
        val partial = File(dir, "${update.apkName}.part")
        if (!target.exists() || (update.sha256 != null && sha256(target) != update.sha256)) {
            target.delete()
            http.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { response ->
                check(response.isSuccessful) { "Download failed (${response.code})" }
                val body = response.body
                val total = body.contentLength().takeIf { it > 0 } ?: update.sizeBytes
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0L
                        var lastEmit = 0f
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            done += read
                            val progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                            if (progress - lastEmit >= 0.01f) {
                                lastEmit = progress
                                emit(DownloadState.Downloading(progress))
                            }
                        }
                    }
                }
            }
            check(partial.renameTo(target)) { "Couldn't save the update." }
        }
        emit(DownloadState.Verifying)
        verify(target, update)
        emit(DownloadState.Ready(target))
    }.flowOn(Dispatchers.IO)

    private fun verify(file: File, update: AvailableUpdate) {
        if (update.sha256 != null) {
            check(sha256(file) == update.sha256) {
                file.delete()
                "The download was corrupted. Please try again."
            }
        }
        val pm = context.packageManager
        val archive = archiveInfo(pm, file) ?: run {
            file.delete()
            error("The downloaded file isn't a valid app.")
        }
        check(archive.packageName == context.packageName) {
            "This build (${context.packageName}) can't be updated from a release APK. Install the release from GitHub instead."
        }
        val installed = installedInfo(pm)
        check(PackageInfoCompat.getLongVersionCode(archive) > PackageInfoCompat.getLongVersionCode(installed)) {
            "The download isn't newer than what you have."
        }
        val expected = signatures(installed)
        val actual = signatures(archive)
        check(expected.isEmpty() || actual.isEmpty() || expected == actual) {
            file.delete()
            "The update isn't signed with Ember's key, so it was discarded."
        }
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun archiveInfo(pm: PackageManager, file: File): PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)
    }

    private fun installedInfo(pm: PackageManager): PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
    }

    private fun signatures(info: PackageInfo): Set<String> {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return raw.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    companion object {
        private val SHA_LINE = Regex("""sha256:\s*([0-9a-fA-F]{64})""")
        private val MANDATORY = Regex("""\[mandatory]|#mandatory""", RegexOption.IGNORE_CASE)

        /** Semantic version comparison: 1.10.0 is newer than 1.9.3. */
        fun isNewer(candidate: String, current: String): Boolean {
            val a = candidate.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val b = current.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        /** Release notes without the machine-readable lines and the mandatory marker. */
        fun cleanNotes(body: String): String = body
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            .replace(MANDATORY, "")
            .lines()
            // Machine lines, plus GitHub's auto-generated changelog header and compare link.
            .filterNot { line ->
                val t = line.trim()
                t.startsWith("sha256:") || t.startsWith("versionCode:") || t.startsWith("**Full Changelog**") || t.startsWith("## What's Changed")
            }
            .map { it.replace("**", "") }
            .joinToString("\n")
            .replace(Regex("""\*\*\[Download.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .trimEnd('-')
            .trim()

        private fun sha256(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
            digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
