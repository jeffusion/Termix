package com.termix.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings as SettingsIcon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.rk.components.compose.preferences.base.PreferenceColumn
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.components.compose.preferences.switch.PreferenceSwitch
import com.termix.resources.drawables
import com.termix.resources.strings
import com.termix.settings.Settings
import com.termix.ui.components.TerminalEnvironmentSegmentedSelector
import com.termix.ui.components.terminalEnvironmentDescriptionRes
import com.termix.ui.components.terminalEnvironmentFromWorkingMode
import com.termix.ui.components.terminalEnvironmentToWorkingMode
import com.termix.ui.components.workingModeIsRoot
import java.text.NumberFormat
import kotlin.math.roundToInt

private enum class SettingsPage(val title: Int, val summary: Int) {
    TERMINAL(strings.settings_terminal_and_sessions, strings.settings_terminal_summary),
    KEYBOARD(strings.settings_keyboard_and_input, strings.settings_keyboard_summary),
    APPEARANCE(strings.settings_appearance_and_layout, strings.settings_appearance_summary),
    PERMISSIONS(strings.permissions, strings.settings_permissions_summary),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Settings(navController: NavController) {
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    BackHandler(enabled = page != null) { page = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(page?.title ?: strings.settings)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (page != null) page = null else navController.popBackStack()
                    }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(strings.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        key(page) {
            PreferenceColumn(
                contentPadding = padding,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (page) {
                    null -> {
                        PreferenceGroup(dividerStartIndent = 16.dp, dividerEndIndent = 16.dp) {
                            SettingsPage.entries.forEach { category ->
                                SettingsLink(
                                    title = stringResource(category.title),
                                    summary = stringResource(category.summary),
                                    icon = {
                                        val tint = MaterialTheme.colorScheme.primary
                                        when (category) {
                                            SettingsPage.TERMINAL -> Icon(painterResource(drawables.terminal), null, tint = tint)
                                            SettingsPage.KEYBOARD -> Icon(Icons.Outlined.Edit, null, tint = tint)
                                            SettingsPage.APPEARANCE -> Icon(Icons.Outlined.SettingsIcon, null, tint = tint)
                                            SettingsPage.PERMISSIONS -> Icon(Icons.Outlined.Lock, null, tint = tint)
                                        }
                                    },
                                    onClick = { page = category },
                                )
                            }
                        }
                        PreferenceGroup(heading = stringResource(strings.settings_help_and_diagnostics)) {
                            DiagnosticLogPreference()
                        }
                    }
                    SettingsPage.TERMINAL -> TerminalSettings()
                    SettingsPage.KEYBOARD -> KeyboardSettings()
                    SettingsPage.APPEARANCE -> AppearanceSettings()
                    SettingsPage.PERMISSIONS -> PermissionSettings()
                }
            }
        }
    }
}

@Composable
internal fun SettingsLink(
    title: String,
    summary: String? = null,
    value: String? = null,
    icon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (value != null) {
            Text(value, Modifier.widthIn(max = 128.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private enum class TerminalSetting(val title: Int) {
    ENVIRONMENT(strings.default_working_mode),
    SHELL(strings.default_shell),
    LAST_SESSION(strings.close_last_session),
    SCROLLBACK(strings.scrollback_lines),
}

@Composable
private fun TerminalSettings() {
    var editing by rememberSaveable { mutableStateOf<TerminalSetting?>(null) }
    var mode by remember { mutableIntStateOf(Settings.working_Mode) }
    var shell by remember { mutableIntStateOf(Settings.default_shell) }
    var closeBehavior by remember { mutableIntStateOf(Settings.close_last_session_behavior) }
    var scrollback by remember { mutableIntStateOf(Settings.scrollback_lines) }
    val environment = terminalEnvironmentFromWorkingMode(mode)
    val root = workingModeIsRoot(mode)
    val environmentName = stringResource(environment.labelRes)
    val format = NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0])

    PreferenceGroup(heading = stringResource(strings.settings_startup)) {
        SettingsLink(
            title = stringResource(TerminalSetting.ENVIRONMENT.title),
            value = if (root) stringResource(strings.settings_root_environment, environmentName) else environmentName,
            onClick = { editing = TerminalSetting.ENVIRONMENT },
        )
        SettingsLink(title = stringResource(TerminalSetting.SHELL.title), value = shellName(shell)) {
            editing = TerminalSetting.SHELL
        }
    }
    PreferenceGroup(heading = stringResource(strings.settings_sessions)) {
        SettingsLink(
            title = stringResource(TerminalSetting.LAST_SESSION.title),
            value = stringResource(closeBehaviorTitle(closeBehavior)),
            onClick = { editing = TerminalSetting.LAST_SESSION },
        )
        SettingsLink(title = stringResource(TerminalSetting.SCROLLBACK.title), value = format.format(scrollback)) {
            editing = TerminalSetting.SCROLLBACK
        }
    }

    editing?.let { setting ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(setting.title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    when (setting) {
                        TerminalSetting.ENVIRONMENT -> {
                            Text(stringResource(terminalEnvironmentDescriptionRes(environment, root)))
                            TerminalEnvironmentSegmentedSelector(
                                selectedEnvironment = environment,
                                onSelected = {
                                    mode = terminalEnvironmentToWorkingMode(it, root)
                                    Settings.working_Mode = mode
                                },
                            )
                            if (environment.supportsRoot) {
                                val changeRoot: (Boolean) -> Unit = {
                                    mode = terminalEnvironmentToWorkingMode(environment, it)
                                    Settings.working_Mode = mode
                                }
                                PreferenceSwitch(
                                    checked = root,
                                    onCheckedChange = changeRoot,
                                    label = stringResource(strings.terminal_env_root_toggle),
                                    description = stringResource(strings.terminal_env_root_toggle_desc, environmentName),
                                    onClick = { changeRoot(!root) },
                                )
                            } else {
                                Text(stringResource(strings.terminal_env_android_root_unavailable_title))
                                Text(stringResource(strings.terminal_env_android_root_unavailable_desc))
                            }
                        }
                        TerminalSetting.SHELL -> Column(Modifier.selectableGroup()) {
                            for ((option, description) in shellDescriptions) {
                                SettingsChoice(shellName(option), stringResource(description), shell == option) {
                                    shell = option
                                    Settings.default_shell = option
                                }
                            }
                        }
                        TerminalSetting.LAST_SESSION -> Column(Modifier.selectableGroup()) {
                            for ((option, description) in closeDescriptions) {
                                SettingsChoice(stringResource(closeBehaviorTitle(option)), stringResource(description), closeBehavior == option) {
                                    closeBehavior = option
                                    Settings.close_last_session_behavior = option
                                }
                            }
                        }
                        TerminalSetting.SCROLLBACK -> {
                            val index = scrollbackSteps.indexOfFirst { it >= scrollback }
                                .takeIf { it >= 0 } ?: scrollbackSteps.lastIndex
                            Text(format.format(scrollback))
                            Slider(
                                value = index.toFloat(),
                                onValueChange = {
                                    scrollback = scrollbackSteps[it.roundToInt().coerceIn(scrollbackSteps.indices)]
                                    Settings.scrollback_lines = scrollback
                                },
                                steps = scrollbackSteps.size - 2,
                                valueRange = 0f..scrollbackSteps.lastIndex.toFloat(),
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(format.format(scrollbackSteps.first()), style = MaterialTheme.typography.labelSmall)
                                Text(format.format(scrollbackSteps.last()), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { editing = null }) { Text(stringResource(strings.settings_done)) }
            },
        )
    }
}

@Composable
private fun SettingsChoice(title: String, summary: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null)
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun shellName(shell: Int): String = when (shell) {
    ShellType.ASH -> "Ash"
    ShellType.ZSH -> "Zsh"
    else -> "Bash"
}

private fun closeBehaviorTitle(behavior: Int): Int = when (behavior) {
    CloseLastSessionBehavior.NEW_SESSION -> strings.close_last_session_new
    else -> strings.close_last_session_exit
}

private val shellDescriptions = listOf(
    ShellType.BASH to strings.shell_bash_desc,
    ShellType.ASH to strings.shell_ash_desc,
    ShellType.ZSH to strings.shell_zsh_desc,
)
private val closeDescriptions = listOf(
    CloseLastSessionBehavior.EXIT_APP to strings.close_last_session_exit_desc,
    CloseLastSessionBehavior.NEW_SESSION to strings.close_last_session_new_desc,
)
private val scrollbackSteps = listOf(500, 1000, 2000, 3000, 5000, 8000, 10000, 20000, 50000)
