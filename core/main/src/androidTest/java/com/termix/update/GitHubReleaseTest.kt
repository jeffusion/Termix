package com.termix.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseTest {
    private val apkName = "termix-v1.3.0.apk"
    private val url = "https://github.com/jeffusion/Termix/releases/download/v1.3.0/$apkName"
    private val sha256 = "2ffdcb810710878b3ac2f570455ed91bded0afb11aa92f8859145aa404574897"
    private val officialApk = """
        {
          "name": "$apkName",
          "state": "uploaded",
          "size": 12874039,
          "digest": "sha256:$sha256",
          "browser_download_url": "$url"
        }
    """.trimIndent()
    private val otherAssets = """
        {"name": "termix-migration-v1.3.0.apk"},
        {"name": "termix-audio-sources.tar.xz"}
    """.trimIndent()

    @Test
    fun selectsVersionedApkFromGitHubReleaseJson() {
        assertEquals(
            GitHubRelease("1.3.0", url, 12874039, sha256),
            latestUpdate(release("$otherAssets,$officialApk"), "1.2.10"),
        )
    }

    @Test
    fun ignoresCurrentAndOlderVersionsWithNumericComparison() {
        assertNull(latestUpdate(release(otherAssets), "1.3.0"))
        assertNull(latestUpdate(release(otherAssets), "1.10.0"))
        val newer = release(officialApk).replace("1.3.0", "1.10.0")
        assertEquals("1.10.0", latestUpdate(newer, "1.3.100")?.version)
    }

    @Test
    fun rejectsUnpublishedReleasesAndNonStableTags() {
        val valid = release(officialApk)
        listOf(
            valid.replace("\"draft\": false", "\"draft\": true"),
            valid.replace("\"prerelease\": false", "\"prerelease\": true"),
            valid.replace("v1.3.0", "v1.3.0-rc.1"),
            valid.replace("v1.3.0", "v1.03.0"),
        ).forEach { assertRejected(it) }
    }

    @Test
    fun requiresOneUploadedApkWithExactUrlBoundedSizeAndDigest() {
        listOf(
            otherAssets,
            "$officialApk,$officialApk",
            officialApk.replace("uploaded", "starter"),
            officialApk.replace("https://", "http://"),
            officialApk.replace("jeffusion/Termix", "other/Termix"),
            officialApk.replace("12874039", "0"),
            officialApk.replace("12874039", "2147483648"),
            officialApk.replace("\"sha256:$sha256\"", "null"),
            officialApk.replace("sha256:$sha256", "sha256:abcd"),
        ).forEach { assertRejected(release(it)) }
    }

    private fun assertRejected(json: String) {
        assertTrue(runCatching { latestUpdate(json, "1.2.0") }.isFailure)
    }

    private fun release(assets: String): String = """
        {
          "tag_name": "v1.3.0",
          "draft": false,
          "prerelease": false,
          "assets": [$assets]
        }
    """.trimIndent()
}
