package com.termux.app.ui.firstrun

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.termux.R
import com.termux.app.ui.theme.TermuxShellTheme

/**
 * The first-run onboarding overlay: a short welcome and an install action for the ~180 MB
 * bootstrap, with a progress state while the installer runs. Rendered full-screen over the shell
 * only while the sessions bar is empty (see [FirstRunState]).
 */
@Composable
fun FirstRunOverlay(
    installing: Boolean,
    onInstall: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.first_run_welcome_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.first_run_install_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(28.dp))
            if (installing) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.first_run_installing),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Button(onClick = onInstall) {
                    Text(text = stringResource(R.string.first_run_install_action))
                }
            }
        }
    }
}

@Preview(name = "First-run overlay", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun FirstRunOverlayPreview() {
    TermuxShellTheme {
        FirstRunOverlay(installing = false, onInstall = {})
    }
}

@Preview(name = "First-run installing", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun FirstRunInstallingPreview() {
    TermuxShellTheme {
        FirstRunOverlay(installing = true, onInstall = {})
    }
}
