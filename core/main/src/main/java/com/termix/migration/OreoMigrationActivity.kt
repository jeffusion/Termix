package com.termix.migration

import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.DocumentsContract
import android.system.Os
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.termix.core.R
import com.termix.ui.activities.terminal.MainActivity
import com.termix.update.UPDATE_METADATA
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Locale

@RequiresApi(Build.VERSION_CODES.O)
class OreoMigrationActivity : ComponentActivity() {
    private lateinit var model: MigrationViewModel
    private lateinit var message: TextView
    private lateinit var progress: ProgressBar
    private lateinit var action: Button
    private lateinit var continueButton: Button
    private lateinit var terminalButton: Button

    private val picker = registerForActivityResult(StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) model.transfer(uri) else model.discardKey()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!pending(this)) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        model = ViewModelProvider(this)[MigrationViewModel::class.java]
        title = getString(R.string.oreo_migration_title)
        render()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!model.busy) finish()
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    message.setText(state.message)
                    progress.visibility = if (state.busy) ProgressBar.VISIBLE else ProgressBar.GONE
                    action.visibility = if (state.action == 0) Button.GONE else Button.VISIBLE
                    if (state.action != 0) action.setText(state.action)
                    continueButton.visibility = if (state.canContinue) Button.VISIBLE else Button.GONE
                    if (state.canContinue) continueButton.setText(state.continueLabel)
                    terminalButton.visibility = if (!state.busy && model.role == Role.EXPORT) Button.VISIBLE else Button.GONE
                    if (state.exit) {
                        finishAffinity()
                        Process.killProcess(Process.myPid())
                    }
                }
            }
        }
    }

    private fun requestKey() {
        if (model.role == Role.EXPORT) {
            model.prepareExportKey()
            showExportKey()
        } else {
            requestKeyInput(confirmExport = false)
        }
    }

    private fun showExportKey() {
        val key = model.exportKey() ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.oreo_migration_key)
            .setMessage(getString(R.string.oreo_migration_save_key, key))
            .setNegativeButton(android.R.string.cancel) { _, _ -> model.discardKey() }
            .setOnCancelListener { model.discardKey() }
            .setPositiveButton(R.string.oreo_migration_key_saved) { dialog, _ ->
                dialog.dismiss()
                requestKeyInput(confirmExport = true)
            }
            .create().apply {
                window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                show()
            }
    }

    private fun requestKeyInput(confirmExport: Boolean) {
        val keyInput = EditText(this).apply {
            hint = getString(R.string.oreo_migration_key)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        AlertDialog.Builder(this)
            .setTitle(if (confirmExport) R.string.oreo_migration_verify_saved_key else R.string.oreo_migration_enter_key)
            .setView(keyInput)
            .setNegativeButton(android.R.string.cancel) { _, _ -> model.discardKey() }
            .setOnCancelListener { model.discardKey() }
            .setPositiveButton(android.R.string.ok, null)
            .apply {
                if (confirmExport) setNeutralButton(R.string.oreo_migration_view_key_again) { dialog, _ ->
                    dialog.dismiss()
                    showExportKey()
                }
            }
            .create().apply {
                setOnDismissListener { keyInput.text.clear() }
                window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                show()
                getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val accepted = if (confirmExport) model.matchesExportKey(keyInput.text) else model.setImportKey(keyInput.text)
                    if (accepted) {
                        dismiss()
                        pickFile()
                    } else {
                        keyInput.error = getString(
                            if (confirmExport) R.string.oreo_migration_key_mismatch else R.string.oreo_migration_invalid_key,
                        )
                    }
                }
            }
    }

    private fun pickFile() {
        val exporting = model.role == Role.EXPORT
        val request = Intent(if (exporting) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (exporting) "application/octet-stream" else "*/*"
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            if (exporting) putExtra(Intent.EXTRA_TITLE, "Termix-backup.tmx")
        }
        try {
            picker.launch(request)
        } catch (_: android.content.ActivityNotFoundException) {
            model.discardKey()
            model.showPickerError()
        }
    }

    private fun render() {
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply {
            text = title
            textSize = 24f
        })
        message = TextView(this).apply {
            textSize = 16f
            setPadding(0, 20, 0, 20)
        }
        content.addView(message)
        progress = ProgressBar(this)
        content.addView(progress)
        action = Button(this).apply {
            setOnClickListener {
                if (model.state.value.action == R.string.oreo_migration_retry) model.checkRestore()
                else requestKey()
            }
        }
        content.addView(action)
        continueButton = Button(this).apply {
            setOnClickListener {
                model.closeMigration()
            }
        }
        content.addView(continueButton)
        terminalButton = Button(this).apply {
            setText(R.string.oreo_migration_open_terminal)
            visibility = Button.GONE
            setOnClickListener {
                if (!model.busy && model.role == Role.EXPORT) {
                    model.discardKey()
                    oldTerminalMode = true
                    startActivity(Intent(this@OreoMigrationActivity, MainActivity::class.java))
                    finish()
                }
            }
        }
        content.addView(terminalButton)
        scroll.addView(content)
        setContentView(scroll)
    }

    internal class MigrationViewModel(application: Application) : AndroidViewModel(application) {
        private val context = application
        val role = role(context)
        val state = MutableStateFlow(State(R.string.oreo_migration_working, busy = true))
        val busy get() = state.value.busy
        private var pendingKey: ByteArray? = null
        private var exportKeyVerified = false

        init {
            if (role == Role.EXPORT) {
                state.value = State(R.string.oreo_migration_export_guide, R.string.oreo_migration_export)
            } else {
                checkRestore()
            }
        }

        fun prepareExportKey() {
            discardKey()
            pendingKey = OreoBackup.newKey()
        }

        fun exportKey(): String? = pendingKey?.let(OreoBackup::encodeKey)

        fun matchesExportKey(value: CharSequence): Boolean {
            exportKeyVerified = pendingKey?.let { OreoBackup.matchesKey(it, value) } == true
            return exportKeyVerified
        }

        fun setImportKey(value: CharSequence): Boolean {
            discardKey()
            pendingKey = OreoBackup.decodeKey(value)
            return pendingKey != null
        }

        fun discardKey() {
            pendingKey?.fill(0)
            pendingKey = null
            exportKeyVerified = false
        }

        override fun onCleared() {
            discardKey()
            super.onCleared()
        }

        fun checkRestore() {
            work(R.string.oreo_migration_import_error, R.string.oreo_migration_retry) {
                val restore = restore()
                when {
                    restore.recover() -> State(
                        R.string.oreo_migration_import_complete,
                        continueLabel = R.string.oreo_migration_close,
                    )
                    restore.canImport() -> State(
                        R.string.oreo_migration_import_guide,
                        R.string.oreo_migration_import,
                        continueLabel = R.string.oreo_migration_skip,
                    )
                    else -> State(
                        R.string.oreo_migration_existing_data,
                        continueLabel = R.string.oreo_migration_close,
                    )
                }
            }
        }

        fun transfer(uri: Uri) {
            if (busy) return
            if (role == Role.EXPORT && !exportKeyVerified) discardKey()
            val key = pendingKey
            pendingKey = null
            exportKeyVerified = false
            if (key == null) {
                val action = if (role == Role.EXPORT) R.string.oreo_migration_export else R.string.oreo_migration_import
                state.value = state.value.copy(message = R.string.oreo_migration_missing_key, action = action)
                return
            }
            if (role == Role.EXPORT) {
                work(R.string.oreo_migration_export_error, R.string.oreo_migration_export) {
                    try {
                        export(uri, key)
                    } finally {
                        key.fill(0)
                    }
                    State(R.string.oreo_migration_export_complete, R.string.oreo_migration_export)
                }
            } else {
                work(R.string.oreo_migration_import_error, R.string.oreo_migration_retry) {
                    try {
                        val resolver = context.contentResolver
                        requireDocumentUri(uri)
                        resolver.openInputStream(uri)?.use { restore().import(it, key) }
                            ?: throw IOException("Unable to open the selected backup")
                    } finally {
                        key.fill(0)
                    }
                    State(R.string.oreo_migration_import_complete, continueLabel = R.string.oreo_migration_close)
                }
            }
        }

        fun showPickerError() {
            state.value = state.value.copy(message = R.string.oreo_migration_picker_error)
        }

        fun closeMigration() {
            if (!state.value.canContinue || busy) return
            work(R.string.oreo_migration_import_error, R.string.oreo_migration_retry) {
                val working = working(context)
                if (working.resolve("ready").exists()) {
                    restore().finish()
                } else {
                    OreoRestore.writeRecord(working.resolve("done").toPath(), "ok\n")
                }
                State(R.string.oreo_migration_working, busy = true, exit = true)
            }
        }

        private fun work(error: Int, retry: Int, operation: () -> State) {
            state.value = State(R.string.oreo_migration_working, busy = true)
            viewModelScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { operation() } }
                state.value = result.getOrElse {
                    val skip = role == Role.IMPORT && !working(context).resolve("ready").exists()
                    State(error, retry, continueLabel = if (skip) R.string.oreo_migration_skip else 0)
                }
            }
        }

        private fun restore() = restore(context)

        private fun requireDocumentUri(uri: Uri) {
            requireArchive(
                uri.scheme == "content" &&
                    uri.authority != "${context.packageName}.documents" &&
                    uri.authority != "${context.packageName}.fileprovider",
                "Select a backup outside Termix",
            )
            val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            val path = id?.substringAfter(':')?.split('/').orEmpty()
            requireArchive(
                path.windowed(3).none { it[0].equals("Android", true) &&
                    it[1].equals("data", true) && it[2].equals(context.packageName, true) },
                "Select a backup outside Termix",
            )
        }

        private fun export(uri: Uri, key: ByteArray) {
            requireDocumentUri(uri)
            val external = external(context)
            val resolver = context.contentResolver
            val written = MessageDigest.getInstance("SHA-256")
            val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("Unable to create the backup")
            DigestOutputStream(output, written).use { archive.write(data(context), external, OreoBackup.encrypt(it, key)) }
            val read = MessageDigest.getInstance("SHA-256")
            val input = resolver.openInputStream(uri) ?: throw IOException("Unable to verify the backup")
            DigestInputStream(input, read).use {
                val buffer = ByteArray(64 * 1024)
                while (it.read(buffer) != -1) {
                    // Read the entire document through the selected storage provider.
                }
            }
            requireArchive(MessageDigest.isEqual(written.digest(), read.digest()), "Backup verification failed")
        }
    }

    internal data class State(
        val message: Int,
        val action: Int = 0,
        val continueLabel: Int = 0,
        val busy: Boolean = false,
        val exit: Boolean = false,
    ) {
        val canContinue get() = continueLabel != 0
    }

    internal enum class Role { NONE, EXPORT, IMPORT }

    companion object {
        private const val OLD_CERT = "a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc"
        private var processPending: Boolean? = null
        @Volatile internal var oldTerminalMode = false
            private set

        @Synchronized
        internal fun pending(context: Context): Boolean {
            if (Build.VERSION.SDK_INT !in Build.VERSION_CODES.O..Build.VERSION_CODES.O_MR1) return false
            if (context.packageName != "com.termix.app") return false
            if (oldTerminalMode) return false
            return processPending ?: when (role(context)) {
                Role.EXPORT -> true
                Role.IMPORT -> !working(context).resolve("done").exists()
                Role.NONE -> false
            }.also { processPending = it }
        }

        @Suppress("DEPRECATION")
        private fun role(context: Context): Role {
            val manager = context.packageManager
            val signatures = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            val signer = signatures?.singleOrNull() ?: return Role.NONE
            val digest = MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
            val legacy = digest.joinToString("") { "%02x".format(Locale.ROOT, it) } == OLD_CERT
            if (!legacy) return Role.IMPORT
            val metadata = manager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
            return if (metadata?.getBoolean(UPDATE_METADATA) == true) Role.EXPORT else Role.NONE
        }

        private fun working(context: Context) = File(context.noBackupFilesDir, "termix-oreo-migration")

        internal fun cleanupAfterDone(context: Context) {
            if (Build.VERSION.SDK_INT !in Build.VERSION_CODES.O..Build.VERSION_CODES.O_MR1) return
            if (context.packageName != "com.termix.app") return
            val work = working(context)
            if (work.resolve("done").isFile && work.resolve("ready").isFile && !work.resolve("external-cleaned").exists()) {
                runCatching { restore(context).cleanupAfterDone() }
            }
        }

        private fun data(context: Context): Path = File(context.applicationInfo.dataDir).toPath()

        private fun external(context: Context): Path =
            context.getExternalFilesDir(null)?.toPath() ?: throw IOException("App external storage is unavailable")

        private fun restore(context: Context) = OreoRestore(data(context), external(context), working(context).toPath(), archive) {
            val sync = ProcessBuilder("/system/bin/toybox", "sync").redirectErrorStream(true).start()
            sync.inputStream.bufferedReader().use { it.readText() }
            requireArchive(sync.waitFor() == 0, "Could not synchronize restored files")
        }

        private val archive = OreoArchive(
            readLink = { Os.readlink(it.toString()) },
            createLink = { path, target -> Os.symlink(target, path.toString()) },
        )
    }
}
