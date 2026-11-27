package com.termix.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppUpdateStateTest {
    @JvmField
    @Rule
    val files = TemporaryFolder()

    private val release = GitHubRelease("1.4.0", "https://github.com/example", 42, "a".repeat(64))

    @Test
    fun checkKeepsVerifiedApkOnlyForSameAsset() {
        val ready = AppUpdateState.Ready(release, files.newFile(), UpdateError.NETWORK)
        assertEquals(ready.copy(checkError = null), checkedUpdate(release, ready, automatic = true))
        val newer = release.copy(version = "1.5.0")
        assertEquals(AppUpdateState.Available(newer, true), checkedUpdate(newer, ready, automatic = true))
        val replaced = release.copy(sha256 = "b".repeat(64))
        assertEquals(AppUpdateState.Available(replaced, false), checkedUpdate(replaced, ready, automatic = false))
        assertEquals(AppUpdateState.Current, checkedUpdate(null, ready, automatic = false))
    }

    @Test
    fun failedCheckKeepsUsableApkAndEvictedApkCanBeDownloadedAgain() {
        val ready = AppUpdateState.Ready(release, files.newFile())
        assertEquals(ready.copy(checkError = UpdateError.RATE_LIMIT), failedUpdateCheck(UpdateError.RATE_LIMIT, ready))
        assertTrue(ready.apk.delete())
        assertEquals(AppUpdateState.Failed(UpdateError.NETWORK, release), failedUpdateCheck(UpdateError.NETWORK, ready))
        assertEquals(AppUpdateState.Available(release, false), checkedUpdate(release, ready, automatic = false))
    }
}
