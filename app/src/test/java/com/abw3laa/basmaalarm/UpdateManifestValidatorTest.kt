package com.abw3laa.basmaalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestValidatorTest {
    @Test fun acceptsOnlyBoundedValidManifestAndShareUrl() {
        val json = """{"versionCode":12,"versionName":"2.0","apkUrl":"https://www.mediafire.com/file/abc/app.apk"}"""
        val parsed = UpdateManifestValidator.parseManifest(json.toByteArray())
        assertEquals(12L, parsed?.versionCode)
        assertEquals("2.0", parsed?.versionName)
        assertNull(UpdateManifestValidator.parseManifest(json.replace("12", "\"12\"").toByteArray()))
        assertNull(UpdateManifestValidator.parseManifest(json.replace("12", "2147483648").toByteArray()))
        assertNull(UpdateManifestValidator.parseManifest("[]".toByteArray()))
        assertNull(UpdateManifestValidator.parseManifest((json + " trailing").toByteArray()))
        assertNull(UpdateManifestValidator.parseManifest(ByteArray(16 * 1024 + 1)))
    }

    @Test fun requiresConservativeVersionNames() {
        assertTrue(UpdateManifestValidator.isValidVersionName("1.3.5"))
        assertTrue(UpdateManifestValidator.isValidVersionName("2.0.0-rc1"))
        listOf(" ", "latest", "1.2.3\n", "1.2.3/<script>", "1".repeat(65)).forEach {
            assertFalse(it, UpdateManifestValidator.isValidVersionName(it))
        }
    }

    @Test fun rejectsUnsafeOrNonShareManifestUrls() {
        listOf(
            "http://mediafire.com/file/a/b",
            "https://user@mediafire.com/file/a/b",
            "https://mediafire.com:8443/file/a/b",
            "https://evilmediafire.com/file/a/b",
            "https://mediafire.com/download/a",
            "https://mediafire.com/not-file/file/a/b",
            "https://mediafire.com\\\\@evil.example/file/a/b",
        ).forEach { assertFalse(it, UpdateManifestValidator.isShareUrl(it)) }
        assertTrue(UpdateManifestValidator.isShareUrl("https://mediafire.com/file/a/b"))
    }

    @Test fun downloadAndNavigationHostsAreStrict() {
        assertTrue(UpdateManifestValidator.isOfficialDownloadUrl("https://download123.mediafire.com/abc/app.apk"))
        assertFalse(UpdateManifestValidator.isOfficialDownloadUrl("https://download.mediafire.com/a"))
        assertFalse(UpdateManifestValidator.isOfficialDownloadUrl("https://download1.evil.mediafire.com/a"))
        assertFalse(UpdateManifestValidator.isAllowedWebNavigation("http://mediafire.com/file/a/b"))
        assertFalse(UpdateManifestValidator.isAllowedWebNavigation("https://evil.example"))
        assertTrue(UpdateManifestValidator.isAllowedWebNavigation("https://www.mediafire.com/file/a/b"))
        assertTrue(UpdateManifestValidator.isAllowedWebNavigation("https://www.mediafire.com/error"))
    }
}
