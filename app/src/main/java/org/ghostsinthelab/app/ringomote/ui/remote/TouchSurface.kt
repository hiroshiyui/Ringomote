package org.ghostsinthelab.app.ringomote.ui.remote

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.ghostsinthelab.app.ringomote.R
import org.ghostsinthelab.app.ringomote.remote.RemoteControl
import kotlin.math.abs

/** Finger travel along the dominant axis that produces one direction step. */
private val StepDistance = 48.dp

/**
 * The Siri Remote's touch surface, reproduced as discrete arrow-key steps.
 *
 * A drag emits one [onStep] (Up, Down, Left or Right) every [StepDistance] of travel along its
 * dominant axis, measured from where the previous step was emitted, with a haptic tick per step.
 * The Apple TV receives arrow keys rather than touch coordinates because the classic HID
 * keyboard profile tvOS accepts has no touch pad. A touch that ends before any step and without
 * leaving the touch slop calls [onSelect]. Directions are physical: they do not flip in RTL.
 *
 * TalkBack intercepts swipes for its own navigation, so the surface also exposes each direction
 * as a custom accessibility action, click as Select and long click as [onHoldSelect].
 */
@Composable
fun TouchSurface(
    enabled: Boolean,
    onStep: (RemoteControl) -> Unit,
    onSelect: () -> Unit,
    onHoldSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val currentOnStep by rememberUpdatedState(onStep)
    val currentOnSelect by rememberUpdatedState(onSelect)

    val description = stringResource(R.string.touch_surface_description)
    val selectLabel = stringResource(R.string.action_select)
    val holdLabel = stringResource(R.string.action_hold)
    val swipeActions = listOf(
        R.string.action_swipe_up to RemoteControl.Up,
        R.string.action_swipe_down to RemoteControl.Down,
        R.string.action_swipe_left to RemoteControl.Left,
        R.string.action_swipe_right to RemoteControl.Right,
    ).map { (labelRes, control) ->
        CustomAccessibilityAction(stringResource(labelRes)) { onStep(control); true }
    }

    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = if (enabled) colors.surfaceVariant else colors.onSurface.copy(alpha = 0.08f),
        contentColor = if (enabled) colors.onSurfaceVariant else colors.onSurface.copy(alpha = 0.38f),
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clearAndSetSemantics {
                contentDescription = description
                if (enabled) {
                    onClick(label = selectLabel) { onSelect(); true }
                    onLongClick(label = holdLabel) { onHoldSelect(); true }
                    customActions = swipeActions
                } else {
                    disabled()
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val stepPx = StepDistance.toPx()
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var origin = down.position
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            if (!moved) currentOnSelect()
                            break
                        }
                        if (change.isConsumed) break // Taken by someone else: cancelled.
                        change.consume()
                        val delta = change.position - origin
                        if (!moved && (change.position - down.position).getDistance() > slop) {
                            moved = true
                        }
                        val step = stepFor(delta, stepPx) ?: continue
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        currentOnStep(step)
                        origin = change.position
                    }
                }
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.touch_surface_label),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

/** The direction for [delta] once its dominant axis reaches [stepPx], or null before that. */
private fun stepFor(delta: Offset, stepPx: Float): RemoteControl? = when {
    abs(delta.x) >= abs(delta.y) && abs(delta.x) >= stepPx ->
        if (delta.x > 0) RemoteControl.Right else RemoteControl.Left
    abs(delta.y) > abs(delta.x) && abs(delta.y) >= stepPx ->
        if (delta.y > 0) RemoteControl.Down else RemoteControl.Up
    else -> null
}
