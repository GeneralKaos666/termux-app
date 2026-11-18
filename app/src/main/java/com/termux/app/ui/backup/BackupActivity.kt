package com.termux.app.ui.backup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import com.termux.app.ui.theme.TermuxShellTheme

/**
 * Hosts the Compose [BackupScreen]. The placeholder state keeps the shell usable until the view
 * model fills it; this activity is the composition root that will own that view model.
 */
class BackupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            TermuxShellTheme {
                val state = remember { BackupUiState() }
                BackupScreen(state = state)
            }
        }
    }
}
