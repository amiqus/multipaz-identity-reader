package org.multipaz.identityreader

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.util.toBase64Url
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** Formats [DemoLog] entries for export: plain text, JSON Lines, an HTML report and a zip bundle. */
object DemoLogExport {
    private const val REDACTED = "[redacted: contains personal data]"

    /** E.g. "14:02:11.123" in local time. */
    fun formatTime(instant: Instant): String {
        val t = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        return "${t.hour.pad(2)}:${t.minute.pad(2)}:${t.second.pad(2)}.${(t.nanosecond / 1_000_000).pad(3)}"
    }

    /** E.g. "20261125-140211" in local time, for file names. */
    fun fileTimestamp(instant: Instant): String {
        val t = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        return "${t.year}${t.month.ordinal.plus(1).pad(2)}${t.day.pad(2)}-" +
                "${t.hour.pad(2)}${t.minute.pad(2)}${t.second.pad(2)}"
    }

    /** E.g. "+1.234s", the time since the start of a session. */
    fun formatOffset(duration: Duration): String {
        val millis = duration.inWholeMilliseconds
        return "+${millis / 1000}.${(millis % 1000).toInt().pad(3)}s"
    }

    fun toJsonLine(entry: DemoLogEntry, includePersonalData: Boolean): String =
        buildJsonObject {
            put("seq", entry.seq)
            put("time", entry.time.toString())
            entry.sessionId?.let { put("session", it) }
            put("level", entry.level.name)
            put("tag", entry.tag)
            put("message", entry.message)
            entry.throwable?.let { put("throwable", it) }
            entry.event?.let { event ->
                putJsonObject("event") {
                    put("step", event.step.name)
                    put("direction", event.direction.name)
                    event.outcome?.let { put("outcome", it.name) }
                    putJsonArray("details") { event.details.forEach { add(it) } }
                    putJsonArray("payloads") {
                        event.payloads.forEach { payload ->
                            add(buildJsonObject {
                                put("name", payload.name)
                                put("type", if (payload.isCbor) "cbor" else "text")
                                put("size", payload.data.size)
                                if (payload.containsPersonalData && !includePersonalData) {
                                    put("redacted", true)
                                } else if (payload.isCbor) {
                                    put("base64url", payload.data.toByteArray().toBase64Url())
                                } else {
                                    put("text", payload.diagnostics)
                                }
                            })
                        }
                    }
                }
            }
        }.toString()

    fun toText(entries: List<DemoLogEntry>, includePersonalData: Boolean): String = buildString {
        for (entry in entries) {
            append(formatTime(entry.time))
            append(entry.sessionId?.let { " [S$it]" } ?: " [--]")
            append(" ${entry.level.letter} ${entry.tag}: ")
            val event = entry.event
            if (event != null) {
                append("${event.direction.arrow} ${entry.message}")
                event.outcome?.let { append(" ${it.symbol}") }
                appendLine()
                event.details.forEach { appendLine("        $it") }
                event.payloads.forEach { payload ->
                    appendLine("        ${payload.name} (${payload.data.size} bytes):")
                    val body = if (payload.containsPersonalData && !includePersonalData) {
                        REDACTED
                    } else {
                        payload.diagnostics
                    }
                    body.lines().forEach { appendLine("            $it") }
                }
            } else {
                appendLine(entry.message)
            }
            entry.throwable?.let { throwable ->
                throwable.lines().forEach { appendLine("        $it") }
            }
        }
    }

    /**
     * Builds the files for a shareable bundle, keyed by path.
     *
     * @param sessions the sessions to include. Entries outside them are included if [allEntries] is set.
     * @param allEntries if `true`, includes every entry and the run files of previous app runs.
     * @param appInfo a line identifying the app build and device.
     */
    fun buildBundle(
        sessions: List<DemoSession>,
        entries: List<DemoLogEntry>,
        allEntries: Boolean,
        includePersonalData: Boolean,
        appInfo: String,
    ): Map<String, ByteArray> {
        val sessionIds = sessions.map { it.id }.toSet()
        val selected = if (allEntries) entries else entries.filter { it.sessionId in sessionIds }
        val files = linkedMapOf<String, ByteArray>()
        files["README.txt"] = readme(sessions, allEntries, includePersonalData, appInfo).encodeToByteArray()
        files["report.html"] = toHtml(sessions, selected, includePersonalData, appInfo).encodeToByteArray()
        files["log.txt"] = toText(selected, includePersonalData).encodeToByteArray()
        files["log.jsonl"] = selected.joinToString("") {
            toJsonLine(it, includePersonalData) + "\n"
        }.encodeToByteArray()

        var payloadNumber = 0
        for (entry in selected) {
            for (payload in entry.event?.payloads ?: emptyList()) {
                if (payload.containsPersonalData && !includePersonalData) {
                    continue
                }
                payloadNumber++
                val base = "payloads/${payloadNumber.pad(3)}-S${entry.sessionId ?: 0}-${payload.name.toFileName()}"
                if (payload.isCbor) {
                    files["$base.cbor"] = payload.data.toByteArray()
                    files["$base.diag.txt"] = payload.diagnostics.encodeToByteArray()
                } else {
                    files["$base.txt"] = payload.data.toByteArray()
                }
            }
        }

        if (allEntries) {
            DemoLog.readRunFiles().forEach { (name, contents) ->
                files["runs/$name"] = contents.encodeToByteArray()
            }
        }
        return files
    }

    private fun readme(
        sessions: List<DemoSession>,
        allEntries: Boolean,
        includePersonalData: Boolean,
        appInfo: String
    ) = buildString {
        appendLine("Amiqus Demo Verifier interaction log")
        appendLine("Exported: ${Clock.System.now()}")
        appendLine("App: $appInfo")
        appendLine()
        appendLine("Sessions:")
        sessions.forEach { appendLine("  ${it.label} ${formatTime(it.startedAt)} ${it.title}: ${it.summary ?: "no outcome"}") }
        if (allEntries) {
            appendLine("  (plus log lines outside sessions)")
        }
        appendLine()
        appendLine("Files:")
        appendLine("  report.html  Readable timeline of each session; open in a browser")
        appendLine("  log.txt      Every log line, including Multipaz library output")
        appendLine("  log.jsonl    The same as JSON Lines, with payloads base64url-encoded")
        appendLine("  payloads/    Raw CBOR messages (.cbor) and their diagnostic notation (.diag.txt)")
        if (allEntries) {
            appendLine("  runs/        Logs written to disk by this and previous app runs, without personal data")
        }
        appendLine()
        if (includePersonalData) {
            appendLine("WARNING: this bundle includes personal data from the presented credentials,")
            appendLine("e.g. the decrypted DeviceResponse. Handle and delete it accordingly.")
        } else {
            appendLine("Personal data is redacted: payloads containing data element values, e.g. the")
            appendLine("decrypted DeviceResponse, are left out. Element names, certificates and")
            appendLine("protocol messages are included.")
        }
    }

    private fun toHtml(
        sessions: List<DemoSession>,
        entries: List<DemoLogEntry>,
        includePersonalData: Boolean,
        appInfo: String
    ): String = buildString {
        appendLine("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
        appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        appendLine("<title>Verifier interaction log</title><style>$HTML_STYLE</style></head><body>")
        appendLine("<h1>Verifier interaction log</h1>")
        appendLine("<p class=\"meta\">Exported ${Clock.System.now().html()} · ${appInfo.html()} · ")
        appendLine(if (includePersonalData) "<b>includes personal data</b>" else "personal data redacted")
        appendLine("</p>")

        for (session in sessions) {
            val sessionEntries = entries.filter { it.sessionId == session.id }
            appendLine("<section><h2>${session.label} · ${session.title.html()} · ${formatTime(session.startedAt)}")
            session.outcome?.let { appendLine(" <span class=\"${it.name.lowercase()}\">${it.symbol}</span>") }
            appendLine("</h2>")
            session.summary?.let { appendLine("<p>${it.html()}</p>") }
            appendLine("<table><thead><tr><th>Time</th><th>Step</th><th>Direction</th><th>Event</th></tr></thead><tbody>")
            for (entry in sessionEntries) {
                val event = entry.event ?: continue
                val outcomeClass = event.outcome?.name?.lowercase() ?: ""
                appendLine("<tr class=\"$outcomeClass\">")
                appendLine("<td class=\"mono\">${formatOffset(entry.time - session.startedAt)}</td>")
                appendLine("<td>${event.step.label}</td><td>${event.direction.label.html()}</td>")
                append("<td><b>${entry.message.html()}</b>")
                event.outcome?.let { append(" <span class=\"$outcomeClass\">${it.symbol}</span>") }
                if (event.details.isNotEmpty()) {
                    append("<ul>")
                    event.details.forEach { append("<li>${it.html()}</li>") }
                    append("</ul>")
                }
                for (payload in event.payloads) {
                    append("<details><summary>${payload.name.html()} (${payload.data.size} bytes)</summary><pre>")
                    append(
                        if (payload.containsPersonalData && !includePersonalData) REDACTED else payload.diagnostics.html()
                    )
                    append("</pre></details>")
                }
                appendLine("</td></tr>")
            }
            appendLine("</tbody></table>")
            appendLine("<details><summary>Full log for ${session.label} (${sessionEntries.size} lines)</summary>")
            appendLine("<pre>${toText(sessionEntries, includePersonalData).html()}</pre></details></section>")
        }

        val outside = entries.filter { entry -> sessions.none { it.id == entry.sessionId } }
        if (outside.isNotEmpty()) {
            appendLine("<section><h2>Other log lines</h2><details><summary>${outside.size} lines</summary>")
            appendLine("<pre>${toText(outside, includePersonalData).html()}</pre></details></section>")
        }
        appendLine("</body></html>")
    }
}

private const val HTML_STYLE = """
:root { color-scheme: light dark; --fg: #1b1b1f; --bg: #ffffff; --muted: #5f6368; --line: #dadce0;
  --pass: #137333; --warn: #b06000; --fail: #c5221f; }
@media (prefers-color-scheme: dark) { :root { --fg: #e3e3e3; --bg: #1b1b1f; --muted: #9aa0a6; --line: #3c4043;
  --pass: #81c995; --warn: #fdd663; --fail: #f28b82; } }
body { font: 15px/1.5 system-ui, sans-serif; color: var(--fg); background: var(--bg); margin: 0 auto;
  max-width: 1100px; padding: 16px; }
.meta { color: var(--muted); }
table { border-collapse: collapse; width: 100%; }
th, td { text-align: left; vertical-align: top; padding: 6px 8px; border-bottom: 1px solid var(--line); }
td ul { margin: 4px 0; padding-left: 20px; color: var(--muted); }
pre { white-space: pre-wrap; word-break: break-all; font-size: 12px; max-height: 480px; overflow: auto; }
.mono { font-family: ui-monospace, monospace; white-space: nowrap; }
.pass { color: var(--pass); } .warn { color: var(--warn); } .fail { color: var(--fail); }
tr.fail td:first-child { border-left: 3px solid var(--fail); }
section { margin-bottom: 32px; }
"""

private fun Int.pad(length: Int) = toString().padStart(length, '0')

private fun String.toFileName() = lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

private fun String.html() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private fun Instant.html() = toString().html()
