package io.github.tgwsproxy.android.ui

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tgwsproxy.android.BuildConfig
import io.github.tgwsproxy.android.SegmentedChoiceBar
import io.github.tgwsproxy.android.data.AppSettings
import io.github.tgwsproxy.android.proxy.CFPROXY_DEFAULT_DOMAINS
import io.github.tgwsproxy.android.proxy.ConnectivityTester
import io.github.tgwsproxy.android.proxy.DcConnectivityStatus
import io.github.tgwsproxy.android.proxy.DcParseResult
import io.github.tgwsproxy.android.proxy.buildTelegramProxyLink
import io.github.tgwsproxy.android.proxy.coerceDomainList
import io.github.tgwsproxy.android.proxy.generateSecretHex
import io.github.tgwsproxy.android.proxy.isValidDomain
import io.github.tgwsproxy.android.proxy.isValidIpv4
import io.github.tgwsproxy.android.proxy.isValidSecretHex
import io.github.tgwsproxy.android.proxy.parseDcIpRules
import io.github.tgwsproxy.android.service.ProxyController
import io.github.tgwsproxy.android.update.AppUpdater
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(strings: AppStrings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by ProxyController.settings.collectAsStateWithLifecycle()
    val isRunning by ProxyController.isRunning.collectAsStateWithLifecycle()
    val isCheckingUpdate by AppUpdater.isChecking.collectAsStateWithLifecycle()
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme

    var draft by remember { mutableStateOf(saved) }
    var portText by remember { mutableStateOf(saved.port.toString()) }
    var bufferText by remember { mutableStateOf(saved.bufferKb.toString()) }
    var poolText by remember { mutableStateOf(saved.poolSize.toString()) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var pendingSyncLink by remember { mutableStateOf<String?>(null) }

    val resetDraftToSaved = {
        draft = saved
        portText = saved.port.toString()
        bufferText = saved.bufferKb.toString()
        poolText = saved.poolSize.toString()
        validationError = null
    }

    LaunchedEffect(saved.secret) {
        if (draft.secret.isEmpty() && saved.secret.isNotEmpty()) {
            resetDraftToSaved()
        }
    }

    val parsedPort = portText.trim().toIntOrNull()
    val parsedBuf = bufferText.trim().toIntOrNull()
    val parsedPool = poolText.trim().toIntOrNull()

    val hasUnsavedChanges = remember(draft, portText, bufferText, poolText, saved) {
        parsedPort != saved.port ||
            parsedBuf != saved.bufferKb ||
            parsedPool != saved.poolSize ||
            !draft.copy(
                port = parsedPort ?: -1,
                bufferKb = parsedBuf ?: -1,
                poolSize = parsedPool ?: -1,
            ).hasSameNetworkConfigAs(saved)
    }

    // Keep quick-toggle fields synced from external toggles only when user doesn't have unsaved edits
    LaunchedEffect(saved.disableSecure, saved.cfproxyEnabled, saved.cfproxyH2Media) {
        if (!hasUnsavedChanges) {
            draft = draft.copy(
                disableSecure = saved.disableSecure,
                cfproxyEnabled = saved.cfproxyEnabled,
                cfproxyH2Media = saved.cfproxyH2Media,
            )
        }
    }

    BackHandler(enabled = hasUnsavedChanges) {
        showDiscardDialog = true
    }

    var testingCf by remember { mutableStateOf(false) }
    var testingWorker by remember { mutableStateOf(false) }
    var testDialogTitle by remember { mutableStateOf<String?>(null) }
    var testDialogBody by remember { mutableStateOf("") }

    val applyNetworkSettings = {
        val err = validateDraft(
            draft = draft,
            portText = portText,
            bufferText = bufferText,
            poolText = poolText,
            strings = strings,
        )
        if (err != null) {
            validationError = err
        } else {
            validationError = null
            val previousSaved = saved
            val finalSettings = saved.copy(
                host = draft.host.trim(),
                port = portText.trim().toInt(),
                secret = draft.secret.trim().lowercase(),
                fakeTlsDomain = draft.fakeTlsDomain.trim().lowercase(),
                forceTestDc = draft.forceTestDc,
                proxyProtocol = draft.proxyProtocol,
                dcIpRules = draft.dcIpRules.trim(),
                cfproxyEnabled = draft.cfproxyEnabled,
                cfproxyH2Media = draft.cfproxyH2Media,
                cfproxyCustomDomainsEnabled = draft.cfproxyCustomDomainsEnabled,
                cfproxyCustomDomains = draft.cfproxyCustomDomains.trim(),
                cfworkerDomains = draft.cfworkerDomains.trim(),
                verboseLogging = draft.verboseLogging,
                disableSecure = draft.disableSecure,
                bufferKb = bufferText.trim().toInt(),
                poolSize = poolText.trim().toInt(),
            )
            draft = finalSettings
            portText = finalSettings.port.toString()
            bufferText = finalSettings.bufferKb.toString()
            poolText = finalSettings.poolSize.toString()

            if (!finalSettings.hasSameClientLinkAs(previousSaved)) {
                pendingSyncLink = buildTelegramProxyLink(finalSettings.toProxyConfig())
            }

            scope.launch {
                val wasRunning = isRunning
                ProxyController.saveSettings(finalSettings, restartIfRunning = true)
                val msg = if (wasRunning) strings.settingsSavedRestarted else strings.settingsSavedApplied
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val fieldShape = RoundedCornerShape(14.dp)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 6.dp, bottom = if (hasUnsavedChanges) 92.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AnimatedVisibility(
                visible = validationError != null,
                enter = fadeIn(tween(150)) + expandVertically(),
                exit = fadeOut(tween(120)) + shrinkVertically(),
            ) {
                VelvetCard(
                    containerColor = cs.errorContainer,
                    rimColor = cs.error.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = cs.onErrorContainer,
                            modifier = Modifier.size(19.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = validationError.orEmpty(),
                            color = cs.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = pendingSyncLink != null,
                enter = fadeIn(tween(180)) + expandVertically(),
                exit = fadeOut(tween(140)) + shrinkVertically(),
            ) {
                VelvetCard(
                    containerColor = velvet.accentSoft,
                    rimColor = velvet.accent.copy(alpha = 0.28f),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = null,
                            tint = velvet.accent,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = strings.syncTelegramPrompt,
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurface,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 10.dp),
                        )
                        Button(
                            onClick = {
                                val link = pendingSyncLink
                                pendingSyncLink = null
                                if (link != null) {
                                    openTelegramProxyLink(context, link, strings)
                                }
                            },
                            shape = RoundedCornerShape(11.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = velvet.accent,
                                contentColor = velvet.onAccent,
                            ),
                        ) {
                            Text(
                                text = strings.btnUpdateTelegram,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }

            // Section 1: MTProto & Fake TLS
            VelvetSectionCard(
                icon = Icons.Rounded.Security,
                title = strings.sectionMtproto,
            ) {
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
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        singleLine = true,
                        shape = fieldShape,
                        modifier = Modifier.weight(1.45f),
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it },
                        label = {
                            Text(
                                text = strings.labelPort,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        singleLine = true,
                        shape = fieldShape,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }

                if (draft.host.trim() == "0.0.0.0") {
                    Text(
                        text = strings.warnBindAllInterfaces,
                        style = MaterialTheme.typography.bodySmall,
                        color = velvet.warning,
                    )
                }

                OutlinedTextField(
                    value = draft.secret,
                    onValueChange = { draft = draft.copy(secret = it) },
                    label = {
                        Text(
                            text = strings.labelSecret,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    singleLine = true,
                    shape = fieldShape,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { draft = draft.copy(secret = generateSecretHex()) }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = strings.btnGenerateSecret)
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
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    placeholder = { Text(strings.hintFakeTlsDomain) },
                    singleLine = true,
                    shape = fieldShape,
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

            // Section 2: Datacenters (DC:IP)
            VelvetSectionCard(
                icon = Icons.Rounded.Dns,
                title = strings.sectionDcRules,
            ) {
                Text(
                    text = strings.labelDcHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draft.dcIpRules,
                    onValueChange = { draft = draft.copy(dcIpRules = it) },
                    minLines = 3,
                    maxLines = 6,
                    shape = fieldShape,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { draft = draft.copy(dcIpRules = AppSettings.DEFAULT_DC_RULES) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = strings.btnDcDefaults,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OutlinedButton(
                        onClick = { draft = draft.copy(dcIpRules = "") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = strings.btnDcClear,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Section 3: Cloudflare Proxy
            VelvetSectionCard(
                icon = Icons.Rounded.CloudQueue,
                title = strings.sectionCfProxy,
            ) {
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
                        shape = fieldShape,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                FilledTonalButton(
                    onClick = {
                        if (testingCf) return@FilledTonalButton
                        testingCf = true
                        scope.launch {
                            try {
                                val secure = !draft.disableSecure
                                val custom = coerceDomainList(draft.cfproxyCustomDomains)
                                if (draft.cfproxyCustomDomainsEnabled && custom.isNotEmpty()) {
                                    val res = ConnectivityTester.testCfProxyMulti(custom, secure)
                                    testDialogTitle = strings.testResultTitleCfProxy
                                    testDialogBody = formatMultiResults(res, secure, strings)
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
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (testingCf) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(strings.testingInProgress)
                    } else {
                        Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(strings.btnTestConnectivity)
                    }
                }
            }

            // Section 4: Cloudflare Worker
            VelvetSectionCard(
                icon = Icons.Rounded.Code,
                title = strings.sectionCfWorker,
            ) {
                OutlinedTextField(
                    value = draft.cfworkerDomains,
                    onValueChange = { draft = draft.copy(cfworkerDomains = it) },
                    label = { Text(strings.labelCfWorkerDomains) },
                    minLines = 2,
                    shape = fieldShape,
                    modifier = Modifier.fillMaxWidth(),
                )
                FilledTonalButton(
                    onClick = {
                        if (testingWorker) return@FilledTonalButton
                        val workers = coerceDomainList(draft.cfworkerDomains)
                        if (workers.isEmpty()) {
                            Toast.makeText(context, strings.errEnterWorkerDomain, Toast.LENGTH_SHORT).show()
                            return@FilledTonalButton
                        }
                        testingWorker = true
                        scope.launch {
                            try {
                                val secure = !draft.disableSecure
                                val res = ConnectivityTester.testCfWorkerMulti(workers, secure)
                                testDialogTitle = strings.testResultTitleCfWorker
                                testDialogBody = formatMultiResults(res, secure, strings)
                            } finally {
                                testingWorker = false
                            }
                        }
                    },
                    enabled = !testingWorker,
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (testingWorker) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(strings.testingInProgress)
                    } else {
                        Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(strings.btnTestConnectivity)
                    }
                }
            }

            // Section 5: Network & Debug
            VelvetSectionCard(
                icon = Icons.Rounded.Speed,
                title = strings.sectionLogsPerf,
            ) {
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
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
                        shape = fieldShape,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
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
                        shape = fieldShape,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Section 6: Config Backup (JSON Export / Import)
            VelvetSectionCard(
                icon = Icons.Rounded.ContentCopy,
                title = strings.sectionBackup,
            ) {
                Text(
                    text = strings.backupSub,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            val currentForExport = draft.copy(
                                port = portText.trim().toIntOrNull() ?: draft.port,
                                bufferKb = bufferText.trim().toIntOrNull() ?: draft.bufferKb,
                                poolSize = poolText.trim().toIntOrNull() ?: draft.poolSize,
                            )
                            copyToClipboard(
                                context = context,
                                label = "TG WS Proxy Config",
                                text = currentForExport.toExportJson(),
                                isSensitive = true,
                            )
                            Toast.makeText(context, strings.toastConfigExported, Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = strings.btnExportConfig,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            val raw = readClipboardText(context)
                            val imported = if (!raw.isNullOrBlank()) draft.applyImportedJson(raw) else null
                            if (imported == null) {
                                Toast.makeText(context, strings.errInvalidConfigJson, Toast.LENGTH_SHORT).show()
                            } else {
                                draft = imported
                                portText = imported.port.toString()
                                bufferText = imported.bufferKb.toString()
                                poolText = imported.poolSize.toString()
                                validationError = null
                                Toast.makeText(context, strings.toastConfigImported, Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = strings.btnImportConfig,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Section 7: System & Appearance (instant save, no restart needed)
            VelvetSectionCard(
                icon = Icons.Rounded.Tune,
                title = strings.sectionInterfaceSystem,
            ) {
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
                    selectedKey = saved.themeMode,
                    onSelect = { ProxyController.setThemeModeAsync(it) },
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SettingsSwitchRow(
                        title = strings.labelDynamicColors,
                        subtitle = strings.subDynamicColors,
                        checked = saved.dynamicColors,
                        onCheckedChange = { ProxyController.setDynamicColorsAsync(it) },
                    )
                }

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
                    selectedKey = saved.language,
                    onSelect = { ProxyController.setLanguageAsync(it) },
                )

                SettingsSwitchRow(
                    title = strings.labelAutoStart,
                    checked = saved.autoStartOnBoot,
                    onCheckedChange = { ProxyController.setAutoStartAsync(it) },
                )
                SettingsSwitchRow(
                    title = strings.labelKeepWakeLock,
                    checked = saved.keepWakeLock,
                    onCheckedChange = { ProxyController.setKeepWakeLockAsync(it) },
                )
            }

            // Section 8: Updates (instant save)
            VelvetSectionCard(
                icon = Icons.Rounded.SystemUpdate,
                title = strings.sectionUpdates,
            ) {
                SettingsSwitchRow(
                    title = strings.labelAutoCheckUpdates,
                    checked = saved.checkUpdatesOnStartup,
                    onCheckedChange = { ProxyController.setCheckUpdatesOnStartupAsync(it) },
                )
                FilledTonalButton(
                    onClick = {
                        if (isCheckingUpdate) return@FilledTonalButton
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
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isCheckingUpdate) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(strings.checkingUpdates)
                    } else {
                        Icon(Icons.Rounded.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("${strings.btnCheckUpdatesNow} (v${BuildConfig.VERSION_NAME})")
                    }
                }
            }
        }

        // Sticky floating Apply / Discard bar when network draft differs from saved
        AnimatedVisibility(
            visible = hasUnsavedChanges,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            enter = fadeIn(tween(180)) + slideInVertically { it / 2 },
            exit = fadeOut(tween(140)) + slideOutVertically { it / 2 },
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = velvet.heroActive,
                border = rememberRimBorder(velvet.heroActiveRim),
                shadowElevation = 8.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = strings.unsavedChangesLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = resetDraftToSaved) {
                            Text(
                                text = strings.btnDiscardChanges,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        Button(
                            onClick = applyNetworkSettings,
                            shape = RoundedCornerShape(13.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = velvet.accent,
                                contentColor = velvet.onAccent,
                            ),
                        ) {
                            Text(
                                text = strings.btnSaveApply,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            containerColor = velvet.card,
            title = { Text(strings.dialogUnsavedTitle) },
            text = { Text(strings.dialogUnsavedBody) },
            confirmButton = {
                Button(
                    onClick = {
                        showDiscardDialog = false
                        resetDraftToSaved()
                    },
                ) {
                    Text(strings.btnLeave)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(strings.btnStay)
                }
            },
        )
    }

    if (testDialogTitle != null) {
        AlertDialog(
            onDismissRequest = { testDialogTitle = null },
            containerColor = velvet.card,
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
private fun VelvetSectionCard(
    icon: ImageVector,
    title: String,
    content: @Composable () -> Unit,
) {
    val velvet = MaterialTheme.velvet
    VelvetCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                VelvetIconBadge(
                    containerColor = velvet.accentSoft,
                    contentColor = velvet.accent,
                    size = 34.dp,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
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
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (enabled) {
                    Modifier.clickable { onCheckedChange(!checked) }
                } else {
                    Modifier
                },
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) cs.onSurface else cs.onSurfaceVariant,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
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
    val ftls = draft.fakeTlsDomain.trim().lowercase()
    if (ftls.isNotEmpty() && !isValidDomain(ftls)) return strings.errInvalidFakeTlsDomain
    if (draft.cfproxyCustomDomainsEnabled) {
        val custom = coerceDomainList(draft.cfproxyCustomDomains)
        if (custom.any { !isValidDomain(it.lowercase()) }) return strings.errInvalidCfDomains
    }
    val workers = coerceDomainList(draft.cfworkerDomains)
    if (workers.any { !isValidDomain(it.lowercase()) }) return strings.errInvalidCfDomains
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
    val sb = StringBuilder("$mode\n")
    if (bestDomain != null) {
        sb.append("${strings.testAutoBestDomain}: $bestDomain\n\n")
    } else {
        sb.append("${strings.testNoDomainResponded}\n\n")
    }
    for ((dc, status) in perDc) {
        val label = when (status) {
            is DcConnectivityStatus.Ok -> "OK (${status.latencyMs} ${strings.unitMs})"
            is DcConnectivityStatus.Failed -> "FAIL (${status.reason})"
        }
        sb.append("DC$dc: $label\n")
    }
    return sb.toString().trimEnd()
}

private fun formatMultiResults(
    perDomain: Map<String, Map<Int, DcConnectivityStatus>>,
    secure: Boolean,
    strings: AppStrings,
): String {
    val mode = if (secure) "TLS :443" else "HTTP :80"
    val sb = StringBuilder("$mode\n\n")
    for ((domain, map) in perDomain) {
        sb.append("[$domain]\n")
        for ((dc, status) in map) {
            val label = when (status) {
                is DcConnectivityStatus.Ok -> "OK (${status.latencyMs} ${strings.unitMs})"
                is DcConnectivityStatus.Failed -> status.reason
            }
            sb.append("  DC$dc: $label\n")
        }
        sb.append('\n')
    }
    return sb.toString().trimEnd()
}
