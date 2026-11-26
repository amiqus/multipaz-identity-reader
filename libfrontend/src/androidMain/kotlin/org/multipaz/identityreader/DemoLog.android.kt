package org.multipaz.identityreader

import android.util.Log
import org.multipaz.util.Logger

internal actual fun platformLog(
    level: Logger.LogPrinter.Level,
    tag: String,
    message: String,
    throwable: Throwable?
) {
    // Logcat truncates long lines, e.g. CBOR diagnostics, so split them like Multipaz does.
    message.chunked(4000).forEachIndexed { index, chunk ->
        val chunkThrowable = if (index == 0) throwable else null
        when (level) {
            Logger.LogPrinter.Level.DEBUG -> Log.d(tag, chunk, chunkThrowable)
            Logger.LogPrinter.Level.INFO -> Log.i(tag, chunk, chunkThrowable)
            Logger.LogPrinter.Level.WARNING -> Log.w(tag, chunk, chunkThrowable)
            Logger.LogPrinter.Level.ERROR -> Log.e(tag, chunk, chunkThrowable)
        }
    }
}
