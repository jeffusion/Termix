package com.termix.update

import java.io.File

internal enum class UpdateError { NETWORK, RATE_LIMIT, VERIFICATION, STORAGE }

internal sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data object Current : AppUpdateState
    data class Available(val release: GitHubRelease, val automatic: Boolean) : AppUpdateState
    data class Downloading(val percent: Int, val cancelling: Boolean = false) : AppUpdateState
    data class Ready(
        val release: GitHubRelease,
        val apk: File,
        val checkError: UpdateError? = null,
    ) : AppUpdateState
    data class Failed(val error: UpdateError, val release: GitHubRelease? = null) : AppUpdateState
}

internal fun checkedUpdate(
    release: GitHubRelease?,
    ready: AppUpdateState.Ready?,
    automatic: Boolean,
): AppUpdateState = when {
    release == null -> AppUpdateState.Current
    ready?.release == release && ready.apk.isFile -> ready.copy(checkError = null)
    else -> AppUpdateState.Available(release, automatic)
}

internal fun failedUpdateCheck(error: UpdateError, ready: AppUpdateState.Ready?): AppUpdateState =
    if (ready?.apk?.isFile == true) ready.copy(checkError = error) else AppUpdateState.Failed(error, ready?.release)
