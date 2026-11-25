package com.termix.ui.screens.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.termix.crashhandler.CrashHandler
import com.termix.resources.strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

@Composable
internal fun DiagnosticLogPreference() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }

    SettingsLink(
        title = stringResource(strings.diagnostic_log_title),
        summary = stringResource(if (loading) strings.diagnostic_log_preparing else strings.diagnostic_log_description),
        icon = { Icon(Icons.AutoMirrored.Outlined.List, null, tint = MaterialTheme.colorScheme.primary) },
        onClick = {
            if (!loading) {
                loading = true
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { CrashHandler.report(context) } }
                        .onSuccess { report = it }
                        .onFailure {
                            Toast.makeText(context, strings.diagnostic_log_read_failed, Toast.LENGTH_SHORT).show()
                        }
                    loading = false
                }
            }
        },
    )

    report?.let { snapshot ->
        val lines = remember(snapshot) { snapshot.lines() }
        AlertDialog(
            onDismissRequest = { if (!sharing) report = null },
            title = { Text(stringResource(strings.diagnostic_log_title)) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                    items(lines) { line ->
                        Text(line.ifEmpty { " " }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !sharing, onClick = {
                    sharing = true
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { diagnosticShareIntent(context, snapshot) } }
                            .mapCatching { context.startActivity(Intent.createChooser(it, null)) }
                            .onSuccess { report = null }
                            .onFailure {
                                Toast.makeText(context, strings.diagnostic_log_share_failed, Toast.LENGTH_SHORT).show()
                            }
                        sharing = false
                    }
                }) {
                    Text(stringResource(if (sharing) strings.diagnostic_log_preparing else strings.diagnostic_log_share))
                }
            },
            dismissButton = {
                TextButton(enabled = !sharing, onClick = { report = null }) {
                    Text(stringResource(strings.cancel))
                }
            },
        )
    }
}

private fun diagnosticShareIntent(context: Context, report: String): Intent {
    val directory = File(context.cacheDir, "diagnostics").apply { mkdirs() }
    val expiredBefore = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
    directory.listFiles { file -> file.isFile && file.name.startsWith("termix-diagnostics-") }
        ?.filter { it.lastModified() in 1 until expiredBefore }
        ?.forEach { it.delete() }
    val file = File.createTempFile("termix-diagnostics-", ".txt", directory)
    try {
        file.writeText(report)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Termix diagnostics", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } catch (error: Exception) {
        file.delete()
        throw error
    }
}
