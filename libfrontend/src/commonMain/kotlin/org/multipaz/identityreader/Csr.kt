package org.multipaz.identityreader

import kotlinx.io.bytestring.ByteString
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1BitString
import org.multipaz.asn1.ASN1Encoding
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1ObjectIdentifier
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.ASN1Set
import org.multipaz.asn1.ASN1String
import org.multipaz.asn1.ASN1StringTag
import org.multipaz.asn1.ASN1TagClass
import org.multipaz.asn1.ASN1TaggedObject
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.EcCurve
import kotlin.io.encoding.Base64

private const val OID_COUNTRY = "2.5.4.6"
private const val OID_ORGANIZATION = "2.5.4.10"
private const val OID_COMMON_NAME = "2.5.4.3"
private const val OID_ECDSA_WITH_SHA256 = "1.2.840.10045.4.3.2"

/**
 * Generates a PKCS#10 certificate signing request (RFC 2986) for [key], signed by [key] itself
 * to prove possession.
 *
 * The subject is `C=[country], O=[organization], CN=[commonName]`. GOV.UK Wallet name-constrains
 * the reader sub-CA, so signers check that O matches it exactly. The values are encoded as
 * PrintableString, as in the certificates the sub-CA issues.
 *
 * @param key a P-256 key, e.g. one held in Android Keystore. Signing uses ECDSA with SHA-256.
 * @param country the two-letter country code, e.g. `GB`.
 * @param organization the organisation name, which must match the sub-CA's.
 * @param commonName the reader's name, shown to the wallet holder.
 * @return the DER-encoded request.
 */
suspend fun generateCsr(
    key: AsymmetricKey,
    country: String,
    organization: String,
    commonName: String,
): ByteString {
    require(key.publicKey.curve == EcCurve.P256) { "Only P-256 keys are supported" }
    require(country.length == 2) { "Country must be a two-letter code" }

    val subject = ASN1Sequence(listOf(
        OID_COUNTRY to country,
        OID_ORGANIZATION to organization,
        OID_COMMON_NAME to commonName,
    ).map { (oid, value) ->
        require(value.all { it in PRINTABLE_STRING_CHARS }) { "\"$value\" isn't a PrintableString" }
        ASN1Set(listOf(
            ASN1Sequence(listOf(
                ASN1ObjectIdentifier(oid),
                ASN1String(value, ASN1StringTag.PRINTABLE_STRING.tag),
            ))
        ))
    })
    val certificationRequestInfo = ASN1Sequence(listOf(
        ASN1Integer(0L),
        subject,
        checkNotNull(ASN1.decode(pemToDer(key.publicKey.toPem()))) { "Couldn't encode public key" },
        // attributes [0] IMPLICIT SET, empty
        ASN1TaggedObject(ASN1TagClass.CONTEXT_SPECIFIC, ASN1Encoding.CONSTRUCTED, 0, ByteArray(0)),
    ))
    val signature = key.sign(ASN1.encode(certificationRequestInfo))
    return ByteString(ASN1.encode(ASN1Sequence(listOf(
        certificationRequestInfo,
        ASN1Sequence(listOf(ASN1ObjectIdentifier(OID_ECDSA_WITH_SHA256))),
        ASN1BitString(0, signature.toDerEncoded()),
    ))))
}

/**
 * Encodes a DER certificate signing request from [generateCsr] as PEM.
 */
fun csrToPem(csr: ByteString): String {
    val base64 = Base64.encode(csr.toByteArray()).chunked(64).joinToString("\n")
    return "-----BEGIN CERTIFICATE REQUEST-----\n$base64\n-----END CERTIFICATE REQUEST-----\n"
}

// X.680 PrintableString: letters, digits, space and '()+,-./:=?
private val PRINTABLE_STRING_CHARS = ('A'..'Z') + ('a'..'z') + ('0'..'9') + " '()+,-./:=?".toList()

private fun pemToDer(pem: String): ByteArray =
    Base64.decode(pem.lines().filterNot { it.startsWith("-----") }.joinToString(""))
