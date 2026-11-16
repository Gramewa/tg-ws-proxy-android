package io.github.tgwsproxy.android.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tgwsproxy.android.BuildConfig
import io.github.tgwsproxy.android.data.AppSettings
import io.github.tgwsproxy.android.proxy.StatsSnapshot
import io.github.tgwsproxy.android.proxy.buildTelegramProxyLink
import io.github.tgwsproxy.android.proxy.humanBytes
import io.github.tgwsproxy.android.service.ProxyController
import io.github.tgwsproxy.android.service.ProxyForegroundService
import io.github.tgwsproxy.android.update.AppUpdater
import io.github.tgwsproxy.android.update.ReleaseInfo
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun ConnectionScreen(strings: AppStrings) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val settings by ProxyController.settings.collectAsStateWithLifecycle()
    val isRunning by ProxyController.isRunning.collectAsStateWithLifecycle()
    val isTransitioning by ProxyController.isTransitioning.collectAsStateWithLifecycle()
    val lastError by ProxyController.lastError.collectAsStateWithLifecycle()
    val stats by ProxyController.statsSnapshot.collectAsStateWithLifecycle()
    val uptimeSec by ProxyController.uptimeSeconds.collectAsStateWithLifecycle()
    val activeConfig by ProxyController.currentConfig.collectAsStateWithLifecycle()
    val availableRelease by AppUpdater.availableRelease.collectAsStateWithLifecycle()

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
                ProxyForegroundService.start(context)
            }
        }
    }

    val openTelegram = {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(proxyLink)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            copyToClipboard(context, "TG Proxy Link", proxyLink)
            Toast.makeText(context, strings.toastTelegramMissingCopied, Toast.LENGTH_LONG).show()
        }
    }

    val copyLink = {
        copyToClipboard(context, "TG Proxy Link", proxyLink)
        Toast.makeText(context, strings.toastLinkCopied, Toast.LENGTH_SHORT).show()
    }

    val bgBrush = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.background,
        ),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush),
    ) {
        AnimatedContent(
            targetState = settings.simpleMode,
            transitionSpec = {
                fadeIn(tween(220)) togetherWith fadeOut(tween(180))
            },
            label = "interfaceMode",
        ) { isSimple ->
            if (isSimple) {
                SimpleConnectionView(
                    strings = strings,
                    settings = settings,
                    isRunning = isRunning,
                    isTransitioning = isTransitioning,
                    lastError = lastError,
                    ignoringBattery = ignoringBattery,
                    availableRelease = availableRelease,
                    onToggleProxy = toggleProxy,
                    onOpenTelegram = openTelegram,
                    onCopyLink = copyLink,
                )
            } else {
                ExpertConnectionView(
                    strings = strings,
                    settings = settings,
                    host = effectiveConfig.host,
                    port = effectiveConfig.port,
                    isRunning = isRunning,
                    isTransitioning = isTransitioning,
                    lastError = lastError,
                    stats = stats,
                    uptimeSec = uptimeSec,
                    ignoringBattery = ignoringBattery,
                    availableRelease = availableRelease,
                    onToggleProxy = toggleProxy,
                    onOpenTelegram = openTelegram,
                    onCopyLink = copyLink,
                )
            }
        }
    }
}

@Composable
private fun SimpleConnectionView(
    strings: AppStrings,
    settings: AppSettings,
    isRunning: Boolean,
    isTransitioning: Boolean,
    lastError: String?,
    ignoringBattery: Boolean,
    availableRelease: ReleaseInfo?,
    onToggleProxy: () -> Unit,
    onOpenTelegram: () -> Unit,
    onCopyLink: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isCheckingUpdate by AppUpdater.isChecking.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (availableRelease != null) {
            UpdateAvailableBanner(
                release = availableRelease,
                strings = strings,
                onClick = { AppUpdater.openDialog() },
            )
        }

        if (!lastError.isNullOrBlank()) {
            ErrorBanner(lastError = lastError, closeDesc = strings.btnClose)
        }

        Spacer(Modifier.height(4.dp))

        HeroPowerOrb(
            isRunning = isRunning,
            isTransitioning = isTransitioning,
            stageSize = 196.dp,
            orbSize = 132.dp,
            onClick = onToggleProxy,
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(
                text = when {
                    isTransitioning -> strings.simpleHeroWorkingTitle
                    isRunning -> strings.simpleHeroRunningTitle
                    else -> strings.simpleHeroStoppedTitle
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (isRunning) strings.simpleHeroRunningSub else strings.simpleHeroStoppedSub,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        // Primary Telegram connect action + subtle copy button
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val gradient = Brush.horizontalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.secondary,
                    ),
                )
                Button(
                    onClick = onOpenTelegram,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(gradient, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(19.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = strings.simpleConnectTgBtn,
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                FilledTonalButton(
                    onClick = onCopyLink,
                    modifier = Modifier.size(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = strings.btnCopyLink)
                }
            }

            Text(
                text = strings.simpleConnectTgHint,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        // Single non-technical rescue toggle card ("Усиленный обход" -> disableSecure)
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = if (settings.disableSecure) {
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Surface(
                    color = if (settings.disableSecure) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = if (settings.disableSecure) {
                                MaterialTheme.colorScheme.onTertiary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = strings.simpleBoostTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (settings.disableSecure) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiary,
                                shape = RoundedCornerShape(999.dp),
                            ) {
                                Text(
                                    text = strings.simpleBoostActiveBadge,
                                    color = MaterialTheme.colorScheme.onTertiary,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    Text(
                        text = strings.simpleBoostSub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Switch(
                    checked = settings.disableSecure,
                    onCheckedChange = { ProxyController.setDisableSecureAsync(it) },
                )
            }
        }

        if (!ignoringBattery) {
            BatteryOptimizationCard(
                title = strings.simpleBatteryTitle,
                body = strings.simpleBatterySub,
                actionLabel = strings.batteryAction,
            )
        }

        // Compact quick controls bar (Language, Theme, Palette, Check Update)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuickFooterPill(
                    label = if (settings.language == "ru") "RU" else "EN",
                    onClick = { ProxyController.toggleLanguageAsync() },
                )

                val themeIcon = when (settings.themeMode) {
                    "dark" -> Icons.Default.DarkMode
                    "light" -> Icons.Default.LightMode
                    else -> Icons.Default.BrightnessAuto
                }
                QuickFooterIconPill(
                    icon = themeIcon,
                    label = when (settings.themeMode) {
                        "dark" -> strings.themeDark
                        "light" -> strings.themeLight
                        else -> strings.themeAuto
                    },
                    onClick = { ProxyController.cycleThemeAsync() },
                )

                val palettePreview = AvailablePalettes.firstOrNull { it.id == settings.colorPalette }?.previewColor
                    ?: MaterialTheme.colorScheme.primary
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.clickable { ProxyController.cyclePaletteAsync() },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            Icons.Default.Palette,
                            contentDescription = strings.labelPalette,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(palettePreview),
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (availableRelease != null) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    modifier = Modifier.clickable(enabled = !isCheckingUpdate) {
                        if (availableRelease != null) {
                            AppUpdater.openDialog()
                        } else {
                            scope.launch {
                                val found = AppUpdater.checkForUpdates(
                                    currentVersion = BuildConfig.VERSION_NAME,
                                    manual = true,
                                )
                                if (found == null) {
                                    val msg = if (AppUpdater.lastCheckStatus.value == "error") {
                                        strings.toastUpdateCheckFailed
                                    } else {
                                        strings.toastUpToDate
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Default.SystemUpdate,
                                contentDescription = strings.simpleCheckUpdateLabel,
                                modifier = Modifier.size(15.dp),
                                tint = if (availableRelease != null) {
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Text(
                            text = if (availableRelease != null) {
                                "v${availableRelease.version}"
                            } else {
                                "v${BuildConfig.VERSION_NAME}"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (availableRelease != null) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpertConnectionView(
    strings: AppStrings,
    settings: AppSettings,
    host: String,
    port: Int,
    isRunning: Boolean,
    isTransitioning: Boolean,
    lastError: String?,
    stats: StatsSnapshot,
    uptimeSec: Long,
    ignoringBattery: Boolean,
    availableRelease: ReleaseInfo?,
    onToggleProxy: () -> Unit,
    onOpenTelegram: () -> Unit,
    onCopyLink: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (availableRelease != null) {
            UpdateAvailableBanner(
                release = availableRelease,
                strings = strings,
                onClick = { AppUpdater.openDialog() },
            )
        }

        if (!lastError.isNullOrBlank()) {
            ErrorBanner(lastError = lastError, closeDesc = strings.btnClose)
        }

        // Expressive Status Hero Card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    HeroPowerOrb(
                        isRunning = isRunning,
                        isTransitioning = isTransitioning,
                        stageSize = 104.dp,
                        orbSize = 76.dp,
                        onClick = onToggleProxy,
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = when {
                                    isTransitioning -> strings.statusStarting
                                    isRunning -> strings.statusRunning
                                    else -> strings.statusStopped
                                },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(
                                    text = "$host:$port",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                            if (settings.disableSecure) {
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Text(
                                        text = strings.disableTlsBadge,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                            }
                        }

                        if (isRunning) {
                            Text(
                                text = "${strings.uptimeLabel}: ${formatUptime(uptimeSec)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Gradient Traffic Cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TrafficGradientCard(
                        label = strings.trafficUp,
                        value = humanBytes(stats.bytesUp),
                        icon = Icons.Default.ArrowUpward,
                        accent = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TrafficGradientCard(
                        label = strings.trafficDown,
                        value = humanBytes(stats.bytesDown),
                        icon = Icons.Default.ArrowDownward,
                        accent = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MiniStatChip(strings.statActive, stats.connectionsActive.toString(), Modifier.weight(1f))
                    MiniStatChip(strings.statWs, stats.connectionsWs.toString(), Modifier.weight(1f))
                    MiniStatChip(strings.statH2, stats.connectionsH2.toString(), Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MiniStatChip(strings.statCf, stats.connectionsCfproxy.toString(), Modifier.weight(1f))
                    MiniStatChip(strings.statTotal, stats.connectionsTotal.toString(), Modifier.weight(1f))
                    MiniStatChip(strings.statErrors, stats.wsErrors.toString(), Modifier.weight(1f))
                }
            }
        }

        // Start/Stop + Telegram action buttons
        Button(
            onClick = onToggleProxy,
            enabled = !isTransitioning,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = if (isRunning) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            } else {
                ButtonDefaults.buttonColors()
            },
        ) {
            if (isTransitioning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = when {
                    isTransitioning -> strings.statusStarting
                    isRunning -> strings.btnStop
                    else -> strings.btnStart
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onOpenTelegram,
                modifier = Modifier.weight(1.35f),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(strings.btnOpenTelegram, maxLines = 1)
            }

            OutlinedButton(
                onClick = onCopyLink,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(strings.btnCopyLink, maxLines = 1)
            }
        }

        // Technical Quick Toggles card (Disable TLS + CF Proxy + H2)
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = strings.quickSettingsTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                QuickSwitchRow(
                    title = strings.disableTlsTitle,
                    subtitle = strings.disableTlsSubtitle,
                    checked = settings.disableSecure,
                    badge = if (settings.disableSecure) strings.disableTlsBadge else null,
                    onCheckedChange = { ProxyController.setDisableSecureAsync(it) },
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                QuickSwitchRow(
                    title = strings.cfProxyTitle,
                    subtitle = strings.cfProxySubtitle,
                    checked = settings.cfproxyEnabled,
                    onCheckedChange = { ProxyController.setCfProxyEnabledAsync(it) },
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                val h2Available = settings.cfproxyEnabled && !settings.disableSecure && !settings.forceTestDc
                QuickSwitchRow(
                    title = strings.h2MediaTitle,
                    subtitle = if (h2Available) strings.h2MediaSubtitle else strings.h2DisabledByTlsHint,
                    checked = settings.cfproxyH2Media && h2Available,
                    enabled = h2Available,
                    onCheckedChange = { ProxyController.setH2MediaEnabledAsync(it) },
                )
            }
        }

        if (!ignoringBattery) {
            BatteryOptimizationCard(
                title = strings.batteryTitle,
                body = strings.batteryBody,
                actionLabel = strings.batteryAction,
            )
        }
    }
}

@Composable
private fun HeroPowerOrb(
    isRunning: Boolean,
    isTransitioning: Boolean,
    stageSize: Dp,
    orbSize: Dp,
    onClick: () -> Unit,
) {
    val palette = rememberHeroPalette(isRunning = isRunning, isTransitioning = isTransitioning)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "orbScale",
    )

    val infinite = rememberInfiniteTransition(label = "orbPulse")
    val pulse1 by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse1",
    )
    val sweepAngle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )

    val c0 by animateColorAsState(palette.orbGradient[0], tween(400), label = "orbC0")
    val c1 by animateColorAsState(palette.orbGradient[1], tween(400), label = "orbC1")
    val ringColor by animateColorAsState(palette.ringColor, tween(400), label = "ringColor")
    val glowColor by animateColorAsState(palette.glowColor, tween(400), label = "glowColor")

    Box(
        modifier = Modifier
            .size(stageSize)
            .scale(scale),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val coreRadius = orbSize.toPx() / 2f
            val maxRadius = size.minDimension / 2f

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glowColor, Color.Transparent),
                    center = center,
                    radius = maxRadius,
                ),
                radius = maxRadius,
                center = center,
            )

            if (isRunning || isTransitioning) {
                val r1 = coreRadius + (maxRadius - coreRadius) * pulse1
                drawCircle(
                    color = ringColor.copy(alpha = (1f - pulse1) * 0.45f),
                    radius = r1,
                    center = center,
                    style = Stroke(width = 2.5.dp.toPx()),
                )

                val p2 = (pulse1 + 0.5f) % 1f
                val r2 = coreRadius + (maxRadius - coreRadius) * p2
                drawCircle(
                    color = ringColor.copy(alpha = (1f - p2) * 0.35f),
                    radius = r2,
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )

                val arcRadius = coreRadius + 6.dp.toPx()
                drawArc(
                    color = ringColor.copy(alpha = 0.85f),
                    startAngle = sweepAngle,
                    sweepAngle = 95f,
                    useCenter = false,
                    topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                    size = Size(arcRadius * 2f, arcRadius * 2f),
                    style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round),
                )
            } else {
                drawCircle(
                    color = ringColor.copy(alpha = 0.3f),
                    radius = coreRadius + 6.dp.toPx(),
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        Box(
            modifier = Modifier
                .size(orbSize)
                .clip(CircleShape)
                .background(Brush.linearGradient(colors = listOf(c0, c1)))
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = !isTransitioning,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isTransitioning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(orbSize * 0.36f),
                    color = Color.White,
                    strokeWidth = 3.dp,
                )
            } else {
                Icon(
                    imageVector = if (isRunning) Icons.Default.Shield else Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    tint = if (isRunning) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(orbSize * 0.42f),
                )
            }
        }
    }
}

@Composable
private fun TrafficGradientCard(
    label: String,
    value: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = accent.copy(alpha = 0.11f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                color = accent.copy(alpha = 0.2f),
                shape = CircleShape,
                modifier = Modifier.size(32.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                }
            }
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun UpdateAvailableBanner(
    release: ReleaseInfo,
    strings: AppStrings,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    Icons.Default.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = "${strings.updateDialogTitle}: v${release.version}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.tertiary,
                shape = RoundedCornerShape(999.dp),
            ) {
                Text(
                    text = strings.simpleUpdateAvailableChip,
                    color = MaterialTheme.colorScheme.onTertiary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(lastError: String, closeDesc: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = lastError,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { ProxyController.clearError() }) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = closeDesc,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun BatteryOptimizationCard(
    title: String,
    body: String,
    actionLabel: String,
) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.BatteryChargingFull,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            OutlinedButton(
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    )
                    runCatching { context.startActivity(intent) }
                },
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun QuickFooterPill(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun QuickFooterIconPill(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(15.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun QuickSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    badge: String? = null,
    onCheckedChange: (Boolean) -> Unit,
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = badge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun MiniStatChip(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
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

fun copyToClipboard(context: Context, label: String, text: String) {
    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cb.setPrimaryClip(ClipData.newPlainText(label, text))
}
