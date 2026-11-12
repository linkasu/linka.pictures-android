import XCTest
import UIKit

// The runner prepares a synthetic guest library; these tests never reset data or authorize network services.
final class MatrixSmokeTests: XCTestCase {
    private let starterNames = ["Буквы", "Еда и питьё", "Занятия", "Общаюсь", "Самочувствие"]
    private let firstPageCards: Set<String> = ["Да", "Нет", "Хочу", "Не хочу", "Помоги", "Больно", "Пить", "Туалет", "Хватит"]

    override func setUpWithError() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .landscapeLeft
    }

    override func tearDownWithError() throws {
        let app = productApplication()
        if let run = testRun, run.failureCount > 0,
           app.state == .runningForeground, app.secureTextFields.count == 0 {
            let screenshot = XCTAttachment(screenshot: app.screenshot())
            screenshot.name = "matrix-failure"
            screenshot.lifetime = .keepAlways
            add(screenshot)
            let tree = XCTAttachment(string: app.debugDescription)
            tree.name = "matrix-failure-ax"
            tree.lifetime = .keepAlways
            add(tree)
        }
        app.terminate()
        XCUIDevice.shared.orientation = .portrait
    }

    func testColdLaunchAndRelaunch() throws {
        let app = productApplication()
        app.launch()
        try assertLibraryReady(app)
        app.terminate()
        app.launch()
        try assertLibraryReady(app)
    }

    func testEnglishAndRussianRelaunch() throws {
        for (language, heading, menu) in [("en", "Your boards", "Library menu"), ("ru", "Ваши наборы", "Меню библиотеки")] {
            let app = productApplication(language: language)
            app.launch()
            _ = try visible(named(heading, in: app))
            _ = try visible(app.buttons[menu])
            XCTAssertEqual(app.state, .runningForeground)
            app.terminate()
        }
    }

    func testOriginalCommunicationFirstPageDecodedAndPaging() throws {
        let app = productApplication()
        app.launch()
        try openBoard("Общаюсь", in: app)
        try assertFirstPage(app)
        try tap(app.buttons["Следующая страница"])
        _ = try visible(named("Страница 2 из 3", in: app))
        try tap(app.buttons["Предыдущая страница"])
        try assertFirstPage(app)
    }

    func testSelectionBackgroundPersistenceRelaunchAndClear() throws {
        let app = productApplication()
        app.launch()
        try openBoard("Общаюсь", in: app)
        XCTAssertEqual(app.buttons["Меню общения"].value as? String, "Составление сообщения")
        try emptyMessage(app)
        try tap(app.buttons["Да"])
        _ = try visible(named("Общее сообщение: Да", in: app))
        XCUIDevice.shared.press(.home)
        try waitUntil("Application must enter background") {
            app.state == .runningBackground || app.state == .runningBackgroundSuspended
        }
        app.activate()
        _ = try visible(named("Общее сообщение: Да", in: app))
        app.terminate()
        app.launch()
        try openBoard("Общаюсь", in: app)
        _ = try visible(named("Общее сообщение: Да", in: app))
        try tap(app.buttons["Очистить сообщение"])
        _ = try visible(named("Общее сообщение: пусто", in: app))
        app.terminate()
        app.launch()
        try openBoard("Общаюсь", in: app)
        _ = try visible(named("Общее сообщение: пусто", in: app))
        XCTAssertFalse(app.buttons["Остановить"].exists)
    }

    func testOrientationCardBoundsAndHitTargets() throws {
        let app = productApplication()
        app.launch()
        try openBoard("Общаюсь", in: app)
        let orientations: [UIDeviceOrientation] = UIDevice.current.userInterfaceIdiom == .pad
            ? [.landscapeLeft, .portrait, .landscapeRight, .portraitUpsideDown]
            : [.landscapeLeft, .landscapeRight]
        for orientation in orientations {
            XCUIDevice.shared.orientation = orientation
            let landscape = orientation == .landscapeLeft || orientation == .landscapeRight
            try waitUntil("Window must follow orientation \(orientation.rawValue)") {
                landscape ? app.frame.width > app.frame.height : app.frame.height > app.frame.width
            }
            try assertFirstPage(app)
        }
    }

    func testLargeTextLibraryAndCommunicationHitTargets() throws {
        let app = productApplication()
        app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        try assertStarterLibrary(app)
        try openBoard("Общаюсь", in: app)
        try assertFirstPage(app)
    }

    func testSettingsOpenAndBackWithoutWrites() throws {
        let app = productApplication()
        app.launch()
        _ = try visible(named("Ваши наборы", in: app))
        try tap(app.buttons["Меню библиотеки"])
        try tap(named("Настройки", in: app))
        _ = try visible(app.buttons["Меню настроек"])
        XCTAssertEqual(app.secureTextFields.count, 0)
        XCTAssertEqual(app.alerts.count, 0, "Settings must not request microphone or other permissions")
        try tap(app.buttons["Назад"])
        try assertLibraryReady(app)
    }

    // Runner must exclude this from shared smoke and invoke it separately last; lexical ordering is not isolation.
    func testZZEditorCreatePublishRelaunchAndCancelCardDraft() throws {
        let app = productApplication()
        let name = "MatrixBoard-" + UUID().uuidString
        let label = "MatrixCard"
        app.launch()
        _ = try visible(named("Ваши наборы", in: app))
        try tap(app.buttons["Создать набор"])
        try tap(app.buttons["Меню редактора"])
        try tap(named("Параметры доски", in: app))
        let title = app.textViews["Новая доска"]
        try reveal(title, in: app)
        try tap(title)
        title.typeKey(XCUIKeyboardKey.downArrow.rawValue, modifierFlags: .command)
        title.typeKey(XCUIKeyboardKey.upArrow.rawValue, modifierFlags: [.command, .shift])
        app.typeText(name)
        _ = try visible(app.textViews[name])
        try dismissKeyboard(app)
        try tap(app.buttons["Готово"])
        try tap(app.buttons["Создать карточку. Строка 1, столбец 1"])
        _ = try visible(named("Тип карточки", in: app))
        XCTAssertEqual(app.textViews.count, 1)
        try tap(app.textViews.firstMatch)
        app.typeText(label)
        _ = try visible(app.textViews[label])
        try dismissKeyboard(app)
        let tts = app.buttons["Локальный TTS ▾"]
        try reveal(tts, in: app)
        try tap(tts)
        try tap(named("Без звука", in: app))
        try reveal(app.buttons["Готово"], in: app)
        try tap(app.buttons["Готово"])
        _ = try visible(app.buttons["\(label). Строка 1, столбец 1"])
        try tap(app.buttons["Сохранить и выйти"])
        _ = try visible(named("Ваши наборы", in: app))
        app.terminate()
        app.launch()
        try openBoard(name, in: app)
        _ = try visible(app.buttons[label])
        try tap(app.buttons["Открыть библиотеку"])
        let actions = app.buttons["Действия с набором «\(name)»"]
        try reveal(actions, in: app)
        try tap(actions)
        try tap(named("Редактировать", in: app))
        try tap(app.buttons["\(label). Строка 1, столбец 1"])
        let cardText = app.textViews[label]
        try tap(cardText)
        cardText.typeKey(XCUIKeyboardKey.downArrow.rawValue, modifierFlags: .command)
        app.typeText("-cancelled")
        _ = try visible(app.textViews[label + "-cancelled"])
        try dismissKeyboard(app)
        try reveal(app.buttons["Отмена"], in: app)
        try tap(app.buttons["Отмена"])
        _ = try visible(app.buttons["\(label). Строка 1, столбец 1"])
        XCTAssertFalse(app.buttons["\(label)-cancelled. Строка 1, столбец 1"].exists)
        try tap(app.buttons["Назад с сохранённым черновиком"])
        try openBoard(name, in: app)
        _ = try visible(app.buttons[label])
        XCTAssertFalse(app.buttons[label + "-cancelled"].exists)
    }

    private func assertLibraryReady(_ app: XCUIApplication) throws {
        _ = try visible(named("Ваши наборы", in: app))
        _ = try visible(app.buttons["Меню библиотеки"])
        _ = try visible(app.buttons["Создать набор"])
        try waitUntil("Library must expose a real enabled board, not only a live process") {
            app.buttons.allElementsBoundByIndex.contains {
                self.starterNames.contains($0.label) && $0.isHittable && $0.isEnabled
            }
        }
        XCTAssertEqual(app.state, .runningForeground)
    }

    private func assertStarterLibrary(_ app: XCUIApplication) throws {
        try assertLibraryReady(app)
        // Lazy grids expose only materialized tiles: collect across scroll positions, not just the initial viewport.
        var seen = Set<String>()
        var previousViewport = Set<String>()
        var scrolls = 0
        for _ in 0..<8 {
            let viewport = Set(app.buttons.allElementsBoundByIndex.filter {
                $0.label.hasPrefix("Действия с набором «") && $0.isHittable
            }.map { $0.label })
            seen.formUnion(viewport)
            if scrolls > 0 && viewport == previousViewport { break }
            previousViewport = viewport
            app.swipeUp()
            scrolls += 1
        }
        XCTAssertEqual(seen, Set(starterNames.map { "Действия с набором «\($0)»" }), "Prepared guest library must contain exactly five starter boards")
        for _ in 0..<scrolls { app.swipeDown() }
        XCTAssertEqual(app.state, .runningForeground)
    }

    private func openBoard(_ name: String, in app: XCUIApplication) throws {
        _ = try visible(named("Ваши наборы", in: app))
        let actions = app.buttons["Действия с набором «\(name)»"]
        try reveal(actions, in: app)
        try tap(actions)
        try tap(named("Открыть", in: app))
        _ = try visible(app.buttons["Меню общения"])
    }

    private func assertFirstPage(_ app: XCUIApplication) throws {
        let page = named("Страница 1 из 3", in: app)
        _ = try visible(page)
        try waitUntil("Nine decoded, enabled, fully visible first-page cards with usable hit targets required") {
            let speak = app.buttons["Произнести"]
            let previous = app.buttons["Предыдущая страница"]
            let next = app.buttons["Следующая страница"]
            guard speak.exists, previous.exists, next.exists else { return false }
            let bottom = min(page.frame.minY, previous.frame.minY, next.frame.minY)
            guard bottom > speak.frame.maxY else { return false }
            let viewport = CGRect(x: app.frame.minX, y: speak.frame.maxY, width: app.frame.width, height: bottom - speak.frame.maxY)
            let cards = app.buttons.allElementsBoundByIndex.filter { !$0.frame.isEmpty && viewport.contains($0.frame) }
            return cards.count == 9 && Set(cards.map { $0.label }) == self.firstPageCards && cards.allSatisfy {
                $0.isHittable && $0.isEnabled && $0.frame.width >= 44 && $0.frame.height >= 44 && $0.value as? String == "Изображение показано"
            }
        }
    }

    private func emptyMessage(_ app: XCUIApplication) throws {
        if !named("Общее сообщение: пусто", in: app).exists {
            try tap(app.buttons["Очистить сообщение"])
        }
        _ = try visible(named("Общее сообщение: пусто", in: app))
    }

    private func named(_ label: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", label)).firstMatch
    }

    private func waitUntil(_ message: String, _ condition: @escaping () -> Bool) throws {
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in condition() }, object: nil)
        guard XCTWaiter.wait(for: [ready], timeout: 30) == .completed else {
            XCTFail(message)
            throw NSError(domain: "MatrixSmokeTests", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
        }
    }

    @discardableResult
    private func visible(_ element: XCUIElement) throws -> XCUIElement {
        try waitUntil("Missing enabled hit target: \(element)") { element.exists && element.isEnabled && element.isHittable }
        return element
    }

    private func tap(_ element: XCUIElement) throws {
        try visible(element).tap()
    }

    private func reveal(_ element: XCUIElement, in app: XCUIApplication) throws {
        for _ in 0..<8 {
            if element.exists && element.isHittable { return }
            app.swipeUp()
        }
        for _ in 0..<8 {
            if element.exists && element.isHittable { return }
            app.swipeDown()
        }
        _ = try visible(element)
    }

    private func dismissKeyboard(_ app: XCUIApplication) throws {
        if app.keyboards.firstMatch.exists {
            let done = app.keyboards.descendants(matching: .any).matching(NSPredicate(format: "label IN %@", ["Скрыть клавиатуру", "Готово", "Done"])).firstMatch
            try tap(done)
            try waitUntil("Native keyboard must disappear after dismissal") { !app.keyboards.firstMatch.exists }
        }
    }
}
