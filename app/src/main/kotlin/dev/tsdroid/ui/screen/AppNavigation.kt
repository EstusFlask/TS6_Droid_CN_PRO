package dev.tsdroid.ui.screen

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.tsdroid.data.SettingsStore
import dev.tsdroid.pro.R
import dev.tsdroid.update.AppUpdateManager
import dev.tsdroid.update.StartupUpdateCheckGate
import dev.tsdroid.update.UpdateCheckResult
import dev.tsdroid.update.UpdateRelease
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context.applicationContext) }
    val appVersionName = remember { context.applicationContext.versionName() }
    val promptUpdates by settingsStore.promptUpdates.collectAsState(initial = true)
    val scope = rememberCoroutineScope()
    var updateDialogState by remember { mutableStateOf<UpdateDialogState?>(null) }
    var isCheckingForUpdates by remember { mutableStateOf(false) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }

    fun startUpdateCheck(manual: Boolean) {
        if (isCheckingForUpdates) return
        scope.launch {
            isCheckingForUpdates = true
            when (val result = AppUpdateManager.checkForUpdate(appVersionName)) {
                is UpdateCheckResult.Available -> {
                    updateDialogState = UpdateDialogState.Available(result.release)
                }
                UpdateCheckResult.UpToDate -> {
                    if (manual) updateDialogState = UpdateDialogState.UpToDate
                }
                UpdateCheckResult.NetworkError -> {
                    if (manual) updateDialogState = UpdateDialogState.NetworkError
                }
            }
            isCheckingForUpdates = false
        }
    }

    fun downloadUpdate(release: UpdateRelease) {
        updateDialogState = null
        if (release.apkDownloadUrl == null) {
            AppUpdateManager.openReleasePage(context, release.releasePageUrl)
            return
        }
        scope.launch {
            isDownloadingUpdate = true
            try {
                AppUpdateManager.downloadAndInstall(context, release)
            } catch (_: Exception) {
                updateDialogState = UpdateDialogState.DownloadFailed
            } finally {
                isDownloadingUpdate = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!StartupUpdateCheckGate.consume()) return@LaunchedEffect
        if (!settingsStore.promptUpdates.first()) return@LaunchedEffect

        isCheckingForUpdates = true
        when (val result = AppUpdateManager.checkForUpdate(appVersionName)) {
            is UpdateCheckResult.Available -> {
                updateDialogState = UpdateDialogState.Available(result.release)
            }
            UpdateCheckResult.UpToDate,
            UpdateCheckResult.NetworkError -> Unit
        }
        isCheckingForUpdates = false
    }

    NavHost(navController = navController, startDestination = "connection") {
        composable("connection") {
            ConnectionScreen(
                onConnected = { navController.navigate("server") {
                    popUpTo("connection") { inclusive = true }
                }},
                onNavigateToAbout = { navController.navigate("about") },
                promptUpdates = promptUpdates,
                onPromptUpdatesChange = { enabled ->
                    scope.launch { settingsStore.setPromptUpdates(enabled) }
                },
                appVersionName = appVersionName,
                isCheckingForUpdates = isCheckingForUpdates,
                onCheckForUpdates = { startUpdateCheck(manual = true) },
            )
        }
        composable("server") {
            ServerScreen(
                onDisconnected = { navController.navigate("connection") {
                    popUpTo("server") { inclusive = true }
                }},
                onNavigateToAbout = { navController.navigate("about") },
                promptUpdates = promptUpdates,
                onPromptUpdatesChange = { enabled ->
                    scope.launch { settingsStore.setPromptUpdates(enabled) }
                },
                appVersionName = appVersionName,
                isCheckingForUpdates = isCheckingForUpdates,
                onCheckForUpdates = { startUpdateCheck(manual = true) },
            )
        }
        composable("about") {
            AboutScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }

    UpdateDialogs(
        state = updateDialogState,
        isDownloadingUpdate = isDownloadingUpdate,
        onDownload = { downloadUpdate(it) },
        onOpenReleasePage = { url ->
            AppUpdateManager.openReleasePage(context, url)
            updateDialogState = null
        },
        onDismiss = { updateDialogState = null },
        onDoNotRemind = {
            updateDialogState = null
            scope.launch { settingsStore.setPromptUpdates(false) }
        },
    )
}

private sealed interface UpdateDialogState {
    data class Available(val release: UpdateRelease) : UpdateDialogState
    data object UpToDate : UpdateDialogState
    data object NetworkError : UpdateDialogState
    data object DownloadFailed : UpdateDialogState
}

@Composable
private fun UpdateDialogs(
    state: UpdateDialogState?,
    isDownloadingUpdate: Boolean,
    onDownload: (UpdateRelease) -> Unit,
    onOpenReleasePage: (String) -> Unit,
    onDismiss: () -> Unit,
    onDoNotRemind: () -> Unit,
) {
    if (isDownloadingUpdate) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.update_downloading_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.update_downloading_message))
                }
            },
            confirmButton = {},
        )
    }

    when (state) {
        is UpdateDialogState.Available -> {
            val release = state.release
            val version = release.tagName.ifBlank { release.versionName }.toVersionLabel()
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.update_available_title)) },
                text = { Text(stringResource(R.string.update_available_message, version)) },
                confirmButton = {
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = { onDownload(release) }) {
                            Text(stringResource(R.string.update_yes))
                        }
                        TextButton(onClick = { onOpenReleasePage(release.releasePageUrl) }) {
                            Text(stringResource(R.string.update_open_github))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(onClick = onDoNotRemind) {
                            Text(stringResource(R.string.update_do_not_remind))
                        }
                    }
                },
            )
        }
        UpdateDialogState.UpToDate -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.update_check_title)) },
                text = { Text(stringResource(R.string.update_current_latest)) },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.update_ok))
                    }
                },
            )
        }
        UpdateDialogState.NetworkError -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.update_check_title)) },
                text = { Text(stringResource(R.string.update_network_error)) },
                confirmButton = {
                    TextButton(onClick = { onOpenReleasePage(AppUpdateManager.RELEASES_URL) }) {
                        Text(stringResource(R.string.update_open_github))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.update_ok))
                    }
                },
            )
        }
        UpdateDialogState.DownloadFailed -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.update_check_title)) },
                text = { Text(stringResource(R.string.update_download_failed)) },
                confirmButton = {
                    TextButton(onClick = { onOpenReleasePage(AppUpdateManager.RELEASES_URL) }) {
                        Text(stringResource(R.string.update_open_github))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.update_ok))
                    }
                },
            )
        }
        null -> Unit
    }
}

internal fun String.toVersionLabel(): String {
    return if (startsWith("v", ignoreCase = true)) this else "v$this"
}

private fun Context.versionName(): String {
    val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(0),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0)
    }
    return packageInfo.versionName ?: "0"
}
