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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tgwsproxy.android.BuildConfig
import io.github.tgwsproxy.android.data.AppSettings
import io.github.tgwsproxy.android.proxy.ProxyConfig
import io.github.tgwsproxy.android.proxy.StatsSnapshot
import io.github.tgwsproxy.android.proxy.buildTelegramProxyLink
import io.github.tgwsproxy.android.proxy.humanBytes
import io.github.tgwsproxy.android.service.ProxyController
import io.github.tgwsproxy.android.service.ProxyForegroundService
import io.github.tgwsproxy.android.update.AppUpdater
import io.github.tgwsproxy.android.update.ReleaseInfo
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

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
            copyToClipboard(context, "TG Proxy Link", proxyLink, isSensitive = true)
            Toast.makeText(context, strings.toastTelegramMissingCopied, Toast.LENGTH_LONG).show()
        }
    }

    val copyLink = {
        copyToClipboard(context, "TG Proxy Link", proxyLink, isSensitive = true)
        Toast.makeText(context, strings.toastLinkCopied, Toast.LENGTH_SHORT).show()
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                    effectiveConfig = effectiveConfig,
                    isRunning = isRunning,
                    isTransitioning = isTransitioning,
                    uptimeSec = uptimeSec,
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
                    effectiveConfig = effectiveConfig,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimpleConnectionView(
    strings: AppStrings,
    settings: AppSettings,
    effectiveConfig: ProxyConfig,
    isRunning: Boolean,
    isTransitioning: Boolean,
    uptimeSec: Long,
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
    val cs = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 10.dp),
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

        // Compact live connection status capsule
        val statusAccent = when {
            isTransitioning -> cs.secondary
            isRunning -> cs.tertiary
            else -> cs.outline
        }
        val capsuleText = when {
            isTransitioning -> strings.simpleHeroWorkingTitle
            isRunning -> {
                val routeTag = if (settings.disableSecure) ":80 HTTP" else ":${effectiveConfig.port}"
                "${strings.simpleHeroRunningTitle} • $routeTag • ${formatUptime(uptimeSec)}"
            }
            else -> strings.simpleHeroStoppedTitle
        }
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = statusAccent.copy(alpha = 0.14f),
            contentColor = cs.onSurface,
            border = BorderStroke(1.dp, statusAccent.copy(alpha = 0.36f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusAccent),
                )
                Text(
                    text = capsuleText,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = if (isRunning) FontFamily.Monospace else FontFamily.Default,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Precision reactor centerpiece
        HeroReactorButton(
            isRunning = isRunning,
            isTransitioning = isTransitioning,
            stageSize = 212.dp,
            orbSize = 142.dp,
            onClick = onToggleProxy,
        )

        // Clean title + subtitle + explicit pill CTA
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(
                text = when {
                    isTransitioning -> strings.simpleHeroWorkingTitle
                    isRunning -> strings.simpleHeroRunningTitle
                    else -> strings.simpleHeroStoppedTitle
                },
                color = cs.onBackground,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (isRunning) strings.simpleHeroRunningSub else strings.simpleHeroStoppedSub,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(2.dp))
            FilledTonalButton(
                onClick = onToggleProxy,
                enabled = !isTransitioning,
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when {
                        isTransitioning -> strings.simpleHeroWorkingTitle
                        isRunning -> strings.simpleTapToDisconnect
                        else -> strings.simpleTapToConnect
                    },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Primary Telegram connect action + glass copy button
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
                    colors = listOf(cs.primary, cs.secondary),
                )
                Button(
                    onClick = onOpenTelegram,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 54.dp),
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 54.dp)
                            .background(gradient, RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                tint = cs.onPrimary,
                                modifier = Modifier.size(19.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = strings.simpleConnectTgBtn,
                                color = cs.onPrimary,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = onCopyLink,
                    modifier = Modifier.size(54.dp),
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(0.dp),
                    border = BorderStroke(1.dp, cs.outline.copy(alpha = 0.28f)),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = strings.btnCopyLink)
                }
            }

            Text(
                text = strings.simpleConnectTgHint,
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        // Single non-technical port 80 toggle card ("Резервный канал")
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            accentColor = if (settings.disableSecure) cs.tertiary else null,
            shape = RoundedCornerShape(22.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Surface(
                    color = if (settings.disableSecure) cs.tertiary else cs.surfaceVariant,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = if (settings.disableSecure) cs.onTertiary else cs.onSurfaceVariant,
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
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = strings.simpleBoostTitle,
                            color = cs.onSurface,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (settings.disableSecure) {
                            Surface(
                                color = cs.tertiary,
                                shape = RoundedCornerShape(999.dp),
                            ) {
                                Text(
                                    text = strings.simpleBoostActiveBadge,
                                    color = cs.onTertiary,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    Text(
                        text = strings.simpleBoostSub,
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

        if (!ignoringBattery) {
            BatteryOptimizationCard(
                title = strings.simpleBatteryTitle,
                body = strings.simpleBatterySub,
                actionLabel = strings.batteryAction,
            )
        }

        // Glassmorphic quick-controls bar
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
                    ?: cs.primary
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = cs.surfaceVariant.copy(alpha = 0.7f),
                    contentColor = cs.onSurface,
                    border = BorderStroke(1.dp, cs.outline.copy(alpha = 0.2f)),
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
                            tint = cs.primary,
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
                        cs.tertiaryContainer
                    } else {
                        cs.surfaceVariant.copy(alpha = 0.7f)
                    },
                    contentColor = if (availableRelease != null) cs.onTertiaryContainer else cs.onSurface,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (availableRelease != null) {
                            cs.tertiary.copy(alpha = 0.45f)
                        } else {
                            cs.outline.copy(alpha = 0.2f)
                        },
                    ),
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
                                tint = if (availableRelease != null) cs.onTertiaryContainer else cs.onSurfaceVariant,
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
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            color = if (availableRelease != null) cs.onTertiaryContainer else cs.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpertConnectionView(
    strings: AppStrings,
    settings: AppSettings,
    effectiveConfig: ProxyConfig,
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
    val cs = MaterialTheme.colorScheme
    val host = effectiveConfig.host
    val port = effectiveConfig.port

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

        // Expressive Telemetry Glass Hero Card
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            accentColor = if (isRunning) cs.tertiary else null,
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    HeroReactorButton(
                        isRunning = isRunning,
                        isTransitioning = isTransitioning,
                        stageSize = 94.dp,
                        orbSize = 68.dp,
                        onClick = onToggleProxy,
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = when {
                                isTransitioning -> strings.statusStarting
                                isRunning -> strings.statusRunning
                                else -> strings.statusStopped
                            },
                            color = cs.onSurface,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            TelemetryBadge(
                                text = "$host:$port",
                                bg = cs.surfaceVariant.copy(alpha = 0.85f),
                                fg = cs.onSurface,
                                mono = true,
                            )
                            if (settings.disableSecure) {
                                TelemetryBadge(
                                    text = strings.disableTlsBadge,
                                    bg = cs.tertiaryContainer,
                                    fg = cs.onTertiaryContainer,
                                    bold = true,
                                )
                            }
                            if (effectiveConfig.fakeTlsDomain.isNotBlank()) {
                                TelemetryBadge(
                                    text = "FakeTLS",
                                    bg = cs.secondaryContainer,
                                    fg = cs.onSecondaryContainer,
                                    bold = true,
                                )
                            }
                            if (effectiveConfig.h2Enabled) {
                                TelemetryBadge(
                                    text = "HTTP/2",
                                    bg = cs.primaryContainer,
                                    fg = cs.onPrimaryContainer,
                                    bold = true,
                                )
                            }
                        }

                        if (isRunning) {
                            Text(
                                text = "${strings.uptimeLabel}: ${formatUptime(uptimeSec)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = cs.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // Live Route Topology Strip
                RouteTopologyStrip(
                    title = strings.routePipelineTitle,
                    stats = stats,
                    config = effectiveConfig,
                )

                // Traffic Cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TrafficGradientCard(
                        label = strings.trafficUp,
                        value = humanBytes(stats.bytesUp),
                        icon = Icons.Default.ArrowUpward,
                        accent = cs.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TrafficGradientCard(
                        label = strings.trafficDown,
                        value = humanBytes(stats.bytesDown),
                        icon = Icons.Default.ArrowDownward,
                        accent = cs.tertiary,
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
                .heightIn(min = 52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = if (isRunning) {
                ButtonDefaults.buttonColors(
                    containerColor = cs.error,
                    contentColor = cs.onError,
                )
            } else {
                ButtonDefaults.buttonColors()
            },
        ) {
            if (isTransitioning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = cs.onPrimary,
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
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onOpenTelegram,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = strings.btnOpenTelegram,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            OutlinedButton(
                onClick = onCopyLink,
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(0.dp),
                border = BorderStroke(1.dp, cs.outline.copy(alpha = 0.3f)),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = strings.btnCopyLink, modifier = Modifier.size(20.dp))
            }
        }

        // Technical Quick Toggles card
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = strings.quickSettingsTitle,
                    color = cs.onSurface,
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

                HorizontalDivider(color = cs.outline.copy(alpha = 0.16f))

                QuickSwitchRow(
                    title = strings.cfProxyTitle,
                    subtitle = strings.cfProxySubtitle,
                    checked = settings.cfproxyEnabled,
                    onCheckedChange = { ProxyController.setCfProxyEnabledAsync(it) },
                )

                HorizontalDivider(color = cs.outline.copy(alpha = 0.16f))

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
private fun HeroReactorButton(
    isRunning: Boolean,
    isTransitioning: Boolean,
    stageSize: Dp,
    orbSize: Dp,
    onClick: () -> Unit,
) {
    val palette = rememberHeroPalette(isRunning = isRunning, isTransitioning = isTransitioning)
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "orbScale",
    )

    val infinite = rememberInfiniteTransition(label = "reactorPulse")
    val pulse by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse",
    )
    val sweepAngle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = LinearEasing),
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

            // 36 radial precision instrument ticks around the dial
            val tickOuter = maxRadius - 2.dp.toPx()
            val tickCount = 36
            for (i in 0 until tickCount) {
                val deg = i * (360f / tickCount)
                val rad = deg * (PI / 180.0)
                val isMajor = i % 3 == 0
                val inner = tickOuter - (if (isMajor) 7.dp.toPx() else 4.dp.toPx())
                val start = Offset(
                    x = center.x + (inner * cos(rad)).toFloat(),
                    y = center.y + (inner * sin(rad)).toFloat(),
                )
                val end = Offset(
                    x = center.x + (tickOuter * cos(rad)).toFloat(),
                    y = center.y + (tickOuter * sin(rad)).toFloat(),
                )
                val alpha = if (isRunning || isTransitioning) {
                    val delta = abs(((deg - sweepAngle + 540f) % 360f) - 180f)
                    if (delta < 75f) 0.90f - (delta / 75f) * 0.65f else if (isMajor) 0.30f else 0.14f
                } else {
                    if (isMajor) 0.24f else 0.11f
                }
                drawLine(
                    color = ringColor.copy(alpha = alpha),
                    start = start,
                    end = end,
                    strokeWidth = if (isMajor) 2.dp.toPx() else 1.2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            if (isRunning || isTransitioning) {
                val pulseRadius = coreRadius + (tickOuter - 10.dp.toPx() - coreRadius) * pulse
                drawCircle(
                    color = ringColor.copy(alpha = (1f - pulse) * 0.42f),
                    radius = pulseRadius,
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )

                val arcRadius = coreRadius + 6.dp.toPx()
                drawArc(
                    color = ringColor.copy(alpha = 0.90f),
                    startAngle = sweepAngle,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                    size = Size(arcRadius * 2f, arcRadius * 2f),
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
                val outerArcRadius = coreRadius + 12.dp.toPx()
                if (outerArcRadius < tickOuter - 6.dp.toPx()) {
                    drawArc(
                        color = cs.primary.copy(alpha = 0.55f),
                        startAngle = 360f - sweepAngle * 0.7f,
                        sweepAngle = 70f,
                        useCenter = false,
                        topLeft = Offset(center.x - outerArcRadius, center.y - outerArcRadius),
                        size = Size(outerArcRadius * 2f, outerArcRadius * 2f),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            } else {
                drawCircle(
                    color = ringColor.copy(alpha = 0.28f),
                    radius = coreRadius + 6.dp.toPx(),
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }

        val specularBorder = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = if (isRunning) 0.50f else 0.22f),
                ringColor.copy(alpha = 0.18f),
            ),
        )
        Box(
            modifier = Modifier
                .size(orbSize)
                .clip(CircleShape)
                .background(cs.surface, CircleShape)
                .background(Brush.linearGradient(colors = listOf(c0, c1)), CircleShape)
                .border(1.5.dp, specularBorder, CircleShape)
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
                    modifier = Modifier.size(orbSize * 0.34f),
                    color = Color.White,
                    strokeWidth = 3.dp,
                )
            } else {
                Icon(
                    imageVector = if (isRunning) Icons.Default.Shield else Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    tint = if (isRunning) Color.White else cs.primary,
                    modifier = Modifier.size(orbSize * 0.40f),
                )
            }
        }
    }
}

@Composable
private fun TelemetryBadge(
    text: String,
    bg: Color,
    fg: Color,
    mono: Boolean = false,
    bold: Boolean = false,
) {
    Surface(
        color = bg,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = text,
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouteTopologyStrip(
    title: String,
    stats: StatsSnapshot,
    config: ProxyConfig,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = cs.surfaceVariant.copy(alpha = 0.42f),
        contentColor = cs.onSurface,
        border = BorderStroke(1.dp, cs.outline.copy(alpha = 0.14f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RouteNodePill(
                    label = "WS Direct",
                    active = stats.connectionsWs > 0 || config.dcRedirects.isNotEmpty(),
                    lit = stats.connectionsWs > 0,
                )
                RouteNodePill(
                    label = if (config.disableSecure) "CF :80" else "CF TLS",
                    active = config.fallbackCfproxy,
                    lit = stats.connectionsCfproxy > 0,
                )
                RouteNodePill(
                    label = "HTTP/2",
                    active = config.h2Enabled,
                    lit = stats.connectionsH2 > 0,
                )
                if (config.cfproxyWorkerDomains.isNotEmpty()) {
                    RouteNodePill(
                        label = "Worker",
                        active = true,
                        lit = stats.cfPoolHits > 0,
                    )
                }
            }
        }
    }
}

@Composable
private fun RouteNodePill(
    label: String,
    active: Boolean,
    lit: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val dotColor = when {
        lit -> cs.tertiary
        active -> cs.primary
        else -> cs.outline.copy(alpha = 0.5f)
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (lit) cs.tertiary.copy(alpha = 0.14f) else cs.surface.copy(alpha = 0.55f),
        contentColor = cs.onSurface,
        border = BorderStroke(
            width = 1.dp,
            color = if (lit) cs.tertiary.copy(alpha = 0.45f) else cs.outline.copy(alpha = 0.18f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) cs.onSurface else cs.onSurfaceVariant,
                fontWeight = if (lit) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
            )
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
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.25f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                color = accent.copy(alpha = 0.2f),
                shape = CircleShape,
                modifier = Modifier.size(28.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = value,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
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
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = cs.tertiaryContainer,
        border = BorderStroke(1.dp, cs.tertiary.copy(alpha = 0.4f)),
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
                    tint = cs.onTertiaryContainer,
                )
                Text(
                    text = "${strings.updateDialogTitle}: v${release.version}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onTertiaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                color = cs.tertiary,
                shape = RoundedCornerShape(999.dp),
            ) {
                Text(
                    text = strings.simpleUpdateAvailableChip,
                    color = cs.onTertiary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(lastError: String, closeDesc: String) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.errorContainer,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, cs.error.copy(alpha = 0.45f)),
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
                tint = cs.onErrorContainer,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = lastError,
                color = cs.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { ProxyController.clearError() }) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = closeDesc,
                    tint = cs.onErrorContainer,
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
    val cs = MaterialTheme.colorScheme
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        accentColor = cs.secondary,
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.BatteryChargingFull,
                    contentDescription = null,
                    tint = cs.secondary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                )
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    )
                    runCatching { context.startActivity(intent) }
                },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = actionLabel,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun QuickFooterPill(label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = cs.surfaceVariant.copy(alpha = 0.7f),
        contentColor = cs.onSurface,
        border = BorderStroke(1.dp, cs.outline.copy(alpha = 0.2f)),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            color = cs.onSurface,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun QuickFooterIconPill(icon: ImageVector, label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = cs.surfaceVariant.copy(alpha = 0.7f),
        contentColor = cs.onSurface,
        border = BorderStroke(1.dp, cs.outline.copy(alpha = 0.2f)),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = label, tint = cs.primary, modifier = Modifier.size(15.dp))
            Text(
                text = label,
                color = cs.onSurface,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
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
                            maxLines = 1,
                            softWrap = false,
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
    val cs = MaterialTheme.colorScheme
    val isDark = cs.background.luminance() < 0.5f
    Surface(
        modifier = modifier,
        color = cs.surfaceVariant.copy(alpha = if (isDark) 0.55f else 0.72f),
        contentColor = cs.onSurface,
        border = BorderStroke(1.dp, cs.outline.copy(alpha = 0.14f)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                color = cs.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
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
