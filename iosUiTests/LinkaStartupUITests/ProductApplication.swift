import XCTest

// Process-local Apple overrides keep scenario selectors stable without changing system language or user content.
func productApplication(language: String = "ru") -> XCUIApplication {
    precondition(language == "ru" || language == "en")
    let app = XCUIApplication(bundleIdentifier: "su.linka.pictures.development")
    app.launchArguments = ["-AppleLanguages", "(\(language))", "-AppleLocale", language == "ru" ? "ru_RU" : "en_US"]
    return app
}
