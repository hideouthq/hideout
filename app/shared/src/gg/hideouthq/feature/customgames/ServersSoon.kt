package gg.hideouthq.feature.customgames

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.HideoutSwitch
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.insetRing
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.cg_servers_badge
import gg.hideouthq.resources.cg_servers_body
import gg.hideouthq.resources.cg_servers_hideout
import gg.hideouthq.resources.cg_servers_hideout_sub
import gg.hideouthq.resources.cg_servers_host_instead
import gg.hideouthq.resources.cg_servers_notify
import gg.hideouthq.resources.cg_servers_notify_off
import gg.hideouthq.resources.cg_servers_notify_on
import gg.hideouthq.resources.cg_servers_player
import gg.hideouthq.resources.cg_servers_player_sub
import gg.hideouthq.resources.cg_servers_quick_join
import gg.hideouthq.resources.cg_servers_quick_join_sub
import gg.hideouthq.resources.cg_servers_title
import gg.hideouthq.resources.servers_globe
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ServersSoon(notify: Boolean, onNotify: (Boolean) -> Unit, onHost: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val colors = HideoutTheme.colors
    val type = HideoutTheme.typography
    val teal = colors.sectionCustomGames
    val shape = RoundedCornerShape(32.dp)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surfaceContainer)
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(teal.copy(alpha = 0.12f), Color.Transparent), Offset(size.width * 0.85f, size.height * 0.3f), size.width * 0.5f))
                drawRect(Brush.radialGradient(listOf(colors.caution.copy(alpha = 0.07f), Color.Transparent), Offset(0f, size.height), size.width * 0.36f))
            }
            .insetRing(shape, Color.White.copy(alpha = 0.04f)) { 1.dp.toPx() },
    ) {
        val globe = maxWidth >= GlobeFrom
        if (globe) GridBehindGlobe(Modifier.matchParentSize())
        Row(
            Modifier.fillMaxWidth().padding(start = 44.dp, top = 40.dp, end = 36.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    Modifier.height(30.dp).background(teal.copy(alpha = 0.14f), CircleShape).padding(start = 10.dp, end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).drawBehind {
                        drawCircle(teal.copy(alpha = 0.18f), size.minDimension / 2 + 4.dp.toPx())
                        drawCircle(teal)
                    })
                    Text(stringResource(Res.string.cg_servers_badge), style = type.caption.copy(fontSize = 12.5.sp, fontWeight = HideoutWeights.Heavy, letterSpacing = 0.02.em), color = teal)
                }
                Text(
                    stringResource(Res.string.cg_servers_title),
                    Modifier.padding(top = 2.dp).semantics { heading() },
                    style = type.displayTitle.copy(fontSize = 40.sp, lineHeight = 42.sp), color = scheme.onSurface,
                )
                Text(stringResource(Res.string.cg_servers_body), Modifier.widthIn(max = 520.dp), style = type.body.copy(fontSize = 15.sp, lineHeight = 23.sp), color = scheme.onSurfaceVariant)
                Column(Modifier.padding(top = 8.dp).widthIn(max = 540.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Feature(HideoutIcons.Verified, 20.dp, teal.copy(alpha = 0.16f), teal, stringResource(Res.string.cg_servers_hideout), stringResource(Res.string.cg_servers_hideout_sub))
                    Feature(HideoutIcons.Play, 18.dp, colors.caution.copy(alpha = 0.14f), colors.caution, stringResource(Res.string.cg_servers_quick_join), stringResource(Res.string.cg_servers_quick_join_sub))
                    Feature(HideoutIcons.Users, 20.dp, scheme.surfaceContainerHigh, scheme.onSurface, stringResource(Res.string.cg_servers_player), stringResource(Res.string.cg_servers_player_sub))
                }
                FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    HideoutButton(stringResource(Res.string.cg_servers_host_instead), onHost, leadingIcon = HideoutIcons.Play, leadingIconSize = 16.dp)
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val label = stringResource(Res.string.cg_servers_notify)
                        HideoutSwitch(notify, onNotify, contentDescription = label)
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(label, style = type.bodyStrong.copy(fontSize = 13.5.sp, fontWeight = HideoutWeights.Label), color = scheme.onSurface)
                            Text(stringResource(if (notify) Res.string.cg_servers_notify_on else Res.string.cg_servers_notify_off), style = type.caption.copy(fontSize = 12.sp), color = scheme.outline)
                        }
                    }
                }
            }
            if (globe) Globe()
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, iconSize: Dp, tile: Color, ink: Color, title: String, sub: String) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    Row(
        Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.035f), RoundedCornerShape(18.dp)).padding(start = 10.dp, top = 10.dp, end = 14.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(tile, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            HideoutIcon(icon, null, size = iconSize, tint = ink)
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = type.bodyStrong.copy(fontSize = 14.5.sp, fontWeight = HideoutWeights.Title), color = scheme.onSurface)
            Text(sub, style = type.caption.copy(fontSize = 12.5.sp), color = scheme.outline)
        }
    }
}

@Composable
private fun GridBehindGlobe(modifier: Modifier) {
    Box(
        modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
            val line = Color.White.copy(alpha = 0.025f)
            val step = 28.dp.toPx()
            val width = 1.dp.toPx()
            var x = 0f
            while (x < size.width) { drawLine(line, Offset(x, 0f), Offset(x, size.height), width); x += step }
            var y = 0f
            while (y < size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y), width); y += step }
            drawRect(
                Brush.radialGradient(0f to Color.Black, 0.75f to Color.Transparent, center = Offset(size.width * 0.8f, size.height * 0.4f), radius = size.width * 0.6f),
                blendMode = BlendMode.DstIn,
            )
        },
    )
}

@Composable
private fun Globe() {
    val colors = HideoutTheme.colors
    val nodes = listOf(
        Node(273.1f, 151.6f, colors.sectionCustomGames, "EU West", "24 ms", 145.1f, 165.6f, 124f, delayed = false),
        Node(295.6f, 137.3f, colors.caution, "EU Central", "31 ms", 311.6f, 99.3f, 145f, delayed = true),
        Node(147.3f, 160.7f, colors.match, "NA East", "112 ms", 7.3f, 118.7f, 130.4f, delayed = false),
    )
    val pulses = if (HideoutTheme.motion.reduced) emptyList() else pulses()
    Box(Modifier.size(460.dp, 380.dp).clearAndSetSemantics {}) {
        Image(painterResource(Res.drawable.servers_globe), null, Modifier.fillMaxSize())
        if (pulses.isNotEmpty()) Box(
            Modifier.fillMaxSize().drawBehind {
                nodes.forEach { node ->
                    val at = pulses[if (node.delayed) 1 else 0].value
                    val grow = 1f + 3f * at
                    drawCircle(node.color.copy(alpha = 0.9f * (1f - at)), 5.dp.toPx() * grow, Offset(node.x.dp.toPx(), node.y.dp.toPx()), style = Stroke(1.5.dp.toPx() * grow))
                }
            },
        )
        nodes.forEach { NodeLabel(it) }
    }
}

@Composable
private fun pulses(): List<State<Float>> {
    val transition = rememberInfiniteTransition(label = "serverPulses")
    return listOf(0, PulseLagMillis).map { lag ->
        transition.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(PulseMillis, easing = LinearOutSlowInEasing), RepeatMode.Restart, StartOffset(lag)),
            label = "serverPulse",
        )
    }
}

@Composable
private fun NodeLabel(node: Node) {
    val type = HideoutTheme.typography
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(13.dp)
    Row(
        Modifier
            .offset(node.labelX.dp, node.labelY.dp)
            .size(node.labelWidth.dp, 26.dp)
            .background(LabelGround, shape)
            .insetRing(shape, Color.White.copy(alpha = 0.1f)) { 1.dp.toPx() }
            .padding(start = 9.5.dp, end = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(6.5.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(node.color, CircleShape))
        Text(node.name, Modifier.weight(1f), style = type.caption.copy(fontSize = 12.sp, fontWeight = HideoutWeights.Title), color = scheme.onSurface, maxLines = 1, softWrap = false)
        Text(node.ping, style = type.caption.copy(fontSize = 12.sp), color = scheme.outline, maxLines = 1, softWrap = false)
    }
}

private class Node(val x: Float, val y: Float, val color: Color, val name: String, val ping: String, val labelX: Float, val labelY: Float, val labelWidth: Float, val delayed: Boolean)

private val LabelGround = Color(0xE00E1111)
private val GlobeFrom = 900.dp
private const val PulseMillis = 2400
private const val PulseLagMillis = 800
