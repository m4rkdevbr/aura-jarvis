package app.dzz.copilot.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dzz.copilot.voice.AuraPhase

/** What the orb shows: idle, listening for "Aura", in a conversation, or working. */
fun orbLabel(phase: AuraPhase): String = when (phase) {
    AuraPhase.Off -> "Desligada"
    AuraPhase.Listening -> "Esperando \"Aura\""
    AuraPhase.Awake -> "Conversando"
    AuraPhase.Running -> "Pensando"
}

/**
 * Aura's presence: a glowing violet-to-cyan orb that breathes while she waits, pulses faster
 * while she talks or works, and goes grey when off. Tapping it starts a conversation.
 */
@Composable
fun AuraOrb(phase: AuraPhase, size: Dp = 200.dp, onClick: (() -> Unit)? = null) {
    val transition = rememberInfiniteTransition(label = "orb")
    val speed = when (phase) {
        AuraPhase.Off -> 4_000
        AuraPhase.Listening -> 2_600
        AuraPhase.Awake -> 1_100
        AuraPhase.Running -> 800
    }
    val breath by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = if (phase == AuraPhase.Off) 0.96f else 1.04f,
        animationSpec = infiniteRepeatable(tween(speed, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(speed * 4, easing = LinearEasing)),
        label = "spin",
    )
    val off = phase == AuraPhase.Off
    val core = if (off) listOf(Color(0xFF3A3F55), Color(0xFF1A1D2B)) else listOf(Cyan, Violet, Color(0xFF2B1B6B))
    val clickModifier = if (onClick != null) {
        Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    } else {
        Modifier
    }
    Box(Modifier.size(size).then(clickModifier), contentAlignment = Alignment.Center) {
        // Soft halo.
        Box(
            Modifier
                .fillMaxSize()
                .scale(breath * 1.08f)
                .background(
                    Brush.radialGradient(listOf((if (off) Color.Gray else Violet).copy(alpha = 0.35f), Color.Transparent)),
                    CircleShape,
                ),
        )
        // Rotating ring.
        Canvas(Modifier.fillMaxSize(0.86f).rotate(spin)) {
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(Color.Transparent, (if (off) Color.Gray else Cyan).copy(alpha = 0.9f), Color.Transparent),
                ),
                style = Stroke(width = 3.dp.toPx()),
            )
        }
        // Core.
        Box(
            Modifier
                .fillMaxSize(0.66f)
                .scale(breath)
                .clip(CircleShape)
                .background(Brush.radialGradient(core, center = Offset.Unspecified))
                .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
        )
    }
}

/** Big rounded action tile for the home grid. */
@Composable
fun ActionTile(label: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .clip(shape)
            .background(GlassHigh)
            .border(1.dp, GlassEdge, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(CopperBrush),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = Paper,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** Small coloured dot + text, for connection and microphone state. */
@Composable
fun StatusDot(label: String, on: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) FieldGreen else MutedField.copy(alpha = 0.6f)))
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) Paper else MutedField, fontWeight = FontWeight.Medium)
    }
}
