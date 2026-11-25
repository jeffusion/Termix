package com.termix.crashhandler

import android.content.Context
import android.os.Process
import com.github.anrwatchdog.ANRError
import kotlin.system.exitProcess

object CrashHandler {
    @Volatile
    private var store: DiagnosticStore? = null

    @Synchronized
    fun install(context: Context) {
        if (store != null) return

        val diagnostics = DiagnosticStore(context.applicationContext)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                runCatching {
                    if (error is ANRError) {
                        diagnostics.recordAnr(error, error.duration)
                    } else {
                        diagnostics.recordCrash(thread, error)
                    }
                }
            } finally {
                if (previous != null) {
                    previous.uncaughtException(thread, error)
                } else {
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            }
        }
        store = diagnostics
        event("Application.onCreate")
    }

    /** Record a fixed event label and safe structured values. */
    fun event(message: String) {
        store?.event(message)
    }

    /** Reads local storage and system exit history. Call from an I/O dispatcher. */
    fun report(context: Context): String =
        (store ?: DiagnosticStore(context.applicationContext)).report()
}
