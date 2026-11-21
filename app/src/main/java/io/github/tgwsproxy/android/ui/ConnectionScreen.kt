package io.github.tgwsproxy.android.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.KeyOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tgwsproxy.android.data.AppSettings
import io.github.tgwsproxy.android.proxy.ConnectivityTester
import io.github.tgwsproxy.android.proxy.DcConnectivityStatus
import io.github.tgwsproxy.android.proxy.ProxyConfig
import io.github.tgwsproxy.android.proxy.QuickRouteReport
import io.github.tgwsproxy.android.proxy.StatsSnapshot
import io.github.tgwsproxy.android.proxy.buildTelegramProxyLink
import io.github.tgwsproxy.android.proxy.humanBytes
import io.github.tgwsproxy.android.service.ProxyController
import io.github.tgwsproxy.android.service.ProxyForegroundService
import io.github.tgwsproxy.android.service.ThroughputTelemetry
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun ConnectionScreen(
    strings: AppStrings,
    onRequestStartProxy: () -> Unit = { },
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val settings by ProxyController.settings.collectAsStateWithLifecycle()
    val isRunning by ProxyController.isRunning.collectAsStateWithLifecycle()
    val isTransitioning by ProxyController.isTransitioning.collectAsStateWithLifecycle()
    val lastError by ProxyController.lastError.collectAsStateWithLifecycle()
    val stats by ProxyController.statsSnapshot.collectAsStateWithLifecycle()
    val telemetry by ProxyController.telemetry.collectAsStateWithLifecycle()
    val uptimeSec by ProxyController.uptimeSeconds.collectAsStateWithLifecycle()
    val activeConfig by ProxyController.currentConfig.collectAsStateWithLifecycle()

    var ignoringBattery by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoringBattery = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val effectiveConfig = activeConfig ?: settings.toProxyConfig()
    val proxyLink = remember(effectiveConfig) { buildTelegramProxyLink(effectiveConfig) }

    val toggleProxy = {
        if (!isTransitioning) {
            if (isRunning) {
                ProxyForegroundService.stop(context)
            } else {
                onRequestStartProxy()
            }
        }
    }

    val openTelegram = {
        openTelegramProxyLink(context, proxyLink, strings)
    }

    val copyLink = {
        copyToClipboard(context, "TG Proxy Link", proxyLink, isSensitive = true)
        Toast.makeText(context, strings.toastLinkCopied, Toast.LENGTH_SHORT).show()
    }

    val shareLink: () -> Unit = {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, proxyLink)
        }
        runCatching {
            context.startActivity(Intent.createChooser(sendIntent, strings.btnShareLink))
        }
    }

    val goodConns = stats.connectionsWs + stats.connectionsCfproxy + stats.connectionsH2 + stats.connectionsTcpFallback
    val showKeyMismatch = isRunning && stats.connectionsBad >= 2L && goodConns == 0L

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 6.dp, bottom = if (settings.simpleMode) 28.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SculptedHeroCard(
            strings = strings,
            effectiveConfig = effectiveConfig,
            isRunning = isRunning,
            isTransitioning = isTransitioning,
            uptimeSec = uptimeSec,
            stats = stats,
            telemetry = telemetry,
            onToggleProxy = toggleProxy,
        )

        if (lastError != null) {
            ErrorAlertCard(
                message = lastError.orEmpty(),
                onDismiss = { ProxyController.clearError() },
            )
        }

        AnimatedVisibility(
            visible = showKeyMismatch,
            enter = fadeIn(tween(180)) + expandVertically(),
            exit = fadeOut(tween(140)) + shrinkVertically(),
        ) {
            KeyMismatchCard(
                strings = strings,
                onSyncTelegram = openTelegram,
            )
        }

        PrimaryActionsStack(
            strings = strings,
            settings = settings,
            effectiveConfig = effectiveConfig,
            showPort80InPrimaryStack = settings.simpleMode,
            onOpenTelegram = openTelegram,
            onCopyLink = copyLink,
            onShareLink = shareLink,
        )

        AnimatedVisibility(
            visible = !settings.simpleMode,
            enter = fadeIn(tween(200)) + expandVertically(),
            exit = fadeOut(tween(150)) + shrinkVertically(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TelemetryGrid(
                    strings = strings,
                    stats = stats,
                )
                ProRoutingGroup(
                    strings = strings,
                    settings = settings,
                )
            }
        }

        if (!ignoringBattery) {
            BatteryPromptCard(
                strings = strings,
                onRequestExemption = { requestBatteryExemption(context) },
            )
        }
    }
}

@Composable
private fun SculptedHeroCard(
    strings: AppStrings,
    effectiveConfig: ProxyConfig,
    isRunning: Boolean,
    isTransitioning: Boolean,
    uptimeSec: Long,
    stats: StatsSnapshot,
    telemetry: ThroughputTelemetry,
    onToggleProxy: () -> Unit,
) {
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme

    val containerColor by animateColorAsState(
        targetValue = if (isRunning) velvet.heroActive else velvet.card,
        animationSpec = tween(320),
        label = "heroContainer",
    )
    val rimColor = if (isRunning) velvet.heroActiveRim else velvet.rim

    val statusLabel = when {
        isTransitioning -> strings.statusStarting
        isRunning -> strings.statusOnline
        else -> strings.statusOffline
    }
    val heroTitle = when {
        isTransitioning -> strings.heroStartingTitle
        isRunning -> strings.heroRunningTitle
        else -> strings.heroStoppedTitle
    }
    val endpointText = "${effectiveConfig.host}:${effectiveConfig.port}"
    val heroSubtitle = if (isRunning) {
        if (effectiveConfig.fakeTlsDomain.isNotEmpty()) {
            "$endpointText  ·  ${effectiveConfig.fakeTlsDomain}"
        } else {
            endpointText
        }
    } else {
        strings.heroStoppedSub
    }

    val upstreamRouteLabel = when {
        effectiveConfig.disableSecure && effectiveConfig.fallbackCfproxy -> strings.routeNodeCfHttp80
        effectiveConfig.h2Enabled -> strings.routeNodeCfH2
        effectiveConfig.fallbackCfproxy -> strings.routeNodeCfTls
        else -> strings.routeNodeDirectWs
    }

    val glowAlpha by animateFloatAsState(
        targetValue = if (isRunning) 1f else 0f,
        animationSpec = tween(380),
        label = "heroGlow",
    )

    VelvetCard(
        containerColor = containerColor,
        rimColor = rimColor,
        shape = RoundedCornerShape(28.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    Modifier.drawBehind {
                        if (glowAlpha > 0.01f && size.width > 0f) {
                            val tint = velvet.accent.copy(
                                alpha = (if (velvet.isDark) 0.15f else 0.11f) * glowAlpha,
                            )
                            drawRect(
                                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                    colors = listOf(tint, Color.Transparent),
                                    center = androidx.compose.ui.geometry.Offset(
                                        x = size.width * 0.86f,
                                        y = size.height * 0.22f,
                                    ),
                                    radius = size.width * 0.72f,
                                ),
                            )
                        }
                    },
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, start = 20.dp, end = 18.dp, bottom = 16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            LiveStatusDot(
                                isRunning = isRunning,
                                isTransitioning = isTransitioning,
                                size = 7.dp,
                            )
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = when {
                                    isRunning -> velvet.liveGreen
                                    isTransitioning -> velvet.warning
                                    else -> cs.onSurfaceVariant
                                },
                                maxLines = 1,
                            )
                            if (isRunning && !isTransitioning) {
                                Text(
                                    text = formatUptime(uptimeSec),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Medium,
                                    ),
                                    color = cs.onSurfaceVariant.copy(alpha = 0.85f),
                                    maxLines = 1,
                                )
                            }
                        }

                        Text(
                            text = heroTitle,
                            style = MaterialTheme.typography.headlineSmall,
                            color = cs.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )

                        Text(
                            text = heroSubtitle,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = if (isRunning) FontFamily.Monospace else FontFamily.SansSerif,
                            ),
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    MorphingPowerButton(
                        isRunning = isRunning,
                        isTransitioning = isTransitioning,
                        contentDescription = heroTitle,
                        onClick = onToggleProxy,
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TrafficStreamReadout(
                        isDown = true,
                        totalBytes = stats.bytesDown,
                        rateBps = telemetry.downBps,
                        bpsSuffix = strings.unitBpsSuffix,
                        isRunning = isRunning,
                    )
                    TrafficStreamReadout(
                        isDown = false,
                        totalBytes = stats.bytesUp,
                        rateBps = telemetry.upBps,
                        bpsSuffix = strings.unitBpsSuffix,
                        isRunning = isRunning,
                    )
                }

                ThroughputWaveCanvas(
                    isRunning = isRunning,
                    downHistory = telemetry.downHistory,
                    upHistory = telemetry.upHistory,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp)
                        .padding(top = 6.dp),
                )

                Spacer(Modifier.height(10.dp))

                LiveRouteStrip(
                    clientLabel = strings.routeNodeTelegram,
                    localEndpoint = ":${effectiveConfig.port}",
                    upstreamLabel = upstreamRouteLabel,
                    isRunning = isRunning,
                )
            }
        }
    }
}

@Composable
private fun TrafficStreamReadout(
    isDown: Boolean,
    totalBytes: Long,
    rateBps: Long,
    bpsSuffix: String,
    isRunning: Boolean,
) {
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme
    val tint = if (isDown) velvet.accent else velvet.liveGreen

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = if (isDown) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
            contentDescription = null,
            tint = if (isRunning) tint else cs.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = humanBytes(totalBytes),
            style = MaterialTheme.typography.titleSmall,
            color = cs.onSurface,
        )
        if (isRunning && rateBps >= 512L) {
            Text(
                text = "· ${humanBytes(rateBps)}$bpsSuffix",
                style = MaterialTheme.typography.labelSmall,
                color = tint,
            )
        }
    }
}

@Composable
private fun PrimaryActionsStack(
    strings: AppStrings,
    settings: AppSettings,
    effectiveConfig: ProxyConfig,
    showPort80InPrimaryStack: Boolean,
    onOpenTelegram: () -> Unit,
    onCopyLink: () -> Unit,
    onShareLink: () -> Unit,
) {
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme
    val totalItems = if (showPort80InPrimaryStack) 3 else 2

    VelvetGroup {
        // Item 0: Open in Telegram + Copy + Share
        VelvetGroupItem(
            index = 0,
            count = totalItems,
            onClick = onOpenTelegram,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VelvetIconBadge(
                    containerColor = velvet.accentSoft,
                    contentColor = velvet.accent,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(13.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = strings.tgActionTitle,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = strings.tgActionSub,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(
                        onClick = onCopyLink,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(velvet.cardSub),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = strings.btnCopyLink,
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    IconButton(
                        onClick = onShareLink,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(velvet.cardSub),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = strings.btnShareLink,
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            }
        }

        // Item 1 (Simple mode): Fallback Port 80
        if (showPort80InPrimaryStack) {
            VelvetGroupItem(
                index = 1,
                count = totalItems,
                onClick = { ProxyController.setDisableSecureAsync(!settings.disableSecure) },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VelvetIconBadge(
                        containerColor = if (settings.disableSecure) velvet.liveGreenSoft else velvet.cardSub,
                        contentColor = if (settings.disableSecure) velvet.liveGreen else cs.onSurfaceVariant,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Text(
                                text = strings.fallbackPortTitle,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            if (settings.disableSecure) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = velvet.liveGreenSoft,
                                ) {
                                    Text(
                                        text = strings.fallbackPortBadge,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = velvet.liveGreen,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            text = strings.fallbackPortSub,
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.disableSecure,
                        onCheckedChange = { ProxyController.setDisableSecureAsync(it) },
                    )
                }
            }
        }

        // Last item: Inline Route Diagnostics
        InlineRouteDiagnosticsItem(
            index = totalItems - 1,
            count = totalItems,
            strings = strings,
            settings = settings,
            effectiveConfig = effectiveConfig,
        )
    }
}

@Composable
private fun InlineRouteDiagnosticsItem(
    index: Int,
    count: Int,
    strings: AppStrings,
    settings: AppSettings,
    effectiveConfig: ProxyConfig,
) {
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var expanded by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<QuickRouteReport?>(null) }

    val runProbe = {
        if (!probing) {
            expanded = true
            probing = true
            scope.launch {
                val res = runCatching {
                    ConnectivityTester.quickRouteCheck(effectiveConfig)
                }.getOrNull()
                report = res
                ProxyController.applyDiagnosticWinner(res?.bestCfDomain, res?.bestCfLatencyMs)
                probing = false
            }
        }
    }

    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200),
        label = "diagChevron",
    )

    VelvetGroupItem(
        index = index,
        count = count,
        onClick = {
            if (!expanded) {
                runProbe()
            } else {
                expanded = false
            }
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VelvetIconBadge(
                containerColor = velvet.cardSub,
                contentColor = cs.onSurfaceVariant,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Speed,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = strings.routeCheckTitle,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = if (probing) strings.routeCheckRunning else strings.routeCheckSub,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
            if (probing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = velvet.accent,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier
                        .size(22.dp)
                        .rotate(chevronRotation),
                )
            }
        }

        AnimatedVisibility(
            visible = expanded && (report != null || probing),
            enter = fadeIn(tween(180)) + expandVertically(),
            exit = fadeOut(tween(140)) + shrinkVertically(),
        ) {
            val currentReport = report
            if (currentReport != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RouteLatencyRow(
                        label = strings.routeDirectWs,
                        status = currentReport.directWs,
                        strings = strings,
                    )
                    RouteLatencyRow(
                        label = strings.routeCfTls,
                        status = currentReport.cfTls,
                        strings = strings,
                    )
                    RouteLatencyRow(
                        label = strings.routeCfHttp80,
                        status = currentReport.cfHttp80,
                        strings = strings,
                    )

                    if (currentReport.suggestPort80 && !settings.disableSecure) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = velvet.accentSoft,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = strings.routeSuggestPort80,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurface,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 10.dp),
                                )
                                Button(
                                    onClick = { ProxyController.setDisableSecureAsync(true) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = velvet.accent,
                                        contentColor = velvet.onAccent,
                                    ),
                                ) {
                                    Text(
                                        text = strings.routeApplyPort80,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Surface(
                            onClick = runProbe,
                            enabled = !probing,
                            shape = RoundedCornerShape(10.dp),
                            color = velvet.cardSub,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    tint = cs.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = strings.routeCheckRetry,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = cs.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            } else if (probing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    androidx.compose.material3.LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color = velvet.accent,
                        trackColor = velvet.cardSub,
                    )
                }
            }
        }
    }
}

@Composable
private fun RouteLatencyRow(
    label: String,
    status: DcConnectivityStatus,
    strings: AppStrings,
) {
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme

    val okStatus = status as? DcConnectivityStatus.Ok
    val latencyMs = okStatus?.latencyMs ?: 0L
    val barFraction = if (okStatus != null) {
        (1f - (latencyMs.coerceIn(30L, 1200L) - 30f) / 1350f).coerceIn(0.18f, 1f)
    } else {
        0f
    }
    val barColor = when {
        okStatus == null -> cs.outline.copy(alpha = 0.25f)
        latencyMs <= 280L -> velvet.liveGreen
        latencyMs <= 650L -> velvet.accent
        else -> velvet.warning
    }
    val statusLabel = if (okStatus != null) {
        "$latencyMs ${strings.unitMs}"
    } else {
        strings.routeTimeout
    }

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurface,
            )
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = if (okStatus != null) barColor else cs.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(velvet.cardSub),
        ) {
            if (barFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(barFraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .background(barColor),
                )
            }
        }
    }
}

@Composable
private fun TelemetryGrid(
    strings: AppStrings,
    stats: StatsSnapshot,
) {
    val totalErrors = stats.wsErrors + stats.h2Errors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TelemetryTile(
                label = strings.statActive,
                value = stats.connectionsActive.toString(),
                highlight = stats.connectionsActive > 0,
                modifier = Modifier.weight(1f),
            )
            TelemetryTile(
                label = strings.statWs,
                value = stats.connectionsWs.toString(),
                modifier = Modifier.weight(1f),
            )
            TelemetryTile(
                label = strings.statH2,
                value = stats.connectionsH2.toString(),
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TelemetryTile(
                label = strings.statCf,
                value = stats.connectionsCfproxy.toString(),
                modifier = Modifier.weight(1f),
            )
            TelemetryTile(
                label = strings.statTotal,
                value = stats.connectionsTotal.toString(),
                modifier = Modifier.weight(1f),
            )
            TelemetryTile(
                label = strings.statErrors,
                value = totalErrors.toString(),
                isWarning = totalErrors > 0,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TelemetryTile(
    label: String,
    value: String,
    highlight: Boolean = false,
    isWarning: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme
    val valueColor = when {
        isWarning -> velvet.warning
        highlight -> velvet.liveGreen
        else -> cs.onSurface
    }
    VelvetCard(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (highlight || isWarning) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(valueColor),
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProRoutingGroup(
    strings: AppStrings,
    settings: AppSettings,
) {
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme
    val h2EffectiveEnabled = settings.cfproxyEnabled && !settings.disableSecure && !settings.forceTestDc

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = strings.quickSettingsTitle,
            style = MaterialTheme.typography.labelLarge,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )

        VelvetGroup {
            VelvetGroupItem(
                index = 0,
                count = 3,
                onClick = { ProxyController.setDisableSecureAsync(!settings.disableSecure) },
            ) {
                ProSwitchRow(
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    badgeActive = settings.disableSecure,
                    title = strings.disableTlsTitle,
                    subtitle = strings.disableTlsSubtitle,
                    checked = settings.disableSecure,
                    enabled = true,
                    onCheckedChange = { ProxyController.setDisableSecureAsync(it) },
                )
            }

            VelvetGroupItem(
                index = 1,
                count = 3,
                onClick = { ProxyController.setCfProxyEnabledAsync(!settings.cfproxyEnabled) },
            ) {
                ProSwitchRow(
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.CloudQueue,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    badgeActive = settings.cfproxyEnabled,
                    title = strings.cfProxyTitle,
                    subtitle = strings.cfProxySubtitle,
                    checked = settings.cfproxyEnabled,
                    enabled = true,
                    onCheckedChange = { ProxyController.setCfProxyEnabledAsync(it) },
                )
            }

            VelvetGroupItem(
                index = 2,
                count = 3,
                onClick = if (h2EffectiveEnabled) {
                    { ProxyController.setH2MediaEnabledAsync(!settings.cfproxyH2Media) }
                } else {
                    null
                },
            ) {
                ProSwitchRow(
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.SyncAlt,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    badgeActive = h2EffectiveEnabled && settings.cfproxyH2Media,
                    title = strings.h2MediaTitle,
                    subtitle = if (h2EffectiveEnabled) {
                        strings.h2MediaSubtitle
                    } else {
                        strings.h2DisabledByTlsHint
                    },
                    checked = settings.cfproxyH2Media && h2EffectiveEnabled,
                    enabled = h2EffectiveEnabled,
                    onCheckedChange = { ProxyController.setH2MediaEnabledAsync(it) },
                )
            }
        }
    }
}

@Composable
private fun ProSwitchRow(
    icon: @Composable () -> Unit,
    badgeActive: Boolean,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VelvetIconBadge(
            containerColor = if (badgeActive) velvet.accentSoft else velvet.cardSub,
            contentColor = if (badgeActive) velvet.accent else cs.onSurfaceVariant,
            content = icon,
        )
        Spacer(Modifier.width(13.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) cs.onSurface else cs.onSurfaceVariant,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun KeyMismatchCard(
    strings: AppStrings,
    onSyncTelegram: () -> Unit,
) {
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme

    VelvetCard(
        containerColor = velvet.warningSoft,
        rimColor = velvet.warning.copy(alpha = 0.28f),
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VelvetIconBadge(
                containerColor = velvet.warning.copy(alpha = 0.20f),
                contentColor = velvet.warning,
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyOff,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = strings.keyMismatchTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onSurface,
                )
                Text(
                    text = strings.keyMismatchBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
            FilledTonalButton(
                onClick = onSyncTelegram,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = strings.keyMismatchAction,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun ErrorAlertCard(
    message: String,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    VelvetCard(
        containerColor = cs.errorContainer,
        rimColor = cs.error.copy(alpha = 0.30f),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = cs.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    tint = cs.onErrorContainer,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

@Composable
private fun BatteryPromptCard(
    strings: AppStrings,
    onRequestExemption: () -> Unit,
) {
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme

    VelvetCard(
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VelvetIconBadge(
                containerColor = velvet.cardSub,
                contentColor = cs.onSurfaceVariant,
            ) {
                Icon(
                    imageVector = Icons.Rounded.BatteryChargingFull,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = strings.batteryTitle,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = strings.batteryBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
            FilledTonalButton(
                onClick = onRequestExemption,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = strings.batteryAction,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

fun openTelegramProxyLink(
    context: Context,
    proxyLink: String,
    strings: AppStrings,
) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(proxyLink)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        copyToClipboard(context, "TG Proxy Link", proxyLink, isSensitive = true)
        Toast.makeText(context, strings.toastTelegramMissingCopied, Toast.LENGTH_LONG).show()
    }
}

private fun requestBatteryExemption(context: Context) {
    val directIntent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    runCatching { context.startActivity(directIntent) }.onFailure {
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(fallback) }
    }
}

private fun formatUptime(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

fun copyToClipboard(
    context: Context,
    label: String,
    text: String,
    isSensitive: Boolean = false,
) {
    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    if (isSensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    cb.setPrimaryClip(clip)
}

fun readClipboardText(context: Context): String? {
    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = cb.primaryClip ?: return null
    if (clip.itemCount <= 0) return null
    return clip.getItemAt(0)?.coerceToText(context)?.toString()
}
