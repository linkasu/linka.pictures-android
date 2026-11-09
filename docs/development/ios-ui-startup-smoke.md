# iOS development startup smoke

`iosUiTests/LinkaStartupUITests.xcodeproj` contains one standalone native XCTest UI-testing target and shared scheme, both named `LinkaStartupUITests`. Target Application is None: Xcode generates its standard UI-test runner; the project neither rebuilds nor copies the application or any Kotlin framework.

`DevelopmentLibraryTests.testDevelopmentLibrarySurvivesRelaunch` launches only the already-installed `su.linka.pictures.development`. On each of two launches (with terminate between them), it waits up to 15 seconds per accessibility element for the visible, hittable **and enabled** library title `Ваши наборы`, library menu `Меню библиотеки`, and button-role create control `Создать набор`. No controls are tapped. Each successful stage and the first failed assertion stage attach an actual application screenshot to the result bundle.

This is a startup/relaunch regression for the development app's Keychain credential creation/read path: a startup failure must not satisfy the library assertions. It does not inspect credentials, reset state, assert an empty library, supply fixtures, or change authentication. It is not a standalone proof of Keychain contents or signing correctness.

## Prerequisites and boundaries

- Use an explicitly owned arm64 iOS 17+ Simulator, with the real development app already installed and independently verified. Do not run against production, a physical device, or unrelated simulators.
- The app must use its own development Keychain group. This project declares no Keychain entitlements, personal team, identity, profiles, credential access, or application dependency. Xcode may inject its standard runner-only Simulator group into the generated test binary; verify that any such group is exactly the isolated runner's identifier, never the product or Keychain-test host group. Do not strip platform-generated entitlements.
- `CODE_SIGNING_ALLOWED=NO` is the default. Any standard Xcode Simulator runner signing is a separate, explicitly authorized operation; this project does not provision accounts or fetch profiles. A macOS permission prompt requires human handling.
- `LINKA_UI_RUNNER_BUNDLE_ID` defaults to `su.linka.pictures.uistartup`; the test bundle identifier is that base plus `.tests`. Supply a unique base for an authorized run. No app launch arguments, environment overrides, test hooks, skips, retries, or fixed sleeps are used.

## Read-only validation

From the repository root:

```sh
plutil -lint iosUiTests/LinkaStartupUITests.xcodeproj/project.pbxproj
xcrun swiftc -frontend -parse iosUiTests/LinkaStartupUITests/DevelopmentLibraryTests.swift
xcodebuild -list -project iosUiTests/LinkaStartupUITests.xcodeproj
xcodebuild test -showBuildSettings -project iosUiTests/LinkaStartupUITests.xcodeproj \
  -scheme LinkaStartupUITests -configuration Debug \
  -destination 'platform=iOS Simulator,id=DD0F9A3F-DC32-4373-BA08-400FECE3A1CC' \
  CODE_SIGNING_ALLOWED=NO
```

`test -showBuildSettings` selects the test action for inspection; it does not build or execute the UI test. The scheme intentionally has no runnable application, so plain `-showBuildSettings` without the test action cannot resolve its destination. After reviewing the project, the primary session must separately authorize and perform runner build/signing and execution on that explicit Simulator. Select only `LinkaStartupUITests/DevelopmentLibraryTests/testDevelopmentLibrarySurvivesRelaunch`, disable test repetition/retry, and retain its `.xcresult`. A native UI-test PASS requires both real startup admissions and their screenshots; parsing or build settings are not runtime evidence.

## Actual run, 6 November 2026

Initial visibility/hittability-only version: **1/1 native XCUI PASS,13.512s**.
It did not prove readiness of protected controls. After the actual filled-flow
test exposed the UIKit-wrapper identity issue and that issue was fixed,
the stronger **enabled/button-role version also passed1/1,11.920s**, owned
iOS17.0 arm64, 0 failures/skips.
Both real app launches reached the title and hittable menu/create AX controls;
actual screenshots are attached to the local result bundle. Before execution,
strict runner/test signatures and isolated runner-only generated entitlements
were checked. The installed development executable SHA matched the independently
verified app; this runner did not rebuild, replace or initialise a fake product.

This verifies startup/relaunch, not filled-board parity, import/provider flows,
microphone/voice, physical devices or full G4. Evidence and local `.xcresult` path
are recorded with `docs/progress-screenshots/2026-11-06-ios-development/`.
