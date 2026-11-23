# Amiqus Demo Verifier

An Android demo verifier for reading the GOV.UK Wallet digital driving licence in person
(ISO/IEC 18013-5: QR engagement, then BLE). It's a fork of Multipaz Identity Reader. Amiqus work
lives on the `amiqus` branch, and `main` tracks upstream. See `NOTICE`.

**This is a demo, not production software.** It uses GOV.UK Wallet sandbox/integration certificates
and synthetic test data only.

## Amiqus changes

| Change | Where | Why |
| --- | --- | --- |
| Reader auth is signed with `ES256` (COSE −7), not `ESP256` (−9) | `libfrontend/…/ReaderQuery.kt` | GOV.UK Wallet rejects RFC 9864 fully-specified algorithms with `unsupportedReaderAuthAlgorithm` |
| Reader `x5chain` leaves out a self-signed root at the end of the imported chain. The full chain is kept for local validation. | `libfrontend/…/ReaderQuery.kt` | GOV.UK Wallet rejects a reader chain that includes the root with `untrustedReaderCertificate` |
| Bundled trusted issuers, loaded on first launch when there's no backend: the GOV.UK sandbox document signer (a stopgap until GDS sends the sandbox IACA), and DVLA's non-prod `mDL IACA Root` for the 2026-12-09 event | `BundledIssuers.kt`, `App.kt`, `composeResources/files/trusted-issuers/` | No manual issuer import, and no reliance on venue Wi-Fi. Bump `BUNDLED_ISSUERS_VERSION` whenever the list changes. Before the event, re-check DVLA's `mdoc_iacas_uri` (`https://credential-issuer-nonprod.dvla.gov.uk/gov-uk/iacas`) and compare the fingerprint `3885aa9c…ce62a9`. |
| The issuer certificate chain of every received document is logged (public certificates only) | `ShowResultsScreen.kt` | Identifies a missing issuer root, e.g. DVLA's at the event |
| No backend by default, so the app never contacts `identityreader.multipaz.org` | `composeApp/build.gradle.kts`, `ReaderBackendConfig.kt`, `App.kt`, `StartScreen.kt`, `SettingsDialog.kt`, `ReaderIdentityScreen.kt` | We use our own GOV.UK-issued reader certificate, not Multipaz's CA. No trust list or Google sign-in. The settings menu (profile icon) still works, without the Google account card. |
| **Device reader key**: the app generates a P-256 reader key in Android Keystore (StrongBox when the phone has it), shares a CSR (`C=GB, O=Amiqus Resolution Limited, CN=Amiqus Demo Verifier`), and imports the certificate chain the Amiqus signer sends back. A reader certificate without its chain is rejected, because the wallet needs the intermediates. | `libfrontend/…/Csr.kt`, `DeviceReaderKey.kt`, `PlatformUtils*.kt`, `ReaderQuery.kt`, `ReaderIdentityScreen.kt` | The reader's private key never leaves the phone, so there's no `.p12` or passphrase to hand around. Each phone gets its own certificate. Tested end to end with GOV.UK Wallet (sandbox) on 2026-11-23. |
| Default reader auth is the **device reader key**. Until its certificate is imported, requests go out unauthenticated and a warning is logged. Importing a PKCS#12 file still works as a fallback. | `libfrontend/…/SettingsModel.kt`, `ReaderQuery.kt` | Uses an Amiqus reader certificate. Existing installs keep whichever method they had. |
| The start screen has no NFC animation or "Hold to Wallet" prompt, just the *Scan QR Code* button. NFC engagement still runs in the background on Android. | `StartScreen.kt` | GOV.UK Wallet engages by QR code, so the NFC prompt misled people |
| **Custom** request: a fourth chip, with *Choose fields* to pick any of the ISO 18013-5 mDL fields (from Multipaz's catalogue). The choice is saved, starts as Identification's 15 fields, and can't be empty. Results show whatever came back; the portrait is optional. Custom asks for the mDL only, not ISO 23220 Photo ID. | `libfrontend/…/ReaderQuery.kt`, `SettingsModel.kt`, `QueryChips.kt`, `CustomQueryScreen.kt`, `ShowResultsScreen.kt`, `App.kt`, `Destinations.kt` | Demo any selective-disclosure scenario without a new build |
| The default request is **Identification**, and the **Age Over 21** request is removed (a saved Age Over 21 choice falls back to Identification) | `libfrontend/…/SettingsModel.kt`, `ReaderQuery.kt`, `ShowResultsScreen.kt` | The demo shows the full licence details, and 21 isn't a UK age threshold. Existing installs keep any other saved choice. |
| App name "Amiqus Demo Verifier", application ID `com.amiqus.demoverifier` | `strings.xml` (×2), `RequestBluetoothPermission.kt`, `composeApp/build.gradle.kts` | Distinguishes it from the upstream app. Apache 2.0 grants no trademark rights. |
| APKs are signed with the Amiqus key from Gradle properties, not the public upstream `devkey.keystore`. Without the key set, debug builds use the local Android debug key and release builds are unsigned. | `composeApp/build.gradle.kts` | Anyone can sign an APK with the public devkey, so it could impersonate our build or update over it |
| `NOTICE` file and this section | repo root | Apache 2.0 §4(b): state that files were changed |

The Kotlin package (`org.multipaz.identityreader`) is unchanged, to keep merges from upstream simple.
**Still to do:** replace the launcher icon (`composeApp/src/androidMain/res/mipmap-*`,
`composeResources/drawable/app_icon.webp`) with an Amiqus icon.

To re-enable the Multipaz backend features, build with `IDENTITY_READER_BACKEND_URL` set.

### Signing

Keep the Amiqus keystore and its passwords out of the repo. Add them to `~/.gradle/gradle.properties`:

```properties
amiqusSigningStoreFile=/path/to/amiqus-demo-verifier.jks
amiqusSigningStorePassword=…
amiqusSigningKeyAlias=amiqus-demo-verifier
amiqusSigningKeyPassword=…
```

Check with `./gradlew :composeApp:signingReport` (it should show `Config: amiqus`). A device with a
build signed by a different key must uninstall it before installing this one.

### Using it

1. Build and install: `./gradlew :composeApp:installDebug` (phone connected over USB)
2. Get the phone a reader certificate (once per phone, and again when it expires after ~90 days):
   1. Tap **Requesting data as** on the start screen (or profile icon → **Reader identity**) →
      *Use this device's reader key*. The app creates the key and shows its certificate signing
      request (CSR). The status says whether the key is in StrongBox or the TEE.
   2. Tap **Share** and send the CSR to someone who can sign with the Amiqus sub-CA (see the
      `amiqus-reader-ca` repo). The CSR holds only the public key, so email or chat is fine.
   3. They send back the **chain file** (`…-chain.pem`: reader certificate, Amiqus sub-CA, GOV.UK
      Verifier CA). Put it on the phone (e.g. `adb push reader-chain.pem /sdcard/Download/`), then
      tap **Import certificate** and choose it. The app checks it's for this phone's key and that
      the chain is valid. It rejects a file with only the reader certificate.
   4. The status changes to *Certified as … valid until …*, and the start screen says
      *Using this device's reader key*.
3. Choose a request, tap *Scan QR*, and scan the GOV.UK Wallet holder's proximity-share QR code

To get the CSR again, tap **Share certificate signing request**. It's for the same key, so a
certificate issued for an earlier copy still imports. **Don't tap *Generate a new key*** unless you
mean to replace the key: its certificate stops working.

**Fallback: PKCS#12.** Profile icon → **Reader identity** → *Use reader certificate from PKCS#12
file* → choose `reader.p12`. The full chain including the root is fine.

Trusted issuers are bundled. Add any others (e.g. DVLA's integration root) under profile icon →
**Trusted issuers** → *Import Certificate*, or add them to `BundledIssuers.kt`.

**Don't bundle `reader.p12` in the APK.** Anyone with the APK could extract the key and sign requests
as Amiqus. Prefer the device reader key: each phone has its own key, which can't be copied off it.

---

*Upstream README follows.*

This is a Compose Multiplatform project targeting Android, iOS.

This is a ISO/IEC 18013-5:2021 proximity reader for requesting credentials built using
the Multipaz SDK. It includes a backend component for certifying reader authentication keys.

Learn more about [Multipaz](https://github.com/openwallet-foundation-labs/identity-credential)…

Learn more about [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/)…

Learn more about [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html)…

This is not an official or supported Google product.

## Directory structure

- [backend](backend) contains the server-side for Multipaz Identity Reader. An instance of this
  is deployed at https://identityreader.multipaz.org/.
- [libbackend](libbackend) contains most of the code used in `backend`. It's available as a library
  to easier facilitate unit-testing with front-end code.
- [libfrontend](libfrontend) contains business-logic and non-UI code for the frontend. This code is
  used by both `composeApp` and `SwiftIdentityReader`.
- [composeApp](composeApp) contains the Compose Multiplatform application.
- [iosApp](iosApp) contains the Xcode project for running the Compose Multiplatform application.
- [SwiftIdentityReader](SwiftIdentityReader) contains a Swift application intended to have the same
  functionality as `composeApp`. This is experimental and in the future may either be deleted or
  graduate to the same level of support as `composeApp`.

## Generating reader keys for import

You can generate [PKCS#12](https://en.wikipedia.org/wiki/PKCS_12) files
for reader keys using the `multipazctl` and `openssl` command-line tools.
First create a reader root for a fictional Relying Party _Utopia Brewery_:

```shell
$ multipazctl generateReaderRoot --subject_and_issuer 'CN=Utopia Brewery Reader CA,O=Utopia Brewery,C=ZZ'
- Generated self-signed reader root cert and private key with curve P384
- Wrote reader root private key to reader_root_private_key.pem
- Wrote reader root cert to reader_root_certificate.pem
```

then create a reader certificate for user Bob Bobson, an employee of
_Utopia Brewery_:

```shell
$ multipazctl generateReaderCert --subject 'CN=Bob Bobson,O=Utopia Brewery,C=ZZ'
- Generated Reader cert and private key with curve P256
- Loaded reader root cert from reader_root_certificate.pem
- Loaded reader root private key from reader_root_private_key.pem
- Wrote reader private key to reader_private_key.pem
- Wrote reader cert to reader_certificate.pem
```

Finally, create the PKCS#12 file like this:

```shell
$ openssl pkcs12 -export -out reader_key_and_certchain.p12 -inkey reader_private_key.pem -in reader_certificate.pem -certfile reader_root_certificate.pem
Enter Export Password:
Verifying - Enter Export Password:
```

twice entering a passphrase to protect the key. The resulting file
can be sent to Bob Bobson (sharing the passphrase out of band) and
they can install it into the Multipaz  Identity Reader.
