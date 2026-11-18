package io.github.tgwsproxy.android.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tgwsproxy.android.BuildConfig

private data class GuideTopic(
    val icon: ImageVector,
    val title: String,
    val body: String,
)

@Composable
fun InfoScreen(strings: AppStrings) {
    val context = LocalContext.current
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme

    val topics = listOf(
        GuideTopic(
            icon = Icons.Rounded.Hub,
            title = "${strings.infoHowItWorksTitle} · v${BuildConfig.VERSION_NAME}",
            body = strings.infoHowItWorksBody,
        ),
        GuideTopic(
            icon = Icons.AutoMirrored.Rounded.Send,
            title = strings.infoConnectTgTitle,
            body = strings.infoConnectTgBody,
        ),
        GuideTopic(
            icon = Icons.Rounded.Bolt,
            title = strings.infoDisableTlsTitle,
            body = strings.infoDisableTlsBody,
        ),
        GuideTopic(
            icon = Icons.Rounded.Speed,
            title = strings.infoMediaTipsTitle,
            body = strings.infoMediaTipsBody,
        ),
        GuideTopic(
            icon = Icons.Rounded.Lock,
            title = strings.infoFakeTlsTitle,
            body = strings.infoFakeTlsBody,
        ),
    )

    var expandedIndex by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 6.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        VelvetGroup {
            topics.forEachIndexed { idx, topic ->
                val expanded = expandedIndex == idx
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    animationSpec = tween(180),
                    label = "guideChevron",
                )

                VelvetGroupItem(
                    index = idx,
                    count = topics.size,
                    onClick = { expandedIndex = if (expanded) -1 else idx },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VelvetIconBadge(
                            containerColor = if (expanded) velvet.accentSoft else velvet.cardSub,
                            contentColor = if (expanded) velvet.accent else cs.onSurfaceVariant,
                            size = 36.dp,
                        ) {
                            Icon(
                                imageVector = topic.icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = topic.title,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                        )
                        Icon(
                            imageVector = Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(rotation),
                        )
                    }

                    AnimatedVisibility(
                        visible = expanded,
                        enter = fadeIn(tween(160)) + expandVertically(),
                        exit = fadeOut(tween(120)) + shrinkVertically(),
                    ) {
                        Text(
                            text = topic.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
        }

        VelvetCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                            imageVector = Icons.Rounded.Code,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(
                        text = strings.infoUpstreamTitle,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                FilledTonalButton(
                    onClick = {
                        val obtainiumSpec =
                            "obtainium://app/%7B%22id%22%3A%22io.github.tgwsproxy.android%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FGramewa%2Ftg-ws-proxy-android%22%2C%22author%22%3A%22Gramewa%22%2C%22name%22%3A%22TG%20WS%20Proxy%22%7D"
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(obtainiumSpec))
                        try {
                            context.startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                            val fallback = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://apps.obtainium.imranr.dev/redirect?r=$obtainiumSpec"),
                            )
                            runCatching { context.startActivity(fallback) }
                        }
                    },
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Rounded.SystemUpdateAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = strings.infoObtainiumBtn,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                OutlinedButton(
                    onClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/Gramewa/tg-ws-proxy-android"),
                        )
                        runCatching { context.startActivity(intent) }
                    },
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = strings.infoAndroidRepoBtn,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                OutlinedButton(
                    onClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/Flowseal/tg-ws-proxy"),
                        )
                        runCatching { context.startActivity(intent) }
                    },
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = strings.infoUpstreamBtn,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
