package org.multipaz.identityreader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import multipazidentityreader.composeapp.generated.resources.Res
import multipazidentityreader.composeapp.generated.resources.app_icon
import multipazidentityreader.composeapp.generated.resources.reader_identity_title
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.multipaz.compose.pickers.rememberFilePicker
import org.multipaz.crypto.X509CertChain
import org.multipaz.prompt.PassphraseEvaluation
import org.multipaz.prompt.PromptDismissedException
import org.multipaz.prompt.PromptModel
import org.multipaz.prompt.requestPassphrase
import org.multipaz.securearea.PassphraseConstraints
import org.multipaz.util.Logger
import kotlin.time.Clock

private const val TAG = "ReaderIdentityScreen"

// Public intermediates appended to the reader certificate the signer sends back: the Amiqus
// Reader Sub-CA and the GOV.UK Wallet Verifier CA, both for the INT (sandbox) environment.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderIdentityScreen(
    promptModel: PromptModel,
    readerBackendClient: ReaderBackendClient,
    settingsModel: SettingsModel,
    onBackPressed: () -> Unit,
    onShowCertificateChain: (certChain: X509CertChain) -> Unit
) {
    val coroutineScope = rememberCoroutineScope { promptModel }
    val showImportErrorDialog = remember { mutableStateOf<String?>(null) }
    val availableReaderIdentities = mutableStateOf<List<ReaderIdentity>?>(null)

    val secureArea = readerBackendClient.secureArea
    val deviceKeyAlias = settingsModel.deviceReaderKeyAlias.collectAsState()
    val deviceCertChain = settingsModel.deviceReaderCertChain.collectAsState()
    val deviceKeyStorage = remember { mutableStateOf<String?>(null) }
    val showCsrDialog = remember { mutableStateOf<String?>(null) }
    val showRegenerateDialog = remember { mutableStateOf(false) }

    LaunchedEffect(deviceKeyAlias.value) {
        deviceKeyStorage.value = deviceKeyAlias.value?.let { alias ->
            try {
                getPlatformUtils().describeKeyStorage(secureArea.getKeyInfo(alias))
            } catch (e: Throwable) {
                Logger.w(TAG, "Error getting device reader key info", e)
                null
            }
        }
    }

    fun generateDeviceKey() {
        coroutineScope.launch {
            try {
                val csr = generateDeviceReaderKey(
                    settingsModel = settingsModel,
                    secureArea = secureArea,
                    createKeySettings = getPlatformUtils().deviceReaderKeySettings()
                )
                settingsModel.readerAuthMethod.value = ReaderAuthMethod.DEVICE_KEY
                showCsrDialog.value = csrToPem(csr)
            } catch (e: Throwable) {
                Logger.e(TAG, "Error generating device reader key", e)
                showImportErrorDialog.value = "Generating reader key failed: $e"
            }
        }
    }

    val importDeviceCertFilePicker = rememberFilePicker(
        // .pem files have no reliable MIME type on Android, so allow any file and parse it.
        types = listOf("*/*"),
        allowMultiple = false,
        onResult = { files ->
            if (files.isNotEmpty()) {
                coroutineScope.launch {
                    try {
                        val certChain = X509CertChain(parseCertificates(files[0]))
                        importDeviceReaderCertChain(settingsModel, secureArea, certChain)
                        settingsModel.readerAuthMethod.value = ReaderAuthMethod.DEVICE_KEY
                    } catch (e: Throwable) {
                        Logger.e(TAG, "Error importing device reader certificate", e)
                        showImportErrorDialog.value = "Importing certificate failed: ${e.message}"
                    }
                }
            }
        }
    )

    showCsrDialog.value?.let { csrPem ->
        AlertDialog(
            onDismissRequest = { showCsrDialog.value = null },
            confirmButton = {
                TextButton(
                    onClick = { getPlatformUtils().shareText("Reader certificate signing request", csrPem) }
                ) {
                    Text(text = "Share")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCsrDialog.value = null }
                ) {
                    Text(text = "Close")
                }
            },
            title = {
                Text(text = "Certificate signing request")
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Send this to the Amiqus reader signer, then import the certificate " +
                                "they send back. It contains only this device's public key."
                    )
                    SelectionContainer {
                        Text(
                            text = csrPem,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        )
    }

    if (showRegenerateDialog.value) {
        AlertDialog(
            onDismissRequest = { showRegenerateDialog.value = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRegenerateDialog.value = false
                        generateDeviceKey()
                    }
                ) {
                    Text(text = "Generate")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRegenerateDialog.value = false }
                ) {
                    Text(text = "Cancel")
                }
            },
            title = {
                Text(text = "Generate a new key?")
            },
            text = {
                Text(
                    text = "The current key and its certificate will be deleted. You'll need a new " +
                            "certificate from the Amiqus reader signer."
                )
            }
        )
    }

    val importReaderKeyFilePicker = rememberFilePicker(
        types = listOf(
            "application/x-pkcs12",
        ),
        allowMultiple = false,
        onResult = { files ->
            if (files.isNotEmpty()) {
                val pkcs12Contents = files[0]
                coroutineScope.launch {
                    try {
                        val passphrase = promptModel.requestPassphrase(
                            title = "Import reader certificate",
                            subtitle = "The PKCS#12 file is protected by a passphrase which have " +
                                    "should have been shared with you. Enter the passphrase to continue",
                            passphraseConstraints = PassphraseConstraints.NONE,
                            passphraseEvaluator = { passphrase ->
                                try {
                                    parsePkcs12(pkcs12Contents, passphrase)
                                    PassphraseEvaluation.OK
                                } catch (e: WrongPassphraseException) {
                                    Logger.w(TAG, "Wrong passphrase", e)
                                    "Wrong passphrase. Try again"
                                    PassphraseEvaluation.TryAgain
                                } catch (_: Throwable) {
                                    // If parsing fails for reasons other than the wrong passphrase
                                    // supplied, just pretend the passphrase worked and we'll catch
                                    // the error below and show it to the user
                                    PassphraseEvaluation.OK
                                }
                            }
                        )
                        val (privateKey, certChain) = parsePkcs12(pkcs12Contents, passphrase)
                        require(privateKey.publicKey == certChain.certificates[0].ecPublicKey) {
                            "First certificate is not for the given key"
                        }
                        require(certChain.validate()) {
                            "Certificate chain did not validate"
                        }
                        println("first : ${certChain.certificates[0].toPem()}")
                        println("second : ${certChain.certificates[1].toPem()}")
                        // TODO: add a couple of additional checks for example that the leaf certificate
                        //   has the correct keyUsage flags, etc.
                        //
                        settingsModel.customReaderAuthKey.value = privateKey
                        settingsModel.customReaderAuthCertChain.value = certChain
                        settingsModel.readerAuthMethod.value = ReaderAuthMethod.CUSTOM_KEY
                    } catch (_: PromptDismissedException) {
                        /* do nothing */
                    } catch (e: Throwable) {
                        e.printStackTrace()
                        showImportErrorDialog.value = "Importing reader key failed: $e"
                    }
                }
            }
        }
    )

    showImportErrorDialog.value?.let {
        AlertDialog(
            onDismissRequest = { showImportErrorDialog.value = null },
            confirmButton = {
                TextButton(
                    onClick = { showImportErrorDialog.value = null }
                ) {
                    Text(text = "Close")
                }
            },
            title = {
                Text(text = "Reader key error")
            },
            text = {
                Text(text = it)
            }
        )
    }

    Scaffold(
        topBar = {
            AppBar(
                title = AnnotatedString(stringResource(Res.string.reader_identity_title)),
                onBackPressed = onBackPressed,
            )
        },
    ) { innerPadding ->
        val scrollState = rememberScrollState()
        Surface(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            val readerAuthMethod = settingsModel.readerAuthMethod.collectAsState()
            val readerAuthMethodGoogleId = settingsModel.readerAuthMethodGoogleIdentity.collectAsState()
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                Text(
                    text = """
When making a request for identity attributes a reader identity may be used to cryptographically
sign the request. The wallet receiving the request may use this to inform the identity holder who
the request is from
                    """.trimIndent().replace("\n", " ").trim(),
                )

                val entries = mutableListOf<@Composable () -> Unit>()
                entries.add {
                    Row(
                        modifier = Modifier.clickable {
                            settingsModel.readerAuthMethod.value = ReaderAuthMethod.NO_READER_AUTH
                            settingsModel.customReaderAuthKey.value = null
                            settingsModel.customReaderAuthCertChain.value = null
                        },
                        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Start),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            modifier = Modifier.size(32.dp),
                            imageVector = Icons.Outlined.Block,
                            contentDescription = null
                        )
                        EntryItem(
                            modifier = Modifier.weight(1.0f),
                            key = "Don't use reader authentication",
                            valueText = "The request won't be signed and the receiving wallet " +
                                    "won't know who's asking"
                        )
                        Checkbox(
                            checked = readerAuthMethod.value == ReaderAuthMethod.NO_READER_AUTH,
                            onCheckedChange = null
                        )
                    }
                }
                entries.add {
                    Row(
                        modifier = Modifier.clickable {
                            if (deviceKeyAlias.value == null) {
                                generateDeviceKey()
                            } else {
                                settingsModel.readerAuthMethod.value = ReaderAuthMethod.DEVICE_KEY
                            }
                        },
                        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Start),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            modifier = Modifier.size(32.dp),
                            imageVector = Icons.Outlined.Key,
                            contentDescription = null
                        )
                        EntryItem(
                            modifier = Modifier.weight(1.0f),
                            key = "Use this device's reader key",
                            valueText = "A key generated in this device's secure hardware signs " +
                                    "requests. It never leaves the device",
                        )
                        Checkbox(
                            checked = readerAuthMethod.value == ReaderAuthMethod.DEVICE_KEY,
                            onCheckedChange = null
                        )
                    }
                }
                entries.add {
                    Row(
                        modifier = Modifier.clickable {
                            importReaderKeyFilePicker.launch()
                        },
                        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Start),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            modifier = Modifier.size(32.dp),
                            imageVector = Icons.Outlined.Badge,
                            contentDescription = null
                        )
                        EntryItem(
                            modifier = Modifier.weight(1.0f),
                            key = "Use reader certificate from PKCS#12 file",
                            valueText = "Uses a custom key to sign requests. The same key will be " +
                                        "used to sign all requests",
                        )
                        Checkbox(
                            checked = readerAuthMethod.value == ReaderAuthMethod.CUSTOM_KEY,
                            onCheckedChange = null
                        )
                    }
                }
                if (isReaderBackendEnabled) entries.add {
                    Row(
                        modifier = Modifier.clickable {
                            settingsModel.readerAuthMethod.value = ReaderAuthMethod.STANDARD_READER_AUTH
                            settingsModel.customReaderAuthKey.value = null
                            settingsModel.customReaderAuthCertChain.value = null
                            // Prime the cache
                            coroutineScope.launch {
                                try {
                                    readerBackendClient.getKey()
                                } catch (e: Throwable) {
                                    Logger.w(TAG, "Error priming cache for standard reader auth", e)
                                }
                            }
                        },
                        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Start),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            modifier = Modifier.size(32.dp),
                            painter = painterResource(Res.drawable.app_icon),
                            tint = Color.Unspecified,
                            contentDescription = null
                        )
                        EntryItem(
                            modifier = Modifier.weight(1.0f),
                            key = "Multipaz Identity Reader",
                            valueText = "The Multipaz Identity Reader CA will be used to " +
                                    "certify single-use reader keys",
                        )
                        Checkbox(
                            checked = readerAuthMethod.value == ReaderAuthMethod.STANDARD_READER_AUTH,
                            onCheckedChange = null
                        )
                    }
                }

                LaunchedEffect(Unit) {
                    if (settingsModel.signedIn.value != null) {
                        coroutineScope.launch {
                            try {
                                availableReaderIdentities.value = readerBackendClient.getReaderIdentities()
                                println("num Reader Identities = ${availableReaderIdentities.value?.size}")
                                availableReaderIdentities.value?.forEach {
                                    println(it)
                                }
                            } catch (e: Throwable) {
                                Logger.i(TAG, "Error loading identities", e)
                                availableReaderIdentities.value = emptyList()
                            }
                        }
                    }
                }

                val signedInData = settingsModel.signedIn.collectAsState()
                signedInData.value?.let {
                    entries.add {
                        Row(
                            modifier = Modifier.clickable {
                                settingsModel.readerAuthMethod.value = ReaderAuthMethod.STANDARD_READER_AUTH_WITH_GOOGLE_ACCOUNT_DETAILS
                                settingsModel.customReaderAuthKey.value = null
                                settingsModel.customReaderAuthCertChain.value = null
                                // Prime the cache
                                coroutineScope.launch {
                                    try {
                                        readerBackendClient.getKey(readerIdentityId = "")
                                    } catch (e: Throwable) {
                                        Logger.w(TAG, "Error priming cache for standard reader auth" +
                                                " w/ Google Account details", e)
                                    }
                                }
                            },
                            horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Start),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            it.ProfilePicture(
                                size = 32.dp
                            )
                            EntryItem(
                                modifier = Modifier.weight(1.0f),
                                key = it.id,
                                valueText = "Information from your Google Account (id, email, and profile picture) " +
                                "will be included in the request",
                            )
                            Checkbox(
                                checked = (readerAuthMethod.value ==
                                        ReaderAuthMethod.STANDARD_READER_AUTH_WITH_GOOGLE_ACCOUNT_DETAILS),
                                onCheckedChange = null
                            )
                        }
                    }
                }

                availableReaderIdentities.value?.forEach { readerIdentityFromGoogleAccount ->
                    entries.add {
                        Row(
                            modifier = Modifier.clickable {
                                settingsModel.readerAuthMethod.value = ReaderAuthMethod.IDENTITY_FROM_GOOGLE_ACCOUNT
                                settingsModel.readerAuthMethodGoogleIdentity.value = readerIdentityFromGoogleAccount
                                // Prime the cache
                                coroutineScope.launch {
                                    try {
                                        readerBackendClient.getKey(
                                            readerIdentityId = settingsModel.readerAuthMethodGoogleIdentity.value!!.id)
                                    } catch (e: Throwable) {
                                        Logger.w(TAG, "Error priming cache for Google Account reader auth", e)
                                    }
                                }
                            },
                            horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Start),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            readerIdentityFromGoogleAccount.Icon()
                            EntryItem(
                                modifier = Modifier.weight(1.0f),
                                key = readerIdentityFromGoogleAccount.displayName,
                                valueText = "Reader identity from your Google account",
                            )
                            Checkbox(
                                checked = (
                                        readerAuthMethod.value == ReaderAuthMethod.IDENTITY_FROM_GOOGLE_ACCOUNT &&
                                        readerAuthMethodGoogleId.value == readerIdentityFromGoogleAccount
                                ),
                                onCheckedChange = null
                            )
                        }
                    }
                }

                EntryList(
                    title = "Reader identity",
                    entries = entries
                )

                if (readerAuthMethod.value == ReaderAuthMethod.DEVICE_KEY && deviceKeyAlias.value != null) {
                    val cert = deviceCertChain.value?.certificates?.first()
                    val status = when {
                        cert == null -> "Waiting for a certificate. Share the certificate signing " +
                                "request with the Amiqus reader signer, then import the certificate " +
                                "they send back"
                        cert.validityNotAfter < Clock.System.now() ->
                            "The certificate expired on ${cert.validityNotAfter.toString().substringBefore('T')}. " +
                                    "Share the certificate signing request again to get a new one"
                        else -> "Certified as ${cert.subject.name}, " +
                                "valid until ${cert.validityNotAfter.toString().substringBefore('T')}"
                    }
                    EntryList(
                        title = "This device's reader key",
                        entries = listOf(
                            {
                                EntryItem(
                                    key = "Status",
                                    valueText = "$status." + (deviceKeyStorage.value?.let { " Key stored in $it." } ?: ""),
                                )
                            },
                            {
                                EntryItem(
                                    modifier = Modifier.clickable {
                                        coroutineScope.launch {
                                            try {
                                                showCsrDialog.value =
                                                    csrToPem(getDeviceReaderCsr(settingsModel, secureArea))
                                            } catch (e: Throwable) {
                                                Logger.e(TAG, "Error generating CSR", e)
                                                showImportErrorDialog.value = "Generating the request failed: $e"
                                            }
                                        }
                                    },
                                    key = "Share certificate signing request",
                                    valueText = "Send it to the Amiqus reader signer",
                                )
                            },
                            {
                                EntryItem(
                                    modifier = Modifier.clickable { importDeviceCertFilePicker.launch() },
                                    key = "Import certificate",
                                    valueText = "The chain .pem file the signer sends back",
                                )
                            },
                            {
                                EntryItem(
                                    modifier = Modifier.clickable { showRegenerateDialog.value = true },
                                    key = "Generate a new key",
                                    valueText = "Deletes this key and its certificate",
                                )
                            },
                        )
                    )
                }

                if (readerAuthMethod.value != ReaderAuthMethod.NO_READER_AUTH) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        style = MaterialTheme.typography.bodyMedium,
                        text = buildAnnotatedString {
                            withLink(
                                LinkAnnotation.Clickable(
                                    tag = "cert",
                                    linkInteractionListener = { link ->
                                        when (readerAuthMethod.value) {
                                            ReaderAuthMethod.NO_READER_AUTH -> {}
                                            ReaderAuthMethod.CUSTOM_KEY -> {
                                                settingsModel.customReaderAuthCertChain.value?.let(onShowCertificateChain)
                                            }
                                            ReaderAuthMethod.DEVICE_KEY -> {
                                                settingsModel.deviceReaderCertChain.value?.let(onShowCertificateChain)
                                            }
                                            ReaderAuthMethod.IDENTITY_FROM_GOOGLE_ACCOUNT -> {
                                                coroutineScope.launch {
                                                    onShowCertificateChain(
                                                        readerBackendClient.getKey(
                                                            readerIdentityId = settingsModel
                                                                .readerAuthMethodGoogleIdentity.value!!.id
                                                        ).second
                                                    )
                                                }
                                            }

                                            ReaderAuthMethod.STANDARD_READER_AUTH -> {
                                                coroutineScope.launch {
                                                    onShowCertificateChain(
                                                        readerBackendClient.getKey(readerIdentityId = null).second
                                                    )
                                                }
                                            }

                                            ReaderAuthMethod.STANDARD_READER_AUTH_WITH_GOOGLE_ACCOUNT_DETAILS -> {
                                                coroutineScope.launch {
                                                    onShowCertificateChain(
                                                        readerBackendClient.getKey(readerIdentityId = "").second
                                                    )
                                                }
                                            }
                                        }
                                    }
                                )
                            ) {
                                withStyle(
                                    style = SpanStyle(
                                        color = Color.Blue,
                                        textDecoration = TextDecoration.Underline
                                    )
                                ) {
                                    append("View certificate chain")
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}
