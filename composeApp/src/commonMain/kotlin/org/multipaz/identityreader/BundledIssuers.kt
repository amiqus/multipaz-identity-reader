package org.multipaz.identityreader

import multipazidentityreader.composeapp.generated.resources.Res
import org.multipaz.crypto.X509Cert
import org.multipaz.trustmanagement.TrustManagerLocal
import org.multipaz.trustmanagement.TrustMetadata
import kotlin.time.Clock

// Bump this whenever bundledIssuers changes, so existing installs replace their built-in list.
private const val BUNDLED_ISSUERS_VERSION = 2L

// Public issuer certificates shipped with the app, used instead of the backend's trust list
// when no reader backend is configured. Files live in composeResources/files/trusted-issuers/.
private val bundledIssuers = listOf(
    // Stopgap until GDS sends "mDL Example IACA Root - INT environment": trusts only licences
    // signed by this one document signer in the GOV.UK Wallet sandbox.
    "files/trusted-issuers/gds-sandbox-example-issuer-dsc-int.pem" to TrustMetadata(
        displayName = "GOV.UK Wallet sandbox (Example Issuer DSC, INT)",
        testOnly = true,
    ),
    // DVLA integration (non-prod) IACA, from mdoc_iacas_uri in DVLA's issuer metadata
    // (https://credential-issuer-nonprod.dvla.gov.uk/gov-uk/iacas). Valid to 2030-11-17.
    // SHA-256 3885aa9cc0f98bab416ce8164f009eb3e2dccee75543168848348d7ea5ce62a9
    "files/trusted-issuers/dvla-mdl-iaca-root-nonprod.pem" to TrustMetadata(
        displayName = "DVLA mDL IACA Root (non-prod)",
        testOnly = true,
    ),
)

/**
 * Loads [bundledIssuers] into [trustManager], replacing whatever it held, unless this
 * version has already been loaded.
 */
suspend fun loadBundledIssuers(trustManager: TrustManagerLocal, settingsModel: SettingsModel) {
    if (settingsModel.builtInIssuersVersion.value == BUNDLED_ISSUERS_VERSION) {
        return
    }
    trustManager.deleteAll()
    for ((path, metadata) in bundledIssuers) {
        val cert = X509Cert.fromPem(Res.readBytes(path).decodeToString())
        trustManager.addX509Cert(certificate = cert, metadata = metadata)
    }
    settingsModel.builtInIssuersVersion.value = BUNDLED_ISSUERS_VERSION
    settingsModel.builtInIssuersUpdatedAt.value = Clock.System.now()
}
