package org.multipaz.identityreader

import io.ktor.client.engine.HttpClientEngineFactory
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.KeyInfo

interface PlatformUtils {
    val name: String

    // Workaround for now until b/460804407 is resolved and used in Multipaz
    val nfcPollingFramesInsertionSupported: Boolean

    val httpClientEngineFactory: HttpClientEngineFactory<*>

    fun exitApp()

    /** Settings for creating the device reader key, using the most secure hardware available. */
    fun deviceReaderKeySettings(): CreateKeySettings

    /** A short description of where [keyInfo]'s key is stored, e.g. "StrongBox". */
    fun describeKeyStorage(keyInfo: KeyInfo): String

    /** Shares [text] using the platform's share sheet, e.g. to email it. */
    fun shareText(subject: String, text: String)
}

expect fun getPlatformUtils(): PlatformUtils
