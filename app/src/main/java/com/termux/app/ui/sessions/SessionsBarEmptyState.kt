package com.termux.app.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.termux.R
import com.termux.app.domain.sessions.StarterCommand

/**
 * The empty bar: a prompt glyph followed by either a "start session" tap target or one chip per
 * starter command, so a fresh install can open its first session without the palette.
 */
@Composable
internal fun SessionsBarEmptyState(
    starterCommands: List<StarterCommand>,
    onNewSession: () -> Unit,
    onStarterCommand: (String) -> Unit,
    startSessionLabel: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "$",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall,
        )
        if (starterCommands.isEmpty()) {
            // A clickable Text rather than a TextButton: the button's 48dp minimum
            // interactive size would keep the empty bar taller than the populated one.
            Text(
                text = stringResource(R.string.action_start_session),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onNewSession)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .semantics { contentDescription = startSessionLabel },
            )
        } else {
            starterCommands.forEach { starter ->
                Text(
                    text = starterLabel(starter.command),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable { onStarterCommand(starter.command) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** Display label for a starter command; the command itself is what gets inserted. */
@Composable
internal fun starterLabel(command: String): String = when (command) {
    "pkg upgrade" -> stringResource(R.string.starter_command_pkg_upgrade)
    "pkg install neofetch" -> stringResource(R.string.starter_command_pkg_install_neofetch)
    "ls" -> stringResource(R.string.starter_command_ls)
    else -> command
}

