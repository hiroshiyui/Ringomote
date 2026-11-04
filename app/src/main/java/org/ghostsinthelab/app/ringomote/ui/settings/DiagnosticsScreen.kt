package org.ghostsinthelab.app.ringomote.ui.settings

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ghostsinthelab.app.ringomote.R

/** File name offered by the system "save" dialog. */
private const val REPORT_FILE_NAME = "ringomote-diagnostics.txt"

/**
 * Shows the Bluetooth diagnostics report (see
 * [org.ghostsinthelab.app.ringomote.diagnostics.DiagnosticsReport]) with four actions:
 * refresh (re-run [buildReport]), copy to the clipboard, share through any app the user
 * picks, and save as a text file through the system document picker. The report is plain
 * text in a monospace font, selectable, and scrolls both ways so long lines are not wrapped
 * out of shape. Back (the system gesture or the button) calls [onBack].
 *
 * The report is built on [Dispatchers.IO] because it queries the Bluetooth adapter; the UI
 * shows nothing until the first build finishes, which takes milliseconds.
 */
@Composable
fun DiagnosticsScreen(
    buildReport: () -> String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf(buildReport()) }
    val copied = stringResource(R.string.diagnostics_copied)
    val saved = stringResource(R.string.diagnostics_saved)
    val shareTitle = stringResource(R.string.diagnostics_share)

    BackHandler(onBack = onBack)

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(report.toByteArray()) } != null
                }.getOrDefault(false)
            }
            if (ok) Toast.makeText(context, saved, Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.diagnostics_back)) }
            OutlinedButton(onClick = { report = buildReport() }) {
                Text(stringResource(R.string.diagnostics_refresh))
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(REPORT_FILE_NAME, report)))
                        Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                    }
                },
            ) { Text(stringResource(R.string.diagnostics_copy)) }
            OutlinedButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, REPORT_FILE_NAME)
                        .putExtra(Intent.EXTRA_TEXT, report)
                    context.startActivity(Intent.createChooser(send, shareTitle))
                },
            ) { Text(stringResource(R.string.diagnostics_share)) }
            FilledTonalButton(onClick = { saveLauncher.launch(REPORT_FILE_NAME) }) {
                Text(stringResource(R.string.diagnostics_save))
            }
        }
        Text(
            text = stringResource(R.string.diagnostics_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        SelectionContainer(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()),
        ) {
            Text(
                text = report,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}
