package com.abw3laa.basmaalarm

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.DownloadListener
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal data class UpdateInfo(val versionCode: Long, val versionName: String, val apkUrl: String)

internal object UpdateManager {
    private const val MANIFEST_URL = "https://raw.githubusercontent.com/abw3laa/basma-alarm-updates/main/update.json"
    private const val PREFS = "basma_alarm_prefs"
    const val RETRY_DELAY_MS = 30_000L
    private const val MAX_TRANSIENT_RETRIES = 1
    private const val MAX_APK_BYTES = 300L * 1024L * 1024L

    fun check(context: Context, callback: (UpdateInfo?) -> Unit) {
        thread {
            val result = runCatching {
                val connection = URL(MANIFEST_URL).openConnection() as? HttpsURLConnection
                    ?: throw IllegalStateException("HTTPS required")
                connection.connectTimeout = 10_000
                connection.readTimeout = 12_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "BasmaAlarm-Android")
                connection.setRequestProperty("Accept", "application/json")
                val bytes = try {
                    if (connection.responseCode != HttpsURLConnection.HTTP_OK || connection.url.host != "raw.githubusercontent.com") return@runCatching null
                    connection.inputStream.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(2048)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (out.size() + count > UpdateManifestValidator.MAX_MANIFEST_BYTES) throw IllegalArgumentException("Update manifest too large")
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    }
                } finally { connection.disconnect() }
                val manifest = UpdateManifestValidator.parseManifest(bytes) ?: return@runCatching null
                val pkg = currentPackageInfo(context)
                val current = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else @Suppress("DEPRECATION") pkg.versionCode.toLong()
                if (manifest.versionCode > current) UpdateInfo(manifest.versionCode, manifest.versionName, manifest.apkUrl) else null
            }.getOrNull()
            Handler(Looper.getMainLooper()).post { callback(result) }
        }
    }

    /** Starts a durable DownloadManager transfer; MediaFire's share page is resolved with WebView first. */
    fun ensureDownloaded(context: Context, info: UpdateInfo, callback: (File?, String) -> Unit) {
        val cached = File(context.cacheDir, "basma-update-${info.versionCode}.apk")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!UpdateManifestValidator.isShareUrl(info.apkUrl)) {
            prefs.edit().putBoolean(failureKey(info), true).apply()
            callback(null, "invalid_manifest"); return
        }
        if (isValidPackage(context, cached, info)) { clearRetryState(prefs, info); callback(cached, "ready"); return }
        val retryAt = prefs.getLong(retryAfterKey(info), 0L)
        if (retryAt > System.currentTimeMillis()) { callback(null, "retry_scheduled"); return }
        if (retryAt > 0L) prefs.edit().remove(retryAfterKey(info)).apply()
        val savedId = prefs.getLong(downloadIdKey(info), -1L)
        if (savedId > 0) {
            val status = queryStatus(context, savedId)
            if (status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PAUSED) {
                if (exceedsSizeLimit(context, savedId)) {
                    discardDownload(context, info, savedId)
                    prefs.edit().putBoolean(failureKey(info), true).apply()
                    callback(null, "invalid_package"); return
                }
                callback(null, "downloading"); return
            }
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                val file = materialize(context, savedId, info)
                if (file != null) { clearRetryState(prefs, info); callback(file, "ready"); return }
                val retries = prefs.getInt(retryKey(info), 0)
                discardDownload(context, info, savedId)
                if (retries >= 1) { prefs.edit().putBoolean(failureKey(info), true).apply(); callback(null, "invalid_package"); return }
                prefs.edit().putInt(retryKey(info), retries + 1).putBoolean(failureKey(info), false).apply()
            } else if (status == DownloadManager.STATUS_FAILED) {
                val retry = scheduleTransientRetry(context, info, savedId, queryReason(context, savedId))
                callback(null, if (retry) "retry_scheduled" else "download_start_failed"); return
            } else if (status != -1) {
                discardDownload(context, info, savedId)
                prefs.edit().remove(retryAfterKey(info)).putBoolean(failureKey(info), true).apply()
                callback(null, "download_start_failed"); return
            } else {
                prefs.edit().remove(downloadIdKey(info)).apply()
                cleanupArtifacts(context, info)
            }
        }
        prefs.edit().putBoolean(failureKey(info), false).apply()
        if (!hasSpaceForInitialDownload(context)) { prefs.edit().putBoolean(failureKey(info), true).apply(); callback(null, "download_start_failed"); return }
        val direct = directDownloadUrl(info.apkUrl)
        if (direct != null) enqueue(context, info, direct, callback)
        else resolveMediaFireWithWebView(context, info, callback)
    }

    /** Called off the main thread while the Activity is visible; returns the verified APK if the transfer finished. */
    fun downloadedFileIfReady(context: Context, info: UpdateInfo): File? {
        val cached = File(context.cacheDir, "basma-update-${info.versionCode}.apk")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (isValidPackage(context, cached, info)) { clearRetryState(prefs, info); return cached }
        val id = prefs.getLong(downloadIdKey(info), -1L)
        if (id <= 0) {
            val retryAt = prefs.getLong(retryAfterKey(info), 0L)
            if (retryAt > 0L && retryAt <= System.currentTimeMillis() && !prefs.getBoolean(failureKey(info), false)) {
                prefs.edit().remove(retryAfterKey(info)).apply()
                ensureDownloaded(context, info) { _, _ -> }
            }
            return null
        }
        val status = queryStatus(context, id)
        if (exceedsSizeLimit(context, id)) {
            discardDownload(context, info, id)
            prefs.edit().putBoolean(failureKey(info), true).apply()
            return null
        }
        if (status == DownloadManager.STATUS_SUCCESSFUL) {
            val file = materialize(context, id, info)
            if (file != null) { clearRetryState(prefs, info); return file }
            val retries = prefs.getInt(retryKey(info), 0)
            discardDownload(context, info, id)
            if (retries < 1) {
                prefs.edit().putInt(retryKey(info), retries + 1).putBoolean(failureKey(info), false).apply()
                ensureDownloaded(context, info) { _, _ -> }
            } else prefs.edit().putBoolean(failureKey(info), true).apply()
            return null
        }
        if (status == DownloadManager.STATUS_FAILED || status == -1) {
            scheduleTransientRetry(context, info, id, if (status == -1) REASON_DOWNLOAD_MISSING else queryReason(context, id))
        }
        return null
    }

    private fun resolveMediaFireWithWebView(context: Context, info: UpdateInfo, callback: (File?, String) -> Unit) {
        val activity = context as? android.app.Activity
        if (activity == null || activity.isFinishing) { callback(null, "resolver_unavailable"); return }
        val completed = AtomicBoolean(false)
        val main = Handler(Looper.getMainLooper())
        var attempts = 0
        var web: WebView? = null
        var parent: ViewGroup? = null
        var timeout: Runnable? = null
        fun cleanup() {
            timeout?.let { main.removeCallbacks(it) }
            runCatching { web?.stopLoading(); parent?.removeView(web); web?.destroy() }
            web = null
        }
        fun finish(file: File?, state: String) {
            if (completed.compareAndSet(false, true)) {
                if (file == null && state != "downloading") context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(failureKey(info), true).apply()
                cleanup(); callback(file, state)
            }
        }
        fun capture(candidate: String): Boolean {
            val normalized = candidate.replace("\\u0026", "&").replace("\\/", "/").replace("&amp;", "&")
            val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return false
            if (!UpdateManifestValidator.isOfficialDownloadUrl(normalized)) return false
            enqueue(context, info, normalized, callback = { file, state -> finish(file, state) })
            return true
        }
        fun evaluateAndRetry() {
            if (completed.get()) return
            val w = web ?: return
            attempts++
            val script = """(function(){var a=document.getElementsByTagName('a'),i,u,b;function ok(s){return /^https:\/\/download[^/?#]*\.mediafire\.com\//i.test(s)&&/\.apk(?:$|[?#])/i.test(s)}for(i=0;i<a.length;i++){u=a[i].href||'';if(ok(u))return u}b=document.getElementById('downloadButton');if(b&&ok(b.href||''))return b.href;return ''})()"""
            w.evaluateJavascript(script) { raw ->
                val candidate = runCatching { (JSONTokener("[$raw]").nextValue() as org.json.JSONArray).optString(0) }.getOrNull().orEmpty()
                if (capture(candidate)) return@evaluateJavascript
                if (attempts < 18) main.postDelayed({ evaluateAndRetry() }, 1_000)
                else finish(null, "mediafire_link_not_found")
            }
        }
        main.post {
            if (activity.isFinishing) { finish(null, "resolver_unavailable"); return@post }
            val container = activity.findViewById<ViewGroup>(android.R.id.content)
            val view = WebView(activity).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                if (Build.VERSION.SDK_INT >= 21) settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                if (Build.VERSION.SDK_INT >= 26) settings.safeBrowsingEnabled = true
                settings.loadsImagesAutomatically = false
                settings.blockNetworkImage = true
                alpha = 0f
                isClickable = false
                layoutParams = FrameLayout.LayoutParams(1, 1)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { evaluateAndRetry() }
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                        if (UpdateManifestValidator.isOfficialDownloadUrl(url)) return capture(url)
                        return !UpdateManifestValidator.isAllowedWebNavigation(url)
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        if (UpdateManifestValidator.isOfficialDownloadUrl(url)) return capture(url)
                        return !UpdateManifestValidator.isAllowedWebNavigation(url)
                    }
                }
                setDownloadListener(DownloadListener { url, _, _, _, _ -> if (!capture(url)) finish(null, "mediafire_link_not_found") })
            }
            web = view; parent = container
            runCatching { container.addView(view); view.loadUrl(info.apkUrl) }.onFailure { finish(null, "resolver_unavailable") }
            timeout = Runnable { finish(null, "mediafire_timeout") }.also { main.postDelayed(it, 30_000) }
        }
    }

    private fun directDownloadUrl(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        return if (UpdateManifestValidator.isOfficialDownloadUrl(url)) url else null
    }

    private fun enqueue(context: Context, info: UpdateInfo, url: String, callback: (File?, String) -> Unit) {
        runCatching {
            val uri = Uri.parse(url)
            require(UpdateManifestValidator.isOfficialDownloadUrl(url))
            val request = DownloadManager.Request(uri)
                .addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                .addRequestHeader("Referer", info.apkUrl)
                .setTitle("منبه بصمة — تحديث ${info.versionName}")
                .setDescription("جارٍ تنزيل تحديث التطبيق في الخلفية")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "basma-update-${info.versionCode}.apk")
            val manager = context.getSystemService(DownloadManager::class.java)
            val cookies = android.webkit.CookieManager.getInstance().getCookie(info.apkUrl)
            if (!cookies.isNullOrBlank()) request.addRequestHeader("Cookie", cookies)
            if (!hasSpaceForInitialDownload(context)) throw IllegalStateException("Insufficient storage for update download")
            val id = manager.enqueue(request)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(downloadIdKey(info), id)
                .remove(retryAfterKey(info)).putBoolean(failureKey(info), false).apply()
            callback(null, "downloading")
        }.onFailure {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(failureKey(info), true).apply()
            callback(null, "download_start_failed")
        }
    }

    private fun queryStatus(context: Context, id: Long): Int {
        val manager = context.getSystemService(DownloadManager::class.java)
        return runCatching { manager.query(DownloadManager.Query().setFilterById(id)).use { cursor -> if (!cursor.moveToFirst()) -1 else cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) } }.getOrDefault(-1)
    }

    private fun exceedsSizeLimit(context: Context, id: Long): Boolean = runCatching {
        context.getSystemService(DownloadManager::class.java)
            .query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (!cursor.moveToFirst()) false else {
                    val totalColumn = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val downloadedColumn = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val total = if (totalColumn >= 0) cursor.getLong(totalColumn) else -1L
                    val downloaded = if (downloadedColumn >= 0) cursor.getLong(downloadedColumn) else -1L
                    total > MAX_APK_BYTES || downloaded > MAX_APK_BYTES
                }
            }
    }.getOrDefault(false)

    private fun queryReason(context: Context, id: Long): Int {
        val manager = context.getSystemService(DownloadManager::class.java)
        return runCatching { manager.query(DownloadManager.Query().setFilterById(id)).use { cursor -> if (!cursor.moveToFirst()) REASON_DOWNLOAD_MISSING else cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)) } }.getOrDefault(REASON_DOWNLOAD_MISSING)
    }

    private fun scheduleTransientRetry(context: Context, info: UpdateInfo, id: Long, reason: Int): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val retries = prefs.getInt(networkRetryCountKey(info), 0)
        discardDownload(context, info, id)
        if (!isRetryableReason(reason) || retries >= MAX_TRANSIENT_RETRIES) {
            prefs.edit().remove(retryAfterKey(info)).putBoolean(failureKey(info), true).apply()
            return false
        }
        prefs.edit().putInt(networkRetryCountKey(info), retries + 1)
            .putLong(retryAfterKey(info), System.currentTimeMillis() + RETRY_DELAY_MS)
            .putBoolean(failureKey(info), false).apply()
        return true
    }

    private fun isRetryableReason(reason: Int): Boolean =
        reason == REASON_DOWNLOAD_MISSING ||
            reason == DownloadManager.ERROR_UNKNOWN ||
            reason == DownloadManager.ERROR_CANNOT_RESUME ||
            reason == DownloadManager.ERROR_HTTP_DATA_ERROR ||
            reason == DownloadManager.ERROR_UNHANDLED_HTTP_CODE ||
            reason == 408 || reason == 429 || reason in 500..599

    private fun clearRetryState(prefs: android.content.SharedPreferences, info: UpdateInfo) {
        prefs.edit().remove(retryKey(info)).remove(networkRetryCountKey(info))
            .remove(retryAfterKey(info)).putBoolean(failureKey(info), false).apply()
    }

    private fun materialize(context: Context, id: Long, info: UpdateInfo): File? {
        val out = File(context.cacheDir, "basma-update-${info.versionCode}.apk")
        val tmp = File(context.cacheDir, "basma-update-${info.versionCode}.part")
        return runCatching {
            val manager = context.getSystemService(DownloadManager::class.java)
            manager.openDownloadedFile(id).use { descriptor ->
                val expectedBytes = descriptor.statSize.coerceAtLeast(0L)
                if (expectedBytes > MAX_APK_BYTES) throw IllegalStateException("APK exceeds size limit")
                if (expectedBytes > 0 && availableBytes(context.cacheDir) < expectedBytes * 2L + MIN_FREE_BYTES) throw IllegalStateException("Insufficient cache storage")
                FileInputStream(descriptor.fileDescriptor).use { input -> FileOutputStream(tmp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > MAX_APK_BYTES || availableBytes(context.cacheDir) < count + MIN_FREE_BYTES) throw IllegalStateException("APK exceeds available cache space")
                        output.write(buffer, 0, count)
                    }
                } }
            }
            if (!isValidPackage(context, tmp, info)) throw IllegalStateException("APK package/signature validation failed")
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
            out
        }.getOrElse { tmp.delete(); null }
    }

    private fun isValidPackage(context: Context, file: File, info: UpdateInfo): Boolean {
        if (!file.exists() || file.length() < 100_000) return false
        val signature = runCatching { file.inputStream().use { byteArrayOf(it.read().toByte(), it.read().toByte(), it.read().toByte(), it.read().toByte()) } }.getOrNull() ?: return false
        if (!signature.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))) return false
        return runCatching {
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
            val candidate = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: return false
            if (candidate.packageName != context.packageName) return false
            val version = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else @Suppress("DEPRECATION") candidate.versionCode.toLong()
            if (version != info.versionCode || candidate.versionName != info.versionName) return false
            val installed = currentPackageInfo(context)
            val installedSigners = signerBytes(installed)
            val candidateSigners = signerBytes(candidate)
            if (Build.VERSION.SDK_INT >= 28) {
                val history = candidate.signingInfo?.signingCertificateHistory?.map { it.toByteArray() }.orEmpty()
                SignerLineageValidator.accepts(
                    installedSigners,
                    candidateSigners,
                    history,
                    candidate.signingInfo?.hasPastSigningCertificates() == true,
                )
            } else sameCertificates(installedSigners, candidateSigners)
        }.getOrDefault(false)
    }

    private fun sameCertificates(left: List<ByteArray>, right: List<ByteArray>): Boolean =
        left.isNotEmpty() && left.size == right.size && left.all { a -> right.any { b -> a.contentEquals(b) } }

    private fun signerBytes(info: PackageInfo): List<ByteArray> {
        return if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        } else {
            @Suppress("DEPRECATION") info.signatures?.map { it.toByteArray() }.orEmpty()
        }
    }

    private fun currentPackageInfo(context: Context): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        return context.packageManager.getPackageInfo(context.packageName, flags)
    }

    private const val MIN_FREE_BYTES = 32L * 1024L * 1024L
    private const val REASON_DOWNLOAD_MISSING = -1

    private fun availableBytes(directory: File): Long = runCatching { StatFs(directory.absolutePath).availableBytes }.getOrDefault(0L)

    private fun hasSpaceForInitialDownload(context: Context): Boolean {
        val destination = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        destination.mkdirs()
        return availableBytes(destination) >= MIN_FREE_BYTES && availableBytes(context.cacheDir) >= MIN_FREE_BYTES
    }

    private fun discardDownload(context: Context, info: UpdateInfo, id: Long) {
        runCatching { context.getSystemService(DownloadManager::class.java).remove(id) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(downloadIdKey(info)).apply()
        File(context.cacheDir, "basma-update-${info.versionCode}.part").delete()
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, "basma-update-${info.versionCode}.apk").delete() }
    }

    private fun cleanupArtifacts(context: Context, info: UpdateInfo) {
        File(context.cacheDir, "basma-update-${info.versionCode}.part").delete()
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, "basma-update-${info.versionCode}.apk").delete() }
    }

    fun hasTerminalFailure(context: Context, info: UpdateInfo): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(failureKey(info), false)

    fun retryDelayRemaining(context: Context, info: UpdateInfo): Long =
        (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(retryAfterKey(info), 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    private fun retryKey(info: UpdateInfo) = "update_retry_${info.versionCode}"
    private fun networkRetryCountKey(info: UpdateInfo) = "update_network_retry_${info.versionCode}"
    private fun retryAfterKey(info: UpdateInfo) = "update_retry_after_${info.versionCode}"
    private fun failureKey(info: UpdateInfo) = "update_failed_${info.versionCode}"
    private fun downloadIdKey(info: UpdateInfo) = "update_download_id_${info.versionCode}"
}
