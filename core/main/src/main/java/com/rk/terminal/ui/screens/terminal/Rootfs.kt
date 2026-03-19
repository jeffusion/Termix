package com.rk.terminal.ui.screens.terminal

import android.os.Environment
import androidx.compose.runtime.mutableStateOf
import com.rk.libcommons.application
import com.rk.libcommons.archDir
import com.rk.libcommons.child
import com.rk.settings.Settings
import com.rk.terminal.ui.screens.settings.WorkingMode
import com.rk.terminal.App
import java.io.File

object Rootfs {
    val reTerminal = application!!.filesDir

    init {
        if (reTerminal.exists().not()){
            reTerminal.mkdirs()
        }
    }

    var isDownloaded = mutableStateOf(isFilesDownloaded())

    fun requiresRootfs(workingMode: Int = Settings.working_Mode): Boolean {
        return workingMode != WorkingMode.ANDROID
    }

    private fun rootfsFileName(workingMode: Int): String {
        return when (workingMode) {
            WorkingMode.ARCH,
            WorkingMode.ARCH_ROOT -> "arch.tar.gz"
            else -> "alpine.tar.gz"
        }
    }

    fun isFilesDownloaded(workingMode: Int = Settings.working_Mode): Boolean{
        if (!requiresRootfs(workingMode)) {
            return true
        }
        val baseReady = reTerminal.exists() &&
                reTerminal.child("proot").exists() &&
                reTerminal.child("libtalloc.so.2").exists()

        if (!baseReady) {
            return false
        }

        if (workingMode == WorkingMode.ARCH || workingMode == WorkingMode.ARCH_ROOT) {
            val archBase = archDir()
            val marker = archBase.parentFile!!.child(".reterminal-arch-installed")
            val hasEtc = archBase.child("etc").exists() || archBase.child("root").child("etc").exists()
            return marker.exists() && hasEtc
        }

        return reTerminal.child(rootfsFileName(workingMode)).exists()
    }
}
