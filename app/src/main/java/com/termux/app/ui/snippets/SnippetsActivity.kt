package com.termux.app.ui.snippets

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.termux.app.data.snippets.SnippetStore
import com.termux.app.data.ui.AndroidUiPreferences
import com.termux.app.ui.theme.TermuxShellTheme

/**
 * Hosts the Compose [SnippetsScreen]. Apply persists the list through [SnippetStore] and closes, so
 * the terminal picks the new chips up on its next resume.
 */
class SnippetsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val state = SnippetsState(SnippetStore(AndroidUiPreferences(this)))

        setContent {
            TermuxShellTheme {
                SnippetsScreen(
                    state = state,
                    onApply = {
                        state.save()
                        finish()
                    },
                )
            }
        }
    }
}
