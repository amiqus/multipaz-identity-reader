package org.multipaz.identityreader

import kotlinx.coroutines.runBlocking
import kotlinx.io.bytestring.ByteString
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1BitString
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.EcSignature
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.buildX509Cert
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class DeviceReaderKeyTest {
    private lateinit var settingsModel: SettingsModel
    private lateinit var secureArea: SecureArea

    private val caKey = Crypto.createEcPrivateKey(EcCurve.P256)
    private val caName = X500Name.fromName("CN=Test Reader CA")
    private val caCert = runBlocking { issue(caKey.publicKey, caName, isCa = true) }

    @BeforeTest
    fun setUp() = runBlocking {
        val storage = EphemeralStorage()
        settingsModel = SettingsModel.create(storage, readOnly = true)
        secureArea = SoftwareSecureArea.create(storage)
    }

    private suspend fun issue(publicKey: EcPublicKey, subject: X500Name, isCa: Boolean = false): X509Cert {
        val now = Clock.System.now()
        return buildX509Cert(
            publicKey = publicKey,
            signingKey = AsymmetricKey.AnonymousExplicit(caKey, Algorithm.ES256),
            serialNumber = ASN1Integer(1L),
            subject = subject,
            issuer = caName,
            validFrom = now - 1.days,
            validUntil = now + 1.days,
        ) {
            if (isCa) setBasicConstraints(true, null)
        }
    }

    private fun assertCsrSignedBy(csr: ByteString, publicKey: EcPublicKey) {
        val seq = ASN1.decode(csr) as ASN1Sequence
        Crypto.checkSignature(
            publicKey = publicKey,
            message = ASN1.encode(seq.elements[0]!!),
            algorithm = Algorithm.ES256,
            signature = EcSignature.fromDerEncoded(256, (seq.elements[2] as ASN1BitString).value),
        )
    }

    @Test
    fun generateCreatesKeyAndCsrButNoCertifiedKeyYet() = runBlocking {
        val csr = generateDeviceReaderKey(settingsModel, secureArea)

        val alias = assertNotNull(settingsModel.deviceReaderKeyAlias.value)
        assertCsrSignedBy(csr, secureArea.getKeyInfo(alias).publicKey)
        assertNull(getDeviceReaderKey(settingsModel, secureArea))
    }

    @Test
    fun importedChainForTheKeyGivesACertifiedKey() = runBlocking {
        generateDeviceReaderKey(settingsModel, secureArea)
        val publicKey = secureArea.getKeyInfo(settingsModel.deviceReaderKeyAlias.value!!).publicKey
        val chain = X509CertChain(listOf(issue(publicKey, X500Name.fromName("CN=Reader")), caCert))

        importDeviceReaderCertChain(settingsModel, secureArea, chain)

        val readerKey = assertNotNull(getDeviceReaderKey(settingsModel, secureArea))
        assertEquals(chain, readerKey.certChain)
        val message = "hello".encodeToByteArray()
        Crypto.checkSignature(publicKey, message, Algorithm.ES256, readerKey.sign(message))
    }

    @Test
    fun chainForAnotherKeyIsRejected() = runBlocking {
        generateDeviceReaderKey(settingsModel, secureArea)
        val otherKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey
        val chain = X509CertChain(listOf(issue(otherKey, X500Name.fromName("CN=Reader")), caCert))

        assertFailsWith<IllegalArgumentException> {
            importDeviceReaderCertChain(settingsModel, secureArea, chain)
        }
        assertNull(settingsModel.deviceReaderCertChain.value)
    }

    @Test
    fun importWithoutAKeyFails() = runBlocking {
        val chain = X509CertChain(listOf(caCert))
        assertFailsWith<IllegalStateException> {
            importDeviceReaderCertChain(settingsModel, secureArea, chain)
        }
        Unit
    }

    @Test
    fun readerCertificateWithoutItsChainIsRejected() = runBlocking {
        generateDeviceReaderKey(settingsModel, secureArea)
        val publicKey = secureArea.getKeyInfo(settingsModel.deviceReaderKeyAlias.value!!).publicKey
        val leafOnly = X509CertChain(listOf(issue(publicKey, X500Name.fromName("CN=Reader"))))

        assertFailsWith<IllegalArgumentException> {
            importDeviceReaderCertChain(settingsModel, secureArea, leafOnly)
        }
        assertNull(settingsModel.deviceReaderCertChain.value)
    }

    @Test
    fun parseCertificatesReadsPemAndDer() = runBlocking {
        val leaf = issue(Crypto.createEcPrivateKey(EcCurve.P256).publicKey, X500Name.fromName("CN=Reader"))
        val pem = leaf.toPem() + caCert.toPem()

        assertEquals(listOf(leaf, caCert), parseCertificates(ByteString(pem.encodeToByteArray())))
        assertEquals(listOf(leaf), parseCertificates(leaf.encoded))
    }

    @Test
    fun generatingAgainReplacesKeyAndClearsChain() = runBlocking {
        generateDeviceReaderKey(settingsModel, secureArea)
        val oldAlias = settingsModel.deviceReaderKeyAlias.value!!
        val publicKey = secureArea.getKeyInfo(oldAlias).publicKey
        importDeviceReaderCertChain(
            settingsModel,
            secureArea,
            X509CertChain(listOf(issue(publicKey, X500Name.fromName("CN=Reader")), caCert))
        )

        generateDeviceReaderKey(settingsModel, secureArea)

        assertNull(settingsModel.deviceReaderCertChain.value)
        assertFailsWith<Throwable> { secureArea.getKeyInfo(oldAlias) }
        Unit
    }
}
