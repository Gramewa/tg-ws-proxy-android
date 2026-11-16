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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tgwsproxy.android.service.ProxyController
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TgWsProxyRoot() {
    val settings by ProxyController.settings.collectAsStateWithLifecycle()
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
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = strings.appName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    actions = {
                        if (availableRelease != null) {
                            IconButton(onClick = { AppUpdater.openDialog() }) {
                                Icon(
                                    imageVector = Icons.Default.SystemUpdate,
                                    contentDescription = strings.updateDialogTitle,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                        InterfaceModePill(
                            isSimple = settings.simpleMode,
                            simpleLabel = strings.modeSimple,
                            expertLabel = strings.modeExpert,
                            onSelect = { ProxyController.setSimpleModeAsync(it) },
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    },
                )
            },
            bottomBar = {
                AnimatedVisibility(visible = !settings.simpleMode) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = { Icon(Icons.Default.PowerSettingsNew, contentDescription = strings.navConnection) },
                            label = {
                                Text(
                                    text = strings.navConnection,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = { Icon(Icons.Default.Settings, contentDescription = strings.navSettings) },
                            label = {
                                Text(
                                    text = strings.navSettings,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                        NavigationBarItem(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            icon = { Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = strings.navLogs) },
                            label = {
                                Text(
                                    text = strings.navLogs,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                        NavigationBarItem(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3 },
                            icon = { Icon(Icons.Default.Info, contentDescription = strings.navInfo) },
                            label = {
                                Text(
                                    text = strings.navInfo,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
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

@Composable
private fun InterfaceModePill(
    isSimple: Boolean,
    simpleLabel: String,
    expertLabel: String,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
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
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
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
