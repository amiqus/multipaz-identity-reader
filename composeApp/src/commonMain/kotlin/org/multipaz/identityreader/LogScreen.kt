package org.multipaz.identityreader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.multipaz.util.Logger
import kotlin.time.Clock

private const val TAG = "LogScreen"

// Long payloads, e.g. a DeviceResponse with a portrait, are truncated on screen but not in exports.
private const val MAX_PAYLOAD_CHARS = 20_000

private enum class LogTab(val title: String) {
    TIMELINE("Timeline"),
    RAW("Raw log"),
}

private enum class LevelFilter(val label: String, val minimum: DemoLogLevel) {
    ALL("All", DemoLogLevel.DEBUG),
    INFO("Info+", DemoLogLevel.INFO),
    WARNINGS("Warnings+", DemoLogLevel.WARNING),
}

/** E.g. "14:02:11.123 [S3] → Sent SessionEstablishment (312 bytes) ✔". */
fun logLineText(entry: DemoLogEntry): String {
    val time = DemoLogExport.formatTime(entry.time)
    val session = entry.sessionId?.let { "S$it" } ?: "--"
    val event = entry.event
    return if (event != null) {
        "$time $session ${event.direction.arrow} ${entry.message}${event.outcome?.let { " ${it.symbol}" } ?: ""}"
    } else {
        "$time $session ${entry.level.letter} ${entry.tag}: ${entry.message}"
    }
}

@Composable
private fun outcomeColor(outcome: DemoOutcome?): Color {
    val dark = isSystemInDarkTheme()
    return when (outcome) {
        DemoOutcome.PASS -> if (dark) Color(0xFF81C995) else Color(0xFF137333)
        DemoOutcome.WARN -> if (dark) Color(0xFFFDD663) else Color(0xFFB06000)
        DemoOutcome.FAIL -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurface
    }
}

@Composable
private fun levelColor(entry: DemoLogEntry): Color = when {
    entry.event?.outcome != null -> outcomeColor(entry.event?.outcome)
    entry.level == DemoLogLevel.ERROR -> MaterialTheme.colorScheme.error
    entry.level == DemoLogLevel.WARNING -> outcomeColor(DemoOutcome.WARN)
    entry.event != null -> MaterialTheme.colorScheme.primary
    entry.level == DemoLogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.onSurface
}

private val monospaceStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)

/** One log line in monospace, coloured by level or outcome, with events in bold. */
@Composable
fun LogLine(
    entry: DemoLogEntry,
    maxLines: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier,
        text = logLineText(entry),
        style = monospaceStyle,
        color = levelColor(entry),
        fontWeight = if (entry.event != null) FontWeight.Bold else FontWeight.Normal,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The interaction log: a readable timeline of each session's protocol steps, the raw log including
 * Multipaz library output, and export of either as a shareable bundle.
 */
@Composable
fun LogScreen(
    settingsModel: SettingsModel,
    onBackPressed: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val entries by DemoLog.entries.collectAsState()
    val sessions by DemoLog.sessions.collectAsState()
    // null means all sessions. Starts on the latest session, if any.
    var selectedSessionId by remember { mutableStateOf(DemoLog.sessions.value.lastOrNull()?.id) }
    var tab by remember { mutableStateOf(LogTab.TIMELINE) }
    var showShareMenu by remember { mutableStateOf(false) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var confirmPersonalDataExport by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }

    val selectedSessions = if (selectedSessionId == null) {
        sessions
    } else {
        sessions.filter { it.id == selectedSessionId }
    }
    val visibleEntries = if (selectedSessionId == null) {
        entries
    } else {
        entries.filter { it.sessionId == selectedSessionId }
    }

    fun share(asText: Boolean, includePersonalData: Boolean) {
        val scope = selectedSessions.singleOrNull()?.label ?: "all"
        val baseName = "verifier-log-$scope-${DemoLogExport.fileTimestamp(Clock.System.now())}"
        coroutineScope.launch {
            try {
                if (asText) {
                    val text = withContext(Dispatchers.Default) {
                        DemoLogExport.toText(visibleEntries, includePersonalData = false)
                    }
                    getPlatformUtils().shareFile("$baseName.txt", "text/plain", text.encodeToByteArray())
                } else {
                    val zip = withContext(Dispatchers.Default) {
                        val files = DemoLogExport.buildBundle(
                            sessions = selectedSessions,
                            entries = entries,
                            allEntries = selectedSessionId == null,
                            includePersonalData = includePersonalData,
                            appInfo = "Amiqus Demo Verifier ${BuildConfig.VERSION}, ${getPlatformUtils().name}",
                        )
                        getPlatformUtils().zip(files.mapKeys { "$baseName/${it.key}" })
                    }
                    getPlatformUtils().shareFile("$baseName.zip", "application/zip", zip)
                }
            } catch (e: Throwable) {
                Logger.e(TAG, "Error exporting log", e)
                exportError = "Exporting the log failed: ${e.message}"
            }
        }
    }

    Scaffold(
        topBar = {
            AppBar(
                title = AnnotatedString("Interaction log"),
                onBackPressed = onBackPressed,
                actions = {
                    Box {
                        IconButton(onClick = { showShareMenu = true }) {
                            Icon(Icons.Filled.Share, contentDescription = "Export")
                        }
                        DropdownMenu(expanded = showShareMenu, onDismissRequest = { showShareMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Share bundle (personal data redacted)") },
                                onClick = {
                                    showShareMenu = false
                                    share(asText = false, includePersonalData = false)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share bundle including personal data…") },
                                onClick = {
                                    showShareMenu = false
                                    confirmPersonalDataExport = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share as text (redacted)") },
                                onClick = {
                                    showShareMenu = false
                                    share(asText = true, includePersonalData = false)
                                }
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { showSettingsMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Log settings")
                        }
                        DropdownMenu(expanded = showSettingsMenu, onDismissRequest = { showSettingsMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Verbose library logging") },
                                trailingIcon = {
                                    Checkbox(
                                        checked = settingsModel.verboseLogging.collectAsState().value,
                                        onCheckedChange = null
                                    )
                                },
                                onClick = { settingsModel.verboseLogging.value = !settingsModel.verboseLogging.value }
                            )
                            DropdownMenuItem(
                                text = { Text("Show live log panel") },
                                trailingIcon = {
                                    Checkbox(
                                        checked = settingsModel.showLogPanel.collectAsState().value,
                                        onCheckedChange = null
                                    )
                                },
                                onClick = { settingsModel.showLogPanel.value = !settingsModel.showLogPanel.value }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear log") },
                                onClick = {
                                    showSettingsMenu = false
                                    DemoLog.clear()
                                }
                            )
                        }
                    }
                }
            )
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedSessionId == null,
                        onClick = { selectedSessionId = null },
                        label = { Text("All") }
                    )
                    sessions.asReversed().forEach { session ->
                        FilterChip(
                            selected = selectedSessionId == session.id,
                            onClick = { selectedSessionId = session.id },
                            label = {
                                Text(
                                    text = "${session.label} ${DemoLogExport.formatTime(session.startedAt).take(8)}" +
                                            (session.outcome?.let { " ${it.symbol}" } ?: ""),
                                    color = outcomeColor(session.outcome),
                                )
                            }
                        )
                    }
                }
                PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                    LogTab.entries.forEach {
                        Tab(selected = tab == it, onClick = { tab = it }, text = { Text(it.title) })
                    }
                }
                when (tab) {
                    LogTab.TIMELINE -> TimelineView(selectedSessions, visibleEntries)
                    LogTab.RAW -> RawLogView(visibleEntries)
                }
            }
        }
    }

    if (confirmPersonalDataExport) {
        AlertDialog(
            onDismissRequest = { confirmPersonalDataExport = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmPersonalDataExport = false
                    share(asText = false, includePersonalData = true)
                }) {
                    Text("Include personal data")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmPersonalDataExport = false }) {
                    Text("Cancel")
                }
            },
            title = { Text("Include personal data?") },
            text = {
                Text(
                    "The bundle will contain the decrypted DeviceResponse, including every returned " +
                            "data element such as name, date of birth and portrait. Only share it with " +
                            "people who need it, and delete it afterwards. Responses are only available " +
                            "for sessions since the app was started."
                )
            }
        )
    }

    exportError?.let { message ->
        AlertDialog(
            onDismissRequest = { exportError = null },
            confirmButton = { TextButton(onClick = { exportError = null }) { Text("Close") } },
            title = { Text("Export failed") },
            text = { Text(message) }
        )
    }
}

@Composable
private fun TimelineView(
    sessions: List<DemoSession>,
    entries: List<DemoLogEntry>,
) {
    val events = entries.filter { it.event != null }
    if (events.isEmpty()) {
        EmptyMessage(
            "No interactions yet. Scan a QR code or tap a wallet to start a session; " +
                    "each protocol step will appear here."
        )
        return
    }
    val sessionsById = sessions.associateBy { it.id }
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
    ) {
        events.forEachIndexed { index, entry ->
            val session = sessionsById[entry.sessionId]
            if (session != null && (index == 0 || events[index - 1].sessionId != entry.sessionId)) {
                item(key = "session-${session.id}") {
                    SessionHeader(session)
                }
            }
            item(key = entry.seq) {
                TimelineEvent(entry, session)
            }
        }
    }
}

@Composable
private fun SessionHeader(session: DemoSession) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "${session.label} · ${session.title} · ${DemoLogExport.formatTime(session.startedAt)}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = session.outcome?.let { "${it.symbol} ${session.summary}" } ?: "In progress",
                style = MaterialTheme.typography.bodyMedium,
                color = outcomeColor(session.outcome),
            )
        }
    }
}

@Composable
private fun TimelineEvent(entry: DemoLogEntry, session: DemoSession?) {
    val event = entry.event!!
    var expanded by remember { mutableStateOf(false) }
    val hasMore = event.details.isNotEmpty() || event.payloads.isNotEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = hasMore) { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                modifier = Modifier.width(64.dp),
                text = session?.let { DemoLogExport.formatOffset(entry.time - it.startedAt) }
                    ?: DemoLogExport.formatTime(entry.time),
                style = monospaceStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                modifier = Modifier.width(20.dp),
                text = event.direction.arrow,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${event.step.label} · ${event.direction.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = entry.message + (event.outcome?.let { " ${it.symbol}" } ?: "") +
                            if (hasMore && !expanded) " …" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (event.outcome != null) outcomeColor(event.outcome) else MaterialTheme.colorScheme.onSurface,
                )
                if (expanded) {
                    EntryDetails(entry)
                }
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 96.dp))
}

/** Details, payloads and stack trace of an entry; long-press to select and copy. */
@Composable
private fun EntryDetails(entry: DemoLogEntry) {
    SelectionContainer {
        Column(
            modifier = Modifier.padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            entry.event?.details?.forEach {
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }
            entry.event?.payloads?.forEach { payload ->
                PayloadView(payload)
            }
            entry.throwable?.let {
                Text(text = it, style = monospaceStyle, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun PayloadView(payload: DemoLogPayload) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                text = "${if (expanded) "Hide" else "Show"} ${payload.name} (${payload.data.size} bytes)" +
                        if (payload.containsPersonalData) " · personal data" else ""
            )
        }
        if (expanded) {
            val text = payload.diagnostics
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                text = if (text.length > MAX_PAYLOAD_CHARS) {
                    text.take(MAX_PAYLOAD_CHARS) + "\n… truncated, ${text.length} characters in total; export for the full payload"
                } else {
                    text
                },
                style = monospaceStyle,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun RawLogView(entries: List<DemoLogEntry>) {
    var levelFilter by remember { mutableStateOf(LevelFilter.ALL) }
    var follow by remember { mutableStateOf(true) }
    var expandedSeq by remember { mutableStateOf<Long?>(null) }
    val filtered = entries.filter { it.level >= levelFilter.minimum }
    val listState = rememberLazyListState()

    LaunchedEffect(filtered.size, follow) {
        if (follow && filtered.isNotEmpty()) {
            listState.scrollToItem(filtered.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LevelFilter.entries.forEach {
                FilterChip(
                    selected = levelFilter == it,
                    onClick = { levelFilter = it },
                    label = { Text(it.label) }
                )
            }
            FilterChip(
                selected = follow,
                onClick = { follow = !follow },
                label = { Text("Follow") }
            )
        }
        if (filtered.isEmpty()) {
            EmptyMessage("No log lines")
            return
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        ) {
            items(filtered, key = { it.seq }) { entry ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expandedSeq = if (expandedSeq == entry.seq) null else entry.seq
                            follow = false
                        }
                        .padding(vertical = 1.dp)
                ) {
                    LogLine(entry, maxLines = if (expandedSeq == entry.seq) Int.MAX_VALUE else 3)
                    if (expandedSeq == entry.seq) {
                        EntryDetails(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
