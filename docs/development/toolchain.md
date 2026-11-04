# P02 normal bootstrap toolchain

Base `f7589be1dd5a5be26ff3d4991c84734d3f5dac9d`, branch
`implementation/p02-foundation`. User explicitly authorized conditional
P02–P14 work while G1 remains open. This package is **P02 bootstrap only**:
not G1/G2 model acceptance, parity, publication or P14/P16 readiness.

## Pinned stack

| Component | Version / contract |
| --- | --- |
| Kotlin Android / KMP / Compose compiler / serialization plugin | 2.2.21 |
| Compose Multiplatform | 1.9.3, existing Material2 (not Material3) |
| Resolved Google Compose Android | 1.9.4 |
| Android Gradle Plugin, application + library | 8.10.1 |
| Repository Gradle wrapper | 8.11.1 |
| JDK | 17; verified Homebrew17.0.19 arm64 |
| Android compileSdk / minSdk | 35 / 21 |
| Android app targetSdk / version | unchanged35 / 1.4, code5 |
| iOS app deployment | exact17.0 |
| Xcode | existing stable27.2, build27B52 |
| Xcode app SDK | installed27.2, simulator and device |
| SQLite bundled | androidx.sqlite:sqlite-bundled:2.5.2 |
| Coroutines core | kotlinx-coroutines-core:1.10.2 |
| Serialization JSON | kotlinx-serialization-json:1.9.0 |
| Okio | com.squareup.okio:okio:3.10.2 |

Common tests use `kotlin.test` and actual `kotlinx-coroutines-test:1.10.2`
`runTest`/`async` execution. No fake filesystem dependency is added: tests use
the real common `FileSystem.SYSTEM` and their own fresh temporary directory.
No Unicode/codec/crypto dependency or architecture choice was added.

Gradle distribution checksum is pinned in
`gradle/wrapper/gradle-wrapper.properties`:

```text
f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6
```

Only the generated wrapper JAR/scripts were reused, byte-for-byte from source
commit `cd78fba`; no probe source, app, fixture, flags, framework or metadata was
copied. SHA-256:

- `gradle/wrapper/gradle-wrapper.jar`: `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`.
- `gradlew`: `a3648413b47ef77af21d5ebc36c687c7d103aaef3e17f33de7d4f080a6f300a3`.
- `gradlew.bat`: `2209f919a22528af59a2af2ad97e8d056cca18e39f7d87aa3fd549a73b180150`.

The checksum is the configured approved distribution pin, not a claim that a
fresh ZIP download was needed or independently rehashed. `./gradlew --version`
actually reports Gradle8.11.1/JVM17.0.19. Its embedded DSL Kotlin2.0.20 is not
the project's Kotlin2.2.21 compiler/plugin version.

## Ordinary modules and entry points — no probe flag

`settings.gradle` still contains only **`:app` and `:shared`**. No `-P` switch,
alternate build file or probe source-set substitution is used.

- `:app`: unchanged legacy Android sources, package/namespace/applicationId
  **`su.linka.pictures`**, version1.4/code5, manifest/assets/preferences and
  existing signing/privacy configuration. Legacy dependencies remain until
  P14. No release keystore/secret is copied or used by this package.
  `assembleDebug` uses the existing standard debug signing, not `main.jks`.
- `:shared`: normal common dependencies above, original Android legacy sources
  and dependencies preserved. Firebase BOM in the KMP dependency handler is
  qualified as `project.dependencies.platform(...)` for KGP2.2 compatibility.
- `iosArm64` and `iosSimulatorArm64` are **always configured**, with real
  **static `Shared.framework`** outputs. Normal `iosMain` implements the
  existing `platformName()` expect; no ProbeViewController or TestPath actor.
- JVM target exists for shared tests, not as a Desktop product. Normal
  `jvmMain` provides only the matching platform actual. No publishing plugin
  or Desktop entry point is introduced.
- `normaliosApp/LinkaPokazhi.xcodeproj`, shared scheme **`LinkaPokazhi`**:
  Debug-only build shell, arm64 simulator/device, iPhone+iPad via
  `TARGETED_DEVICE_FAMILY=1,2`. It links the matching already-built normal
  Shared framework by SDK-specific search path; build Shared first as mapped
  in [verification.md](verification.md). No hidden Gradle/build/signing step.
- The Swift shell calls the real exported
  **`Platform_iosKt.platformName()`** from Shared and displays an explicit
  **P02 bootstrap** label, not a fake empty library or AudioReady state.

The provisional iOS bundle is **`su.linka.pictures.development`**, not a
production/store ID. Code signing is disabled, no team/profile/entitlements or
permission usage strings are assigned. No marketing/build release versions
are guessed for this shell; the plist deliberately has no release-version
keys. Both unsigned generic builds validate successfully, but install/store
readiness and release versioning are not claimed.

The iOS app plist and executable both target17.0. Lower Shared library minima
(simulator14.0/device12.0) are not a reason to apply native overrides. The
simulator link retains the known CMP/Skiko ICU data-object17.2 warning: both
members have SHA `a05864b10cb096e35bc318de76cecdb748b97e19485f1821abf12302106a2780`,
matching the previously reviewed data-only objects. No object metadata,
version check or warning is suppressed. Broader G1 evidence remains separate.

No source/library/migration/access runtime bindings, domain contracts, model
placeholders or production filesystem wrappers are supplied here. COORD owns
those contracts and later integration; normal apps are **not installed or run**
by this package. See the exact executed scope and artifact hashes below in
[verification.md](verification.md).
