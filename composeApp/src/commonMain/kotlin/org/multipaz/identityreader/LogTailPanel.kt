package org.multipaz.identityreader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val TAIL_LINES = 8

/**
 * A collapsible strip showing the latest [DemoLog] lines, so the protocol steps are visible while
 * a presentation happens. Debug lines are only shown in the full [LogScreen].
 *
 * @param onOpenLog opens the full [LogScreen], or `null` if leaving the current screen isn't safe.
 */
@Composable
fun LogTailPanel(
    onOpenLog: (() -> Unit)?,
) {
    val entries by DemoLog.entries.collectAsState()
    var expanded by remember { mutableStateOf(true) }
    val tail = entries.asReversed().asSequence()
        .filter { it.level != DemoLogLevel.DEBUG }
        .take(TAIL_LINES)
        .toList()
        .asReversed()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    modifier = Modifier.weight(1f),
                    text = if (expanded) "Live log" else tail.lastOrNull()?.let { logLineText(it) } ?: "Live log",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (onOpenLog != null) {
                    IconButton(onClick = onOpenLog) {
                        Icon(Icons.Filled.OpenInFull, contentDescription = "Open interaction log")
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                    contentDescription = if (expanded) "Collapse live log" else "Expand live log",
                    modifier = Modifier.padding(end = 12.dp)
                )
            }
            if (expanded) {
                // Fixed height so the screen above doesn't jump as lines arrive.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((TAIL_LINES * 16 + 8).dp)
                        .clickable(enabled = onOpenLog != null) { onOpenLog?.invoke() }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    tail.forEach { entry ->
                        LogLine(entry = entry, maxLines = 1)
                    }
                }
            }
        }
    }
}
