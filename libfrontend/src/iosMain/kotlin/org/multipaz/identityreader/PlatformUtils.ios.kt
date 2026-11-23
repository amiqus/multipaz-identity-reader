package org.multipaz.identityreader

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin
import org.multipaz.crypto.Algorithm
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.KeyInfo
import org.multipaz.util.Logger
import platform.UIKit.UIDevice
import platform.posix.exit

class IOSPlatformUtils: PlatformUtils {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion

    // No API to do this on iOS
    override val nfcPollingFramesInsertionSupported = false

    override val httpClientEngineFactory: HttpClientEngineFactory<*> = Darwin

    override fun exitApp() {
        exit(0)
    }

    override fun deviceReaderKeySettings(): CreateKeySettings =
        CreateKeySettings(algorithm = Algorithm.ESP256)

    override fun describeKeyStorage(keyInfo: KeyInfo): String = "Secure Enclave"

    override fun shareText(subject: String, text: String) {
        // TODO: use UIActivityViewController. The device reader key is Android-only for now.
        Logger.w("Platform", "Sharing isn't supported on iOS yet")
    }
}

actual fun getPlatformUtils(): PlatformUtils = IOSPlatformUtils()
