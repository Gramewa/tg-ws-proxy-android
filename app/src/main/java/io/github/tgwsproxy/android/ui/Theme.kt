package io.github.tgwsproxy.android.ui

import android.app.Activity
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

@Immutable
data class VelvetColors(
    val isDark: Boolean,
    val bg: Color,
    val card: Color,
    val cardSub: Color,
    val rim: Color,
    val heroActive: Color,
    val heroActiveRim: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val liveGreen: Color,
    val liveGreenSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val consoleBg: Color,
)

private val DarkVelvetColors = VelvetColors(
    isDark = true,
    bg = Color(0xFF0B0D11),
    card = Color(0xFF141820),
    cardSub = Color(0xFF1C222D),
    rim = Color(0x12FFFFFF),
    heroActive = Color(0xFF132642),
    heroActiveRim = Color(0x3685B2FF),
    accent = Color(0xFF85B2FF),
    onAccent = Color(0xFF061D3D),
    accentSoft = Color(0x2485B2FF),
    liveGreen = Color(0xFF4ADE80),
    liveGreenSoft = Color(0x244ADE80),
    warning = Color(0xFFFBBF24),
    warningSoft = Color(0x24FBBF24),
    consoleBg = Color(0xFF0E1117),
)

private val LightVelvetColors = VelvetColors(
    isDark = false,
    bg = Color(0xFFF1F3F8),
    card = Color(0xFFFFFFFF),
    cardSub = Color(0xFFE6EBF4),
    rim = Color(0x120F172A),
    heroActive = Color(0xFFDAE7FF),
    heroActiveRim = Color(0x2E2356C8),
    accent = Color(0xFF2356C8),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0x1A2356C8),
    liveGreen = Color(0xFF16A34A),
    liveGreenSoft = Color(0x1F16A34A),
    warning = Color(0xFFD97706),
    warningSoft = Color(0x1FD97706),
    consoleBg = Color(0xFFF7F9FC),
)

private val VelvetDarkScheme = darkColorScheme(
    primary = Color(0xFF85B2FF),
    onPrimary = Color(0xFF061D3D),
    primaryContainer = Color(0xFF173156),
    onPrimaryContainer = Color(0xFFD8E6FF),
    secondary = Color(0xFF9CB2D4),
    onSecondary = Color(0xFF102238),
    secondaryContainer = Color(0xFF213044),
    onSecondaryContainer = Color(0xFFD6E3F8),
    tertiary = Color(0xFF4ADE80),
    onTertiary = Color(0xFF052E16),
    tertiaryContainer = Color(0xFF0F3D24),
    onTertiaryContainer = Color(0xFFBBF7D0),
    background = Color(0xFF0B0D11),
    onBackground = Color(0xFFECEFF6),
    surface = Color(0xFF141820),
    onSurface = Color(0xFFECEFF6),
    surfaceVariant = Color(0xFF1C222D),
    onSurfaceVariant = Color(0xFF939DB2),
    outline = Color(0xFF414B5E),
    outlineVariant = Color(0xFF252C3A),
    error = Color(0xFFFCA5A5),
    onError = Color(0xFF4C0509),
    errorContainer = Color(0xFF5C161B),
    onErrorContainer = Color(0xFFFEE2E2),
)

private val VelvetLightScheme = lightColorScheme(
    primary = Color(0xFF2356C8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDAE7FF),
    onPrimaryContainer = Color(0xFF081E4A),
    secondary = Color(0xFF475875),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE4F4),
    onSecondaryContainer = Color(0xFF111E33),
    tertiary = Color(0xFF16A34A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCFCE7),
    onTertiaryContainer = Color(0xFF052E16),
    background = Color(0xFFF1F3F8),
    onBackground = Color(0xFF11151E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF11151E),
    surfaceVariant = Color(0xFFE6EBF4),
    onSurfaceVariant = Color(0xFF566074),
    outline = Color(0xFF8B95A9),
    outlineVariant = Color(0xFFD5DCE8),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF450A0A),
)

private val VelvetTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = "tnum",
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.4).sp,
        fontFeatureSettings = "tnum",
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.3).sp,
        fontFeatureSettings = "tnum",
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        letterSpacing = (-0.15).sp,
        fontFeatureSettings = "tnum",
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        fontFeatureSettings = "tnum",
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.5.sp,
        fontFeatureSettings = "tnum",
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
        fontFeatureSettings = "tnum",
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = "tnum",
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontFeatureSettings = "tnum",
    ),
)

val LocalVelvetColors = staticCompositionLocalOf { DarkVelvetColors }

val MaterialTheme.velvet: VelvetColors
    @Composable
    @ReadOnlyComposable
    get() = LocalVelvetColors.current

fun velvetGroupShape(index: Int, count: Int): Shape {
    val outer = 24.dp
    val inner = 6.dp
    return when {
        count <= 1 -> RoundedCornerShape(outer)
        index <= 0 -> RoundedCornerShape(
            topStart = outer,
            topEnd = outer,
            bottomStart = inner,
            bottomEnd = inner,
        )
        index >= count - 1 -> RoundedCornerShape(
            topStart = inner,
            topEnd = inner,
            bottomStart = outer,
            bottomEnd = outer,
        )
        else -> RoundedCornerShape(inner)
    }
}

@Composable
fun rememberRimBorder(rimColor: Color = MaterialTheme.velvet.rim): BorderStroke {
    return remember(rimColor) {
        BorderStroke(
            width = 1.dp,
            brush = Brush.verticalGradient(
                0.0f to rimColor,
                0.42f to rimColor.copy(alpha = rimColor.alpha * 0.30f),
                1.0f to Color.Transparent,
            ),
        )
    }
}

@Composable
fun VelvetCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.velvet.card,
    rimColor: Color = MaterialTheme.velvet.rim,
    shape: Shape = RoundedCornerShape(24.dp),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = rememberRimBorder(rimColor),
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content,
        )
    }
}

@Composable
fun VelvetGroup(
    modifier: Modifier = Modifier,
    spacing: Dp = 3.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

@Composable
fun VelvetGroupItem(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.velvet.card,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = velvetGroupShape(index, count)
    val rim = if (index == 0) MaterialTheme.velvet.rim else MaterialTheme.velvet.rim.copy(alpha = MaterialTheme.velvet.rim.alpha * 0.55f)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = containerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = rememberRimBorder(rim),
        ) {
            Column(
                modifier = Modifier.padding(contentPadding),
                content = content,
            )
        }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = containerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = rememberRimBorder(rim),
        ) {
            Column(
                modifier = Modifier.padding(contentPadding),
                content = content,
            )
        }
    }
}

@Composable
fun VelvetIconBadge(
    containerColor: Color = MaterialTheme.velvet.accentSoft,
    contentColor: Color = MaterialTheme.velvet.accent,
    size: Dp = 40.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(13.dp))
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

@Composable
fun MorphingPowerButton(
    isRunning: Boolean,
    isTransitioning: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val velvet = MaterialTheme.velvet
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val targetCorner = when {
        isPressed -> 14.dp
        isTransitioning -> 22.dp
        isRunning -> 20.dp
        else -> 34.dp
    }
    val cornerRadius by animateDpAsState(
        targetValue = targetCorner,
        animationSpec = spring(
            dampingRatio = 0.62f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "powerCorner",
    )
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
        label = "powerScale",
    )
    val containerColor by animateColorAsState(
        targetValue = when {
            isTransitioning -> velvet.accent.copy(alpha = 0.78f)
            isRunning -> velvet.accent
            else -> velvet.accent
        },
        animationSpec = tween(260),
        label = "powerBg",
    )

    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .size(66.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(containerColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = !isTransitioning,
                role = Role.Button,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isTransitioning) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.6.dp,
                color = velvet.onAccent,
            )
        } else {
            Icon(
                imageVector = if (isRunning) Icons.Rounded.Stop else Icons.Rounded.PowerSettingsNew,
                contentDescription = contentDescription,
                tint = velvet.onAccent,
                modifier = Modifier.size(if (isRunning) 28.dp else 27.dp),
            )
        }
    }
}

@Composable
fun ThroughputWaveCanvas(
    isRunning: Boolean,
    downHistory: List<Float>,
    upHistory: List<Float>,
    modifier: Modifier = Modifier,
) {
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme

    val phase = if (isRunning) {
        val transition = rememberInfiniteTransition(label = "velvetWave")
        val p by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2.0 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 4200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "wavePhase",
        )
        p
    } else {
        0f
    }

    val beaconPulse = if (isRunning) {
        val transition = rememberInfiniteTransition(label = "velvetBeacon")
        val b by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "beaconAlpha",
        )
        b
    } else {
        0f
    }

    val primaryColor = velvet.accent
    val secondaryColor = velvet.liveGreen
    val idleLineColor = cs.outline.copy(alpha = if (velvet.isDark) 0.34f else 0.28f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 4f || h <= 4f) return@Canvas

        val padRight = 10.dp.toPx()
        val usableW = (w - padRight).coerceAtLeast(1f)
        val baseLineY = h * 0.84f

        if (!isRunning) {
            drawLine(
                color = idleLineColor,
                start = Offset(0f, baseLineY),
                end = Offset(usableW, baseLineY),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
            return@Canvas
        }

        val n = downHistory.size.coerceAtLeast(2)
        var peakBps = 48f * 1024f
        for (v in downHistory) if (v > peakBps) peakBps = v
        for (v in upHistory) if (v > peakBps) peakBps = v

        fun buildWavePoints(series: List<Float>, carrierAmp: Float, phaseShift: Float): List<Offset> {
            val count = series.size.coerceAtLeast(2)
            val stepX = usableW / (count - 1).toFloat()
            val maxAmplitude = h * 0.68f
            return List(count) { idx ->
                val x = idx * stepX
                val raw = series[idx].coerceAtLeast(0f)
                // Soft sqrt curve makes both light chat traffic and heavy media downloads look balanced
                val norm = kotlin.math.sqrt((raw / max(peakBps, 1f)).coerceIn(0f, 1f))
                val carrier = (sin(phase + phaseShift + idx * 0.36f) * 0.5f + 0.5f) * carrierAmp
                val lift = (norm * maxAmplitude + carrier * h).coerceIn(2f, h * 0.78f)
                Offset(x, (baseLineY - lift).coerceIn(4f, baseLineY))
            }
        }

        fun buildSmoothPath(points: List<Offset>): Path {
            val path = Path()
            if (points.isEmpty()) return path
            path.moveTo(points[0].x, points[0].y)
            for (i in 0 until points.size - 1) {
                val p0 = points[i]
                val p1 = points[i + 1]
                val midX = (p0.x + p1.x) * 0.5f
                path.cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
            }
            return path
        }

        // Secondary upload stream (subtle emerald undertone)
        if (upHistory.size >= 2) {
            val upPoints = buildWavePoints(upHistory, carrierAmp = 0.045f, phaseShift = 1.7f)
            val upStrokePath = buildSmoothPath(upPoints)
            drawPath(
                path = upStrokePath,
                color = secondaryColor.copy(alpha = if (velvet.isDark) 0.42f else 0.34f),
                style = Stroke(
                    width = 1.6.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }

        // Primary download stream (sculpted sapphire curve + soft gradient fill + live tip beacon)
        val downPoints = buildWavePoints(
            series = if (downHistory.size >= 2) downHistory else List(n) { 0f },
            carrierAmp = 0.085f,
            phaseShift = 0f,
        )
        val strokePath = buildSmoothPath(downPoints)
        val fillPath = Path().apply {
            addPath(strokePath)
            lineTo(downPoints.last().x, h)
            lineTo(downPoints.first().x, h)
            close()
        }

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                0.0f to primaryColor.copy(alpha = if (velvet.isDark) 0.32f else 0.22f),
                0.78f to primaryColor.copy(alpha = if (velvet.isDark) 0.06f else 0.04f),
                1.0f to Color.Transparent,
                startY = 0f,
                endY = h,
            ),
        )
        drawPath(
            path = strokePath,
            color = primaryColor,
            style = Stroke(
                width = 2.4.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )

        val tip = downPoints.last()
        drawCircle(
            color = primaryColor.copy(alpha = 0.28f * beaconPulse),
            radius = 9.dp.toPx() * (0.85f + 0.3f * beaconPulse),
            center = tip,
        )
        drawCircle(
            color = primaryColor,
            radius = 3.8.dp.toPx(),
            center = tip,
        )
    }
}

@Composable
fun LiveRouteStrip(
    clientLabel: String,
    localEndpoint: String,
    upstreamLabel: String,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme

    val travelProgress = if (isRunning) {
        val transition = rememberInfiniteTransition(label = "routePacket")
        val p by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "packetProgress",
        )
        p
    } else {
        0f
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (isRunning) {
            cs.background.copy(alpha = if (velvet.isDark) 0.34f else 0.55f)
        } else {
            velvet.cardSub.copy(alpha = if (velvet.isDark) 0.65f else 0.72f)
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RouteNodeTag(
                text = clientLabel,
                active = isRunning,
                accent = false,
            )
            RouteConnectorWire(
                progress = if (isRunning) (travelProgress * 2f).coerceIn(0f, 1f) else -1f,
                active = isRunning,
                modifier = Modifier.weight(1f),
            )
            RouteNodeTag(
                text = localEndpoint,
                active = isRunning,
                accent = false,
                mono = true,
            )
            RouteConnectorWire(
                progress = if (isRunning) ((travelProgress - 0.5f) * 2f).coerceIn(0f, 1f) else -1f,
                active = isRunning,
                modifier = Modifier.weight(1f),
            )
            RouteNodeTag(
                text = upstreamLabel,
                active = isRunning,
                accent = isRunning,
            )
        }
    }
}

@Composable
private fun RouteNodeTag(
    text: String,
    active: Boolean,
    accent: Boolean,
    mono: Boolean = false,
) {
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme
    val bg = when {
        accent && active -> velvet.accentSoft
        active -> velvet.card.copy(alpha = if (velvet.isDark) 0.72f else 0.88f)
        else -> Color.Transparent
    }
    val fg = when {
        accent && active -> velvet.accent
        active -> cs.onSurface
        else -> cs.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        androidx.compose.material3.Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif,
                fontWeight = if (accent) FontWeight.Bold else FontWeight.Medium,
            ),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RouteConnectorWire(
    progress: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme
    val lineColor = if (active) {
        velvet.accent.copy(alpha = 0.34f)
    } else {
        cs.outline.copy(alpha = 0.28f)
    }
    val dotColor = velvet.liveGreen

    Canvas(modifier = modifier.height(10.dp)) {
        val midY = size.height * 0.5f
        drawLine(
            color = lineColor,
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
        if (active && progress in 0.02f..0.98f) {
            val x = size.width * progress
            drawCircle(
                color = dotColor.copy(alpha = 0.30f),
                radius = 5.dp.toPx(),
                center = Offset(x, midY),
            )
            drawCircle(
                color = dotColor,
                radius = 2.4.dp.toPx(),
                center = Offset(x, midY),
            )
        }
    }
}

@Composable
fun LiveStatusDot(
    isRunning: Boolean,
    isTransitioning: Boolean,
    size: Dp = 8.dp,
) {
    val velvet = MaterialTheme.velvet
    val cs = MaterialTheme.colorScheme
    val dotColor = when {
        isTransitioning -> velvet.warning
        isRunning -> velvet.liveGreen
        else -> cs.onSurfaceVariant.copy(alpha = 0.55f)
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(dotColor),
    )
}

@Composable
fun TgWsProxyTheme(
    themeMode: String,
    dynamicColors: Boolean,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    val context = LocalContext.current
    val useDynamic = dynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colorScheme = if (useDynamic) {
        if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (useDark) VelvetDarkScheme else VelvetLightScheme
    }

    val velvetColors = remember(useDark, useDynamic, colorScheme) {
        if (!useDynamic) {
            if (useDark) DarkVelvetColors else LightVelvetColors
        } else {
            val base = if (useDark) DarkVelvetColors else LightVelvetColors
            base.copy(
                bg = colorScheme.background,
                card = colorScheme.surfaceContainer,
                cardSub = colorScheme.surfaceContainerHigh,
                heroActive = colorScheme.primaryContainer,
                heroActiveRim = colorScheme.primary.copy(alpha = if (useDark) 0.24f else 0.18f),
                accent = colorScheme.primary,
                onAccent = colorScheme.onPrimary,
                accentSoft = colorScheme.primary.copy(alpha = if (useDark) 0.16f else 0.12f),
                consoleBg = colorScheme.surfaceContainerLowest,
            )
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !useDark
            controller.isAppearanceLightNavigationBars = !useDark
        }
    }

    CompositionLocalProvider(LocalVelvetColors provides velvetColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = VelvetTypography,
        ) {
            CompositionLocalProvider(LocalContentColor provides colorScheme.onBackground) {
                content()
            }
        }
    }
}
