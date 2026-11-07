import XCTest
import UIKit

final class SmotriOriginalSetsTests: XCTestCase {
    private struct Fixture {
        let file: String
        let name: String
        let slug: String
        let pages: Int
        let rows: Int
        let columns: Int
        let direct: Bool
    }
    private let fixtures = [
        Fixture(file: "Фраза.linka", name: "SMOTRI Фраза", slug: "phrase", pages: 2, rows: 3, columns: 3, direct: false),
        Fixture(file: "Азбука.linka", name: "SMOTRI Азбука", slug: "alphabet", pages: 5, rows: 2, columns: 2, direct: true),
        // Authorized test copy: two MP3s repaired and two trailing JPEG CRLFs removed; config/codestreams unchanged.
        Fixture(file: "sounds2-validated-v2.linka", name: "SMOTRI звуки2 (копия)", slug: "sounds2", pages: 10, rows: 2, columns: 3, direct: true),
        Fixture(file: "3x3.linka", name: "SMOTRI 3x3", slug: "keyboard3x3", pages: 5, rows: 3, columns: 3, direct: false),
        Fixture(file: "Клавиатура.linka", name: "SMOTRI Клавиатура", slug: "keyboard", pages: 2, rows: 4, columns: 12, direct: false),
    ]
    private let localNames = ["iPhone", "На iPhone", "На моём iPhone", "On My iPhone",
                              "iPad", "На iPad", "На моём iPad", "On My iPad"]
    private struct Unavailable: Error {}
    private var stage = "launch"
    private var capturePrefix = "fixed-"
    private var applicationFrame = CGRect.zero

    override func setUpWithError() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .landscapeLeft
    }

    func testLandscapePolicyAcrossLibrarySettingsAndBoard() throws {
        let app = XCUIApplication(bundleIdentifier: "su.linka.pictures.development")
        app.launch()
        try requireLandscape(app)
        _ = try visible(named("Ваши наборы", app))
        for orientation in [UIDeviceOrientation.portrait, .landscapeRight, .portraitUpsideDown, .landscapeLeft] {
            XCUIDevice.shared.orientation = orientation
            try requireLandscape(app)
        }
        try tap(app.buttons.matching(identifier: "Меню библиотеки"))
        try tap(named("Настройки", app))
        try requireLandscape(app)
        try tap(named("Назад", app))
        guard try findBoard(fixtures[0].name, app) else { throw Unavailable() }
        try tap(app.buttons.matching(identifier: "Действия с набором «\(fixtures[0].name)»"))
        try tap(named("Открыть", app))
        _ = try visible(app.buttons.matching(identifier: "Меню общения"))
        let before = message(app)
        for orientation in [UIDeviceOrientation.landscapeRight, .portrait, .landscapeLeft] {
            XCUIDevice.shared.orientation = orientation
            try requireLandscape(app)
            XCTAssertEqual(message(app), before)
            XCTAssertFalse(app.buttons.matching(identifier: "Остановить").firstMatch.exists)
        }
        stage = "smotri-landscape-policy"
        screenshot()
        try tap(app.buttons.matching(identifier: "Открыть библиотеку"))
    }

    private func requireLandscape(_ app: XCUIApplication) throws {
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            app.frame.width > app.frame.height
        }, object: nil)
        guard XCTWaiter.wait(for: [ready], timeout: 10) == .completed else {
            XCTFail("Product must remain landscape; frame=\(app.frame)")
            throw Unavailable()
        }
        applicationFrame = app.frame
    }

    func testInspectOriginalLibraryWithoutWrites() throws {
        let app = XCUIApplication(bundleIdentifier: "su.linka.pictures.development")
        app.launch()
        applicationFrame = app.frame
        _ = try visible(named("Ваши наборы", app))
        let tree = XCTAttachment(string: app.debugDescription)
        tree.name = "smotri-library-ax"
        tree.lifetime = .keepAlways
        add(tree)
        stage = "smotri-library-read-only"
        screenshot()
    }

    func testImportOrReuseOriginalsAndCaptureEveryPageWithoutSelection() throws {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "su.linka.pictures.development")
        app.launch()
        applicationFrame = app.frame
        do {
            _ = try visible(named("Ваши наборы", app))
            stage = "base-scale-read-only"
            try tap(app.buttons.matching(identifier: "Меню библиотеки"))
            try tap(named("Настройки", app))
            try reveal(app.staticTexts.matching(identifier: "1.0"), app)
            try tap(named("Назад", app))
            _ = try visible(named("Ваши наборы", app))
            for fixture in fixtures {
                if try !findBoard(fixture.name, app) { try importOriginal(fixture, app) }
                guard try findBoard(fixture.name, app) else { throw Unavailable() }
            }
            try captureBoards(app)
        } catch {
            stage += "-failure"
            screenshot()
            XCTFail("\(stage): inspect staged originals/Files/AX; never blindly replay import confirmation or reset library")
            throw error
        }
    }

    func testFixedPagesAndAdultRejection() throws {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "su.linka.pictures.development")
        app.launch()
        applicationFrame = app.frame
        _ = try visible(named("Ваши наборы", app))
        try captureBoards(app)
    }

    func testRestoreBaselineCaptureSettingsAndComposeMode() throws {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "su.linka.pictures.development")
        app.launch()
        applicationFrame = app.frame
        _ = try visible(named("Ваши наборы", app))
        try tap(app.buttons.matching(identifier: "Меню библиотеки"))
        try tap(named("Настройки", app))
        try reveal(app.staticTexts.matching(identifier: "1.0"), app)
        XCTAssertFalse(try visible(app.switches.matching(identifier: "Высокий контраст")).isSelected)
        try tap(named("Назад", app))
        guard try findBoard(fixtures[0].name, app) else { throw Unavailable() }
        try tap(app.buttons.matching(identifier: "Действия с набором «\(fixtures[0].name)»"))
        try tap(named("Открыть", app))
        _ = try setSessionMode(false, app)
        XCTAssertEqual(try visible(app.buttons.matching(identifier: "Меню общения")).value as? String, "Составление сообщения")
        stage = "smotri-restored-compose"
        screenshot()
        try tap(app.buttons.matching(identifier: "Открыть библиотеку"))
        _ = try visible(named("Ваши наборы", app))
    }

    func testRejectedBoardPreservesWorkingBoardAndMessage() throws {
        try XCTSkipIf(UIDevice.current.userInterfaceIdiom == .pad, "Keyboard admission on iPad is viewport-dependent; covered by fixed-page capture")
        let app = XCUIApplication(bundleIdentifier: "su.linka.pictures.development")
        app.launch()
        applicationFrame = app.frame
        _ = try visible(named("Ваши наборы", app))
        try openFixture(fixtures[0], app)
        XCTAssertFalse(try awaitAdmission(app), "Phrase must fit at baseline")
        try tap(app.buttons.matching(identifier: "Следующая страница"))
        _ = try visible(named("Страница 2 из 2", app))
        let before = message(app)
        XCTAssertNotNil(before)
        let mode = try visible(app.buttons.matching(identifier: "Меню общения")).value as? String
        let audio = app.buttons.matching(identifier: "Произнести").firstMatch.value as? String
        try tap(app.buttons.matching(identifier: "Открыть библиотеку"))
        try openFixture(fixtures[4], app)
        XCTAssertTrue(try awaitAdmission(app), "Dense keyboard must be rejected on the iPhone baseline")
        stage = "smotri-fixed-keyboard-rejected-preserves-session"
        screenshot()
        try tap(named("Закрыть", app))
        _ = try visible(named("Ваши наборы", app))
        try tap(app.buttons.matching(identifier: "Меню библиотеки"))
        try tap(named("К сообщению", app))
        _ = try visible(named(fixtures[0].name, app))
        _ = try visible(named("Страница 2 из 2", app))
        XCTAssertEqual(message(app), before)
        XCTAssertEqual(try visible(app.buttons.matching(identifier: "Меню общения")).value as? String, mode)
        XCTAssertEqual(app.buttons.matching(identifier: "Произнести").firstMatch.value as? String, audio)
        XCTAssertFalse(app.buttons.matching(identifier: "Остановить").firstMatch.exists)
        try tap(app.buttons.matching(identifier: "Открыть библиотеку"))
    }

    func testUnsupportedScaleDoesNotChangeWorkingBoard() throws {
        try XCTSkipIf(UIDevice.current.userInterfaceIdiom == .pad, "Scale rejection requires the iPhone viewport")
        let app = XCUIApplication(bundleIdentifier: "su.linka.pictures.development")
        app.launch()
        applicationFrame = app.frame
        _ = try visible(named("Ваши наборы", app))
        try openFixture(fixtures[0], app)
        XCTAssertFalse(try awaitAdmission(app))
        let before = message(app)
        XCTAssertNotNil(before)
        let mode = try visible(app.buttons.matching(identifier: "Меню общения")).value as? String
        let audio = app.buttons.matching(identifier: "Произнести").firstMatch.value as? String
        try tap(app.buttons.matching(identifier: "Открыть библиотеку"))
        try tap(app.buttons.matching(identifier: "Меню библиотеки"))
        try tap(named("Настройки", app))
        let contrastQuery = app.switches.matching(identifier: "Высокий контраст")
        try reveal(app.staticTexts.matching(identifier: "1.0"), app)
        try reveal(contrastQuery, app)
        XCTAssertFalse(try visible(contrastQuery).isSelected)
        try reveal(named("Масштаб текста", app), app)
        try tap(named("Масштаб текста", app))
        let field = try visible(app.textViews)
        guard field.label == "1.0" || field.value as? String == "1.0" else { throw Unavailable() }
        field.coordinate(withNormalizedOffset: CGVector(dx: 0.98, dy: 0.5)).tap()
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 64))
        field.typeText("3.0")
        _ = try visible(app.textViews.matching(identifier: "3.0"))
        try transferTap("Применить", app)
        _ = try visible(named("Масштаб не поддерживается", app))
        stage = "smotri-fixed-scale-rejected-before-save"
        screenshot()
        try tap(named("Закрыть", app))
        try transferTap("Отмена", app)
        try tap(named("Назад", app))
        try tap(app.buttons.matching(identifier: "Меню библиотеки"))
        try tap(named("Настройки", app))
        try reveal(app.staticTexts.matching(identifier: "1.0"), app)
        try reveal(contrastQuery, app)
        XCTAssertFalse(try visible(contrastQuery).isSelected)
        try tap(named("Назад", app))
        try tap(app.buttons.matching(identifier: "Меню библиотеки"))
        try tap(named("К сообщению", app))
        _ = try visible(named(fixtures[0].name, app))
        _ = try visible(named("Страница 1 из 2", app))
        XCTAssertEqual(message(app), before)
        XCTAssertEqual(try visible(app.buttons.matching(identifier: "Меню общения")).value as? String, mode)
        XCTAssertEqual(app.buttons.matching(identifier: "Произнести").firstMatch.value as? String, audio)
        XCTAssertFalse(app.buttons.matching(identifier: "Остановить").firstMatch.exists)
        try tap(app.buttons.matching(identifier: "Открыть библиотеку"))
    }

    private func openFixture(_ fixture: Fixture, _ app: XCUIApplication) throws {
        guard try findBoard(fixture.name, app) else {
            XCTFail("Missing \(fixture.name); capture never imports")
            throw Unavailable()
        }
        try tap(app.buttons.matching(identifier: "Действия с набором «\(fixture.name)»"))
        try tap(named("Открыть", app))
    }

    private func awaitAdmission(_ app: XCUIApplication) throws -> Bool {
        let warning = named("Масштаб не поддерживается", app)
        let menu = app.buttons.matching(identifier: "Меню общения")
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            warning.allElementsBoundByIndex.contains { self.onScreen($0) && $0.isHittable } ||
                menu.allElementsBoundByIndex.contains { self.onScreen($0) && $0.isHittable }
        }, object: nil)
        guard XCTWaiter.wait(for: [ready], timeout: 30) == .completed else {
            XCTFail("Expected adult warning or admitted child board within 30 seconds")
            throw Unavailable()
        }
        return warning.allElementsBoundByIndex.contains { onScreen($0) && $0.isHittable }
    }

    private func captureBoards(_ app: XCUIApplication) throws {
        try requireLandscape(app)
        for fixture in fixtures {
            stage = "\(fixture.slug)-lookup"
            try openFixture(fixture, app)
            if try awaitAdmission(app) {
                stage = "smotri-\(capturePrefix)\(fixture.slug)-unsupported"
                screenshot()
                attach("source=\(fixture.file);status=unsupported;childBoardNotOpened=true", name: stage + "-admission")
                try tap(named("Закрыть", app))
                _ = try visible(named("Ваши наборы", app))
                XCTAssertFalse(app.buttons.matching(identifier: "Меню общения").firstMatch.exists)
                continue
            }
            attach("source=\(fixture.file);status=admitted;expectedPages=\(fixture.pages);allPagesPreflight=production-contract", name: "smotri-\(capturePrefix)\(fixture.slug)-admission")
            _ = try visible(app.buttons.matching(identifier: "Меню общения"))
            _ = try visible(named(fixture.name, app))
            _ = try visible(named("Страница 1 из \(fixture.pages)", app))
            let previousDirect = try setSessionMode(fixture.direct, app)
            let messageBefore = message(app)
            XCTAssertNotNil(messageBefore, "Message semantics required for no-selection check")
            let audioBefore = app.buttons.matching(identifier: "Произнести").firstMatch.value as? String
            do { for page in 1...fixture.pages {
                stage = "smotri-\(capturePrefix)\(fixture.slug)-page-\(page)"
                _ = try visible(named("Страница \(page) из \(fixture.pages)", app))
                XCTAssertEqual(message(app), messageBefore, "Paging must not select cards")
                XCTAssertFalse(app.buttons.matching(identifier: "Остановить").firstMatch.exists)
                XCTAssertEqual(app.buttons.matching(identifier: "Произнести").firstMatch.value as? String, audioBefore)
                let menu = try visible(app.buttons.matching(identifier: "Меню общения"))
                if let mode = menu.value as? String, !mode.isEmpty {
                    XCTAssertEqual(mode, fixture.direct ? "Прямой режим" : "Составление сообщения")
                }
                try captureWholePage(fixture, page: page, app: app)
                XCTAssertEqual(message(app), messageBefore, "Whole-page capture must not select cards")
                XCTAssertFalse(app.buttons.matching(identifier: "Остановить").firstMatch.exists)
                XCTAssertEqual(app.buttons.matching(identifier: "Произнести").firstMatch.value as? String, audioBefore)
                if page < fixture.pages { try tap(app.buttons.matching(identifier: "Следующая страница")) }
            } } catch {
                _ = try? setSessionMode(previousDirect, app)
                throw error
            }
            _ = try setSessionMode(previousDirect, app)
            try tap(app.buttons.matching(identifier: "Открыть библиотеку"))
            _ = try visible(named("Ваши наборы", app))
        }
    }

    private func setSessionMode(_ direct: Bool, _ app: XCUIApplication) throws -> Bool {
        let menu = try visible(app.buttons.matching(identifier: "Меню общения"))
        guard let current = menu.value as? String,
              ["Прямой режим", "Составление сообщения"].contains(current) else { throw Unavailable() }
        let previous = current == "Прямой режим"
        if previous != direct {
            try tap(app.buttons.matching(identifier: "Меню общения"))
            try tap(named(direct ? "Перейти в прямой режим" : "Составлять сообщение", app))
            let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                menu.value as? String == (direct ? "Прямой режим" : "Составление сообщения")
            }, object: nil)
            guard XCTWaiter.wait(for: [ready], timeout: 15) == .completed else { throw Unavailable() }
        }
        return previous
    }

    private func captureWholePage(_ fixture: Fixture, page: Int, app: XCUIApplication) throws {
        let speak = try visible(app.buttons.matching(identifier: "Произнести"))
        let pageLabel = try visible(named("Страница \(page) из \(fixture.pages)", app))
        let previous = try visible(app.buttons.matching(identifier: "Предыдущая страница"))
        let next = try visible(app.buttons.matching(identifier: "Следующая страница"))
        let footerTop = min(previous.frame.minY, next.frame.minY, pageLabel.frame.minY)
        let viewport = CGRect(x: app.frame.minX, y: speak.frame.maxY, width: app.frame.width,
                              height: footerTop - speak.frame.maxY).intersection(app.frame)
        guard viewport.width > 0, viewport.height > 0 else { throw Unavailable() }
        try awaitVisibleMedia(app, viewport: viewport)
        let controls = [speak.frame, previous.frame, next.frame, pageLabel.frame]
        let cards = try buttonSnapshots(app).filter { element in
            let intersection = element.frame.intersection(viewport)
            return !controls.contains(element.frame) && intersection.width > 0 && intersection.height > 0
        }
        XCTAssertFalse(cards.isEmpty, "Admitted page must expose content-card buttons")
        // Counts come from unchanged source ZIPs; cardType 2 and unfilled final slots are EMPTY.
        let expectedButtons: Int
        switch fixture.slug {
        case "phrase", "keyboard3x3": expectedButtons = fixture.slug == "keyboard3x3" && page == 5 ? 7 : 9
        case "alphabet", "sounds2": expectedButtons = 4
        case "keyboard": expectedButtons = page == 1 ? 46 : 40
        default: throw Unavailable()
        }
        XCTAssertEqual(cards.count, expectedButtons, "Every non-EMPTY slot must be visible in the single viewport")
        for card in cards {
            XCTAssertGreaterThanOrEqual(card.frame.width, 48)
            XCTAssertGreaterThanOrEqual(card.frame.height, 48)
            XCTAssertTrue(viewport.contains(CGPoint(x: card.frame.midX, y: card.frame.midY)),
                          "Card center outside fixed grid: \(card.label) \(card.frame)")
            XCTAssertTrue(viewport.contains(card.frame),
                          "Full card frame must fit without scrolling: \(card.label) \(card.frame); grid=\(viewport)")
        }
        var pending = [try app.snapshot()]
        var cells = [XCUIElementSnapshot]()
        while let node = pending.popLast() {
            if node.elementType == .cell && node.frame.intersects(viewport) { cells.append(node) }
            pending.append(contentsOf: node.children)
        }
        for cell in cells {
            XCTAssertTrue(viewport.contains(cell.frame), "AX collection cell must fit: \(cell.frame)")
        }
        let content = cards.map {
            "id=\($0.identifier);label=\($0.label);value=\(String(describing: $0.value));frame=\($0.frame)"
        }.sorted().joined(separator: "\n")
        let cellMetadata = cells.map {
            "cellId=\($0.identifier);label=\($0.label);frame=\($0.frame)"
        }.sorted().joined(separator: "\n")
        stage = "smotri-\(capturePrefix)\(fixture.slug)-page-\(page)"
        screenshot()
        attach("source=\(fixture.file);page=\(page);expectedRows=\(fixture.rows);expectedColumns=\(fixture.columns);" +
               "visibleContentCardCount=\(cards.count);exposedAXCellCount=\(cells.count);gridFrame=\(viewport)\n" +
               "EMPTY coverage limitation: XCTest snapshots have no Compose CollectionItem row/column metadata; " +
               "AX cells are recorded if exposed, but exact EMPTY positions/count are not asserted.\n" + content + "\n" + cellMetadata,
               name: stage + "-ax")
        attach(app.debugDescription, name: stage + "-tree")
    }

    private func attach(_ text: String, name: String) {
        let attachment = XCTAttachment(string: text)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func awaitVisibleMedia(_ app: XCUIApplication, viewport: CGRect) throws {
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            guard let buttons = try? self.buttonSnapshots(app) else { return false }
            return !buttons.contains { element in
                element.frame.intersects(viewport) &&
                    (element.value as? String)?.contains("Изображение ещё не показано") == true
            }
        }, object: nil)
        guard XCTWaiter.wait(for: [ready], timeout: 30) == .completed else {
            XCTFail("Visible card image did not decode within 30 seconds; stable pixels are insufficient")
            throw Unavailable()
        }
    }

    private func buttonSnapshots(_ app: XCUIApplication) throws -> [XCUIElementSnapshot] {
        var pending = [try app.snapshot()]
        var buttons = [XCUIElementSnapshot]()
        while let node = pending.popLast() {
            if node.elementType == .button { buttons.append(node) }
            pending.append(contentsOf: node.children)
        }
        return buttons
    }

    private func importOriginal(_ fixture: Fixture, _ app: XCUIApplication) throws {
        stage = "\(fixture.slug)-import-name"
        try tap(app.buttons.matching(identifier: "Меню библиотеки"))
        try tap(named("Импорт, резервная копия и восстановление", app))
        _ = try visible(named("Файлы и восстановление", app))
        let field = try visible(app.textViews)
        guard app.textViews.count == 1 else { throw Unavailable() }
        field.coordinate(withNormalizedOffset: CGVector(dx: 0.98, dy: 0.5)).tap()
        guard app.keyboards.firstMatch.waitForExistence(timeout: 5) else { throw Unavailable() }
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 64))
        field.typeText(fixture.name)
        _ = try visible(app.textViews.matching(identifier: fixture.name))
        try transferTap("Выбрать .linka для импорта (без записи в библиотеку)", app)
        stage = "\(fixture.slug)-files"
        try chooseOriginal(fixture.file, app)
        stage = "\(fixture.slug)-preview"
        try reveal(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Страниц: \(fixture.pages);")), app, timeout: 30)
        _ = try visible(named("Доска: «\(fixture.name)»", app))
        if fixture.slug == "keyboard" {
            try reveal(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Неактивных вложений EMPTY/SPACE: 11.")), app)
            stage = "smotri-keyboard-inactive-preview"
            screenshot()
        }
        try transferTap("Разрешить команды в этом экране", app, upwards: false)
        stage = "\(fixture.slug)-single-confirmation"
        // A timeout stops the test; a later run first scans for the unique imported name.
        try transferTap("Подтверждаю импорт «\(fixture.name)» как новой доски", app)
        try reveal(named("Операция завершена.", app), app, upwards: false, timeout: 2)
        try tap(named("Назад", app))
        _ = try visible(named("Ваши наборы", app))
    }

    private func chooseOriginal(_ filename: String, _ app: XCUIApplication) throws {
        _ = try visible(app.buttons.matching(NSPredicate(format: "label IN %@", ["Отменить", "Cancel"])))
        if app.navigationBars.staticTexts.matching(identifier: "u14-fixtures").firstMatch.exists {
            let back = app.navigationBars.firstMatch.buttons.element(boundBy: 0)
            guard back.frame.maxX < app.frame.width / 2 else { throw Unavailable() }
            back.tap()
        }
        let names = [filename, String(filename.dropLast(".linka".count))]
        let file = app.cells.containing(NSPredicate(format: "label IN %@", names))
        // Files remembers the last folder; never tap a similarly named library element outside the picker.
        if !wait(file, timeout: 10) {
            let folder = app.cells.containing(.any, identifier: "smotri-originals")
            if !wait(folder, timeout: 2) {
                let host = app.cells.containing(.any, identifier: "LinkaKeychainHost")
                if !wait(host, timeout: 2) {
                    if !wait(named(localNames, app), timeout: 2) { try tap(named(["Обзор", "Browse"], app)) }
                    if !wait(named(localNames, app), timeout: 2) { try tap(named(["Обзор", "Browse"], app)) }
                    try tap(named(localNames, app))
                }
                try tap(host)
            }
            try tap(folder)
        }
        try tap(file)
    }

    private func findBoard(_ name: String, _ app: XCUIApplication) throws -> Bool {
        let query = app.buttons.matching(identifier: "Действия с набором «\(name)»")
        // Scan the actual library to both edges before declaring a name absent.
        for direction in [false, true] {
            var previous = ""
            var reachedEdge = false
            for _ in 0..<100 {
                if wait(query, timeout: 0.3) { return true }
                let tiles = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ OR label BEGINSWITH %@",
                    "Действия с набором «", "Действия с набором «"))
                    .allElementsBoundByIndex.filter { onScreen($0) && $0.isHittable }
                let signature = tiles.map { "\($0.identifier):\($0.frame)" }.joined(separator: "|")
                if signature == previous { reachedEdge = true; break }
                previous = signature
                let origin = app.coordinate(withNormalizedOffset: .zero)
                let top = app.frame.height * 0.25
                let bottom = app.frame.height * 0.8
                origin.withOffset(CGVector(dx: app.frame.width * 0.93, dy: direction ? bottom : top))
                    .press(forDuration: 0.05, thenDragTo:
                        origin.withOffset(CGVector(dx: app.frame.width * 0.93, dy: direction ? top : bottom)))
            }
            guard reachedEdge else { throw Unavailable() }
        }
        return false
    }

    private func message(_ app: XCUIApplication) -> String? {
        app.descendants(matching: .any).matching(NSPredicate(
            format: "label BEGINSWITH %@ OR identifier BEGINSWITH %@", "Общее сообщение:", "Общее сообщение:"
        )).allElementsBoundByIndex.first(where: { $0.exists && $0.isHittable })?.label
    }

    private func named(_ name: String, _ app: XCUIApplication) -> XCUIElementQuery { named([name], app) }

    private func named(_ names: [String], _ app: XCUIApplication) -> XCUIElementQuery {
        app.descendants(matching: .any).matching(NSPredicate(format: "label IN %@ OR identifier IN %@", names, names))
    }

    private func wait(_ query: XCUIElementQuery, timeout: TimeInterval = 15) -> Bool {
        let predicate = NSPredicate { _, _ in
            query.allElementsBoundByIndex.contains { self.onScreen($0) && $0.isHittable }
        }
        if predicate.evaluate(with: nil) { return true }
        let ready = XCTNSPredicateExpectation(predicate: predicate, object: nil)
        return XCTWaiter.wait(for: [ready], timeout: timeout) == .completed
    }

    private func visible(_ query: XCUIElementQuery) throws -> XCUIElement {
        guard wait(query), let element = query.allElementsBoundByIndex.first(where: { onScreen($0) && $0.isHittable })
        else { throw Unavailable() }
        return element
    }

    private func tap(_ query: XCUIElementQuery) throws {
        var lastFrame: CGRect?
        var since = ProcessInfo.processInfo.systemUptime
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            guard let element = query.allElementsBoundByIndex.first(where: { self.onScreen($0) && $0.isHittable && $0.isEnabled }) else { return false }
            if element.frame != lastFrame { lastFrame = element.frame; since = ProcessInfo.processInfo.systemUptime; return false }
            return ProcessInfo.processInfo.systemUptime - since >= 0.25
        }, object: nil)
        guard XCTWaiter.wait(for: [ready], timeout: 15) == .completed else { throw Unavailable() }
        try visible(query).tap()
    }

    private func onScreen(_ element: XCUIElement) -> Bool {
        guard element.exists else { return false }
        let frame = element.frame
        return frame.width > 0 && frame.height > 0 && applicationFrame.contains(CGPoint(x: frame.midX, y: frame.midY))
    }

    private func largestScroll(_ app: XCUIApplication) -> XCUIElement? {
        (app.scrollViews.allElementsBoundByIndex + app.collectionViews.allElementsBoundByIndex).filter { $0.exists && $0.isHittable }.max {
            $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height
        }
    }

    private func reveal(_ query: XCUIElementQuery, _ app: XCUIApplication, upwards: Bool = true,
                        timeout: TimeInterval = 3) throws {
        if wait(query, timeout: timeout) { return }
        for _ in 0..<24 {
            guard let scroll = largestScroll(app) else { throw Unavailable() }
            let keyboard = app.keyboards.firstMatch
            let keyboardFrame = keyboard.exists ? keyboard.frame : .zero
            // iOS17 XCTest can expose the landscape keyboard in unrotated panel coordinates.
            let keyboardTop = keyboardFrame.height > app.frame.height && keyboardFrame.width < app.frame.width / 2
                ? app.frame.maxY - keyboardFrame.width : keyboardFrame.minY
            let bottom = keyboard.exists ? keyboardTop : app.frame.maxY - 20
            let top = max(scroll.frame.minY, app.frame.minY + 80)
            let height = min(scroll.frame.maxY, bottom) - top
            guard height > 60 else { throw Unavailable() }
            let origin = app.coordinate(withNormalizedOffset: .zero)
            let x = app.frame.width * 0.8
            let start = top + height * (upwards ? 0.65 : 0.35)
            let end = top + height * (upwards ? 0.35 : 0.65)
            origin.withOffset(CGVector(dx: x, dy: start)).press(forDuration: 0.05,
                thenDragTo: origin.withOffset(CGVector(dx: x, dy: end)))
            if wait(query, timeout: 2) { return }
        }
        throw Unavailable()
    }

    private func transferTap(_ name: String, _ app: XCUIApplication, upwards: Bool = true) throws {
        let query = app.buttons.matching(identifier: name)
        try reveal(query, app, upwards: upwards)
        try tap(query)
    }

    private func screenshot() {
        let shot = XCUIScreen.main.screenshot()
        let png = XCTAttachment(data: shot.pngRepresentation, uniformTypeIdentifier: "public.png")
        png.name = stage + ".png"
        png.lifetime = .keepAlways
        add(png)
    }
}
