package org.multipaz.identityreader

import org.multipaz.util.Logger

internal actual fun platformLog(
    level: Logger.LogPrinter.Level,
    tag: String,
    message: String,
    throwable: Throwable?
) {
    println("${level.name.first()}/$tag: $message")
    throwable?.let { println(it.stackTraceToString()) }
}
