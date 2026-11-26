package org.multipaz.identityreader

import kotlinx.coroutines.runBlocking
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborArray
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.buildX509Cert
import org.multipaz.mdoc.engagement.buildDeviceEngagement
import org.multipaz.util.toBase64Url
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class DemoLogTest {
    private val start = Clock.System.now()
    private val session = DemoSession(id = 1, startedAt = start, title = "QR code", DemoOutcome.PASS, "Verified")

    // Stands in for a decrypted DeviceResponse.
    private val personalData = Cbor.encode(Tstr("Jane Doe, born 1990-01-01"))
    private val request = Cbor.encode(Tstr("a DeviceRequest"))

    private fun entry(seq: Long, sessionId: Int?, message: String, event: DemoEvent? = null) = DemoLogEntry(
        seq = seq,
        time = start + seq.seconds,
        sessionId = sessionId,
        level = DemoLogLevel.INFO,
        tag = "Test",
        message = message,
        throwable = null,
        event = event,
    )

    private val entries = listOf(
        entry(0, null, "before any session"),
        entry(1, 1, "Built DeviceRequest", DemoEvent(
            DemoStep.REQUEST, DemoDirection.LOCAL, null, listOf("mDL: given_name"),
            listOf(DemoLogPayload.cbor("DeviceRequest", request))
        )),
        entry(2, 1, "Decrypted DeviceResponse", DemoEvent(
            DemoStep.RESPONSE, DemoDirection.LOCAL, null, emptyList(),
            listOf(DemoLogPayload.cbor("DeviceResponse", personalData, containsPersonalData = true))
        )),
        entry(3, 2, "another session"),
    )

    private fun bundle(includePersonalData: Boolean, allEntries: Boolean = false) =
        DemoLogExport.buildBundle(listOf(session), entries, allEntries, includePersonalData, "test")
            .mapValues { it.value.decodeToString() }

    @Test
    fun personalDataIsRedactedByDefault() {
        val files = bundle(includePersonalData = false)
        files.values.forEach { assertFalse("Jane Doe" in it) }
        assertFalse(files.values.any { personalData.toBase64Url() in it })
        assertTrue(files.keys.none { "deviceresponse" in it })
        assertContains(files.getValue("log.jsonl"), "\"redacted\":true")
        assertContains(files.getValue("README.txt"), "Personal data is redacted")
    }

    @Test
    fun personalDataIsIncludedOnlyWhenAsked() {
        val files = bundle(includePersonalData = true)
        assertContains(files.getValue("report.html"), "Jane Doe")
        assertContains(files.getValue("log.jsonl"), personalData.toBase64Url())
        assertTrue(files.keys.any { it.endsWith("-deviceresponse.cbor") })
        assertContains(files.getValue("README.txt"), "WARNING")
    }

    @Test
    fun bundleHasTheSessionAndItsPayloads() {
        val files = bundle(includePersonalData = false)
        assertEquals(
            setOf("README.txt", "report.html", "log.txt", "log.jsonl",
                "payloads/001-S1-devicerequest.cbor", "payloads/001-S1-devicerequest.diag.txt"),
            files.keys
        )
        assertEquals(2, files.getValue("log.jsonl").lines().count { it.isNotEmpty() })
        assertFalse("another session" in files.getValue("log.txt"))
        assertFalse("before any session" in files.getValue("log.txt"))
        assertContains(files.getValue("payloads/001-S1-devicerequest.diag.txt"), "\"a DeviceRequest\"")
    }

    @Test
    fun bundleOfEverythingIncludesLinesOutsideSessions() {
        val log = bundle(includePersonalData = false, allEntries = true).getValue("log.txt")
        assertContains(log, "before any session")
        assertContains(log, "another session")
    }

    @Test
    fun htmlIsEscaped() {
        val html = DemoLogExport.buildBundle(
            listOf(session),
            listOf(entry(1, 1, "<script>", DemoEvent(DemoStep.TRUST, DemoDirection.LOCAL, DemoOutcome.FAIL, emptyList(), emptyList()))),
            allEntries = false,
            includePersonalData = false,
            appInfo = "test",
        ).getValue("report.html").decodeToString()
        assertFalse("<script>" in html)
        assertContains(html, "&lt;script&gt;")
    }

    @Test
    fun guardedContainsLoggingErrorsButNotCancellation() {
        // Would fail the test if the error escaped.
        DemoLog.guarded("test event") { error("logging bug") }
        assertFailsWith<CancellationException> {
            DemoLog.guarded("test event") { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun describesUnsignedRequest() = runBlocking {
        val description = describeDeviceRequest(encodedRequest(readerKey = null))
        assertFalse(description.readerAuthenticated)
        assertContains(description.details, "  Reader authentication: none")
        assertTrue(description.details.any { "given_name" in it })
    }

    @Test
    fun describesSignedRequestAlgorithmAndChain() = runBlocking {
        val caKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val readerKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val now = Clock.System.now()
        val cert = buildX509Cert(
            publicKey = readerKey.publicKey,
            signingKey = AsymmetricKey.AnonymousExplicit(caKey, Algorithm.ES256),
            serialNumber = ASN1Integer(1L),
            subject = X500Name.fromName("CN=Test Reader"),
            issuer = X500Name.fromName("CN=Test Reader CA"),
            validFrom = now - 1.days,
            validUntil = now + 1.days,
        ) {}
        val description = describeDeviceRequest(
            encodedRequest(AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(cert)), readerKey, Algorithm.ES256))
        )
        assertTrue(description.readerAuthenticated)
        assertContains(description.details, "  Reader authentication: COSE_Sign1, alg -7 (ES256)")
        assertContains(description.details, "  x5chain: 1 certificate(s)")
        assertTrue(description.details.any { "CN=Test Reader (issuer CN=Test Reader CA" in it })
    }

    private suspend fun encodedRequest(readerKey: AsymmetricKey.X509Compatible?) = generateEncodedDeviceRequest(
        query = ReaderQuery.CUSTOM,
        customDataElements = listOf("given_name"),
        deviceEngagement = buildDeviceEngagement(Crypto.createEcPrivateKey(EcCurve.P256).publicKey) {},
        intentToRetain = false,
        encodedSessionTranscript = Cbor.encode(buildCborArray { add(Simple.NULL); add(Simple.NULL); add(Simple.NULL) }),
        readerKey = readerKey,
    )
}
