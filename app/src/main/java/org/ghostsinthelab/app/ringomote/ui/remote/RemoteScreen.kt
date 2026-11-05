package org.ghostsinthelab.app.ringomote.ui.remote

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.ghostsinthelab.app.ringomote.R
import org.ghostsinthelab.app.ringomote.remote.RemoteControl
import org.ghostsinthelab.app.ringomote.settings.RemoteLayout
import org.ghostsinthelab.app.ringomote.ui.theme.RingomoteTheme

/** Largest share of the screen height the [RemoteScreen] header may take before it scrolls. */
private const val HEADER_MAX_FRACTION = 0.4f

/**
 * The remote itself, laid out like the 1st-generation Siri Remote: the [TouchSurface], then
 * Menu and TV (Home), Play/Pause, and Volume up, Mute and down, with [header] (the connection panel)
 * above it.
 *
 * The whole remote always fits on one screen, because scrolling it is unreliable: drags that
 * start on the touch surface or a button are remote input, not scrolling. So the layout is
 * sized to the screen instead of scrolled:
 * - Portrait: the [header] takes at most [HEADER_MAX_FRACTION] of the height and scrolls
 *   inside that, the buttons take what they need at the current font scale, and the touch
 *   surface is the largest square that fits in what is left; the remote is centred in the
 *   space below the header.
 * - Landscape: header and buttons scroll in a column on the start side; the touch surface is a
 *   square as tall as the screen on the end side.
 *
 * Stateless: [enabled] (true only while connected) and the input callbacks come from the
 * caller, normally [RemoteViewModel]. While disabled the remote is not shown at all: the
 * controls fade out, and fade back in with a slight scale-up when the link is connected
 * ([RemoteFade]), so nothing can be tapped or focused while input would go nowhere. The
 * [header]'s status row says why, so the remote carries no hint text of its own.
 *
 * [layout]: [RemoteLayout.Compact] keeps the controls at their natural size, grouped in the
 * middle; [RemoteLayout.Spread] lets the touch surface fill the width and stretches the
 * buttons into a grid that reaches the screen edges, with wider gaps.
 *
 * [disabledControls] are shown but disabled while the rest is enabled: the active link cannot
 * carry them (Mute over Wi-Fi).
 */
@Composable
fun RemoteScreen(
    enabled: Boolean,
    onPress: (RemoteControl) -> Unit,
    onRelease: (RemoteControl) -> Unit,
    onTap: (RemoteControl) -> Unit,
    onHold: (RemoteControl) -> Unit,
    modifier: Modifier = Modifier,
    layout: RemoteLayout = RemoteLayout.Compact,
    disabledControls: Set<RemoteControl> = emptySet(),
    header: @Composable () -> Unit = {},
) {
    val spread = layout == RemoteLayout.Spread
    val gap = if (spread) 24.dp else 16.dp

    @Composable
    fun Surface(sizing: Modifier) = TouchSurface(
        enabled = enabled,
        onStep = onTap,
        onSelect = { onTap(RemoteControl.Select) },
        onHoldSelect = { onHold(RemoteControl.Select) },
        modifier = sizing,
    )

    @Composable
    fun Buttons() = RemoteButtons(enabled, spread, disabledControls, onPress, onRelease, onTap, onHold)

    @Composable
    fun Remote(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
        RemoteFade(visible = enabled, modifier = modifier, content = content)

    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    header()
                    Remote(Modifier.fillMaxWidth()) { Buttons() }
                }
                Remote(Modifier.fillMaxHeight()) {
                    Surface(Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true))
                }
            }
        } else {
            val headerMaxHeight = maxHeight * HEADER_MAX_FRACTION
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = headerMaxHeight)
                        .verticalScroll(rememberScrollState()),
                ) { header() }
                // The remote itself, centred in the space the header leaves.
                Remote(Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically),
                    ) {
                        Surface(
                            Modifier
                                .weight(1f, fill = false)
                                .then(if (spread) Modifier.fillMaxWidth() else Modifier.widthIn(max = 480.dp))
                                .aspectRatio(1f, matchHeightConstraintsFirst = true),
                        )
                        Buttons()
                    }
                }
            }
        }
    }
}

/** How long the remote takes to fade in; quick and subtle, as a system transition would be. */
private const val FADE_IN_MILLIS = 250

/** The remote leaves faster than it arrives, so a dropped link does not linger on screen. */
private const val FADE_OUT_MILLIS = 150

/**
 * Shows [content] only while [visible]: a fade with a slight scale-up on the way in, a plain
 * fade on the way out. Compose scales these durations by the system animator setting, so
 * "remove animations" makes the switch instant.
 */
@Composable
private fun RemoteFade(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(FADE_IN_MILLIS)) + scaleIn(tween(FADE_IN_MILLIS), initialScale = 0.96f),
        exit = fadeOut(tween(FADE_OUT_MILLIS)),
    ) { content() }
}

/**
 * Menu | TV, Play/Pause, and Volume + | Mute | −, as on the physical remote. With [spread] each row
 * fills the width and its buttons share it equally, so the grid reaches the screen edges.
 */
@Composable
private fun RemoteButtons(
    enabled: Boolean,
    spread: Boolean,
    disabledControls: Set<RemoteControl>,
    onPress: (RemoteControl) -> Unit,
    onRelease: (RemoteControl) -> Unit,
    onTap: (RemoteControl) -> Unit,
    onHold: (RemoteControl) -> Unit,
) {
    @Composable
    fun ControlButton(
        control: RemoteControl,
        @StringRes label: Int,
        @StringRes description: Int,
        labelStyle: TextStyle = MaterialTheme.typography.titleMedium,
        @DrawableRes icon: Int? = null,
        modifier: Modifier = Modifier,
    ) = RemoteButton(
        modifier = modifier,
        control = control,
        label = stringResource(label),
        contentDescription = stringResource(description),
        enabled = enabled && control !in disabledControls,
        onPress = onPress,
        onRelease = onRelease,
        onTap = onTap,
        onHold = onHold,
        labelStyle = labelStyle,
        icon = icon?.let { painterResource(it) },
    )

    // "+" and "−" are single thin glyphs; draw them large and bold so they stay visible.
    val symbolStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)

    val gap = if (spread) 24.dp else 16.dp
    val cell = if (spread) Modifier.fillMaxWidth() else Modifier
    // In a Row, weight() is what makes the buttons share the width; it needs the RowScope.
    @Composable
    fun RowScope.cell() = if (spread) Modifier.weight(1f) else Modifier

    Column(
        modifier = cell,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        Row(modifier = cell, horizontalArrangement = Arrangement.spacedBy(if (spread) gap else 24.dp)) {
            ControlButton(
                RemoteControl.Menu,
                R.string.button_menu_label,
                R.string.button_menu_description,
                icon = R.drawable.ic_menu,
                modifier = cell(),
            )
            ControlButton(
                RemoteControl.Home,
                R.string.button_home_label,
                R.string.button_home_description,
                icon = R.drawable.ic_tv,
                modifier = cell(),
            )
        }
        ControlButton(
            RemoteControl.PlayPause,
            R.string.button_play_pause_label,
            R.string.button_play_pause_description,
            icon = R.drawable.ic_play_pause,
            modifier = cell,
        )
        Row(modifier = cell, horizontalArrangement = Arrangement.spacedBy(if (spread) gap else 24.dp)) {
            ControlButton(
                RemoteControl.VolumeUp,
                R.string.button_volume_up_label,
                R.string.button_volume_up_description,
                symbolStyle,
                modifier = cell(),
            )
            ControlButton(
                RemoteControl.Mute,
                R.string.button_mute_label,
                R.string.button_mute_description,
                icon = R.drawable.ic_volume_off,
                modifier = cell(),
            )
            ControlButton(
                RemoteControl.VolumeDown,
                R.string.button_volume_down_label,
                R.string.button_volume_down_description,
                symbolStyle,
                modifier = cell(),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun RemoteScreenConnectedPreview() {
    RingomoteTheme {
        RemoteScreen(enabled = true, onPress = {}, onRelease = {}, onTap = {}, onHold = {})
    }
}

@Preview(showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun RemoteScreenDisabledPreview() {
    RingomoteTheme {
        RemoteScreen(enabled = false, onPress = {}, onRelease = {}, onTap = {}, onHold = {})
    }
}

@Preview(showBackground = true, widthDp = 860, heightDp = 400)
@Composable
private fun RemoteScreenLandscapePreview() {
    RingomoteTheme {
        RemoteScreen(enabled = true, onPress = {}, onRelease = {}, onTap = {}, onHold = {})
    }
}
