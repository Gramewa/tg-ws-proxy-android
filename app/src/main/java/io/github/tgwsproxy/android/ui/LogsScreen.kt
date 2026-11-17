package io.github.tgwsproxy.android.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tgwsproxy.android.proxy.LogLevel
import io.github.tgwsproxy.android.proxy.ProxyLogger

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogsScreen(strings: AppStrings) {
    val context = LocalContext.current
    val entries by ProxyLogger.entries.collectAsStateWithLifecycle()

    var showDebug by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(true) }
    var showWarn by remember { mutableStateOf(true) }
    var showError by remember { mutableStateOf(true) }
    var search by remember { mutableStateOf("") }

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
    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) {
            listState.scrollToItem(filtered.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text(strings.logsSearchPlaceholder) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = showInfo,
                onClick = { showInfo = !showInfo },
                label = { Text("INFO", maxLines = 1, softWrap = false) },
            )
            FilterChip(
                selected = showWarn,
                onClick = { showWarn = !showWarn },
                label = { Text("WARN", maxLines = 1, softWrap = false) },
            )
            FilterChip(
                selected = showError,
                onClick = { showError = !showError },
                label = { Text("ERR", maxLines = 1, softWrap = false) },
            )
            FilterChip(
                selected = showDebug,
                onClick = { showDebug = !showDebug },
                label = { Text("DBG", maxLines = 1, softWrap = false) },
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    val text = filtered.joinToString("\n") { it.formatLine() }
                    copyToClipboard(context, "TG WS Proxy Logs", text)
                    Toast.makeText(context, strings.toastLogsCopied, Toast.LENGTH_SHORT).show()
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = strings.btnCopyLogs,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            OutlinedButton(
                onClick = {
                    val text = filtered.joinToString("\n") { it.formatLine() }
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "TG WS Proxy Android Logs")
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(intent, strings.btnShareLogs))
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = strings.btnShareLogs,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            OutlinedButton(
                onClick = { ProxyLogger.clear() },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = strings.btnClearLogs,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            color = Color(0xFF070D17),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
            shape = RoundedCornerShape(18.dp),
        ) {
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = strings.logsEmpty,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF94A7C2),
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(filtered) { entry ->
                        val color = when (entry.level) {
                            LogLevel.ERROR -> Color(0xFFF87171)
                            LogLevel.WARN -> Color(0xFFFBBF24)
                            LogLevel.DEBUG -> Color(0xFF94A3B8)
                            LogLevel.INFO -> Color(0xFF7DD3FC)
                        }
                        Text(
                            text = entry.formatLine(),
                            color = color,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                    }
                }
            }
        }
    }
}
