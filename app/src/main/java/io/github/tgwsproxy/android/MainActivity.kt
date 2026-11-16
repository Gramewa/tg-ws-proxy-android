package io.github.tgwsproxy.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tgwsproxy.android.service.ProxyController
import io.github.tgwsproxy.android.ui.ConnectionScreen
import io.github.tgwsproxy.android.ui.InfoScreen
import io.github.tgwsproxy.android.ui.LogsScreen
import io.github.tgwsproxy.android.ui.SettingsScreen
import io.github.tgwsproxy.android.ui.TgWsProxyTheme
import io.github.tgwsproxy.android.ui.stringsFor

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
    val strings = stringsFor(settings.language)
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    TgWsProxyTheme(
        themeMode = settings.themeMode,
        dynamicColors = settings.dynamicColors,
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(strings.appName) },
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.PowerSettingsNew, contentDescription = strings.navConnection) },
                        label = { Text(strings.navConnection, maxLines = 1) },
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = strings.navSettings) },
                        label = { Text(strings.navSettings, maxLines = 1) },
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.ListAlt, contentDescription = strings.navLogs) },
                        label = { Text(strings.navLogs, maxLines = 1) },
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = { Icon(Icons.Default.Info, contentDescription = strings.navInfo) },
                        label = { Text(strings.navInfo, maxLines = 1) },
                    )
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                when (selectedTab) {
                    0 -> ConnectionScreen(strings)
                    1 -> SettingsScreen(strings)
                    2 -> LogsScreen(strings)
                    else -> InfoScreen(strings)
                }
            }
        }
    }
}
