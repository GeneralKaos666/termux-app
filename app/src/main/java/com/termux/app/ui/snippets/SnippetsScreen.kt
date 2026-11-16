package com.termux.app.ui.snippets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.termux.R
import com.termux.app.domain.snippets.Snippet
import com.termux.app.ui.materialIcon

/**
 * Editor for the saved snippets: one row per snippet with its label, its command and a delete
 * action, plus an add button and Apply. Edits are held by [SnippetsState] until Apply.
 */
@Composable
fun SnippetsScreen(
    state: SnippetsState,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.snippets_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Button(onClick = onApply) {
                Text(stringResource(R.string.snippets_apply))
            }
        }

        if (state.items.isEmpty()) {
            Text(
                text = stringResource(R.string.snippets_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.items) { snippet ->
                    SnippetRow(
                        snippet = snippet,
                        onLabelChange = { state.update(snippet.id, it, snippet.command) },
                        onCommandChange = { state.update(snippet.id, snippet.label, it) },
                        onDelete = { state.remove(snippet.id) },
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(onClick = state::addBlank) {
                Text(stringResource(R.string.snippets_add))
            }
        }
    }
}

@Composable
private fun SnippetRow(
    snippet: Snippet,
    onLabelChange: (String) -> Unit,
    onCommandChange: (String) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = snippet.label,
                onValueChange = onLabelChange,
                label = { Text(stringResource(R.string.snippets_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = snippet.command,
                onValueChange = onCommandChange,
                label = { Text(stringResource(R.string.snippets_command)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = SnippetsIcons.Delete,
                contentDescription = stringResource(R.string.snippets_delete),
            )
        }
    }
}

/** A row of quick-insert chips for the shell, drawn above the extra-keys grid. */
@Composable
fun SnippetChipsRow(
    items: List<Snippet>,
    onInsert: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(items, key = { it.id.ifEmpty { it.label } }) { snippet ->
            AssistChip(
                onClick = { onInsert(snippet.command) },
                label = { Text(snippet.label) },
            )
        }
    }
}

/** Material "delete" glyph, used by a snippet row. */
private object SnippetsIcons {
    val Delete: ImageVector = materialIcon(
        "Delete",
        "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z",
    )
}
