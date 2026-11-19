package com.termux.app.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.termux.app.domain.sessions.SessionState

/**
 * Status indicator for a session state. Each state is a distinct shape as well as a distinct
 * colour, so state is not conveyed by colour alone: running is a filled circle, exited-success is
 * a hollow ring, exited-with-error is a square (WCAG 1.4.1).
 */
@Composable
internal fun SessionStateDot(state: SessionState, unseen: Boolean) {
    val color = sessionStateColor(state)
    // The wrapper keeps every tab the same width whether or not the halo is drawn, so a badge
    // appearing cannot shift the neighbouring tabs.
    Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        if (unseen) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.28f))
            )
        }
        when (state) {
            SessionState.RUNNING -> Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            SessionState.EXITED_SUCCESS -> Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .border(width = 2.dp, color = color, shape = CircleShape)
            )
            SessionState.EXITED_ERROR -> Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
        }
    }
}

/** Status-dot color for a session state. */
private fun sessionStateColor(state: SessionState): Color = when (state) {
    SessionState.RUNNING -> Color(0xFF43A047)
    SessionState.EXITED_SUCCESS -> Color(0xFF9E9E9E)
    SessionState.EXITED_ERROR -> Color(0xFFE53935)
}
