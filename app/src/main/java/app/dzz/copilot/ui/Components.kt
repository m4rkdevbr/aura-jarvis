package app.dzz.copilot.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AppBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .background(AtmosphereBrush)
            .background(AuraGlowBrush),
    ) {
        content()
    }
}

@Composable
fun PageHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = Paper,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = mutedStyle(),
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        action?.invoke()
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedGlass(
        modifier = modifier.fillMaxWidth(),
        radius = Space.radiusLg,
        elevation = 10.dp,
    ) {
        Column(
            Modifier.padding(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = Copper,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

@Composable
fun HeroCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Space.radiusXl)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(18.dp, shape, ambientColor = Copper.copy(alpha = 0.35f), spotColor = Copper.copy(alpha = 0.45f))
            .clip(shape)
            .background(CopperBrush)
            .background(HeroSheen)
            .border(1.dp, Color.White.copy(alpha = 0.22f), shape)
            .padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
        content = content,
    )
}

@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    PressSurface(enabled = onClick != null, onClick = onClick) { scaleMod ->
        ElevatedGlass(
            modifier = modifier
                .fillMaxWidth()
                .then(scaleMod),
            radius = Space.radius,
            elevation = 6.dp,
        ) {
            Column(
                Modifier.padding(Space.md),
                verticalArrangement = Arrangement.spacedBy(Space.sm),
                content = content,
            )
        }
    }
}

@Composable
fun ActionRow(
    title: String,
    detail: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accent: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    GlassPanel(modifier = modifier, onClick = onClick) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (accent) CopperSoft else GlassHigh)
                    .border(1.dp, if (accent) Copper.copy(alpha = 0.5f) else GlassEdge, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (accent) Copper else MutedField,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (accent) Copper else Paper,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    detail,
                    style = mutedStyle(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailing?.invoke()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PillRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = { content() },
    )
}

@Composable
fun FeatureCard(
    title: String,
    detail: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    PressSurface(enabled = onClick != null && enabled, onClick = onClick) { scaleMod ->
        ElevatedGlass(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 92.dp)
                .then(scaleMod),
            radius = Space.radius,
            elevation = 8.dp,
            muted = !enabled,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(Space.md),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (icon != null) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(CopperSoft)
                                .border(1.dp, Copper.copy(alpha = 0.45f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(icon, null, tint = Copper, modifier = Modifier.size(17.dp))
                        }
                    }
                    Text(
                        title,
                        color = if (enabled) Paper else MutedField,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    detail,
                    style = mutedStyle(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun MetricCard(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    PressSurface(enabled = onClick != null, onClick = onClick) { scaleMod ->
        ElevatedGlass(
            modifier = modifier.then(scaleMod),
            radius = 20.dp,
            elevation = 8.dp,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    value,
                    style = MaterialTheme.typography.titleLarge,
                    color = Copper,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MutedField,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun ShortcutChip(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    PressSurface(onClick = onClick) { scaleMod ->
        Column(
            modifier
                .then(scaleMod)
                .shadow(8.dp, RoundedCornerShape(22.dp), ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Copper.copy(alpha = 0.25f))
                .clip(RoundedCornerShape(22.dp))
                .background(GlassHigh)
                .background(GlassBrush)
                .border(1.dp, GlassEdge, RoundedCornerShape(22.dp))
                .padding(vertical = 14.dp, horizontal = 6.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .shadow(6.dp, CircleShape, ambientColor = Copper.copy(alpha = 0.3f), spotColor = Copper.copy(alpha = 0.4f))
                    .clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(Copper.copy(alpha = 0.28f), Copper.copy(alpha = 0.08f))))
                    .border(1.dp, Copper.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = label, tint = Copper, modifier = Modifier.size(22.dp))
            }
            Text(
                label,
                color = Paper,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun WideButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Space.touch)
            .shadow(if (enabled) 10.dp else 0.dp, RoundedCornerShape(18.dp), spotColor = Copper.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Copper,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = GlassLine,
            disabledContentColor = MutedField,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 2.dp),
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun StatusPill(label: String, accent: Boolean) {
    Surface(
        color = if (accent) CopperSoft else GlassHigh,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(
            1.dp,
            if (accent) Copper.copy(alpha = 0.55f) else GlassEdge,
        ),
        shadowElevation = if (accent) 3.dp else 0.dp,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            color = if (accent) Copper else MutedField,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun RowScope.ActionButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .weight(1f)
            .heightIn(min = Space.touch),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Copper,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = GlassLine,
            disabledContentColor = MutedField,
        ),
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun RowScope.QuietButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .weight(1f)
            .heightIn(min = Space.touch),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, if (enabled) Copper.copy(alpha = 0.45f) else GlassLine),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (enabled) Paper else MutedField),
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun FeatureGrid(content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm), content = content)
}

@Composable
fun TwoUp(left: @Composable RowScope.() -> Unit, right: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.Top,
    ) {
        left()
        right()
    }
}

@Composable
fun TwoCol(
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Box(Modifier.weight(1f)) { first() }
        Box(Modifier.weight(1f)) { second() }
    }
}

@Composable
fun HeaderIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    PressSurface(onClick = onClick) { scaleMod ->
        Box(
            Modifier
                .then(scaleMod)
                .size(44.dp)
                .shadow(8.dp, CircleShape, spotColor = Copper.copy(alpha = 0.3f))
                .clip(CircleShape)
                .background(GlassHigh)
                .border(1.dp, GlassEdge, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = Copper, modifier = Modifier.size(22.dp))
        }
    }
}

fun screenPadding(): PaddingValues = PaddingValues(
    start = Space.lg,
    end = Space.lg,
    top = Space.md,
    bottom = Space.xl,
)

@Composable
private fun ElevatedGlass(
    modifier: Modifier = Modifier,
    radius: Dp,
    elevation: Dp,
    muted: Boolean = false,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.45f),
                spotColor = Copper.copy(alpha = 0.18f),
            )
            .clip(shape)
            .background(if (muted) GlassSolid.copy(alpha = 0.7f) else Glass)
            .background(GlassBrush)
            .border(1.dp, if (muted) GlassLine else GlassEdge, shape),
    ) {
        content()
    }
}

@Composable
private fun PressSurface(
    enabled: Boolean = true,
    onClick: (() -> Unit)?,
    content: @Composable (Modifier) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "press",
    )
    Box(
        Modifier
            .scale(scale)
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        content(Modifier)
    }
}
