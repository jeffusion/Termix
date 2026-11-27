package com.termix.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.termix.core.R
import com.termix.update.AppUpdateState
import com.termix.update.GitHubAppUpdater
import com.termix.update.GitHubRelease
import com.termix.update.UpdateError
import java.io.File

@Suppress("DEPRECATION")
@Composable
internal fun AboutTermix(updater: GitHubAppUpdater?) {
    val context = LocalContext.current
    val version = remember(context) {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(88.dp).clip(RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                // The launcher foreground includes transparent padding for the adaptive icon mask.
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.requiredSize(136.dp),
                )
            }
            Text(
                text = "Termix",
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(
                    R.string.app_update_installed,
                    version ?: stringResource(R.string.about_termix_unknown_version),
                ),
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (updater != null) AboutUpdate(updater)
    }
}

@Composable
private fun AboutUpdate(updater: GitHubAppUpdater) {
    val state by updater.state.collectAsState()
    val buttonModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    when (val update = state) {
        AppUpdateState.Idle, AppUpdateState.Current -> {
            Button(onClick = { updater.check() }, modifier = buttonModifier) {
                Text(stringResource(R.string.app_update_check))
            }
            if (update == AppUpdateState.Current) Text(stringResource(R.string.app_update_current))
        }
        AppUpdateState.Checking -> {
            Button(onClick = {}, enabled = false, modifier = buttonModifier) {
                Text(stringResource(R.string.app_update_checking), textAlign = TextAlign.Center)
            }
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is AppUpdateState.Available -> {
            Button(onClick = updater::download, modifier = buttonModifier) {
                Text(stringResource(R.string.app_update_download))
            }
            AvailableUpdate(update.release)
        }
        is AppUpdateState.Downloading -> {
            val status = when {
                update.cancelling -> stringResource(R.string.app_update_cancelling)
                update.percent == 100 -> stringResource(R.string.app_update_verifying)
                else -> stringResource(R.string.app_update_downloading, update.percent)
            }
            Text(status)
            LinearProgressIndicator(
                progress = { update.percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(enabled = !update.cancelling, onClick = updater::cancelDownload) {
                Text(stringResource(R.string.app_update_cancel))
            }
        }
        is AppUpdateState.Ready -> ReadyUpdate(updater, update, buttonModifier)
        is AppUpdateState.Failed -> {
            Button(
                onClick = { if (update.release == null) updater.check() else updater.download() },
                modifier = buttonModifier,
            ) {
                Text(stringResource(R.string.app_update_retry))
            }
            Text(stringResource(updateErrorResource(update.error)))
            if (update.release != null) {
                TextButton(onClick = { updater.check() }) { Text(stringResource(R.string.app_update_check)) }
            }
        }
    }
}

@Composable
private fun AvailableUpdate(release: GitHubRelease) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.app_update_available),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            UpdateDetail(stringResource(R.string.app_update_new_version), release.version, emphasized = true)
            HorizontalDivider()
            UpdateDetail(
                stringResource(R.string.app_update_apk_size),
                Formatter.formatFileSize(context, release.size),
            )
        }
    }
}

@Composable
private fun UpdateDetail(label: String, value: String, emphasized: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = colors.onSurfaceVariant)
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            color = if (emphasized) colors.primary else colors.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun ReadyUpdate(updater: GitHubAppUpdater, state: AppUpdateState.Ready, buttonModifier: Modifier) {
    val context = LocalContext.current
    var canInstall by remember { mutableStateOf(canInstallFromTermix(context)) }
    var installError by rememberSaveable(state.apk.path) {
        mutableStateOf<Int?>(null)
    }
    val sourceSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        canInstall = canInstallFromTermix(context)
    }
    Text(stringResource(R.string.app_update_ready, state.release.version))
    state.checkError?.let { Text(stringResource(updateErrorResource(it))) }
    Text(stringResource(R.string.app_update_session_warning))
    if (!canInstall) Text(stringResource(R.string.app_update_unknown_source))
    installError?.let { Text(stringResource(it)) }
    Button(
        onClick = {
            installError = null
            canInstall = canInstallFromTermix(context)
            val error = if (canInstall) R.string.app_update_installer_error else R.string.app_update_source_settings_error
            try {
                if (canInstall) {
                    updater.openInstaller(state) { openSystemInstaller(context, it) }
                } else {
                    sourceSettings.launch(Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.fromParts("package", context.packageName, null),
                    ))
                }
            } catch (_: ActivityNotFoundException) {
                installError = error
            } catch (_: SecurityException) {
                installError = error
            }
        },
        modifier = buttonModifier,
    ) {
        Text(
            stringResource(if (canInstall) R.string.app_update_install else R.string.app_update_allow_source),
            textAlign = TextAlign.Center,
        )
    }
    TextButton(onClick = { updater.discardApk(state) }) {
        Text(stringResource(R.string.app_update_discard))
    }
    TextButton(onClick = { updater.check() }) { Text(stringResource(R.string.app_update_check)) }
}

private fun updateErrorResource(error: UpdateError): Int = when (error) {
    UpdateError.NETWORK -> R.string.app_update_network_error
    UpdateError.RATE_LIMIT -> R.string.app_update_rate_error
    UpdateError.VERIFICATION -> R.string.app_update_verify_error
    UpdateError.STORAGE -> R.string.app_update_storage_error
}

private fun canInstallFromTermix(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && context.packageManager.canRequestPackageInstalls()

@Suppress("DEPRECATION")
private fun openSystemInstaller(context: Context, apk: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
    val intent = Intent(Intent.ACTION_INSTALL_PACKAGE, uri).apply {
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val installer = context.packageManager.queryIntentActivities(
        intent, PackageManager.MATCH_SYSTEM_ONLY or PackageManager.MATCH_DEFAULT_ONLY,
    ).firstOrNull()?.activityInfo ?: throw ActivityNotFoundException()
    intent.setClassName(installer.packageName, installer.name)
    context.startActivity(intent)
}
