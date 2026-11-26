package org.multipaz.identityreader

import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.mdoc.engagement.DeviceEngagement
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.revocation.RevocationStatus
import org.multipaz.util.Constants

// Human-readable detail lines for DemoLog events. They describe structure, never data element values.

fun DeviceEngagement.describe(): List<String> = buildList {
    add("Version: $version")
    add("EDeviceKey: ephemeral ${eDeviceKey.curve.name} public key")
    connectionMethods.forEach { add("Connection method offered: $it") }
    capabilities.forEach { (capability, value) -> add("Capability $capability: ${value.diagnostics()}") }
    originInfos.forEach { add("Origin info: $it") }
}

fun X509Cert.describe(): String =
    "${subject.name} (issuer ${issuer.name}, valid ${validityNotBefore} to ${validityNotAfter})"

fun X509CertChain.describe(): List<String> =
    certificates.mapIndexed { index, cert -> "Certificate ${index + 1}/${certificates.size}: ${cert.describe()}" }

/** Describes a COSE algorithm identifier, e.g. "-7 (ES256)". */
fun describeCoseAlgorithm(identifier: Long): String {
    val name = try {
        Algorithm.fromCoseAlgorithmIdentifier(identifier.toInt()).name
    } catch (_: Throwable) {
        "unknown"
    }
    return "$identifier ($name)"
}

class DeviceRequestDescription(
    val details: List<String>,
    val readerAuthenticated: Boolean,
)

fun describeDeviceRequest(encodedDeviceRequest: ByteArray): DeviceRequestDescription = try {
    describeParsedDeviceRequest(Cbor.decode(encodedDeviceRequest))
} catch (e: Throwable) {
    DeviceRequestDescription(listOf("Could not describe DeviceRequest: $e"), false)
}

private fun describeParsedDeviceRequest(dataItem: DataItem): DeviceRequestDescription {
    val deviceRequest = DeviceRequest.fromDataItem(dataItem)
    // DocRequest.readerAuth is only available once verified, so read the COSE_Sign1 from the CBOR.
    val encodedDocRequests = dataItem["docRequests"].asArray
    var readerAuthenticated = deviceRequest.docRequests.isNotEmpty()
    val details = buildList {
        add("Version: ${deviceRequest.version}")
        deviceRequest.deviceRequestInfo?.let {
            add("DeviceRequestInfo (extended request) with ${it.useCases.size} use case(s)")
        }
        deviceRequest.docRequests.forEachIndexed { index, docRequest ->
            add("DocRequest ${index + 1}: ${docRequest.docType}")
            docRequest.nameSpaces.forEach { (namespace, elements) ->
                val retained = elements.filterValues { it }.keys
                add("  $namespace: ${elements.keys.joinToString(", ")}")
                add("  Intent to retain: ${if (retained.isEmpty()) "none" else retained.joinToString(", ")}")
            }
            val readerAuth = encodedDocRequests[index].getOrNull("readerAuth")?.asCoseSign1
            if (readerAuth == null) {
                readerAuthenticated = false
                add("  Reader authentication: none")
            } else {
                val alg = readerAuth.protectedHeaders.findNumberLabel(Cose.COSE_LABEL_ALG)?.asNumber
                add("  Reader authentication: COSE_Sign1, alg ${alg?.let { describeCoseAlgorithm(it) } ?: "missing"}")
                val x5chain = (readerAuth.unprotectedHeaders.findNumberLabel(Cose.COSE_LABEL_X5CHAIN)
                    ?: readerAuth.protectedHeaders.findNumberLabel(Cose.COSE_LABEL_X5CHAIN))
                if (x5chain == null) {
                    add("  x5chain: missing")
                } else {
                    val chain = X509CertChain.fromDataItem(x5chain)
                    add("  x5chain: ${chain.certificates.size} certificate(s)")
                    chain.describe().forEach { add("    $it") }
                }
            }
        }
    }
    return DeviceRequestDescription(details, readerAuthenticated)
}

class RevocationStatusDescription(
    val summary: String,
    val details: List<String>,
)

/**
 * Describes the status reference an issuer put in the MSO. Revocation isn't checked yet, so this
 * only shows what a check would use. The list index or identifier is left out because it
 * identifies the credential; it's in the MSO payload instead.
 */
fun describeRevocationStatus(status: RevocationStatus?): RevocationStatusDescription {
    val notChecked = "Not checked: this app doesn't fetch revocation lists yet, so the document " +
            "is accepted on its signatures and MSO validity period alone"
    return when (status) {
        null -> RevocationStatusDescription(
            "No revocation status in the MSO",
            listOf(
                "The issuer didn't include a status reference, so a revoked document still " +
                        "verifies until its MSO expires",
            )
        )
        is RevocationStatus.StatusList -> RevocationStatusDescription(
            "Revocation not checked (status list)",
            listOf(
                "Mechanism: status list (one status per credential, looked up by index)",
                "List URI: ${status.uri}",
                "List signer: ${status.certificate?.describe() ?: "no certificate given in the MSO"}",
                notChecked,
            )
        )
        is RevocationStatus.IdentifierList -> RevocationStatusDescription(
            "Revocation not checked (identifier list)",
            listOf(
                "Mechanism: identifier list (revoked credentials listed by identifier)",
                "List URI: ${status.uri}",
                "List signer: ${status.certificate?.describe() ?: "no certificate given in the MSO"}",
                notChecked,
            )
        )
        is RevocationStatus.Unknown -> RevocationStatusDescription(
            "Revocation not checked (unrecognised status format)",
            listOf("The MSO has a status reference this app doesn't recognise", notChecked)
        )
    }
}

/** Describes a SessionData status code from ISO/IEC 18013-5 table 20. */
fun describeSessionStatus(status: Long?): String = when (status) {
    null -> "none"
    Constants.SESSION_DATA_STATUS_ERROR_SESSION_ENCRYPTION -> "$status (error: session encryption)"
    Constants.SESSION_DATA_STATUS_ERROR_CBOR_DECODING -> "$status (error: CBOR decoding)"
    Constants.SESSION_DATA_STATUS_SESSION_TERMINATION -> "$status (session termination)"
    else -> "$status (unknown)"
}

private fun Map<CoseLabel, DataItem>.findNumberLabel(number: Long): DataItem? =
    entries.firstOrNull { (it.key as? CoseNumberLabel)?.number == number }?.value

private fun DataItem.diagnostics(): String = try {
    Cbor.toDiagnostics(this)
} catch (_: Throwable) {
    toString()
}
