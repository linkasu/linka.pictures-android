import XCTest

final class DevelopmentLibraryTests: XCTestCase {
    func testDevelopmentLibrarySurvivesRelaunch() {
        let app = XCUIApplication(bundleIdentifier: "su.linka.pictures.development")
        app.launch()
        guard assertLibrary(in: app, stage: "Initial launch") else { return }

        app.terminate()
        app.launch()
        _ = assertLibrary(in: app, stage: "Relaunch")
    }

    private func assertLibrary(in app: XCUIApplication, stage: String) -> Bool {
        let elements: [(String, XCUIElementQuery)] = [
            ("Ваши наборы", app.staticTexts.matching(identifier: "Ваши наборы")),
            ("Меню библиотеки", app.descendants(matching: .any).matching(
                NSPredicate(format: "identifier == %@ OR label == %@", "Меню библиотеки", "Меню библиотеки")
            )),
            ("Создать набор", app.descendants(matching: .any).matching(
                NSPredicate(format: "identifier == %@ OR label == %@", "Создать набор", "Создать набор")
            )),
        ]

        for (label, query) in elements {
            let visible = XCTNSPredicateExpectation(
                predicate: NSPredicate { _, _ in
                    query.allElementsBoundByIndex.contains { $0.exists && $0.isHittable }
                },
                object: nil
            )
            let result = XCTWaiter.wait(for: [visible], timeout: 15)
            if result != .completed {
                attachScreenshot(of: app, stage: stage)
                XCTFail("\(stage): library element did not become visible and hittable: \(label)")
                return false
            }
        }

        attachScreenshot(of: app, stage: stage)
        return true
    }

    private func attachScreenshot(of app: XCUIApplication, stage: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = stage
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
