package io.github.tgwsproxy.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tgwsproxy.android.data.AppSettings
import io.github.tgwsproxy.android.service.ProxyController
import io.github.tgwsproxy.android.service.ProxyForegroundService
import io.github.tgwsproxy.android.ui.AppStrings
import io.github.tgwsproxy.android.ui.ConnectionScreen
import io.github.tgwsproxy.android.ui.InfoScreen
import io.github.tgwsproxy.android.ui.LiveStatusDot
import io.github.tgwsproxy.android.ui.LogsScreen
import io.github.tgwsproxy.android.ui.SettingsScreen
import io.github.tgwsproxy.android.ui.TgWsProxyTheme
import io.github.tgwsproxy.android.ui.UpdateDialog
import io.github.tgwsproxy.android.ui.rememberRimBorder
import io.github.tgwsproxy.android.ui.stringsFor
import io.github.tgwsproxy.android.ui.velvet
import io.github.tgwsproxy.android.update.AppUpdater
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var askedNotificationPermThisSession = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            ProxyForegroundService.start(applicationContext)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProxyController.init(applicationContext)
        enableEdgeToEdge()

        setContent {
            TgWsProxyRoot(
                onRequestStartProxy = ::startProxyWithPermissionIfNeeded,
            )
        }
    }

    private fun startProxyWithPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedNotificationPermThisSession) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                askedNotificationPermThisSession = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        ProxyForegroundService.start(applicationContext)
    }
}

@Composable
private fun TgWsProxyRoot(
    onRequestStartProxy: () -> Unit,
) {
    val settings by ProxyController.settings.collectAsStateWithLifecycle()
    val isRunning by ProxyController.isRunning.collectAsStateWithLifecycle()
    val isTransitioning by ProxyController.isTransitioning.collectAsStateWithLifecycle()
    val availableRelease by AppUpdater.availableRelease.collectAsStateWithLifecycle()
    val showUpdateDialog by AppUpdater.showDialog.collectAsStateWithLifecycle()

    val strings = stringsFor(settings.language)
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showAppearanceSheet by rememberSaveable { mutableStateOf(false) }
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

    BackHandler(enabled = !settings.simpleMode && selectedTab != 0) {
        selectedTab = 0
    }

    TgWsProxyTheme(
        themeMode = settings.themeMode,
        dynamicColors = settings.dynamicColors,
    ) {
        val velvet = MaterialTheme.velvet

        Scaffold(
            containerColor = velvet.bg,
            contentColor = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxSize(),
            topBar = {
                VelvetTopBar(
                    title = strings.appName,
                    isRunning = isRunning,
                    isTransitioning = isTransitioning,
                    hasUpdate = availableRelease != null,
                    updateDesc = strings.updateDialogTitle,
                    appearanceDesc = strings.appearanceSheetTitle,
                    isSimple = settings.simpleMode,
                    simpleLabel = strings.modeSimple,
                    expertLabel = strings.modeExpert,
                    onOpenUpdate = { AppUpdater.openDialog() },
                    onOpenAppearance = { showAppearanceSheet = true },
                    onSelectMode = { ProxyController.setSimpleModeAsync(it) },
                )
            },
            bottomBar = {
                AnimatedVisibility(
                    visible = !settings.simpleMode,
                    enter = fadeIn(tween(180)) + slideInVertically { it / 2 },
                    exit = fadeOut(tween(140)) + slideOutVertically { it / 2 },
                ) {
                    VelvetBottomDock(
                        selectedTab = selectedTab,
                        onSelectTab = { selectedTab = it },
                        items = listOf(
                            DockItem(Icons.Rounded.PowerSettingsNew, strings.navConnection),
                            DockItem(Icons.Rounded.Settings, strings.navSettings),
                            DockItem(Icons.AutoMirrored.Rounded.ListAlt, strings.navLogs),
                            DockItem(Icons.AutoMirrored.Rounded.HelpOutline, strings.navInfo),
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
                    0 -> ConnectionScreen(
                        strings = strings,
                        onRequestStartProxy = onRequestStartProxy,
                    )
                    1 -> SettingsScreen(strings = strings)
                    2 -> LogsScreen(strings = strings)
                    else -> InfoScreen(strings = strings)
                }
            }
        }

        if (showAppearanceSheet) {
            AppearanceBottomSheet(
                strings = strings,
                settings = settings,
                onDismiss = { showAppearanceSheet = false },
            )
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
private fun VelvetTopBar(
    title: String,
    isRunning: Boolean,
    isTransitioning: Boolean,
    hasUpdate: Boolean,
    updateDesc: String,
    appearanceDesc: String,
    isSimple: Boolean,
    simpleLabel: String,
    expertLabel: String,
    onOpenUpdate: () -> Unit,
    onOpenAppearance: () -> Unit,
    onSelectMode: (Boolean) -> Unit,
) {
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = cs.onBackground,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(9.dp))
            LiveStatusDot(
                isRunning = isRunning,
                isTransitioning = isTransitioning,
                size = 7.dp,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hasUpdate) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = velvet.liveGreenSoft,
                    border = rememberRimBorder(velvet.liveGreen.copy(alpha = 0.28f)),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clickable(onClick = onOpenUpdate),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SystemUpdate,
                            contentDescription = updateDesc,
                            tint = velvet.liveGreen,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }

            InterfaceModePill(
                isSimple = isSimple,
                simpleLabel = simpleLabel,
                expertLabel = expertLabel,
                onSelect = onSelectMode,
            )

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = velvet.card,
                border = rememberRimBorder(velvet.rim),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clickable(onClick = onOpenAppearance),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = appearanceDesc,
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceBottomSheet(
    strings: AppStrings,
    settings: AppSettings,
    onDismiss: () -> Unit,
) {
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isCheckingUpdate by AppUpdater.isChecking.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = velvet.card,
        contentColor = cs.onSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = strings.appearanceSheetTitle,
                style = MaterialTheme.typography.titleLarge,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = strings.labelTheme,
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurfaceVariant,
                )
                SegmentedChoiceBar(
                    options = listOf(
                        "auto" to strings.themeAuto,
                        "light" to strings.themeLight,
                        "dark" to strings.themeDark,
                    ),
                    selectedKey = settings.themeMode,
                    onSelect = { ProxyController.setThemeModeAsync(it) },
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = velvet.cardSub,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { ProxyController.setDynamicColorsAsync(!settings.dynamicColors) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = strings.labelDynamicColors,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = strings.subDynamicColors,
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.dynamicColors,
                            onCheckedChange = { ProxyController.setDynamicColorsAsync(it) },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = strings.labelLanguage,
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurfaceVariant,
                )
                SegmentedChoiceBar(
                    options = listOf(
                        "ru" to strings.langRu,
                        "en" to strings.langEn,
                    ),
                    selectedKey = settings.language,
                    onSelect = { ProxyController.setLanguageAsync(it) },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
                FilledTonalButton(
                    enabled = !isCheckingUpdate,
                    onClick = {
                        scope.launch {
                            val found = AppUpdater.checkForUpdates(
                                currentVersion = BuildConfig.VERSION_NAME,
                                manual = true,
                                postponedVersion = settings.postponedUpdateVersion,
                                postponedUntilMs = settings.postponedUpdateUntilMs,
                            )
                            when {
                                found != null -> onDismiss()
                                AppUpdater.lastCheckStatus.value == "up_to_date" ->
                                    Toast.makeText(context, strings.toastUpToDate, Toast.LENGTH_SHORT).show()
                                else ->
                                    Toast.makeText(context, strings.toastUpdateCheckFailed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                ) {
                    if (isCheckingUpdate) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isCheckingUpdate) strings.checkingUpdates else strings.btnCheckUpdatesNow,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
fun SegmentedChoiceBar(
    options: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = velvet.cardSub,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            options.forEach { (key, label) ->
                val selected = key == selectedKey
                val bg by androidx.compose.animation.animateColorAsState(
                    targetValue = if (selected) velvet.accent else Color.Transparent,
                    animationSpec = tween(180),
                    label = "segBg",
                )
                val fg by androidx.compose.animation.animateColorAsState(
                    targetValue = if (selected) velvet.onAccent else cs.onSurfaceVariant,
                    animationSpec = tween(180),
                    label = "segFg",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(11.dp))
                        .background(bg)
                        .clickable {
                            if (!selected) {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                onSelect(key)
                            }
                        }
                        .padding(vertical = 9.dp, horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private data class DockItem(
    val icon: ImageVector,
    val label: String,
)

@Composable
private fun VelvetBottomDock(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    items: List<DockItem>,
) {
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = velvet.card,
            contentColor = cs.onSurface,
            border = rememberRimBorder(velvet.rim),
            shadowElevation = if (velvet.isDark) 6.dp else 3.dp,
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
                    val bg by androidx.compose.animation.animateColorAsState(
                        targetValue = if (selected) velvet.accentSoft else Color.Transparent,
                        animationSpec = tween(180),
                        label = "dockBg",
                    )
                    val fg by androidx.compose.animation.animateColorAsState(
                        targetValue = if (selected) velvet.accent else cs.onSurfaceVariant,
                        animationSpec = tween(180),
                        label = "dockFg",
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(bg)
                            .clickable {
                                if (!selected) {
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                    onSelectTab(index)
                                }
                            }
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
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    Surface(
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(12.dp),
        color = velvet.card,
        contentColor = cs.onSurface,
        border = rememberRimBorder(velvet.rim),
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ModeSegment(
                text = simpleLabel,
                selected = isSimple,
                onClick = {
                    if (!isSimple) {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        onSelect(true)
                    }
                },
            )
            ModeSegment(
                text = expertLabel,
                selected = !isSimple,
                onClick = {
                    if (isSimple) {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        onSelect(false)
                    }
                },
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
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme
    val bg by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) velvet.accent else Color.Transparent,
        animationSpec = tween(180),
        label = "modeBg",
    )
    val fg by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) velvet.onAccent else cs.onSurfaceVariant,
        animationSpec = tween(180),
        label = "modeFg",
    )
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
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
