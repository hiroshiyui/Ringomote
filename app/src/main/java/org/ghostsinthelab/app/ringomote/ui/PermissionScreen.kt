package org.ghostsinthelab.app.ringomote.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.ghostsinthelab.app.ringomote.R
import org.ghostsinthelab.app.ringomote.bluetooth.BluetoothPermissions
import org.ghostsinthelab.app.ringomote.ui.theme.RingomoteTheme

/**
 * Explains why Ringomote needs Bluetooth permissions and requests
 * [BluetoothPermissions.runtime].
 *
 * Calls [onGranted] only when every permission was granted. After a denial it also offers the
 * app's system settings page, because Android stops showing the dialog after repeated denials.
 */
@Composable
fun PermissionScreen(onGranted: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) onGranted() else denied = true
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.permission_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.permission_rationale))
        Button(onClick = { launcher.launch(BluetoothPermissions.runtime) }) {
            Text(stringResource(R.string.permission_grant))
        }
        if (denied) {
            Text(
                text = stringResource(R.string.permission_denied),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            OutlinedButton(
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    )
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        // Some vendor builds lack the page; the denial text already says where to go.
                    }
                },
            ) {
                Text(stringResource(R.string.permission_open_settings))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PermissionScreenPreview() {
    RingomoteTheme { PermissionScreen(onGranted = {}) }
}
