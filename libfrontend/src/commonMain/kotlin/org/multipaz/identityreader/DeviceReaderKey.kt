package org.multipaz.identityreader

import kotlinx.io.bytestring.ByteString
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea
import org.multipaz.util.Logger

// Functions for the reader key used by ReaderAuthMethod.DEVICE_KEY. The key is created in
// [SecureArea] (Android Keystore on Android) and never leaves the device: the signer certifies it
// from a CSR, and the certificate chain it sends back is stored in SettingsModel.

private const val TAG = "DeviceReaderKey"

// GOV.UK Wallet name-constrains the reader sub-CA to C=GB, O=Amiqus Resolution Limited.
private const val CSR_COUNTRY = "GB"
private const val CSR_ORGANIZATION = "Amiqus Resolution Limited"
private const val CSR_COMMON_NAME = "Amiqus Demo Verifier"

/**
 * Creates a new device reader key, replacing any existing one along with its certificate chain.
 *
 * @param createKeySettings settings for the key, e.g. to use StrongBox. Must give a P-256 key.
 * @return a DER-encoded CSR for the new key, to send to the signer.
 */
suspend fun generateDeviceReaderKey(
    settingsModel: SettingsModel,
    secureArea: SecureArea,
    createKeySettings: CreateKeySettings = CreateKeySettings(algorithm = Algorithm.ESP256)
): ByteString {
    deleteDeviceReaderKey(settingsModel, secureArea)
    val keyInfo = secureArea.createKey(null, createKeySettings)
    settingsModel.deviceReaderKeyAlias.value = keyInfo.alias
    return getDeviceReaderCsr(settingsModel, secureArea)
}

/**
 * Generates a CSR for the existing device reader key, e.g. to send it to the signer again.
 *
 * @throws IllegalStateException if there is no device reader key.
 */
suspend fun getDeviceReaderCsr(settingsModel: SettingsModel, secureArea: SecureArea): ByteString {
    val alias = checkNotNull(settingsModel.deviceReaderKeyAlias.value) { "No device reader key" }
    val keyInfo = secureArea.getKeyInfo(alias)
    return generateCsr(
        key = AsymmetricKey.AnonymousSecureAreaBased(alias, secureArea, keyInfo),
        country = CSR_COUNTRY,
        organization = CSR_ORGANIZATION,
        commonName = CSR_COMMON_NAME,
    )
}

/**
 * Stores the certificate chain the signer issued for the device reader key.
 *
 * The chain is sent to wallets as-is (less any self-signed root), so it must include the
 * intermediates up to the wallet's trusted root. A reader certificate on its own is rejected.
 *
 * @param certChain the chain, starting with the reader certificate.
 * @throws IllegalStateException if there is no device reader key.
 * @throws IllegalArgumentException if the chain is only the reader certificate, isn't for the
 *   device reader key, or doesn't validate.
 */
suspend fun importDeviceReaderCertChain(
    settingsModel: SettingsModel,
    secureArea: SecureArea,
    certChain: X509CertChain
) {
    val alias = checkNotNull(settingsModel.deviceReaderKeyAlias.value) { "No device reader key" }
    require(certChain.certificates.size >= 2) {
        "The file has only the reader certificate. Ask the signer for the chain file (…-chain.pem)"
    }
    require(certChain.certificates[0].ecPublicKey == secureArea.getKeyInfo(alias).publicKey) {
        "The certificate is not for this device's reader key"
    }
    require(certChain.validate()) {
        "Certificate chain did not validate"
    }
    settingsModel.deviceReaderCertChain.value = certChain
}

/**
 * Parses certificates from a file: one or more PEM certificates, or a single DER certificate.
 *
 * @throws IllegalArgumentException if no certificates could be parsed.
 */
fun parseCertificates(data: ByteString): List<X509Cert> {
    val text = data.toByteArray().decodeToString()
    if (!text.contains(PEM_CERT_BEGIN)) {
        return listOf(X509Cert(data))
    }
    val certs = text.split(PEM_CERT_BEGIN).drop(1).map { X509Cert.fromPem(PEM_CERT_BEGIN + it) }
    require(certs.isNotEmpty()) { "No certificates found" }
    return certs
}

private const val PEM_CERT_BEGIN = "-----BEGIN CERTIFICATE-----"

/**
 * Gets the certified device reader key for signing requests.
 *
 * @return the key, or `null` if there's no key or it hasn't been certified yet.
 */
suspend fun getDeviceReaderKey(
    settingsModel: SettingsModel,
    secureArea: SecureArea
): AsymmetricKey.X509CertifiedSecureAreaBased? {
    val alias = settingsModel.deviceReaderKeyAlias.value ?: return null
    val certChain = settingsModel.deviceReaderCertChain.value ?: return null
    return AsymmetricKey.X509CertifiedSecureAreaBased(
        certChain = certChain,
        alias = alias,
        secureArea = secureArea,
        keyInfo = secureArea.getKeyInfo(alias)
    )
}

/**
 * Deletes the device reader key, if any, and its certificate chain.
 */
suspend fun deleteDeviceReaderKey(settingsModel: SettingsModel, secureArea: SecureArea) {
    settingsModel.deviceReaderKeyAlias.value?.let { alias ->
        try {
            secureArea.deleteKey(alias)
        } catch (e: Throwable) {
            Logger.w(TAG, "Error deleting device reader key", e)
        }
    }
    settingsModel.deviceReaderKeyAlias.value = null
    settingsModel.deviceReaderCertChain.value = null
}
