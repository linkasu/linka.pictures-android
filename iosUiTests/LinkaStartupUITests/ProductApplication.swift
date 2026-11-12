import XCTest

// Process-local Apple overrides keep scenario selectors stable without changing system language or user content.
func productApplication(language: String = "ru") -> XCUIApplication {
    precondition(language == "ru" || language == "en")
    let bundleID = ProcessInfo.processInfo.environment["LINKA_UI_PRODUCT_BUNDLE_ID"] ?? "su.linka.pictures.development"
    precondition(["su.linka.pictures.development", "su.linka.pictures"].contains(bundleID), "Unsupported matrix product bundle ID")
    let app = XCUIApplication(bundleIdentifier: bundleID)
    app.launchArguments = ["-AppleLanguages", "(\(language))", "-AppleLocale", language == "ru" ? "ru_RU" : "en_US"]
    return app
}
