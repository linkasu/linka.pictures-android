# Isolated iOS Keychain XCTest host

`iosAppHost/LinkaKeychainHost.xcodeproj` is test infrastructure, not the product app.
It supports only arm64 iOS Simulator, minimum iOS 17.0. The UIKit application displays
`LINKa isolated Keychain tests` and does not load Kotlin or initialise product services,
audio, accounts, or permission requests. Only the hosted XCTest bundle links the static
`SharedAppHostTests.framework`, avoiding a second Kotlin runtime in the host process.

## Kotlin seam

The parent Gradle configuration must produce the framework from the existing
`iosSimulatorArm64` `appHostTest` compilation, associated with `main`:

```sh
./gradlew :shared:verifyAppHostTestFramework
```

Expected artifact:
`shared/build/bin/iosSimulatorArm64/appHostDebugFramework/SharedAppHostTests.framework`.
Before compiling Swift, inspect its generated header to confirm the exported
`KeychainAppHostChecks.shared` methods:

* `bytesReopenUpdateAndRevocation(): String?`
* `boundsAndServiceValidation(): String?`

Each calls the corresponding **existing** `KeychainCredentialStoreTest` method.
Those synchronous `runTest` methods complete before the bridge returns `null`.
Thrown failures become bounded, redacted type/message diagnostics and fail their
individual XCTest via `XCTAssertNil`; there are no skips or expected failures.
Services are isolated as `su.linka.pictures.test.access.<UUID>` with account
`credential`; the existing tests delete only their own records in `finally`.

## Review-only checks

These checks neither sign nor run an app:

```sh
plutil -lint iosAppHost/LinkaKeychainHost.xcodeproj/project.pbxproj
plutil -lint iosAppHost/LinkaKeychainHost/LinkaKeychainHost.entitlements
xcodebuild -list -project iosAppHost/LinkaKeychainHost.xcodeproj CODE_SIGNING_ALLOWED=NO
```

After the framework is available, an unsigned compile check can use the
`build-for-testing` command below with `CODE_SIGNING_ALLOWED=NO` **instead of**
the signing, team and identity arguments. An unsigned build is not runtime Keychain proof.

## Authorised signing and execution (primary session only)

The user explicitly approved **Xcode-managed Simulator signing** for this isolated
app-hosted XCTest after a forced Development signature was rejected by AMFI −413
(no matching profile). This is not permission to retry the old CLI/ad-hoc wrapper,
strip entitlements, bypass AMFI/SIP/ACL, or fetch provisioning profiles.
Supply the approved team only at the command line; let Xcode choose its Simulator
signature. No personal team, identity, profile or account belongs in this project.
The host entitlements contain its own team/application identifier, exactly one
matching Keychain group and Debug `get-task-allow`. The test bundle declares no
Keychain entitlements. Verify the actual host Mach-O `__entitlements` section as
well as strict signatures: Simulator entitlements are not in the ordinary
codesign entitlement blob. An empty blob is not evidence of missing Simulator
entitlements, and is not a reason to re-sign or remove the Mach-O payload.

Set `SIMULATOR_UDID` to the explicitly authorised simulator, not a generic or
all-devices destination. `LINKA_HOST_BUNDLE_ID` defaults to
`su.linka.pictures.keychainhost`; override it with a unique own test identifier
when needed. The test bundle automatically adds `.tests` to that base. Do not
override `PRODUCT_BUNDLE_IDENTIFIER` globally: that would give both targets the
same identifier. Use a fresh writable `DERIVED_DATA` directory and a new
`RESULT_BUNDLE` path for each recorded run.

```sh
xcodebuild build-for-testing \
  -project iosAppHost/LinkaKeychainHost.xcodeproj \
  -scheme LinkaKeychainHost -configuration Debug -sdk iphonesimulator \
  -destination "platform=iOS Simulator,id=$SIMULATOR_UDID" \
  -derivedDataPath "$DERIVED_DATA" \
  CODE_SIGNING_ALLOWED=YES CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM="$APPROVED_TEAM" \
  LINKA_HOST_BUNDLE_ID="$OWN_TEST_BUNDLE_ID"

xcodebuild test-without-building \
  -project iosAppHost/LinkaKeychainHost.xcodeproj \
  -scheme LinkaKeychainHost -configuration Debug -sdk iphonesimulator \
  -destination "platform=iOS Simulator,id=$SIMULATOR_UDID" \
  -derivedDataPath "$DERIVED_DATA" -resultBundlePath "$RESULT_BUNDLE" \
  -parallel-testing-enabled NO \
  -only-testing:LinkaKeychainHostTests/KeychainTests \
  CODE_SIGNING_ALLOWED=YES CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM="$APPROVED_TEAM" \
  LINKA_HOST_BUNDLE_ID="$OWN_TEST_BUNDLE_ID"
```

Acceptance requires **both actual hosted XCTests passing** in the result bundle:
`testBytesReopenUpdateAndRevocation` and `testBoundsAndServiceValidation`.
Static checks, framework compilation and unsigned builds alone do not establish
Keychain G4 PASS. Signing, installation, execution, device state and certificate
management are outside the implementation lane.

## Actual result — 6 November 2026

**2 actual hosted XCTests PASS on owned iOS17.0 arm64**, 0 failures/skips/expected
failures, confirmed by `xcresulttool get test-results summary` and individual
test log entries. Bounds/service test:0.028s; bytes/reopen/update/revocation:0.031s.
Before installation, the exact own-group Simulator payload and both strict
signatures were verified. Installed host/test executable SHA256 values matched
those verified products after execution. Existing Keychain test UUID services
were the only credential records written/deleted. No product root was started.

Evidence: `docs/development/evidence/ios-keychain-xctest/2026-11-06/`.
The full `.xcresult` remains in the local OpenCode temporary directory recorded in
the evidence manifest, not in Git. This closes the Simulator Keychain app-host
compartment only: product startup/group binding, native owner verification,
physical devices, migration and full P08/G4/release acceptance remain separate.
