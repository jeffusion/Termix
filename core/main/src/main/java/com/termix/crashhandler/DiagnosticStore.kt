package com.termix.crashhandler

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.Process
import android.util.AtomicFile
import androidx.annotation.RequiresApi
import androidx.core.content.pm.PackageInfoCompat
import com.termix.core.BuildConfig
import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private const val EVENT_LIMIT = 64
private const val EVENT_LENGTH = 160
private const val EVENT_FILE_LIMIT = EVENT_LIMIT * (EVENT_LENGTH + 64)
private const val STACK_LIMIT = 128 * 1024
private const val CAPTURE_LIMIT = STACK_LIMIT + 8 * 1024
private const val EXCEPTION_LIMIT = 128
private const val EXIT_LIMIT = 8

internal class DiagnosticStore(
    private val context: Context,
    private val eventWriter: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Termix diagnostics").apply { isDaemon = true }
    },
) {
    private val pid = Process.myPid()
    private val events = ArrayDeque<String>(EVENT_LIMIT)
    private var writeQueued = false
    private val eventsFile by lazy { file("events") }
    private val crashFile by lazy { file("crash") }
    private val anrFile by lazy { file("anr") }
    private val previousEvents by lazy {
        runCatching {
            eventsFile.readText(EVENT_FILE_LIMIT).lineSequence().filter(String::isNotEmpty)
                .toList().takeLast(EVENT_LIMIT)
        }.getOrDefault(emptyList())
    }

    fun event(message: String) {
        val label = message.take(EVENT_LENGTH).replace('\r', ' ').replace('\n', ' ')
        val line = "${diagnosticTime()} pid=$pid $label"
        synchronized(events) {
            if (events.size == EVENT_LIMIT) events.removeFirst()
            events.addLast(line)
            if (writeQueued) return
            writeQueued = true
            try {
                eventWriter.execute(::writeEvents)
            } catch (_: RuntimeException) {
                writeQueued = false
            }
        }
    }

    private fun writeEvents() {
        val current = synchronized(events) {
            writeQueued = false
            events.toList()
        }
        runCatching {
            eventsFile.writeText((previousEvents + current).takeLast(EVENT_LIMIT).joinToString("\n"))
        }
    }

    fun recordCrash(thread: Thread, error: Throwable) {
        val threadType = if (thread === Looper.getMainLooper().thread) "main" else "worker"
        crashFile.writeText(capture(error, "Java/Kotlin crash thread=$threadType"))
    }

    fun recordAnr(error: Throwable, durationMillis: Long) {
        anrFile.writeText(
            capture(error, "Watchdog ANR main_thread_blocked_ms=$durationMillis", "Thread stacks (main first)"),
        )
    }

    private fun capture(error: Throwable, label: String, stackTitle: String = "Stack trace"): String = buildString {
        appendLine("${diagnosticTime()} pid=$pid $label")
        appendLine("commit=${BuildConfig.GIT_SHORT_COMMIT_HASH}")
        appendLine("Recent lifecycle events")
        synchronized(events) { events.takeLast(16) }.forEach { appendLine(it) }
        appendLine(stackTitle)
        append(formatStack(error))
    }

    fun report(): String = buildString {
        @Suppress("DEPRECATION")
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        appendLine("Termix diagnostics v1")
        appendLine("App version=${packageInfo?.versionName ?: "unknown"} " +
            "versionCode=${packageInfo?.let(PackageInfoCompat::getLongVersionCode) ?: "unknown"} " +
            "commit=${BuildConfig.GIT_SHORT_COMMIT_HASH}")
        appendLine("Android ${Build.VERSION.RELEASE} SDK=${Build.VERSION.SDK_INT} " +
            "manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} build=${Build.ID}")
        appendLine("Report time=${diagnosticTime()} pid=$pid")
        appendLine()
        appendLine("Recent lifecycle events (oldest first)")
        val recent = (previousEvents + synchronized(events) { events.toList() }).takeLast(EVENT_LIMIT)
        appendLine(recent.joinToString("\n").ifEmpty { "(none recorded)" })
        appendLine()
        appendLine("Last Java/Kotlin crash")
        appendLine(savedCapture { crashFile })
        appendLine()
        appendLine("Last watchdog ANR")
        appendLine(savedCapture { anrFile })
        appendLine()
        appendLine("Recent Android process exits (newest first)")
        appendLine(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { processExits() }.getOrDefault("(unavailable)")
            } else {
                "(requires Android 11 or later)"
            },
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun processExits(): String {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return "(unavailable)"
        return manager.getHistoricalProcessExitReasons(context.packageName, 0, EXIT_LIMIT)
            .take(EXIT_LIMIT)
            .joinToString("\n") { exit ->
                "${diagnosticTime(exit.timestamp)} pid=${exit.pid} " +
                    "reason=${exitReason(exit.reason)}(${exit.reason}) status=${exit.status} " +
                    "importance=${exit.importance}"
            }.ifEmpty { "(none recorded)" }
    }

    private fun file(name: String) = AtomicFile(File(context.noBackupFilesDir, "termix-diagnostics-$name.txt"))
}

private fun diagnosticTime(timestamp: Long = System.currentTimeMillis()): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(timestamp))

private fun exitReason(reason: Int) = when (reason) {
    ApplicationExitInfo.REASON_ANR -> "ANR"
    ApplicationExitInfo.REASON_CRASH -> "CRASH"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
    ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
    ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
    ApplicationExitInfo.REASON_OTHER -> "OTHER"
    ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
    ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
    ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
    ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
    else -> "UNKNOWN"
}

private fun savedCapture(file: () -> AtomicFile): String = try {
    file().readText(CAPTURE_LIMIT)
} catch (_: FileNotFoundException) {
    "(none recorded)"
} catch (_: Exception) {
    "(unavailable)"
}

private fun AtomicFile.readText(limit: Int): String = synchronized(this) {
    openRead().bufferedReader().use { reader ->
        val chars = CharArray(limit)
        var length = 0
        while (length < limit) {
            val read = reader.read(chars, length, limit - length)
            if (read < 0) break
            length += read
        }
        String(chars, 0, length)
    }
}

private fun AtomicFile.writeText(text: String) = synchronized(this) {
    val stream = startWrite()
    try {
        stream.write(text.toByteArray())
        finishWrite(stream)
    } catch (failure: Throwable) {
        failWrite(stream)
        throw failure
    }
}

internal fun formatStack(error: Throwable): String = buildString {
    val seen = IdentityHashMap<Throwable, Unit>()
    var truncated = false

    fun appendStackLine(text: String) {
        val remaining = STACK_LIMIT - length
        if (text.length < remaining) {
            appendLine(text)
        } else {
            append(text, 0, remaining)
            truncated = true
        }
    }

    fun appendError(value: Throwable, indent: String, label: String) {
        if (length >= STACK_LIMIT || seen.size >= EXCEPTION_LIMIT) {
            truncated = true
            return
        }
        if (seen.put(value, Unit) != null) {
            appendStackLine("$indent$label[repeated ${value.javaClass.name}]")
            return
        }
        // Exception messages can contain terminal input or other user data.
        appendStackLine("$indent$label${value.javaClass.name}")
        for (frame in value.stackTrace) {
            if (length >= STACK_LIMIT) {
                truncated = true
                break
            }
            appendStackLine("$indent    at $frame")
        }
        for (suppressed in value.suppressed) {
            if (length >= STACK_LIMIT || seen.size >= EXCEPTION_LIMIT) {
                truncated = true
                break
            }
            appendError(suppressed, "$indent    ", "Suppressed: ")
        }
        value.cause?.let { appendError(it, indent, "Caused by: ") }
    }

    appendError(error, "", "")
    if (truncated) appendLine("\n[Stack trace truncated]")
}
