import XCTest
import UIKit

// Run once on a fresh owned Simulator, after read-only smoke: this saves a global guest setting.
final class MessageRowLayoutTests: XCTestCase {
    func testDefault20Select28SaveRelaunchAndFoodLayout() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .landscapeLeft
        let app = productApplication()
        defer {
            if let run = testRun, run.failureCount > 0, app.secureTextFields.count == 0 {
                let evidence = XCTAttachment(screenshot: app.screenshot())
                evidence.name = "message-row-layout-failure"
                evidence.lifetime = .keepAlways
                add(evidence)
                let tree = XCTAttachment(string: app.debugDescription)
                tree.name = "message-row-layout-failure-ax"
                tree.lifetime = .keepAlways
                add(tree)
            }
            app.terminate()
            XCUIDevice.shared.orientation = .portrait
        }
        app.launch()
        try assertFreshLibrary(app)
        try openBoard("Еда и питьё", in: app)
        let original = try assertGrid(app, paged: false)
        attachScreenshot(app, name: "food-message-row-default-20")
        try tap(app.buttons["Открыть библиотеку"])
        try openSettings(app)
        let defaultRow = settingRow("20%", in: app)
        try reveal(defaultRow, in: app)
        try tap(defaultRow)
        for percent in [16, 20, 24, 28, 32] {
            _ = try visible(named("\(percent)%", in: app))
        }
        try tap(named("28%", in: app))
        try reveal(settingRow("28%", in: app), in: app)
        attachScreenshot(app, name: "message-row-selected-28-before-save")
        try reveal(app.buttons["Сохранить настройки"], in: app)
        try tap(app.buttons["Сохранить настройки"])
        // LocalAssistantAccess permits CHANGE_SETTINGS for a fresh unprotected guest; never pre-authorize it.
        try waitUntil("Guest save must finish without an authorization dialog") {
            app.buttons["Сохранить настройки"].isEnabled && !self.named("Сохранение настроек", in: app).exists
        }
        try tap(app.buttons["Назад"])
        try openSettings(app)
        try reveal(settingRow("28%", in: app), in: app)
        XCTAssertEqual(app.secureTextFields.count, 0)
        XCTAssertEqual(app.alerts.count, 0, "No microphone, OTP or system authorization requests")
        app.terminate()
        app.launch()
        try openSettings(app)
        try reveal(settingRow("28%", in: app), in: app)
        try tap(app.buttons["Назад"])
        try openBoard("Еда и питьё", in: app)
        let saved = try assertGrid(app, paged: false)
        attachScreenshot(app, name: "food-message-row-saved-28")
        XCTAssertGreaterThan(saved.message.height, original.message.height + 1, "Saved 28% must enlarge the message row")
        XCTAssertEqual(saved.message.minY, original.message.minY, accuracy: 2, "Toolbar must not move")
        XCTAssertEqual(saved.grid.maxY, original.grid.maxY, accuracy: 2, "Only the central grid gives space to the message row")
        try tap(app.buttons["Открыть библиотеку"])
        try openBoard("Общаюсь", in: app)
        _ = try visible(named("Страница 1 из 3", in: app))
        let paged = try assertGrid(app, paged: true)
        XCTAssertLessThanOrEqual(paged.message.height, saved.message.height + 3, "Paged board may give space back to its grid, not exceed the global target")
        try tap(app.buttons["Следующая страница"])
        _ = try visible(named("Страница 2 из 3", in: app))
        _ = try assertGrid(app, paged: true)
        XCTAssertEqual(app.alerts.count, 0)
    }

    private func assertFreshLibrary(_ app: XCUIApplication) throws {
        _ = try visible(named("Ваши наборы", in: app))
        var names = Set<String>()
        for _ in 0..<8 {
            names.formUnion(app.buttons.allElementsBoundByIndex.filter {
                $0.isHittable && $0.label.hasPrefix("Действия с набором «")
            }.map { $0.label })
            app.swipeUp()
        }
        XCTAssertEqual(names, Set(["Буквы", "Еда и питьё", "Занятия", "Общаюсь", "Самочувствие"].map { "Действия с набором «\($0)»" }))
        for _ in 0..<8 { app.swipeDown() }
    }

    private func openSettings(_ app: XCUIApplication) throws {
        _ = try visible(named("Ваши наборы", in: app))
        try tap(app.buttons["Меню библиотеки"])
        try tap(named("Настройки", in: app))
        _ = try visible(app.buttons["Меню настроек"])
    }

    private func openBoard(_ name: String, in app: XCUIApplication) throws {
        let actions = app.buttons["Действия с набором «\(name)»"]
        try reveal(actions, in: app)
        try tap(actions)
        try tap(named("Открыть", in: app))
        _ = try visible(app.buttons["Меню общения"])
    }

    private func assertGrid(_ app: XCUIApplication, paged: Bool) throws -> (message: CGRect, grid: CGRect) {
        let speak = app.buttons["Произнести"]
        var grid = CGRect.zero
        try waitUntil("Nine decoded cards must fit below the message with usable targets") {
            let cards = app.buttons.allElementsBoundByIndex.filter { $0.value as? String == "Изображение показано" }
            guard cards.count == 9, speak.exists, speak.frame.height >= 44 else { return false }
            grid = cards.reduce(CGRect.null) { $0.union($1.frame) }
            return cards.allSatisfy {
                $0.isEnabled && $0.isHittable && $0.frame.width >= 44 && $0.frame.height >= 44 && app.frame.contains($0.frame)
            } && grid.minY >= speak.frame.maxY && abs(grid.midX - app.frame.midX) <= 2
        }
        if paged {
            let previous = app.buttons["Предыдущая страница"]
            let next = try visible(app.buttons["Следующая страница"])
            XCTAssertTrue(previous.exists)
            XCTAssertEqual(previous.frame.height, 48, accuracy: 1)
            XCTAssertEqual(next.frame.height, 48, accuracy: 1)
            XCTAssertEqual(previous.frame.minY, next.frame.minY, accuracy: 1)
            // Default text/navigation put the 48pt footer buttons at the grid area's lower edge.
            let availableTop = speak.frame.maxY + 1
            let availableBottom = min(previous.frame.minY, next.frame.minY)
            XCTAssertGreaterThan(availableBottom, availableTop)
            XCTAssertGreaterThanOrEqual(grid.minY + 1, availableTop)
            XCTAssertLessThanOrEqual(grid.maxY, availableBottom + 1)
            XCTAssertEqual(grid.midY, (availableTop + availableBottom) / 2, accuracy: 1,
                           "Only the grid must be vertically centered between message divider and footer")
            XCTAssertTrue(app.frame.contains(previous.frame))
            XCTAssertTrue(app.frame.contains(next.frame))
        } else {
            XCTAssertFalse(app.buttons["Предыдущая страница"].exists)
            XCTAssertFalse(app.buttons["Следующая страница"].exists)
        }
        return (speak.frame, grid)
    }

    private func attachScreenshot(_ app: XCUIApplication, name: String) {
        guard app.secureTextFields.count == 0 else { return }
        let evidence = XCTAttachment(screenshot: app.screenshot())
        evidence.name = name
        evidence.lifetime = .keepAlways
        add(evidence)
    }

    private func settingRow(_ percent: String, in app: XCUIApplication) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@ AND label CONTAINS %@", "Высота строки сообщения", percent)).element
    }

    private func named(_ label: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", label)).firstMatch
    }

    private func waitUntil(_ message: String, _ condition: @escaping () -> Bool) throws {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in condition() }, object: nil)
        guard XCTWaiter.wait(for: [expectation], timeout: 30) == .completed else {
            let app = productApplication()
            if app.secureTextFields.count == 0 {
                attachScreenshot(app, name: "message-row-timeout")
                let tree = XCTAttachment(string: app.debugDescription)
                tree.name = "message-row-timeout-ax"
                tree.lifetime = .keepAlways
                add(tree)
            }
            XCTFail(message)
            throw NSError(domain: "MessageRowLayoutTests", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
        }
    }

    @discardableResult
    private func visible(_ element: XCUIElement) throws -> XCUIElement {
        try waitUntil("Missing enabled hit target: \(element)") { element.exists && element.isEnabled && element.isHittable }
        return element
    }

    private func tap(_ element: XCUIElement) throws { try visible(element).tap() }

    private func reveal(_ element: XCUIElement, in app: XCUIApplication) throws {
        let content = app.frame.insetBy(dx: 0, dy: max(64, app.frame.height * 0.12))
        let scrollView = app.scrollViews.allElementsBoundByIndex.first {
            $0.isHittable && !$0.frame.intersection(content).isEmpty
        }
        let viewport = scrollView.map { $0.frame.intersection(content) } ?? content
        let origin = app.coordinate(withNormalizedOffset: .zero)
        let upper = origin.withOffset(CGVector(dx: viewport.minX + viewport.width * 0.8 - app.frame.minX,
                                              dy: viewport.minY + viewport.height * 0.2 - app.frame.minY))
        let lower = origin.withOffset(CGVector(dx: viewport.minX + viewport.width * 0.8 - app.frame.minX,
                                              dy: viewport.minY + viewport.height * 0.8 - app.frame.minY))
        let isVisible = {
            element.exists && element.isEnabled && element.isHittable && !element.frame.intersection(viewport).isEmpty
        }
        for attempt in 0..<32 {
            if isVisible() { return }
            let above = element.exists ? element.frame.midY < viewport.midY : attempt >= 16
            (above ? upper : lower).press(forDuration: 0.05, thenDragTo: above ? lower : upper)
        }
        try waitUntil("Missing enabled hit target in content viewport: \(element)", isVisible)
    }
}
