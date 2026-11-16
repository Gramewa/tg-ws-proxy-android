package io.github.tgwsproxy.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tgwsproxy.android.BuildConfig
import io.github.tgwsproxy.android.proxy.humanBytes
import io.github.tgwsproxy.android.service.ProxyController
import io.github.tgwsproxy.android.update.AppUpdater
import io.github.tgwsproxy.android.update.DownloadState
import io.github.tgwsproxy.android.update.ReleaseInfo
import kotlinx.coroutines.launch

@Composable
fun UpdateDialog(
    release: ReleaseInfo,
    strings: AppStrings,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloadState by AppUpdater.downloadState.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = {
            if (downloadState !is DownloadState.Progress) {
                AppUpdater.dismissDialog()
            }
        },
        icon = {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        },
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = strings.updateDialogTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        text = "v${BuildConfig.VERSION_NAME} → v${release.version}",
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    Text(
                        text = release.notes.ifBlank { release.title },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                when (val state = downloadState) {
                    is DownloadState.Progress -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val total = state.totalBytes
                            if (total > 0L) {
                                val fraction = (state.downloadedBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                LinearProgressIndicator(
                                    progress = { fraction },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = strings.updateDownloading,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    Text(
                                        text = "${(fraction * 100).toInt()}% (${humanBytes(state.downloadedBytes)} / ${humanBytes(total)})",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text(
                                    text = "${strings.updateDownloading} ${humanBytes(state.downloadedBytes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                    is DownloadState.ReadyToInstall -> {
                        Text(
                            text = strings.updateNeedInstallPerm,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    is DownloadState.Error -> {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    DownloadState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!release.apkDownloadUrl.isNullOrBlank()) {
                    val state = downloadState
                    Button(
                        onClick = {
                            if (state is DownloadState.ReadyToInstall && state.file.exists()) {
                                AppUpdater.triggerInstall(context, state.file)
                            } else if (state !is DownloadState.Progress) {
                                scope.launch {
                                    AppUpdater.downloadAndInstall(context, release)
                                }
                            }
                        },
                        enabled = state !is DownloadState.Progress,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = if (state is DownloadState.ReadyToInstall) {
                                strings.btnInstallReady
                            } else {
                                strings.btnDownloadInstall
                            },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl))
                            runCatching { context.startActivity(intent) }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(strings.btnOpenGithub, maxLines = 1)
                    }

                    TextButton(
                        onClick = {
                            ProxyController.postponeUpdateAsync(
                                release.version,
                                System.currentTimeMillis() + 86_400_000L,
                            )
                            AppUpdater.dismissDialog()
                        },
                        enabled = downloadState !is DownloadState.Progress,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(strings.btnLater, maxLines = 1)
                    }
                }
            }
        },
        dismissButton = null,
    )
}
