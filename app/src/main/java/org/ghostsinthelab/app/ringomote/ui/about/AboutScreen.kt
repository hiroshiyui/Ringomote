package org.ghostsinthelab.app.ringomote.ui.about

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.ghostsinthelab.app.ringomote.R
import org.ghostsinthelab.app.ringomote.ui.theme.RingomoteTheme

private const val REPOSITORY_URL = "https://github.com/hiroshiyui/Ringomote"
private const val LICENSE_URL = "$REPOSITORY_URL/blob/main/LICENSE"
private const val NOTICES_URL = "$REPOSITORY_URL/blob/main/NOTICES.md"

/**
 * The About tab: app identity, version, privacy, license, open-source notices and trademarks.
 *
 * The texts summarise `LICENSE`, `NOTICES.md` and the README's trademark section; keep them in
 * step when those change. Links open in the browser through an explicit `ACTION_VIEW` (the app
 * itself has no internet permission); if no browser is installed, the tap does nothing rather
 * than crash. Section titles are headings, so TalkBack users can jump between them.
 */
@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version = remember(context) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName.orEmpty() to info.longVersionCode
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppIcon()
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.about_version, version.first, version.second),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.about_tagline),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.about_copyright),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HorizontalDivider()
            Section(R.string.about_privacy_title, R.string.about_privacy_body)
            Section(
                R.string.about_license_title,
                R.string.about_license_body,
                R.string.about_license_link to LICENSE_URL,
                R.string.about_source_link to REPOSITORY_URL,
            )
            Section(
                R.string.about_oss_title,
                R.string.about_oss_body,
                R.string.about_notices_link to NOTICES_URL,
            )
            Section(R.string.about_trademarks_title, R.string.about_trademarks_body)
        }
    }
}

/**
 * The adaptive launcher icon, drawn from its two layers (adaptive icons are not painters).
 * Launchers show only the central 72 of the layers' 108 units, so the layers are drawn 108/72
 * times larger than the visible circle to look the same as on the home screen.
 */
@Composable
private fun AppIcon() {
    val visible = 96.dp
    val layer = visible * (108f / 72f)
    Box(Modifier.size(visible).clip(CircleShape), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.requiredSize(layer))
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(layer))
    }
}

/** A titled paragraph, followed by any [links] as (label, URL) pairs. */
@Composable
private fun Section(
    @StringRes title: Int,
    @StringRes body: Int,
    vararg links: Pair<Int, String>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(text = stringResource(body), style = MaterialTheme.typography.bodyMedium)
        links.forEach { (label, url) -> Link(stringResource(label), url) }
    }
}

/** A text link that opens [url] in the browser. */
@Composable
private fun Link(label: String, url: String) {
    val context = LocalContext.current
    val style = TextLinkStyles(
        SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline),
    )
    val text = buildAnnotatedString {
        withLink(
            LinkAnnotation.Url(url, style) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                } catch (_: ActivityNotFoundException) {
                    // No browser installed: nothing sensible to open.
                }
            },
        ) { append(label) }
    }
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun AboutScreenPreview() {
    RingomoteTheme { AboutScreen() }
}
