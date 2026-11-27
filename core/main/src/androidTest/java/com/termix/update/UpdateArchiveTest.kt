package com.termix.update

import android.content.pm.PackageManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class UpdateArchiveTest {
    @Suppress("DEPRECATION")
    @Test
    fun readsCurrentSignerFromSignedArchive() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val installed = manager.getPackageInfo(context.packageName, flags)
        val archive = requireNotNull(manager.readUpdateArchive(File(context.applicationInfo.sourceDir)))
        assertEquals(installed.packageName, archive.packageName)
        val installedSigner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            installed.signingInfo?.apkContentsSigners?.singleOrNull()
        } else {
            installed.signatures?.singleOrNull()
        }
        val archiveSigner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.signingInfo?.apkContentsSigners?.singleOrNull()
        } else {
            archive.signatures?.singleOrNull()
        }
        assertArrayEquals(requireNotNull(installedSigner).toByteArray(), requireNotNull(archiveSigner).toByteArray())
    }
}
