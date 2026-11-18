package io.github.tgwsproxy.android.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tgwsproxy.android.proxy.LogLevel
import io.github.tgwsproxy.android.proxy.ProxyLogger
import kotlinx.coroutines.launch

@Composable
fun LogsScreen(strings: AppStrings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by ProxyLogger.entries.collectAsStateWithLifecycle()
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme

    var showDebug by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(true) }
    var showWarn by remember { mutableStateOf(true) }
    var showError by remember { mutableStateOf(true) }
    var search by remember { mutableStateOf("") }

    val counts = remember(entries) {
        var i = 0
        var w = 0
        var e = 0
        var d = 0
        for (entry in entries) {
            when (entry.level) {
                LogLevel.INFO -> i++
                LogLevel.WARN -> w++
                LogLevel.ERROR -> e++
                LogLevel.DEBUG -> d++
            }
        }
        intArrayOf(i, w, e, d)
    }

    val filtered = remember(entries, showDebug, showInfo, showWarn, showError, search) {
        val q = search.trim()
        entries.filter { entry ->
            val levelOk = when (entry.level) {
                LogLevel.DEBUG -> showDebug
                LogLevel.INFO -> showInfo
                LogLevel.WARN -> showWarn
                LogLevel.ERROR -> showError
            }
            val textOk = q.isEmpty() || entry.message.contains(q, ignoreCase = true)
            levelOk && textOk
        }
    }

    val listState = rememberLazyListState()
    val isAtBottom by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            if (total <= 1) {
                true
            } else {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible >= total - 3
            }
        }
    }

    val lastEntryId = filtered.lastOrNull()?.id ?: -1L
    LaunchedEffect(lastEntryId) {
        if (filtered.isNotEmpty() && isAtBottom) {
            listState.scrollToItem(filtered.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 6.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text(strings.logsSearchPlaceholder) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                    )
                },
                trailingIcon = if (search.isNotEmpty()) {
                    {
                        IconButton(onClick = { search = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(17.dp))
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f),
            )

            LogActionIconButton(
                icon = Icons.Rounded.ContentCopy,
                contentDescription = strings.btnCopyLogs,
                onClick = {
                    val text = filtered.joinToString("\n") { it.formatLine() }
                    copyToClipboard(context, "TG WS Proxy Logs", text)
                    Toast.makeText(context, strings.toastLogsCopied, Toast.LENGTH_SHORT).show()
                },
            )
            LogActionIconButton(
                icon = Icons.Rounded.Share,
                contentDescription = strings.btnShareLogs,
                onClick = {
                    val text = filtered.joinToString("\n") { it.formatLine() }
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "TG WS Proxy Android Logs")
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(intent, strings.btnShareLogs))
                    }
                },
            )
            LogActionIconButton(
                icon = Icons.Rounded.DeleteOutline,
                contentDescription = strings.btnClearLogs,
                onClick = { ProxyLogger.clear() },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LogLevelPill(
                label = "INFO",
                count = counts[0],
                selected = showInfo,
                accentColor = velvet.accent,
                onClick = { showInfo = !showInfo },
                modifier = Modifier.weight(1f),
            )
            LogLevelPill(
                label = "WARN",
                count = counts[1],
                selected = showWarn,
                accentColor = velvet.warning,
                onClick = { showWarn = !showWarn },
                modifier = Modifier.weight(1f),
            )
            LogLevelPill(
                label = "ERR",
                count = counts[2],
                selected = showError,
                accentColor = cs.error,
                onClick = { showError = !showError },
                modifier = Modifier.weight(1f),
            )
            LogLevelPill(
                label = "DBG",
                count = counts[3],
                selected = showDebug,
                accentColor = cs.onSurfaceVariant,
                onClick = { showDebug = !showDebug },
                modifier = Modifier.weight(1f),
            )
        }

        LogConsoleCard(
            filtered = filtered,
            listState = listState,
            isAtBottom = isAtBottom,
            emptyText = strings.logsEmpty,
            jumpLatestText = strings.btnJumpLatest,
            onJumpLatest = {
                scope.launch {
                    if (filtered.isNotEmpty()) {
                        listState.animateScrollToItem(filtered.lastIndex)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

@Composable
private fun LogConsoleCard(
    filtered: List<io.github.tgwsproxy.android.proxy.LogEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isAtBottom: Boolean,
    emptyText: String,
    jumpLatestText: String,
    onJumpLatest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme

    Surface(
        modifier = modifier,
        color = velvet.consoleBg,
        border = rememberRimBorder(velvet.rim),
        shape = RoundedCornerShape(22.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = emptyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    items(
                        items = filtered,
                        key = { it.id },
                    ) { entry ->
                        val levelColor = when (entry.level) {
                            LogLevel.ERROR -> if (velvet.isDark) Color(0xFFF87171) else Color(0xFFDC2626)
                            LogLevel.WARN -> velvet.warning
                            LogLevel.DEBUG -> cs.onSurfaceVariant
                            LogLevel.INFO -> velvet.accent
                        }
                        val levelShort = when (entry.level) {
                            LogLevel.ERROR -> "E"
                            LogLevel.WARN -> "W"
                            LogLevel.DEBUG -> "D"
                            LogLevel.INFO -> "I"
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Text(
                                text = entry.timeText,
                                color = cs.onSurfaceVariant.copy(alpha = 0.75f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                            )
                            Text(
                                text = levelShort,
                                color = levelColor,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                            )
                            Text(
                                text = entry.message,
                                color = cs.onSurface,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                lineHeight = 15.5.sp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = filtered.isNotEmpty() && !isAtBottom,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(120)),
            ) {
                Surface(
                    onClick = onJumpLatest,
                    shape = RoundedCornerShape(999.dp),
                    color = velvet.accent,
                    contentColor = velvet.onAccent,
                    shadowElevation = 6.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowDownward,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = jumpLatestText,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogActionIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(13.dp),
        color = velvet.card,
        border = rememberRimBorder(velvet.rim),
    ) {
        Box(
            modifier = Modifier.size(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun LogLevelPill(
    label: String,
    count: Int,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme
    val bg = if (selected) accentColor.copy(alpha = if (velvet.isDark) 0.18f else 0.14f) else velvet.card
    val fg = if (selected) accentColor else cs.onSurfaceVariant

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$label · $count",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
        )
    }
}
