package org.ghostsinthelab.app.ringomote.ui.remote

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.ghostsinthelab.app.ringomote.R
import org.ghostsinthelab.app.ringomote.remote.RemoteControl

/**
 * One physical remote button, e.g. Menu or Volume up.
 *
 * Touch input is forwarded as raw press and release, so holding the button holds the key on the
 * Apple TV for as long as the finger stays down: [onPress] fires on finger down and [onRelease]
 * on finger up, on gesture cancellation, and when the button is disabled or leaves the
 * composition mid-press, so a key can never stay stuck.
 *
 * Accessibility services do not produce pointer events; they use the semantics actions instead:
 * click calls [onTap] and long click calls [onHold] (a timed hold done by the caller), so
 * press-and-hold does not depend on the user's timing. [contentDescription] names what the
 * button does on the TV; [label] is the short visible text.
 *
 * When [enabled] is false the button is dimmed, ignores touches and is announced as disabled.
 * The callbacks are plain lambdas so the button can be previewed without a ViewModel.
 */
@Composable
fun RemoteButton(
    control: RemoteControl,
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onPress: (RemoteControl) -> Unit,
    onRelease: (RemoteControl) -> Unit,
    onTap: (RemoteControl) -> Unit,
    onHold: (RemoteControl) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
) {
    val haptic = LocalHapticFeedback.current
    val holdLabel = stringResource(R.string.action_hold)
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnRelease by rememberUpdatedState(onRelease)
    var pressed by remember { mutableStateOf(false) }

    val colors = MaterialTheme.colorScheme
    val container = when {
        !enabled -> colors.onSurface.copy(alpha = 0.12f)
        pressed -> colors.primary
        else -> colors.secondaryContainer
    }
    val content = when {
        !enabled -> colors.onSurface.copy(alpha = 0.38f)
        pressed -> colors.onPrimary
        else -> colors.onSecondaryContainer
    }

    Surface(
        shape = shape,
        color = container,
        contentColor = content,
        modifier = modifier
            .widthIn(min = 64.dp)
            .heightIn(min = 56.dp)
            .clearAndSetSemantics {
                role = Role.Button
                this.contentDescription = contentDescription
                if (enabled) {
                    onClick { onTap(control); true }
                    onLongClick(label = holdLabel) { onHold(control); true }
                } else {
                    disabled()
                }
            }
            .pointerInput(control, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown().consume()
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    currentOnPress(control)
                    try {
                        waitForUpOrCancellation()?.consume()
                    } finally {
                        // Also runs when this input block is cancelled (disabled, disposed).
                        pressed = false
                        currentOnRelease(control)
                    }
                }
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}
