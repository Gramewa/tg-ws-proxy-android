package io.github.tgwsproxy.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tgwsproxy.android.BuildConfig
import io.github.tgwsproxy.android.proxy.humanBytes
import io.github.tgwsproxy.android.service.ProxyController
import io.github.tgwsproxy.android.update.AppUpdater
import io.github.tgwsproxy.android.update.ReleaseInfo

@Composable
fun UpdateDialog(
    release: ReleaseInfo,
    strings: AppStrings,
) {
    val context = LocalContext.current
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme

    val downloadLabel = if (release.apkSizeBytes > 0L) {
        "${strings.btnDownloadInstall} · ${humanBytes(release.apkSizeBytes)}"
    } else {
        strings.btnDownloadInstall
    }

    AlertDialog(
        onDismissRequest = { AppUpdater.dismissDialog() },
        containerColor = velvet.card,
        shape = RoundedCornerShape(28.dp),
        icon = {
            VelvetIconBadge(
                containerColor = velvet.liveGreenSoft,
                contentColor = velvet.liveGreen,
                size = 48.dp,
            ) {
                Icon(
                    imageVector = Icons.Rounded.SystemUpdate,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
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
                )
                Surface(
                    color = velvet.accentSoft,
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        text = "v${BuildConfig.VERSION_NAME} → v${release.version}",
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = velvet.accent,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .background(
                            color = velvet.cardSub,
                            shape = RoundedCornerShape(14.dp),
                        )
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    Text(
                        text = release.notes.ifBlank { release.title },
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurface,
                    )
                }

                Text(
                    text = strings.updateDownloadHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { AppUpdater.openUpdateDownload(context, release) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = velvet.accent,
                        contentColor = velvet.onAccent,
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = downloadLabel,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            val safeUrl = if (AppUpdater.isTrustedGithubHttpsUrl(release.htmlUrl)) {
                                release.htmlUrl
                            } else {
                                AppUpdater.RELEASES_PAGE_URL
                            }
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl))
                            runCatching { context.startActivity(intent) }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(13.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = strings.btnOpenGithub,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    TextButton(
                        onClick = {
                            ProxyController.postponeUpdateAsync(
                                release.version,
                                System.currentTimeMillis() + 86_400_000L,
                            )
                            AppUpdater.dismissDialog()
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = strings.btnLater,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        dismissButton = null,
    )
}
