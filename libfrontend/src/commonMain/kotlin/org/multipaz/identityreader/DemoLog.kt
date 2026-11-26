package org.multipaz.identityreader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.bytestring.ByteString
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.util.Logger
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

enum class DemoLogLevel(val letter: Char) {
    DEBUG('D'),
    INFO('I'),
    WARNING('W'),
    ERROR('E'),
}

/** A step of an ISO/IEC 18013-5 presentation, used to group [DemoEvent]s. */
enum class DemoStep(val label: String) {
    ENGAGEMENT("Engagement"),
    SESSION("Session setup"),
    REQUEST("Request"),
    TRANSPORT("Transport"),
    RESPONSE("Response"),
    VERIFY("Verification"),
    TRUST("Issuer trust"),
    REVOCATION("Revocation"),
}

enum class DemoDirection(val arrow: String, val label: String) {
    TO_WALLET("→", "Reader → Wallet"),
    FROM_WALLET("←", "Wallet → Reader"),
    LOCAL("•", "Reader"),
}

enum class DemoOutcome(val symbol: String) {
    PASS("✔"),
    WARN("⚠"),
    FAIL("✘"),
}

/**
 * Binary data attached to a [DemoEvent], e.g. an encoded DeviceRequest.
 *
 * @param containsPersonalData if `true`, the data is kept in memory only and left out of exports
 *   unless personal data is explicitly included.
 */
class DemoLogPayload(
    val name: String,
    val data: ByteString,
    val isCbor: Boolean = true,
    val containsPersonalData: Boolean = false,
) {
    /** CBOR diagnostic notation, or the data as text if [isCbor] is false. */
    val diagnostics: String by lazy {
        if (!isCbor) {
            data.toByteArray().decodeToString()
        } else {
            try {
                Cbor.toDiagnostics(
                    data.toByteArray(),
                    setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR)
                )
            } catch (e: Throwable) {
                "Not valid CBOR ($e)"
            }
        }
    }

    companion object {
        fun cbor(name: String, data: ByteArray, containsPersonalData: Boolean = false) =
            DemoLogPayload(name, ByteString(data), true, containsPersonalData)

        fun text(name: String, text: String) =
            DemoLogPayload(name, ByteString(text.encodeToByteArray()), false, false)
    }
}

/** A structured description of one step of a presentation, see [DemoLog.event]. */
data class DemoEvent(
    val step: DemoStep,
    val direction: DemoDirection,
    val outcome: DemoOutcome?,
    val details: List<String>,
    val payloads: List<DemoLogPayload>,
)

/** A log line, either captured from Multipaz's [Logger] or a structured [event]. */
data class DemoLogEntry(
    val seq: Long,
    val time: Instant,
    val sessionId: Int?,
    val level: DemoLogLevel,
    val tag: String,
    val message: String,
    val throwable: String?,
    val event: DemoEvent?,
)

/** One reader interaction, from receiving the device engagement onwards. */
data class DemoSession(
    val id: Int,
    val startedAt: Instant,
    val title: String,
    val outcome: DemoOutcome? = null,
    val summary: String? = null,
) {
    val label: String get() = "S$id"
}

/**
 * In-app log of reader interactions, for debugging and for walking through demo scenarios.
 *
 * Captures everything logged through Multipaz's [Logger] (still forwarded to the platform log) and
 * structured [DemoEvent]s added with [event]. Recent entries are kept in memory and the run is also
 * written to a JSON Lines file, without payloads that contain personal data.
 */
object DemoLog {
    const val MAX_ENTRIES = 20_000
    private const val MAX_SESSIONS = 50
    private const val MAX_RUN_FILES = 10
    private const val TAG = "DemoLog"

    private sealed interface Message {
        class Entry(val entry: DemoLogEntry) : Message
        data object Clear : Message
    }

    private val incoming = Channel<Message>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _entries = MutableStateFlow<List<DemoLogEntry>>(emptyList())

    /** Recent entries, oldest first. Updated in batches at most ten times a second. */
    val entries: StateFlow<List<DemoLogEntry>> = _entries.asStateFlow()

    private val _sessions = MutableStateFlow<List<DemoSession>>(emptyList())

    /** Recent sessions, oldest first. */
    val sessions: StateFlow<List<DemoSession>> = _sessions.asStateFlow()

    @Volatile
    private var currentSessionId: Int? = null
    private val onceKeys = MutableStateFlow<Set<String>>(emptySet())
    private var installed = false
    private var logDirectory: Path? = null

    /**
     * Starts capturing [Logger] output. Safe to call more than once.
     *
     * @param logDirectory where to write the per-run JSON Lines files, or `null` to keep logs in memory only.
     */
    fun install(logDirectory: String?) {
        if (installed) {
            return
        }
        installed = true
        Logger.logPrinter = object : Logger.LogPrinter {
            override fun print(level: Logger.LogPrinter.Level, tag: String, msg: String, throwable: Throwable?) {
                platformLog(level, tag, msg, throwable)
                record(level.toDemoLogLevel(), tag, msg, throwable, null)
            }
        }
        val sink = logDirectory?.let { openRunFile(Path(it)) }
        scope.launch { consume(sink) }
    }

    /** Enables or disables debug-level logging in Multipaz, e.g. BLE and NFC transport details. */
    fun setVerbose(verbose: Boolean) {
        Logger.isDebugEnabled = verbose
    }

    /** Starts a new session. Subsequent entries are tagged with it until the next session starts. */
    fun startSession(title: String): DemoSession {
        var session: DemoSession? = null
        _sessions.update { sessions ->
            val newSession = DemoSession(
                id = (sessions.lastOrNull()?.id ?: 0) + 1,
                startedAt = Clock.System.now(),
                title = title,
            )
            session = newSession
            (sessions + newSession).takeLast(MAX_SESSIONS)
        }
        currentSessionId = session!!.id
        return session
    }

    /** Records how the current session ended, shown in the session list and exports. */
    fun setSessionOutcome(outcome: DemoOutcome, summary: String) {
        val id = currentSessionId ?: return
        _sessions.update { sessions ->
            sessions.map { if (it.id == id) it.copy(outcome = outcome, summary = summary) else it }
        }
    }

    /**
     * Returns `true` the first time it's called with [key] in the current session, e.g. so
     * verification events aren't logged again when a results screen is recomposed.
     */
    fun once(key: String): Boolean {
        val sessionKey = "$currentSessionId/$key"
        var added = false
        onceKeys.update { keys ->
            added = sessionKey !in keys
            if (added) keys + sessionKey else keys
        }
        return added
    }

    /** Adds a structured event for the current session. */
    fun event(
        step: DemoStep,
        direction: DemoDirection,
        message: String,
        outcome: DemoOutcome? = null,
        details: List<String> = emptyList(),
        payloads: List<DemoLogPayload> = emptyList(),
    ) {
        val level = when (outcome) {
            DemoOutcome.FAIL -> DemoLogLevel.ERROR
            DemoOutcome.WARN -> DemoLogLevel.WARNING
            DemoOutcome.PASS, null -> DemoLogLevel.INFO
        }
        val tag = "$TAG/${step.name}"
        val fullMessage = "${direction.arrow} $message${outcome?.let { " ${it.symbol}" } ?: ""}"
        platformLog(
            level.toLoggerLevel(),
            tag,
            (listOf(fullMessage) + details.map { "    $it" }).joinToString("\n"),
            null
        )
        record(level, tag, message, null, DemoEvent(step, direction, outcome, details, payloads))
    }

    /**
     * Runs [block], which builds and logs events, so that a mistake in logging code can't break the
     * flow being logged: anything it throws, other than cancellation, is logged as a warning instead.
     */
    inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.w("DemoLog", "Couldn't log $what", e)
        }
    }

    /** Removes all in-memory entries. The run file on disk is kept. */
    fun clear() {
        incoming.trySend(Message.Clear)
    }

    /** The JSON Lines files of this and previous runs, oldest first, as (file name, contents). */
    fun readRunFiles(): List<Pair<String, String>> {
        val directory = logDirectory ?: return emptyList()
        return try {
            listRunFiles(directory).map { path ->
                path.name to SystemFileSystem.source(path).buffered().use { it.readString() }
            }
        } catch (e: Throwable) {
            platformLog(Logger.LogPrinter.Level.WARNING, TAG, "Error reading run files", e)
            emptyList()
        }
    }

    private fun record(
        level: DemoLogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
        event: DemoEvent?
    ) {
        incoming.trySend(
            Message.Entry(
                DemoLogEntry(
                    seq = 0,
                    time = Clock.System.now(),
                    sessionId = currentSessionId,
                    level = level,
                    tag = tag,
                    message = message,
                    throwable = throwable?.stackTraceToString(),
                    event = event,
                )
            )
        )
    }

    // Entries are appended and written by this single coroutine, and published in batches so a
    // burst of debug logging doesn't copy the whole list for every line.
    private suspend fun consume(sink: Sink?) {
        val buffer = ArrayDeque<DemoLogEntry>()
        var nextSeq = 0L
        for (first in incoming) {
            var message: Message? = first
            while (message != null) {
                when (message) {
                    is Message.Clear -> buffer.clear()
                    is Message.Entry -> {
                        val entry = message.entry.copy(seq = nextSeq++)
                        buffer.addLast(entry)
                        try {
                            sink?.writeString(DemoLogExport.toJsonLine(entry, includePersonalData = false) + "\n")
                        } catch (e: Throwable) {
                            platformLog(Logger.LogPrinter.Level.WARNING, TAG, "Error writing run file", e)
                        }
                    }
                }
                message = incoming.tryReceive().getOrNull()
            }
            while (buffer.size > MAX_ENTRIES) {
                buffer.removeFirst()
            }
            try {
                sink?.flush()
            } catch (e: Throwable) {
                platformLog(Logger.LogPrinter.Level.WARNING, TAG, "Error flushing run file", e)
            }
            _entries.value = buffer.toList()
            delay(100)
        }
    }

    private fun openRunFile(directory: Path): Sink? = try {
        SystemFileSystem.createDirectories(directory)
        listRunFiles(directory).dropLast(MAX_RUN_FILES - 1).forEach { SystemFileSystem.delete(it) }
        val path = Path(directory, "run-${DemoLogExport.fileTimestamp(Clock.System.now())}.jsonl")
        logDirectory = directory
        SystemFileSystem.sink(path, append = true).buffered()
    } catch (e: Throwable) {
        platformLog(Logger.LogPrinter.Level.WARNING, TAG, "Error opening run file, logging to memory only", e)
        null
    }

    private fun listRunFiles(directory: Path): List<Path> =
        SystemFileSystem.list(directory)
            .filter { it.name.startsWith("run-") && it.name.endsWith(".jsonl") }
            .sortedBy { it.name }
}

private fun Logger.LogPrinter.Level.toDemoLogLevel() = when (this) {
    Logger.LogPrinter.Level.DEBUG -> DemoLogLevel.DEBUG
    Logger.LogPrinter.Level.INFO -> DemoLogLevel.INFO
    Logger.LogPrinter.Level.WARNING -> DemoLogLevel.WARNING
    Logger.LogPrinter.Level.ERROR -> DemoLogLevel.ERROR
}

private fun DemoLogLevel.toLoggerLevel() = when (this) {
    DemoLogLevel.DEBUG -> Logger.LogPrinter.Level.DEBUG
    DemoLogLevel.INFO -> Logger.LogPrinter.Level.INFO
    DemoLogLevel.WARNING -> Logger.LogPrinter.Level.WARNING
    DemoLogLevel.ERROR -> Logger.LogPrinter.Level.ERROR
}

/** Writes to the platform log, e.g. logcat. Setting [Logger.logPrinter] replaces Multipaz's own. */
internal expect fun platformLog(
    level: Logger.LogPrinter.Level,
    tag: String,
    message: String,
    throwable: Throwable?
)
