package org.multipaz.identityreader

import kotlinx.coroutines.runBlocking
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Simple
import org.multipaz.cbor.buildCborArray
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.mdoc.engagement.Capability
import org.multipaz.mdoc.engagement.buildDeviceEngagement
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReaderQueryTest {
    @Test
    fun customRequestsExactlyTheChosenElements() {
        val chosen = listOf("family_name", "driving_privileges", "family_name")
        assertEquals(listOf("family_name", "driving_privileges"), ReaderQuery.CUSTOM.mdlDataElements(chosen))
    }

    @Test
    fun otherQueriesIgnoreTheCustomElements() {
        val chosen = listOf("driving_privileges")
        assertEquals(listOf("age_over_18", "portrait"), ReaderQuery.AGE_OVER_18.mdlDataElements(chosen))
        assertEquals(IDENTIFICATION_MDL_DATA_ELEMENTS, ReaderQuery.IDENTIFICATION.mdlDataElements(chosen))
    }

    @Test
    fun customStartsAsIdentificationAndIdentificationIsTheDefault() = runBlocking {
        val settingsModel = SettingsModel.create(EphemeralStorage(), readOnly = true)
        assertEquals(IDENTIFICATION_MDL_DATA_ELEMENTS, settingsModel.customQueryDataElements.value)
        assertEquals(ReaderQuery.IDENTIFICATION.name, settingsModel.selectedQueryName.value)
    }

    private suspend fun requestFor(query: ReaderQuery, custom: List<String>, extendedRequests: Boolean): DeviceRequest {
        val deviceEngagement = buildDeviceEngagement(Crypto.createEcPrivateKey(EcCurve.P256).publicKey) {
            if (extendedRequests) addCapability(Capability.EXTENDED_REQUEST_SUPPORT, Simple.TRUE)
        }
        val encoded = generateEncodedDeviceRequest(
            query = query,
            customDataElements = custom,
            deviceEngagement = deviceEngagement,
            intentToRetain = false,
            encodedSessionTranscript = Cbor.encode(buildCborArray { add(Simple.NULL); add(Simple.NULL); add(Simple.NULL) }),
            readerKey = null,
        )
        return DeviceRequest.fromDataItem(Cbor.decode(encoded))
    }

    @Test
    fun customDeviceRequestAsksForOnlyTheChosenMdlElements() = runBlocking {
        for (extendedRequests in listOf(false, true)) {
            val request = requestFor(ReaderQuery.CUSTOM, listOf("family_name", "driving_privileges"), extendedRequests)
            val docRequest = request.docRequests.single()
            assertEquals(DrivingLicense.MDL_DOCTYPE, docRequest.docType)
            assertEquals(
                mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false, "driving_privileges" to false)),
                docRequest.nameSpaces
            )
        }
    }

    @Test
    fun identificationStillAsksForPhotoIdWhenSupported() = runBlocking {
        assertEquals(1, requestFor(ReaderQuery.IDENTIFICATION, emptyList(), extendedRequests = false).docRequests.size)
        assertEquals(2, requestFor(ReaderQuery.IDENTIFICATION, emptyList(), extendedRequests = true).docRequests.size)
    }

    @Test
    fun emptyCustomRequestIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            runBlocking { requestFor(ReaderQuery.CUSTOM, emptyList(), extendedRequests = false) }
        }
    }
}
