package org.multipaz.identityreader

import android.content.Intent
import android.os.Build
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.android.Android
import kotlinx.io.bytestring.ByteString
import org.multipaz.context.applicationContext
import org.multipaz.context.getActivity
import org.multipaz.crypto.Algorithm
import org.multipaz.securearea.AndroidKeystoreCreateKeySettings
import org.multipaz.securearea.AndroidKeystoreKeyInfo
import org.multipaz.securearea.AndroidKeystoreSecureArea
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.KeyInfo
import org.multipaz.util.Logger

private const val TAG = "Platform"

class AndroidPlatformUtils : PlatformUtils {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"

    override val nfcPollingFramesInsertionSupported by lazy {
        // Use an allow-list until b/460804407 is resolved and used in Multipaz
        if (Build.MANUFACTURER == "Google" && (
                    Build.MODEL.startsWith("Pixel 8") ||
                    Build.MODEL.startsWith("Pixel 9") ||
                    Build.MODEL.startsWith("Pixel 10") ||
                    Build.MODEL.startsWith("Pixel 11")
            )
        ) {
            Logger.i(TAG, "Device is on allow-list for nfcPollingFramesInsertionSupported")
            true
        } else {
            Logger.w(TAG, "Device is not allow-list for nfcPollingFramesInsertionSupported")
            false
        }
    }

    override val httpClientEngineFactory: HttpClientEngineFactory<*> = Android

    override fun exitApp() {
        System.exit(0)
    }

    override fun deviceReaderKeySettings(): CreateKeySettings {
        val useStrongBox = AndroidKeystoreSecureArea.Capabilities().strongBoxSupported
        Logger.i(TAG, "Creating device reader key, StrongBox: $useStrongBox")
        // The attestation challenge isn't used; the signer certifies the key from its CSR.
        return AndroidKeystoreCreateKeySettings.Builder(ByteString("reader".encodeToByteArray()))
            .setAlgorithm(Algorithm.ESP256)
            .setUseStrongBox(useStrongBox)
            .build()
    }

    override fun describeKeyStorage(keyInfo: KeyInfo): String =
        if ((keyInfo as? AndroidKeystoreKeyInfo)?.isStrongBoxBacked == true) {
            "StrongBox"
        } else {
            "Trusted Execution Environment"
        }

    override fun shareText(subject: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        applicationContext.startActivity(
            Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

actual fun getPlatformUtils(): PlatformUtils = AndroidPlatformUtils()
