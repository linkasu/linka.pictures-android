import XCTest

final class FilledBoardImportTests: XCTestCase {
    private let cards = [
        "КУШАТЬ", "ПИТЬ", "СПАТЬ", "КУПАТЬСЯ", "ХОЧУ",
        "ПИСАТЬ КАКАТЬ", "ГУЛЯТЬ", "ИГРАТЬ", "ОБНИМАТЬСЯ",
    ]
    private var stage = "00-launch"
    private struct Unavailable: Error {}

    func testImportOwnedFixtureThroughFilesAndCompose() throws {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "su.linka.pictures.development")
        app.launch()
        do {
            _ = try visible(named("Ваши наборы", in: app))
            screenshot(app)
            stage = "01-empty-library"
            guard wait(named("В библиотеке пока нет досок. Создайте доску или импортируйте файл .linka.", in: app), timeout: 15) else {
                throw Unavailable() // Existing or unreadable library requires a separate reopen-only run.
            }
            screenshot(app)
            try tap(named("Меню библиотеки", in: app))
            try tap(named("К сообщению", in: app))
            stage = "02-empty-message"
            _ = try visible(named("Общее сообщение: пусто", in: app))
            screenshot(app)
            try tap(named("Открыть библиотеку", in: app))
            try tap(named("Меню библиотеки", in: app))
            try tap(named("Импорт, резервная копия и восстановление", in: app))

            stage = "03-import-name"
            _ = try visible(named("Файлы и восстановление", in: app))
            let fieldQuery = app.descendants(matching: .any).matching(NSPredicate(
                format: "(elementType == %d OR elementType == %d) AND (label == %@ OR identifier == %@)",
                XCUIElement.ElementType.textField.rawValue, XCUIElement.ElementType.textView.rawValue,
                "Название импортируемой доски", "Название импортируемой доски"
            ))
            let field = try visible(fieldQuery)
            guard field.value as? String == "Импортированная доска" else { throw Unavailable() }
            field.tap()
            field.press(forDuration: 1)
            try tap(named(["Выбрать всё", "Выделить всё", "Select All", "Select all"], in: app))
            field.typeText("ХОЧУ")
            let entered = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                field.value as? String == "ХОЧУ"
            }, object: nil)
            guard XCTWaiter.wait(for: [entered], timeout: 5) == .completed else { throw Unavailable() }
            screenshot(app)
            try tapTransfer("Выбрать .linka для импорта (без записи в библиотеку)", in: app)

            stage = "04-files-browse"
            try tap(named(["Обзор", "Browse"], in: app))
            // A picker initially showing Recents may need its Browse tab and then the Browse back button.
            if !wait(named(["На моём iPhone", "On My iPhone"], in: app), timeout: 3) {
                try tap(named(["Обзор", "Browse"], in: app))
            }
            screenshot(app)
            try tap(named(["На моём iPhone", "On My iPhone"], in: app))
            stage = "05-files-own-host"
            try tap(named("LinkaKeychainHost", in: app))
            screenshot(app)
            stage = "06-files-fixture-folder"
            try tap(named("u14-fixtures", in: app))
            screenshot(app)
            stage = "07-files-fixture"
            // Files may hide the extension; both names identify only the staged public archive here.
            _ = try visible(named(["ХОЧУ.linka", "ХОЧУ"], in: app))
            screenshot(app)
            try tap(named(["ХОЧУ.linka", "ХОЧУ"], in: app))

            stage = "08-real-preview"
            let preview = app.staticTexts.matching(NSPredicate(
                format: "label BEGINSWITH %@",
                "Страниц: 1; карточек: 9; изображений: 9; записей: 9;"
            ))
            try revealTransfer(preview, in: app, timeout: 30)
            _ = try visible(named("Доска: «ХОЧУ»", in: app))
            screenshot(app)
            stage = "09-explicit-unlock"
            try tapTransfer("Разрешить команды в этом экране", in: app, upwards: false)
            screenshot(app)
            stage = "10-explicit-import"
            screenshot(app)
            try tapTransfer("Подтверждаю импорт «ХОЧУ» как новой доски", in: app)
            stage = "11-published"
            try revealTransfer(named("Операция завершена.", in: app), in: app, timeout: 30)
            screenshot(app)
            try tap(named("Назад", in: app))

            stage = "12-board-empty"
            try openOwnedBoard(in: app)
            try assertCompose(in: app)
            try assertCards(in: app)
            _ = try visible(named("Общее сообщение: пусто", in: app))
            screenshot(app)
            try tap(app.buttons.matching(identifier: "ХОЧУ"))
            try tap(app.buttons.matching(identifier: "КУШАТЬ"))
            stage = "13-filled-message"
            _ = try visible(named("Общее сообщение: ХОЧУ КУШАТЬ", in: app))
            try assertCards(in: app)
            screenshot(app)
            try tap(named("Открыть библиотеку", in: app))
            stage = "14-library-return"
            _ = try visible(named("Ваши наборы", in: app))
            screenshot(app)
            try openOwnedBoard(in: app)
            stage = "15-reopened-filled-message"
            try assertCompose(in: app)
            try assertCards(in: app)
            _ = try visible(named("Общее сообщение: ХОЧУ КУШАТЬ", in: app))
            screenshot(app)
        } catch {
            screenshot(app)
            let hint = stage == "01-empty-library"
                ? "reopen-only required; existing/unknown library must not be reset or imported again"
                : "required visible UI missing; verify fixture staging/AX assumptions; do not retry import"
            XCTFail("\(stage): \(hint)")
        }
    }

    private func openOwnedBoard(in app: XCUIApplication) throws {
        _ = try visible(named("Ваши наборы", in: app))
        // Use the unique tile's actual menu, not the toolbar title or a search field named ХОЧУ.
        let menu = app.buttons.matching(identifier: "Действия с набором «ХОЧУ»")
        _ = try visible(menu)
        guard menu.count == 1 else { throw Unavailable() }
        try tap(menu)
        try tap(named("Открыть", in: app))
    }

    private func assertCompose(in app: XCUIApplication) throws {
        try tap(named("Меню общения", in: app))
        _ = try visible(named("Перейти в прямой режим", in: app))
        // Dismiss by tapping the visible toolbar outside the menu; never change the mode.
        try tap(named("Меню общения", in: app))
        let dismissed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            !self.named("Перейти в прямой режим", in: app).allElementsBoundByIndex.contains { $0.isHittable }
        }, object: nil)
        guard XCTWaiter.wait(for: [dismissed], timeout: 5) == .completed else { throw Unavailable() }
    }

    private func assertCards(in app: XCUIApplication) throws {
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.cards.allSatisfy { label in
                let matches = app.buttons.matching(identifier: label)
                return matches.count == 1 && matches.element.exists && matches.element.isHittable
            }
        }, object: nil)
        guard XCTWaiter.wait(for: [ready], timeout: 15) == .completed else { throw Unavailable() }
    }

    private func named(_ label: String, in app: XCUIApplication) -> XCUIElementQuery {
        named([label], in: app)
    }

    private func named(_ labels: [String], in app: XCUIApplication) -> XCUIElementQuery {
        app.descendants(matching: .any).matching(NSPredicate(format: "label IN %@ OR identifier IN %@", labels, labels))
    }

    private func wait(_ query: XCUIElementQuery, timeout: TimeInterval) -> Bool {
        let visible = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            query.allElementsBoundByIndex.contains { $0.exists && $0.isHittable }
        }, object: nil)
        return XCTWaiter.wait(for: [visible], timeout: timeout) == .completed
    }

    private func visible(_ query: XCUIElementQuery, timeout: TimeInterval = 15) throws -> XCUIElement {
        guard wait(query, timeout: timeout),
              let element = query.allElementsBoundByIndex.first(where: { $0.exists && $0.isHittable })
        else { throw Unavailable() }
        return element
    }

    private func tap(_ query: XCUIElementQuery) throws {
        let enabled = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            query.allElementsBoundByIndex.contains { $0.exists && $0.isHittable && $0.isEnabled }
        }, object: nil)
        guard XCTWaiter.wait(for: [enabled], timeout: 15) == .completed,
              let element = query.allElementsBoundByIndex.first(where: { $0.exists && $0.isHittable && $0.isEnabled })
        else { throw Unavailable() }
        element.tap()
    }

    private func tapTransfer(_ label: String, in app: XCUIApplication, upwards: Bool = true) throws {
        let query = app.buttons.matching(identifier: label)
        try revealTransfer(query, in: app, upwards: upwards)
        try tap(query)
    }

    private func revealTransfer(_ query: XCUIElementQuery, in app: XCUIApplication,
                                upwards: Bool = true, timeout: TimeInterval = 5) throws {
        if wait(query, timeout: timeout) { return }
        for _ in 0..<6 {
            let scroll = app.scrollViews.allElementsBoundByIndex.first { $0.exists && $0.isHittable }
            guard let scroll = scroll else { throw Unavailable() }
            if upwards { scroll.swipeUp() } else { scroll.swipeDown() }
            if wait(query, timeout: 2) { return }
        }
        throw Unavailable()
    }

    private func screenshot(_ app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = stage
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
