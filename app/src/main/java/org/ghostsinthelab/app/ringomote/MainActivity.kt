package org.ghostsinthelab.app.ringomote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ghostsinthelab.app.ringomote.ui.RingomoteApp
import org.ghostsinthelab.app.ringomote.ui.theme.RingomoteTheme

/**
 * The single activity: hosts [RingomoteApp], which handles the permission gate, the
 * connection panel and the remote. All state lives in
 * [org.ghostsinthelab.app.ringomote.ui.remote.RemoteViewModel], so recreation (rotation,
 * theme change) keeps the Bluetooth link. The theme follows
 * [org.ghostsinthelab.app.ringomote.settings.AppearanceSettings] from the [AppContainer].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as RingomoteApplication).container
        val settings = container.appearanceSettings
        setContent {
            val appearance by settings.appearance.collectAsStateWithLifecycle()
            RingomoteTheme(appearance = appearance) {
                RingomoteApp(appearanceSettings = settings, diagnosticsReport = container.diagnosticsReport)
            }
        }
    }
}
