package io.github.tgwsproxy.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tgwsproxy.android.service.ProxyController
import io.github.tgwsproxy.android.ui.AmbientAuroraBackground
import io.github.tgwsproxy.android.ui.ConnectionScreen
import io.github.tgwsproxy.android.ui.InfoScreen
import io.github.tgwsproxy.android.ui.LogsScreen
import io.github.tgwsproxy.android.ui.SettingsScreen
import io.github.tgwsproxy.android.ui.TgWsProxyTheme
import io.github.tgwsproxy.android.ui.UpdateDialog
import io.github.tgwsproxy.android.ui.stringsFor
import io.github.tgwsproxy.android.update.AppUpdater

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProxyController.init(applicationContext)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        setContent {
            TgWsProxyRoot()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Composable
private fun TgWsProxyRoot() {
    val settings by ProxyController.settings.collectAsStateWithLifecycle()
    val isRunning by ProxyController.isRunning.collectAsStateWithLifecycle()
    val isTransitioning by ProxyController.isTransitioning.collectAsStateWithLifecycle()
    val availableRelease by AppUpdater.availableRelease.collectAsStateWithLifecycle()
    val showUpdateDialog by AppUpdater.showDialog.collectAsStateWithLifecycle()

    val strings = stringsFor(settings.language)
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var didStartupUpdateCheck by remember { mutableStateOf(false) }

    LaunchedEffect(settings.secret) {
        if (!didStartupUpdateCheck && settings.secret.isNotEmpty()) {
            didStartupUpdateCheck = true
            if (settings.checkUpdatesOnStartup) {
                AppUpdater.checkForUpdates(
                    currentVersion = BuildConfig.VERSION_NAME,
                    manual = false,
                    postponedVersion = settings.postponedUpdateVersion,
                    postponedUntilMs = settings.postponedUpdateUntilMs,
                )
            }
        }
    }

    LaunchedEffect(settings.simpleMode) {
        if (settings.simpleMode) {
            selectedTab = 0
        }
    }

    TgWsProxyTheme(
        themeMode = settings.themeMode,
        colorPalette = settings.colorPalette,
        dynamicColors = settings.dynamicColors,
    ) {
        AmbientAuroraBackground(
            isRunning = isRunning,
            isTransitioning = isTransitioning,
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    CyberTopBar(
                        title = strings.appName,
                        isRunning = isRunning,
                        isTransitioning = isTransitioning,
                        hasUpdate = availableRelease != null,
                        updateDesc = strings.updateDialogTitle,
                        isSimple = settings.simpleMode,
                        simpleLabel = strings.modeSimple,
                        expertLabel = strings.modeExpert,
                        onOpenUpdate = { AppUpdater.openDialog() },
                        onSelectMode = { ProxyController.setSimpleModeAsync(it) },
                    )
                },
                bottomBar = {
                    AnimatedVisibility(
                        visible = !settings.simpleMode,
                        enter = fadeIn(tween(200)) + slideInVertically { it / 2 },
                        exit = fadeOut(tween(150)) + slideOutVertically { it / 2 },
                    ) {
                        FloatingGlassDock(
                            selectedTab = selectedTab,
                            onSelectTab = { selectedTab = it },
                            items = listOf(
                                DockItem(Icons.Default.PowerSettingsNew, strings.navConnection),
                                DockItem(Icons.Default.Settings, strings.navSettings),
                                DockItem(Icons.AutoMirrored.Filled.ListAlt, strings.navLogs),
                                DockItem(Icons.Default.Info, strings.navInfo),
                            ),
                        )
                    }
                },
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    val tabToRender = if (settings.simpleMode) 0 else selectedTab
                    when (tabToRender) {
                        0 -> ConnectionScreen(strings)
                        1 -> SettingsScreen(strings)
                        2 -> LogsScreen(strings)
                        else -> InfoScreen(strings)
                    }
                }
            }

            val release = availableRelease
            if (showUpdateDialog && release != null) {
                UpdateDialog(
                    release = release,
                    strings = strings,
                )
            }
        }
    }
}

@Composable
private fun CyberTopBar(
    title: String,
    isRunning: Boolean,
    isTransitioning: Boolean,
    hasUpdate: Boolean,
    updateDesc: String,
    isSimple: Boolean,
    simpleLabel: String,
    expertLabel: String,
    onOpenUpdate: () -> Unit,
    onSelectMode: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val dotColor by animateColorAsState(
        targetValue = when {
            isTransitioning -> cs.secondary
            isRunning -> cs.tertiary
            else -> cs.outline
        },
        animationSpec = tween(350),
        label = "statusDot",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(cs.primary, cs.secondary),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    tint = cs.onPrimary,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (hasUpdate) {
                IconButton(
                    onClick = onOpenUpdate,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = updateDesc,
                        tint = cs.tertiary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            InterfaceModePill(
                isSimple = isSimple,
                simpleLabel = simpleLabel,
                expertLabel = expertLabel,
                onSelect = onSelectMode,
            )
        }
    }
}

private data class DockItem(
    val icon: ImageVector,
    val label: String,
)

@Composable
private fun FloatingGlassDock(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    items: List<DockItem>,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.background.luminance() < 0.5f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            color = cs.surface.copy(alpha = if (isDark) 0.88f else 0.94f),
            border = BorderStroke(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        cs.primary.copy(alpha = 0.28f),
                        cs.outline.copy(alpha = 0.16f),
                    ),
                ),
            ),
            shadowElevation = 10.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, item ->
                    val selected = selectedTab == index
                    val bg = if (selected) cs.primary.copy(alpha = 0.16f) else Color.Transparent
                    val fg = if (selected) cs.primary else cs.onSurfaceVariant

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(bg)
                            .clickable { onSelectTab(index) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = fg,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = item.label,
                            color = fg,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InterfaceModePill(
    isSimple: Boolean,
    simpleLabel: String,
    expertLabel: String,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.background.luminance() < 0.5f

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = cs.surface.copy(alpha = if (isDark) 0.78f else 0.88f),
        border = BorderStroke(1.dp, cs.outline.copy(alpha = 0.24f)),
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ModeSegment(
                text = simpleLabel,
                selected = isSimple,
                onClick = { onSelect(true) },
            )
            ModeSegment(
                text = expertLabel,
                selected = !isSimple,
                onClick = { onSelect(false) },
            )
        }
    }
}

@Composable
private fun ModeSegment(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val bg = if (selected) cs.primary else Color.Transparent
    val fg = if (selected) cs.onPrimary else cs.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
