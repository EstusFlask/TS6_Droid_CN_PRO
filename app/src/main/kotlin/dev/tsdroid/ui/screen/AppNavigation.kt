package dev.tsdroid.ui.screen

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.tsdroid.data.SettingsStore
import dev.tsdroid.pro.R
import dev.tsdroid.update.AppUpdateManager
import dev.tsdroid.update.DownloadProgress
import dev.tsdroid.update.StartupUpdateCheckGate
import dev.tsdroid.update.UpdateCheckResult
import dev.tsdroid.update.UpdateRelease
import java.util.Locale
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
    var downloadProgress by remember { mutableStateOf<DownloadProgress?>(null) }

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
            downloadProgress = DownloadProgress(0L, -1L)
            try {
                AppUpdateManager.downloadAndInstall(context, release) { progress ->
                    downloadProgress = progress
                }
            } catch (_: Exception) {
                updateDialogState = UpdateDialogState.DownloadFailed
            } finally {
                isDownloadingUpdate = false
                downloadProgress = null
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
        downloadProgress = downloadProgress,
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
    downloadProgress: DownloadProgress?,
    onDownload: (UpdateRelease) -> Unit,
    onOpenReleasePage: (String) -> Unit,
    onDismiss: () -> Unit,
    onDoNotRemind: () -> Unit,
) {
    if (isDownloadingUpdate) {
        val downloadedBytes = downloadProgress?.downloadedBytes ?: 0L
        val totalBytes = downloadProgress?.totalBytes ?: -1L
        val hasTotalBytes = totalBytes > 0L
        val progressValue = if (hasTotalBytes) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.update_downloading_title)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.update_downloading_message))
                    Spacer(Modifier.height(16.dp))
                    if (hasTotalBytes) {
                        LinearProgressIndicator(
                            progress = { progressValue },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (hasTotalBytes) {
                            stringResource(
                                R.string.update_download_progress,
                                downloadedBytes.toReadableFileSize(),
                                totalBytes.toReadableFileSize(),
                            )
                        } else {
                            stringResource(
                                R.string.update_download_progress_unknown,
                                downloadedBytes.toReadableFileSize(),
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {},
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp,
        )
    }

    when (state) {
        is UpdateDialogState.Available -> {
            val release = state.release
            val version = release.tagName.ifBlank { release.versionName }.toVersionLabel()
            UpdateAvailableDialog(
                version = version,
                onDismiss = onDismiss,
                onDownload = { onDownload(release) },
                onOpenReleasePage = { onOpenReleasePage(release.releasePageUrl) },
                onDoNotRemind = onDoNotRemind,
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

@Composable
private fun UpdateAvailableDialog(
    version: String,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onOpenReleasePage: () -> Unit,
    onDoNotRemind: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.update_available_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.update_available_message, version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onDownload,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(stringResource(R.string.update_yes))
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onOpenReleasePage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(R.string.update_open_github))
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                ) {
                    Text(stringResource(R.string.update_later))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.update_do_not_remind),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDoNotRemind)
                        .padding(vertical = 8.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                )
            }
        }
    }
}

internal fun String.toVersionLabel(): String {
    return if (startsWith("v", ignoreCase = true)) this else "v$this"
}

private fun Long.toReadableFileSize(): String {
    if (this < 1024L) return "$this B"

    val units = listOf("KB", "MB", "GB")
    var value = this.toDouble() / 1024.0
    var unitIndex = 0

    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }

    return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
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
