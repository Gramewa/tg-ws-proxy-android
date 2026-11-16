package io.github.tgwsproxy.android.ui

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tgwsproxy.android.BuildConfig
import io.github.tgwsproxy.android.data.AppSettings
import io.github.tgwsproxy.android.proxy.CFPROXY_DEFAULT_DOMAINS
import io.github.tgwsproxy.android.proxy.ConnectivityTester
import io.github.tgwsproxy.android.proxy.DcConnectivityStatus
import io.github.tgwsproxy.android.proxy.DcParseResult
import io.github.tgwsproxy.android.proxy.coerceDomainList
import io.github.tgwsproxy.android.proxy.generateSecretHex
import io.github.tgwsproxy.android.proxy.isValidIpv4
import io.github.tgwsproxy.android.proxy.isValidSecretHex
import io.github.tgwsproxy.android.proxy.parseDcIpRules
import io.github.tgwsproxy.android.service.ProxyController
import io.github.tgwsproxy.android.update.AppUpdater
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(strings: AppStrings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by ProxyController.settings.collectAsStateWithLifecycle()
    val isRunning by ProxyController.isRunning.collectAsStateWithLifecycle()
    val isCheckingUpdate by AppUpdater.isChecking.collectAsStateWithLifecycle()

    var draft by remember { mutableStateOf(saved) }
    var portText by remember { mutableStateOf(saved.port.toString()) }
    var bufferText by remember { mutableStateOf(saved.bufferKb.toString()) }
    var poolText by remember { mutableStateOf(saved.poolSize.toString()) }
    var validationError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(saved.secret) {
        if (draft.secret.isEmpty() && saved.secret.isNotEmpty()) {
            draft = saved
            portText = saved.port.toString()
            bufferText = saved.bufferKb.toString()
            poolText = saved.poolSize.toString()
        }
    }

    LaunchedEffect(saved.disableSecure) {
        draft = draft.copy(disableSecure = saved.disableSecure)
    }
    LaunchedEffect(saved.cfproxyEnabled) {
        draft = draft.copy(cfproxyEnabled = saved.cfproxyEnabled)
    }
    LaunchedEffect(saved.cfproxyH2Media) {
        draft = draft.copy(cfproxyH2Media = saved.cfproxyH2Media)
    }
    LaunchedEffect(saved.simpleMode) {
        draft = draft.copy(simpleMode = saved.simpleMode)
    }
    LaunchedEffect(saved.colorPalette) {
        draft = draft.copy(colorPalette = saved.colorPalette)
    }
    LaunchedEffect(saved.dynamicColors) {
        draft = draft.copy(dynamicColors = saved.dynamicColors)
    }
    LaunchedEffect(saved.themeMode) {
        draft = draft.copy(themeMode = saved.themeMode)
    }
    LaunchedEffect(saved.language) {
        draft = draft.copy(language = saved.language)
    }

    var testingCf by remember { mutableStateOf(false) }
    var testingWorker by remember { mutableStateOf(false) }
    var testDialogTitle by remember { mutableStateOf<String?>(null) }
    var testDialogBody by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (validationError != null) {
            ElevatedCard(shape = RoundedCornerShape(12.dp)) {
                Text(
                    text = validationError.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        // Section 1: MTProto & Fake TLS
        SectionCard(title = strings.sectionMtproto) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = draft.host,
                    onValueChange = { draft = draft.copy(host = it) },
                    label = {
                        Text(
                            text = strings.labelHost,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1.4f),
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it },
                    label = {
                        Text(
                            text = strings.labelPort,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }

            OutlinedTextField(
                value = draft.secret,
                onValueChange = { draft = draft.copy(secret = it) },
                label = {
                    Text(
                        text = strings.labelSecret,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = { draft = draft.copy(secret = generateSecretHex()) }) {
                        Icon(Icons.Default.Refresh, contentDescription = strings.btnGenerateSecret)
                    }
                },
            )

            OutlinedTextField(
                value = draft.fakeTlsDomain,
                onValueChange = { draft = draft.copy(fakeTlsDomain = it) },
                label = {
                    Text(
                        text = strings.labelFakeTlsDomain,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                placeholder = { Text(strings.hintFakeTlsDomain) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SettingsSwitchRow(
                title = strings.labelForceTestDc,
                checked = draft.forceTestDc,
                onCheckedChange = { draft = draft.copy(forceTestDc = it) },
            )
            SettingsSwitchRow(
                title = strings.labelProxyProtocol,
                checked = draft.proxyProtocol,
                onCheckedChange = { draft = draft.copy(proxyProtocol = it) },
            )
        }

        // Section 2: DC -> IP rules
        SectionCard(title = strings.sectionDcRules) {
            Text(
                text = strings.labelDcHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = draft.dcIpRules,
                onValueChange = { draft = draft.copy(dcIpRules = it) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { draft = draft.copy(dcIpRules = AppSettings.DEFAULT_DC_RULES) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = strings.btnDcDefaults,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(
                    onClick = { draft = draft.copy(dcIpRules = "") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = strings.btnDcClear,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Section 3: Cloudflare Proxy
        SectionCard(title = strings.sectionCfProxy) {
            SettingsSwitchRow(
                title = strings.labelCfEnable,
                checked = draft.cfproxyEnabled,
                onCheckedChange = { draft = draft.copy(cfproxyEnabled = it) },
            )

            val h2Allowed = draft.cfproxyEnabled && !draft.disableSecure && !draft.forceTestDc
            SettingsSwitchRow(
                title = strings.labelH2Enable,
                subtitle = if (!h2Allowed) strings.h2DisabledByTlsHint else null,
                checked = draft.cfproxyH2Media && h2Allowed,
                enabled = h2Allowed,
                onCheckedChange = { draft = draft.copy(cfproxyH2Media = it) },
            )

            SettingsSwitchRow(
                title = strings.labelCfCustomDomainsToggle,
                checked = draft.cfproxyCustomDomainsEnabled,
                onCheckedChange = { draft = draft.copy(cfproxyCustomDomainsEnabled = it) },
            )

            if (draft.cfproxyCustomDomainsEnabled) {
                OutlinedTextField(
                    value = draft.cfproxyCustomDomains,
                    onValueChange = { draft = draft.copy(cfproxyCustomDomains = it) },
                    label = { Text(strings.labelCfCustomDomains) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedButton(
                onClick = {
                    if (testingCf) return@OutlinedButton
                    testingCf = true
                    scope.launch {
                        try {
                            val secure = !draft.disableSecure
                            val custom = coerceDomainList(draft.cfproxyCustomDomains)
                            if (draft.cfproxyCustomDomainsEnabled && custom.isNotEmpty()) {
                                val res = ConnectivityTester.testCfProxyMulti(custom, secure)
                                testDialogTitle = strings.testResultTitleCfProxy
                                testDialogBody = formatMultiResults(res, secure)
                            } else {
                                val res = ConnectivityTester.testCfProxyAuto(CFPROXY_DEFAULT_DOMAINS, secure)
                                testDialogTitle = strings.testResultTitleCfProxy
                                testDialogBody = formatAutoResults(res.bestDomain, res.perDc, secure, strings)
                            }
                        } finally {
                            testingCf = false
                        }
                    }
                },
                enabled = !testingCf,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (testingCf) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = strings.testingInProgress,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = strings.btnTestConnectivity,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Section 4: Cloudflare Worker
        SectionCard(title = strings.sectionCfWorker) {
            OutlinedTextField(
                value = draft.cfworkerDomains,
                onValueChange = { draft = draft.copy(cfworkerDomains = it) },
                label = { Text(strings.labelCfWorkerDomains) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = {
                    if (testingWorker) return@OutlinedButton
                    val workers = coerceDomainList(draft.cfworkerDomains)
                    if (workers.isEmpty()) {
                        Toast.makeText(context, strings.errEnterWorkerDomain, Toast.LENGTH_SHORT).show()
                        return@OutlinedButton
                    }
                    testingWorker = true
                    scope.launch {
                        try {
                            val secure = !draft.disableSecure
                            val res = ConnectivityTester.testCfWorkerMulti(workers, secure)
                            testDialogTitle = strings.testResultTitleCfWorker
                            testDialogBody = formatMultiResults(res, secure)
                        } finally {
                            testingWorker = false
                        }
                    }
                },
                enabled = !testingWorker,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (testingWorker) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = strings.testingInProgress,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = strings.btnTestConnectivity,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Section 5: Logs & Performance
        SectionCard(title = strings.sectionLogsPerf) {
            SettingsSwitchRow(
                title = strings.labelVerbose,
                checked = draft.verboseLogging,
                onCheckedChange = { draft = draft.copy(verboseLogging = it) },
            )
            SettingsSwitchRow(
                title = strings.disableTlsTitle,
                subtitle = strings.disableTlsSubtitle,
                checked = draft.disableSecure,
                onCheckedChange = { draft = draft.copy(disableSecure = it) },
            )
            OutlinedTextField(
                value = bufferText,
                onValueChange = { bufferText = it },
                label = {
                    Text(
                        text = strings.labelBufKb,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = poolText,
                onValueChange = { poolText = it },
                label = {
                    Text(
                        text = strings.labelPoolSize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Section 6: Updates
        SectionCard(title = strings.sectionUpdates) {
            SettingsSwitchRow(
                title = strings.labelAutoCheckUpdates,
                checked = draft.checkUpdatesOnStartup,
                onCheckedChange = { draft = draft.copy(checkUpdatesOnStartup = it) },
            )
            OutlinedButton(
                onClick = {
                    if (isCheckingUpdate) return@OutlinedButton
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
                },
                enabled = !isCheckingUpdate,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isCheckingUpdate) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = strings.checkingUpdates,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${strings.btnCheckUpdatesNow} (v${BuildConfig.VERSION_NAME})",
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Section 7: Interface & System
        SectionCard(title = strings.sectionInterfaceSystem) {
            Text(strings.labelInterfaceMode, style = MaterialTheme.typography.labelLarge)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = draft.simpleMode,
                    onClick = { draft = draft.copy(simpleMode = true) },
                    label = { Text(strings.modeSimple, maxLines = 1, softWrap = false) },
                )
                FilterChip(
                    selected = !draft.simpleMode,
                    onClick = { draft = draft.copy(simpleMode = false) },
                    label = { Text(strings.modeExpert, maxLines = 1, softWrap = false) },
                )
            }

            Text(strings.labelLanguage, style = MaterialTheme.typography.labelLarge)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = draft.language == "ru",
                    onClick = { draft = draft.copy(language = "ru") },
                    label = { Text(strings.langRu, maxLines = 1, softWrap = false) },
                )
                FilterChip(
                    selected = draft.language == "en",
                    onClick = { draft = draft.copy(language = "en") },
                    label = { Text(strings.langEn, maxLines = 1, softWrap = false) },
                )
            }

            Text(strings.labelTheme, style = MaterialTheme.typography.labelLarge)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = draft.themeMode == "auto",
                    onClick = { draft = draft.copy(themeMode = "auto") },
                    label = { Text(strings.themeAuto, maxLines = 1, softWrap = false) },
                )
                FilterChip(
                    selected = draft.themeMode == "light",
                    onClick = { draft = draft.copy(themeMode = "light") },
                    label = { Text(strings.themeLight, maxLines = 1, softWrap = false) },
                )
                FilterChip(
                    selected = draft.themeMode == "dark",
                    onClick = { draft = draft.copy(themeMode = "dark") },
                    label = { Text(strings.themeDark, maxLines = 1, softWrap = false) },
                )
            }

            Text(strings.labelPalette, style = MaterialTheme.typography.labelLarge)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (opt in AvailablePalettes) {
                    val paletteTitle = when (opt.id) {
                        "emerald" -> strings.paletteEmerald
                        "amethyst" -> strings.paletteAmethyst
                        "sunset" -> strings.paletteSunset
                        else -> strings.paletteOcean
                    }
                    FilterChip(
                        selected = !draft.dynamicColors && draft.colorPalette == opt.id,
                        onClick = {
                            draft = draft.copy(colorPalette = opt.id, dynamicColors = false)
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(opt.previewColor),
                            )
                        },
                        label = { Text(paletteTitle, maxLines = 1, softWrap = false) },
                    )
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SettingsSwitchRow(
                    title = strings.labelDynamicColors,
                    checked = draft.dynamicColors,
                    onCheckedChange = { draft = draft.copy(dynamicColors = it) },
                )
            }
            SettingsSwitchRow(
                title = strings.labelAutoStart,
                checked = draft.autoStartOnBoot,
                onCheckedChange = { draft = draft.copy(autoStartOnBoot = it) },
            )
            SettingsSwitchRow(
                title = strings.labelKeepWakeLock,
                checked = draft.keepWakeLock,
                onCheckedChange = { draft = draft.copy(keepWakeLock = it) },
            )
        }

        Button(
            onClick = {
                val err = validateDraft(
                    draft = draft,
                    portText = portText,
                    bufferText = bufferText,
                    poolText = poolText,
                    strings = strings,
                )
                if (err != null) {
                    validationError = err
                    return@Button
                }
                validationError = null
                val finalSettings = draft.copy(
                    host = draft.host.trim(),
                    port = portText.trim().toInt(),
                    secret = draft.secret.trim().lowercase(),
                    fakeTlsDomain = draft.fakeTlsDomain.trim(),
                    bufferKb = bufferText.trim().toInt(),
                    poolSize = poolText.trim().toInt(),
                )
                scope.launch {
                    val wasRunning = isRunning
                    ProxyController.saveSettings(finalSettings, restartIfRunning = true)
                    val msg = if (wasRunning) strings.settingsSavedRestarted else strings.settingsSavedApplied
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(
                text = strings.btnSaveApply,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(12.dp))
    }

    if (testDialogTitle != null) {
        AlertDialog(
            onDismissRequest = { testDialogTitle = null },
            title = { Text(testDialogTitle.orEmpty()) },
            text = {
                Text(
                    text = testDialogBody,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { testDialogTitle = null }) {
                    Text(strings.btnClose)
                }
            },
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
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
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

private fun validateDraft(
    draft: AppSettings,
    portText: String,
    bufferText: String,
    poolText: String,
    strings: AppStrings,
): String? {
    if (!isValidIpv4(draft.host.trim())) return strings.errInvalidHost
    val port = portText.trim().toIntOrNull()
    if (port == null || port !in 1..65535) return strings.errInvalidPort
    if (!isValidSecretHex(draft.secret.trim())) return strings.errInvalidSecret
    when (val res = parseDcIpRules(draft.dcIpRules)) {
        is DcParseResult.Error -> return when (res.kind) {
            DcParseResult.Error.Kind.FORMAT -> "${strings.errDcFormat}${res.entry}"
            DcParseResult.Error.Kind.INVALID -> "${strings.errDcInvalid}${res.entry}"
        }
        is DcParseResult.Success -> Unit
    }
    val buf = bufferText.trim().toIntOrNull()
    if (buf == null || buf !in 4..4096) return strings.errInvalidBuffer
    val pool = poolText.trim().toIntOrNull()
    if (pool == null || pool !in 0..16) return strings.errInvalidPool
    return null
}

private fun formatAutoResults(
    bestDomain: String?,
    perDc: Map<Int, DcConnectivityStatus>,
    secure: Boolean,
    strings: AppStrings,
): String {
    val mode = if (secure) "TLS :443" else "HTTP :80"
    val sb = StringBuilder("Mode: $mode\n")
    if (bestDomain != null) {
        sb.append("${strings.testAutoBestDomain}: $bestDomain\n\n")
    } else {
        sb.append("${strings.testNoDomainResponded}\n\n")
    }
    for ((dc, status) in perDc) {
        val label = when (status) {
            is DcConnectivityStatus.Ok -> "OK (101)"
            is DcConnectivityStatus.Failed -> "FAIL (${status.reason})"
        }
        sb.append("DC$dc: $label\n")
    }
    return sb.toString().trimEnd()
}

private fun formatMultiResults(
    perDomain: Map<String, Map<Int, DcConnectivityStatus>>,
    secure: Boolean,
): String {
    val mode = if (secure) "TLS :443" else "HTTP :80"
    val sb = StringBuilder("Mode: $mode\n\n")
    for ((domain, map) in perDomain) {
        sb.append("[$domain]\n")
        for ((dc, status) in map) {
            val label = when (status) {
                is DcConnectivityStatus.Ok -> "OK"
                is DcConnectivityStatus.Failed -> status.reason
            }
            sb.append("  DC$dc: $label\n")
        }
        sb.append('\n')
    }
    return sb.toString().trimEnd()
}
