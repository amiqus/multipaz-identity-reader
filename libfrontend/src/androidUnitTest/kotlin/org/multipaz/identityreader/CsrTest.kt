package org.multipaz.identityreader

import kotlinx.coroutines.runBlocking
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1BitString
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1ObjectIdentifier
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.ASN1Set
import org.multipaz.asn1.ASN1String
import org.multipaz.asn1.ASN1StringTag
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.EcSignature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CsrTest {
    private val privateKey = Crypto.createEcPrivateKey(EcCurve.P256)
    private val key = AsymmetricKey.AnonymousExplicit(privateKey, Algorithm.ES256)

    private suspend fun amiqusCsr() =
        generateCsr(key, "GB", "Amiqus Resolution Limited", "Amiqus Demo Verifier")

    @Test
    fun csrIsSignedByKeyAndCarriesItsPublicKey() = runBlocking {
        val csr = ASN1.decode(amiqusCsr()) as ASN1Sequence
        val info = csr.elements[0] as ASN1Sequence
        val signatureAlgorithm = csr.elements[1] as ASN1Sequence
        val signature = csr.elements[2] as ASN1BitString

        assertEquals(0L, (info.elements[0] as ASN1Integer).toLong())
        val subject = (info.elements[1] as ASN1Sequence).elements.map {
            val attribute = (it as ASN1Set).elements.single() as ASN1Sequence
            val value = attribute.elements[1] as ASN1String
            assertEquals(ASN1StringTag.PRINTABLE_STRING.tag, value.tag)
            (attribute.elements[0] as ASN1ObjectIdentifier).oid to value.value
        }
        assertEquals(
            listOf("2.5.4.6" to "GB", "2.5.4.10" to "Amiqus Resolution Limited", "2.5.4.3" to "Amiqus Demo Verifier"),
            subject
        )
        val spkiPem = "-----BEGIN PUBLIC KEY-----\n" +
                kotlin.io.encoding.Base64.encode(ASN1.encode(info.elements[2])) +
                "\n-----END PUBLIC KEY-----\n"
        assertEquals(privateKey.publicKey, EcPublicKey.fromPem(spkiPem, EcCurve.P256))

        assertEquals("1.2.840.10045.4.3.2", (signatureAlgorithm.elements[0] as ASN1ObjectIdentifier).oid)
        Crypto.checkSignature(
            publicKey = privateKey.publicKey,
            message = ASN1.encode(info),
            algorithm = Algorithm.ES256,
            signature = EcSignature.fromDerEncoded(256, signature.value),
        )
    }

    @Test
    fun pemIsWrappedAt64Characters() = runBlocking {
        val lines = csrToPem(amiqusCsr()).trimEnd().lines()
        assertEquals("-----BEGIN CERTIFICATE REQUEST-----", lines.first())
        assertEquals("-----END CERTIFICATE REQUEST-----", lines.last())
        assertTrue(lines.drop(1).dropLast(1).all { it.length <= 64 })
    }

    @Test
    fun rejectsCurvesOtherThanP256() {
        val p384 = AsymmetricKey.AnonymousExplicit(Crypto.createEcPrivateKey(EcCurve.P384), Algorithm.ES384)
        assertFailsWith<IllegalArgumentException> {
            runBlocking { generateCsr(p384, "GB", "Amiqus Resolution Limited", "Amiqus Demo Verifier") }
        }
    }

    @Test
    fun rejectsSubjectsThatArentPrintableStrings() {
        assertFailsWith<IllegalArgumentException> {
            runBlocking { generateCsr(key, "GBR", "Amiqus Resolution Limited", "Amiqus Demo Verifier") }
        }
        assertFailsWith<IllegalArgumentException> {
            runBlocking { generateCsr(key, "GB", "Amiqus Resolution Limited", "Amiqus & Co") }
        }
    }
}
