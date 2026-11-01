package com.abw3laa.basmaalarm

import java.net.URI

/** Pure validation for remotely supplied update manifests and MediaFire URLs. */
internal object UpdateManifestValidator {
    const val MAX_MANIFEST_BYTES = 16 * 1024
    private const val MAX_VERSION_NAME_LENGTH = 64
    private const val MAX_URL_LENGTH = 2048
    private val versionNamePattern = Regex("""^[0-9]{1,4}(?:\.[0-9]{1,4}){1,3}(?:[-+][A-Za-z0-9][A-Za-z0-9._-]{0,24})?$""")

    data class Manifest(val versionCode: Long, val versionName: String, val apkUrl: String)

    fun parseManifest(bytes: ByteArray): Manifest? {
        if (bytes.isEmpty() || bytes.size > MAX_MANIFEST_BYTES) return null
        val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        val tokener = org.json.JSONTokener(text)
        val root = runCatching { tokener.nextValue() }.getOrNull() as? org.json.JSONObject ?: return null
        if (runCatching { tokener.nextClean() }.getOrNull() != '\u0000') return null

        val rawCode = root.opt("versionCode")
        val code = when (rawCode) {
            is Int, is Long -> (rawCode as Number).toLong()
            is Double -> if (rawCode.isFinite() && rawCode % 1.0 == 0.0 && rawCode >= 1.0 && rawCode <= Int.MAX_VALUE.toDouble()) rawCode.toLong() else return null
            else -> return null
        }
        if (code !in 1..Int.MAX_VALUE.toLong()) return null
        val name = root.opt("versionName") as? String ?: return null
        if (!isValidVersionName(name)) return null
        val url = root.opt("apkUrl") as? String ?: return null
        if (!isShareUrl(url)) return null
        return Manifest(code, name, url)
    }

    fun isValidVersionName(value: String): Boolean =
        value.length in 1..MAX_VERSION_NAME_LENGTH && versionNamePattern.matches(value)

    fun isShareUrl(value: String): Boolean {
        if (value.length !in 1..MAX_URL_LENGTH) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", true) && uri.rawUserInfo == null && uri.port == -1 &&
            host in setOf("mediafire.com", "www.mediafire.com") &&
            uri.rawPath.orEmpty().startsWith("/file/", ignoreCase = true)
    }

    fun isOfficialDownloadUrl(value: String): Boolean {
        if (value.length !in 1..MAX_URL_LENGTH) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", true) && uri.rawUserInfo == null && uri.port == -1 &&
            host.matches(Regex("download[0-9]+\\.mediafire\\.com"))
    }

    /** Only first-party HTTPS MediaFire pages or its dedicated download hosts may load in the resolver. */
    fun isAllowedWebNavigation(value: String): Boolean {
        if (value.length !in 1..MAX_URL_LENGTH) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val secureFirstPartyPage = uri.scheme.equals("https", true) && uri.rawUserInfo == null && uri.port == -1 &&
            host in setOf("mediafire.com", "www.mediafire.com")
        return secureFirstPartyPage || isOfficialDownloadUrl(value)
    }
}
