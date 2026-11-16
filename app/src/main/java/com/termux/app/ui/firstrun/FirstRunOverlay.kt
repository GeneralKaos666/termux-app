package com.termux.app.ui.firstrun

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.termux.R
import com.termux.app.ui.splash.TypewriterText
import com.termux.app.ui.theme.TermuxShellTheme
import com.termux.app.ui.theme.shellAnimationsEnabled

/**
 * The first-run onboarding overlay: a terminal-styled boot panel with the typed welcome, the
 * install log lines for the current [BootstrapStage], and the install action for the ~180 MB
 * bootstrap. Rendered full-screen over the shell only while the sessions bar is empty (see
 * [FirstRunState]).
 *
 * The commands that are worth running afterwards are listed as the boot script's next steps; the
 * same commands are the actionable chips on the empty sessions bar once a session exists.
 */
@Composable
fun FirstRunOverlay(
    installing: Boolean,
    stage: BootstrapStage?,
    onInstall: () -> Unit,
) {
    val animationsEnabled = shellAnimationsEnabled()
    val promptColor = MaterialTheme.colorScheme.primary
    val logStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)

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
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "$ ", color = promptColor, fontFamily = FontFamily.Monospace)
                    TypewriterText(
                        text = stringResource(R.string.first_run_welcome_title),
                        animationsEnabled = animationsEnabled,
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.first_run_install_body),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Start,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val lines = logLines(stage)
                if (lines.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    lines.forEach { line ->
                        Text(text = line, style = logStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                if (installing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.first_run_installing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Button(onClick = onInstall) {
                        Text(text = stringResource(R.string.first_run_install_action))
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = stringResource(R.string.first_run_next_steps),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    tipCommands().forEach { command ->
                        Text(
                            text = "$ $command",
                            style = logStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "First-run overlay", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun FirstRunOverlayPreview() {
    TermuxShellTheme {
        FirstRunOverlay(installing = false, stage = null, onInstall = {})
    }
}

@Preview(name = "First-run installing", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun FirstRunInstallingPreview() {
    TermuxShellTheme {
        FirstRunOverlay(installing = true, stage = BootstrapStage.EXTRACTING, onInstall = {})
    }
}
