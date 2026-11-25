package com.termix.crashhandler

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class DiagnosticStoreTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val temporary = TemporaryFolder(target.cacheDir)

    @Test
    fun newestEventsSurviveReopening() {
        val directory = temporary.newFolder()
        val context = contextWithStorage(directory)
        val writes = ArrayDeque<Runnable>()
        val store = DiagnosticStore(context, Executor { writes.addLast(it) })

        repeat(70) { store.event("lifecycle-event-$it;") }

        assertEquals(1, writes.size)
        assertTrue(directory.list().orEmpty().isEmpty())
        writes.removeFirst().run()

        val report = DiagnosticStore(context).report()
        assertFalse(report.contains("lifecycle-event-5;"))
        assertTrue(report.contains("lifecycle-event-6;"))
        assertTrue(report.contains("lifecycle-event-69;"))
    }

    @Test
    fun crashAndAnrRetainStackAndEventsWithoutExceptionMessages() {
        val store = DiagnosticStore(contextWithStorage(temporary.newFolder()), Executor {})
        store.event("event-before-crash")
        store.recordAnr(IllegalAccessError("private watchdog text"), 5030)
        val cause = IOException("private terminal text").apply {
            stackTrace = arrayOf(StackTraceElement("FaultSource", "fail", "FaultSource.kt", 41))
        }
        val error = IllegalStateException("private session name", cause).apply {
            addSuppressed(IllegalArgumentException("private launch argument"))
        }
        store.recordCrash(Thread.currentThread(), error)

        val report = store.report()
        assertTrue(report.contains("event-before-crash"))
        assertTrue(report.contains("Caused by: java.io.IOException"))
        assertTrue(report.contains("at FaultSource.fail(FaultSource.kt:41)"))
        assertTrue(report.contains("Suppressed: java.lang.IllegalArgumentException"))
        assertTrue(report.contains("main_thread_blocked_ms=5030"))
        assertTrue(report.contains("java.lang.IllegalAccessError"))
        assertFalse(report.contains("private "))
    }

    private fun contextWithStorage(directory: File): Context =
        object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = directory
        }
}
