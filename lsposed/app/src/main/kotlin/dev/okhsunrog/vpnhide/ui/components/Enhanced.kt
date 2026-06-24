package dev.okhsunrog.vpnhide.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import dev.okhsunrog.vpnhide.ui.theme.AppMotion

/*
 * Enhanced Material 3 controls: a light haptic tick on activation plus a spring
 * press-scale, for a tactile, expressive feel. Adapted from ImageToolbox's
 * EnhancedButton / EnhancedSwitch (Apache-2.0, © T8RIN).
 */

@Composable
fun EnhancedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    shape: Shape = MaterialTheme.shapes.large,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, AppMotion.fastSpatial())
    val tick = rememberHapticTick()
    Button(
        onClick = {
            tick()
            onClick()
        },
        modifier =
            modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        enabled = enabled,
        colors = colors,
        shape = shape,
        interactionSource = interactionSource,
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
fun EnhancedOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.large,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, AppMotion.fastSpatial())
    val tick = rememberHapticTick()
    OutlinedButton(
        onClick = {
            tick()
            onClick()
        },
        modifier =
            modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        enabled = enabled,
        shape = shape,
        interactionSource = interactionSource,
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
fun EnhancedIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, AppMotion.fastSpatial())
    val tick = rememberHapticTick()
    IconButton(
        onClick = {
            tick()
            onClick()
        },
        modifier =
            modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}

@Composable
fun EnhancedSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tick = rememberHapticTick()
    Switch(
        checked = checked,
        onCheckedChange =
            onCheckedChange?.let { cb ->
                { value ->
                    tick()
                    cb(value)
                }
            },
        modifier = modifier,
        enabled = enabled,
    )
}
