package com.termix.service

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import com.termix.model.WorkingMode
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

internal object PulseAudioServer {
    private const val TAG = "TermixPulseAudio"
    private const val RUNTIME_VERSION = "pulse-17.0-5"
    private val executor = Executors.newSingleThreadExecutor()
    private var process: Process? = null
    private var socket: File? = null

    fun supportsMode(mode: Int) = mode == WorkingMode.ALPINE || mode == WorkingMode.ARCH

    fun start(context: Context, readyFile: File): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)
        val executable = nativeLibraryDir.resolve("libtermix_pulseaudio.so")
        if (!executable.canExecute()) return null
        val abi = when (nativeLibraryDir.name) {
            "arm64" -> "arm64-v8a"
            "x86_64" -> "x86_64"
            else -> return null
        }

        val runtime = context.filesDir.resolve("$RUNTIME_VERSION-$abi")
        val endpoint = runtime.resolve("run/native")
        if (endpoint.path.toByteArray().size >= 108) {
            Log.w(TAG, "PulseAudio socket path is too long: ${endpoint.path}")
            return null
        }
        val appContext = context.applicationContext
        executor.execute {
            runCatching {
                launch(appContext, executable, runtime, endpoint, "termix-pulse-$abi.zip")
                awaitSocket(endpoint)
                if (readyFile.parentFile?.isDirectory == true) {
                    readyFile.createNewFile()
                }
            }
                .onFailure { Log.e(TAG, "PulseAudio could not start", it) }
        }
        return "unix:${endpoint.path}"
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun launch(context: Context, executable: File, runtime: File, endpoint: File, asset: String) {
        if (process?.isAlive == true) return
        installRuntime(context, runtime, asset)

        val run = runtime.resolve("run")
        val state = runtime.resolve("state")
        val home = runtime.resolve("home")
        val temp = runtime.resolve("tmp")
        for (directory in listOf(run, state, home, temp)) {
            check(directory.isDirectory || directory.mkdir()) { "Cannot create ${directory.path}" }
        }
        check(!endpoint.exists() || endpoint.delete()) { "Cannot remove stale PulseAudio socket" }

        val builder = ProcessBuilder(
            executable.path,
            "--dl-search-path=${runtime.resolve("modules").path}",
            "-n", "-F", runtime.resolve("config/termix.pa").path,
        ).directory(runtime)
            .redirectErrorStream(true)
            .redirectOutput(runtime.resolve("server.log"))
        builder.environment().apply {
            put("LD_LIBRARY_PATH", runtime.resolve("lib").path)
            put("PULSE_CONFIG", runtime.resolve("config/daemon.conf").path)
            put("PULSE_RUNTIME_PATH", run.path)
            put("PULSE_STATE_PATH", state.path)
            put("HOME", home.path)
            put("TMPDIR", temp.path)
            put("DBUS_SESSION_BUS_ADDRESS", "unix:path=${run.resolve("dbus-disabled").path}")
        }
        process = builder.start()
        socket = endpoint
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun awaitSocket(endpoint: File) {
        val address = LocalSocketAddress(endpoint.path, LocalSocketAddress.Namespace.FILESYSTEM)
        val deadline = SystemClock.elapsedRealtime() + 1_500
        while (SystemClock.elapsedRealtime() < deadline) {
            check(process?.isAlive == true) { "PulseAudio exited; see server.log" }
            if (endpoint.exists()) {
                try {
                    LocalSocket().use { it.connect(address) }
                    return
                } catch (_: IOException) {
                    // The daemon may have created the socket before it started listening.
                }
            }
            Thread.sleep(20)
        }
        error("PulseAudio did not start listening")
    }

    fun stop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        executor.execute {
            runCatching { stopProcess() }
                .onFailure { Log.e(TAG, "PulseAudio could not stop", it) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun stopProcess() {
        val running = process ?: return
        running.destroy()
        if (!running.waitFor(2, TimeUnit.SECONDS)) {
            running.destroyForcibly()
            check(running.waitFor(2, TimeUnit.SECONDS)) { "PulseAudio did not exit" }
        }
        socket?.delete()
        socket = null
        process = null
    }

    private fun installRuntime(context: Context, runtime: File, asset: String) {
        if (runtime.isDirectory) return
        val staging = runtime.resolveSibling("${runtime.name}.pending")
        staging.deleteRecursively()
        check(staging.mkdir()) { "Cannot create PulseAudio staging directory" }
        try {
            val prefix = staging.canonicalPath + File.separator
            ZipInputStream(context.assets.open(asset)).use { archive ->
                while (true) {
                    val entry = archive.nextEntry ?: break
                    val output = staging.resolve(entry.name).canonicalFile
                    check(output.path.startsWith(prefix)) { "Invalid PulseAudio asset path" }
                    if (entry.isDirectory) continue
                    output.parentFile!!.mkdirs()
                    output.outputStream().use { archive.copyTo(it) }
                }
            }
            check(staging.resolve("modules/module-aaudio-sink.so").isFile) { "Incomplete PulseAudio asset" }
            check(staging.renameTo(runtime)) { "Cannot install PulseAudio runtime" }
        } finally {
            staging.deleteRecursively()
        }
    }
}
