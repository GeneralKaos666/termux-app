package com.termux.app.ui.keys

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.termux.R
import com.termux.app.ui.theme.TermuxShellTheme
import com.termux.app.data.keys.ExtraKeysPresetStore

/**
 * Hosts the Compose [KeyLayoutGallery]. Picking a card writes the same `extra-keys` preset
 * preference the Settings list writes, then closes; the terminal applies the change on its next
 * resume through `TermuxTerminalExtraKeys.setExtraKeys()`.
 */
class KeyLayoutGalleryActivity : ComponentActivity() {

    private lateinit var presetStore: ExtraKeysPresetStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        presetStore = ExtraKeysPresetStore(this)

        setContent {
            var selectedId by mutableStateOf(presetStore.current().orEmpty())

            TermuxShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    Text(
                        text = stringResource(R.string.key_gallery_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
                    )
                    Text(
                        text = stringResource(R.string.key_gallery_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    KeyLayoutGallery(
                        selectedId = selectedId,
                        onUse = { id ->
                            selectedId = id
                            presetStore.save(id)
                            finish()
                        },
                    )
                }
            }
        }
    }
}
