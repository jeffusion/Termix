package com.termix.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkInstallerRetentionTest {
    @JvmField
    @Rule
    val files = TemporaryFolder()

    @Test
    fun expiredHandoffsStopProtectingTheirFiles() {
        val expired = files.newFile()
        val active = files.newFile()
        val retainedUntil = mutableMapOf(expired to 100L, active to 200L)

        assertEquals(setOf(active), activeInstallerApks(retainedUntil, 100L))
        assertEquals(mapOf(active to 200L), retainedUntil)
        assertEquals(emptySet<java.io.File>(), activeInstallerApks(retainedUntil, 200L))
    }

    @Test
    fun failedHandoffRestoresThePreviousCacheTimestamp() {
        val apk = files.newFile()
        assertTrue(apk.setLastModified(60_000L))

        assertThrows(SecurityException::class.java) {
            launchWithCacheTimestamp(apk, 120_000L) { throw SecurityException("Installer unavailable") }
        }
        assertEquals(60_000L, apk.lastModified())
        assertTrue(apk.isFile)

        assertTrue(launchWithCacheTimestamp(apk, 180_000L) { assertEquals(apk, it) })
        assertEquals(180_000L, apk.lastModified())
    }
}
