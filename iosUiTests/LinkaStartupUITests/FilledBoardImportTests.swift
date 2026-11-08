import XCTest
import UIKit

final class FilledBoardImportTests: XCTestCase {
    private let importedBoardName = "Импортированная доска"
    private let localDeviceNames = ["iPhone", "На iPhone", "На моём iPhone", "On My iPhone", "iPad", "На iPad", "На моём iPad", "On My iPad"]
    private let cards = [
        "КУШАТЬ", "ПИТЬ", "СПАТЬ", "КУПАТЬСЯ", "ХОЧУ",
        "ПИСАТЬ КАКАТЬ", "ГУЛЯТЬ", "ИГРАТЬ", "ОБНИМАТЬСЯ",
    ]
    private var stage = "00-launch"
    private struct Unavailable: Error {}

    func testElectronEditorAtlasWithoutPublishing() throws {
        continueAfterFailure = false
        let app = productApplication()
        app.launch()
        if UIDevice.current.userInterfaceIdiom == .pad {
            XCUIDevice.shared.orientation = .landscapeLeft
            let landscape = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                app.frame.width > app.frame.height
            }, object: nil)
            guard XCTWaiter.wait(for: [landscape], timeout: 15) == .completed else { throw Unavailable() }
        }
        defer { XCUIDevice.shared.orientation = .portrait }
        _ = try visible(named("Ваши наборы", in: app))
        try tap(app.buttons.matching(identifier: "Действия с набором «\(importedBoardName)»"))
        try tap(named("Редактировать", in: app))
        if wait(app.buttons.matching(identifier: "Разрешить команды в этом экране"), timeout: 2) {
            try tap(app.buttons.matching(identifier: "Разрешить команды в этом экране"))
        }
        if app.buttons.matching(identifier: "Начать редактирование").firstMatch.exists {
            try tap(app.buttons.matching(identifier: "Начать редактирование"))
        }
        stage = "electron-editor-grid"
        _ = try visible(app.buttons.matching(identifier: "КУШАТЬ. Строка 1, столбец 1"))
        screenshot(app)
        try tap(app.buttons.matching(identifier: "КУШАТЬ. Строка 1, столбец 1"))
        stage = "electron-editor-card"
        _ = try visible(named("Тип карточки", in: app))
        screenshot(app)
        try tap(app.textViews.matching(identifier: "КУШАТЬ"))
        guard app.keyboards.firstMatch.waitForExistence(timeout: 5) else { throw Unavailable() }
        stage = "electron-editor-keyboard"
        screenshot(app)
        try tap(app.keyboards.descendants(matching: .any).matching(NSPredicate(
            format: "label IN %@ OR identifier IN %@", ["Готово", "Done"], ["Готово", "Done"])))
        try tap(app.buttons.matching(identifier: "Отмена"))
        _ = try visible(app.buttons.matching(identifier: "КУШАТЬ. Строка 1, столбец 1"))
        try tap(named("Назад с сохранённым черновиком", in: app))
        try openOwnedBoard(in: app)
        try assertCards(in: app)
        stage = "electron-editor-published-board-unchanged"
        screenshot(app)
    }

    func testElectronVisualAtlasExistingLibrary() throws {
        continueAfterFailure = false
        let app = productApplication()
        app.launch()
        stage = "electron-library"
        _ = try visible(named("Ваши наборы", in: app))
        screenshot(app)
        try openOwnedBoard(in: app)
        stage = "electron-board"
        try assertCards(in: app)
        let message = try visible(app.descendants(matching: .any).matching(NSPredicate(
            format: "label BEGINSWITH %@ OR identifier BEGINSWITH %@", "Общее сообщение:", "Общее сообщение:")))
        let original = message.label
        screenshot(app)
        try tap(named("Настройки доски", in: app))
        stage = "electron-board-settings"
        _ = try visible(named("Параметры доски", in: app))
        screenshot(app)
        try tap(app.buttons.matching(identifier: "Закрыть"))
        try tap(named("Открыть библиотеку", in: app))
        try tap(named("Меню библиотеки", in: app))
        try tap(named("Настройки", in: app))
        stage = "electron-settings"
        _ = try visible(named("Масштаб текста", in: app))
        screenshot(app)
        try tap(named("Масштаб текста", in: app))
        stage = "electron-scale-dialog"
        _ = try visible(app.textViews)
        screenshot(app)
        try tap(app.buttons.matching(identifier: "Отмена"))
        try tap(named("Назад", in: app))
        try openOwnedBoard(in: app)
        stage = "electron-restored-board"
        try assertCards(in: app)
        _ = try visible(named(original, in: app))
        screenshot(app)
    }

    func testImportOwnedFixtureThroughFilesAndCompose() throws {
        continueAfterFailure = false
        let app = productApplication()
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

            stage = "03-import-default-name"
            _ = try visible(named("Файлы и восстановление", in: app))
            _ = try visible(app.textViews.matching(identifier: importedBoardName))
            screenshot(app)
            try tapTransfer("Выбрать .linka для импорта (без записи в библиотеку)", in: app)

            stage = "04-files-browse"
            let hostFolder = app.cells.containing(.any, identifier: "LinkaKeychainHost")
            // iPad already exposes the local host; wait for that remote picker cell before enumerating its tree.
            if !hostFolder.firstMatch.waitForExistence(timeout: 5) {
                if !wait(named(localDeviceNames, in: app), timeout: 2) {
                    try tap(named(["Обзор", "Browse"], in: app))
                }
                // A picker initially showing Recents may need Browse and then its back button.
                if !wait(named(localDeviceNames, in: app), timeout: 3) {
                    try tap(named(["Обзор", "Browse"], in: app))
                }
                try tap(named(localDeviceNames, in: app))
            }
            screenshot(app)
            stage = "05-files-own-host"
            try tap(hostFolder)
            screenshot(app)
            stage = "06-files-fixture-folder"
            try tap(app.cells.containing(.any, identifier: "u14-fixtures"))
            screenshot(app)
            stage = "07-files-fixture"
            // Files may hide the extension; both names identify only the staged public archive here.
            _ = try visible(named(["ХОЧУ.linka", "ХОЧУ"], in: app))
            screenshot(app)
            let archiveCell = app.cells.containing(NSPredicate(
                format: "label == %@ OR label == %@", "ХОЧУ.linka", "ХОЧУ"
            ))
            try tap(archiveCell)

            stage = "08-real-preview"
            let preview = app.staticTexts.matching(NSPredicate(
                format: "label BEGINSWITH %@",
                "Страниц: 1; карточек: 9; изображений: 9; записей: 9;"
            ))
            try revealTransfer(preview, in: app, timeout: 30)
            _ = try visible(named("Доска: «\(importedBoardName)»", in: app))
            screenshot(app)
            stage = "09-explicit-unlock"
            try tapTransfer("Разрешить команды в этом экране", in: app, upwards: false)
            screenshot(app)
            stage = "10-explicit-import"
            screenshot(app)
            try tapTransfer("Подтверждаю импорт «\(importedBoardName)» как новой доски", in: app)
            stage = "11-published"
            try revealTransfer(named("Операция завершена.", in: app), in: app, timeout: 30)
            screenshot(app)
            try tap(named("Назад", in: app))

            stage = "12-board-empty"
            try openOwnedBoard(in: app)
            try composeAndReopen(in: app)
        } catch {
            screenshot(app)
            let hint = stage == "01-empty-library"
                ? "reopen-only required; existing/unknown library must not be reset or imported again"
                : "required visible UI missing; verify fixture staging/AX assumptions; do not retry import"
            XCTFail("\(stage): \(hint)")
        }
    }

    func testComposeExistingOwnedBoardAndReopen() throws {
        continueAfterFailure = false
        let app = productApplication()
        app.launch()
        do {
            stage = "existing-owned-board"
            try openOwnedBoard(in: app)
            try composeAndReopen(in: app)
        } catch {
            screenshot(app)
            XCTFail("\(stage): existing fixture state does not match; never clear or reimport")
        }
    }

    func testExistingOwnedBoardPortraitAndLandscapeWithoutMessageEdits() throws {
        continueAfterFailure = false
        let app = productApplication()
        app.launch()
        defer { XCUIDevice.shared.orientation = .portrait }
        do {
            for orientation in [UIDeviceOrientation.portrait, .landscapeLeft] {
                stage = orientation == .portrait ? "atlas-portrait" : "atlas-landscape"
                _ = try visible(named("Ваши наборы", in: app))
                XCUIDevice.shared.orientation = orientation
                let rotated = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                    let frame = app.frame
                    return orientation == .portrait ? frame.height > frame.width : frame.width > frame.height
                }, object: nil)
                guard XCTWaiter.wait(for: [rotated], timeout: 15) == .completed else { throw Unavailable() }
                screenshot(app)
                try openOwnedBoard(in: app)
                try assertCards(in: app)
                _ = try visible(named("Общее сообщение: ХОЧУ КУШАТЬ", in: app))
                guard !app.buttons.matching(identifier: "Остановить").firstMatch.exists else { throw Unavailable() }
                let screen = XCUIScreen.main.screenshot()
                let full = XCTAttachment(screenshot: screen)
                full.name = stage + "-full-screen"
                full.lifetime = .keepAlways
                add(full)
                let geometry = XCTAttachment(string: "appFrame=\(app.frame); screenImage=\(screen.image.size); imageOrientation=\(screen.image.imageOrientation.rawValue)")
                geometry.name = stage + "-capture-geometry"
                geometry.lifetime = .keepAlways
                add(geometry)
                let image = screen.image.size
                guard orientation == .portrait ? image.height > image.width : image.width > image.height else { throw Unavailable() }
                try tap(named("Открыть библиотеку", in: app))
                _ = try visible(named("Ваши наборы", in: app))
            }
        } catch {
            screenshot(app)
            XCTFail("\(stage): orientation/visible-card acceptance incomplete; library and message must not be reset")
        }
    }

    func testExportAndBackupExistingOwnedBoardThroughLocalFiles() throws {
        continueAfterFailure = false
        let app = productApplication()
        app.launch()
        do {
            stage = "export-existing-owned-board"
            try tap(app.buttons.matching(identifier: "Действия с набором «\(importedBoardName)»"))
            try tap(named("Экспортировать", in: app))
            for backup in [false, true] {
                stage = backup ? "backup-prepare" : "export-prepare"
                try tapTransfer("Разрешить команды в этом экране", in: app, upwards: false)
                try tapTransfer(backup ? "Подготовить резервную копию всей библиотеки"
                    : "Подготовить экспорт выбранной доски .linka", in: app)
                stage = backup ? "backup-local-folder" : "export-local-folder"
                try chooseOwnedOutputFolder(in: app)
                stage = backup ? "backup-fresh-confirmation" : "export-fresh-confirmation"
                try tapTransfer("Разрешить команды в этом экране", in: app, upwards: false)
                if backup {
                    try revealTransfer(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Досок: 1; черновиков: 0;")), in: app, timeout: 30)
                } else {
                    try revealTransfer(named("Доска: «\(importedBoardName)»", in: app), in: app, timeout: 30)
                    _ = try visible(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@",
                        "Страниц: 1; карточек: 9; изображений: 9; записей: 9;")))
                }
                screenshot(app)
                try tapTransfer(backup ? "Подтверждаю запись открытого архива всей библиотеки"
                    : "Подтверждаю запись открытого файла доски", in: app)
                stage = backup ? "backup-completed" : "export-completed"
                try revealTransfer(named("Операция завершена.", in: app), in: app, timeout: 30)
                screenshot(app)
            }
            try tap(named("Назад", in: app))
            try openOwnedBoard(in: app)
            try assertCards(in: app)
            _ = try visible(named("Общее сообщение: ХОЧУ КУШАТЬ", in: app))
            stage = "export-backup-preserved-board-message"
            screenshot(app)
        } catch {
            screenshot(app)
            XCTFail("\(stage): export/backup incomplete; leave any partial file and never overwrite or restore to force success")
        }
    }

    func testInspectOwnedSettingsWithoutChangingThem() throws {
        continueAfterFailure = false
        let app = productApplication()
        app.launch()
        do {
            stage = "settings-existing-library"
            _ = try visible(named("Ваши наборы", in: app))
            try tap(named("Меню библиотеки", in: app))
            try tap(named("Настройки", in: app))
            stage = "settings-native-controls"
            _ = try visible(named("Масштаб текста", in: app))
            screenshot(app)
            let controls = app.descendants(matching: .any).matching(NSPredicate(
                format: "label IN %@ OR identifier IN %@", ["Тёмная тема", "Высокий контраст"], ["Тёмная тема", "Высокий контраст"]
            ))
            let details = XCTAttachment(string: controls.allElementsBoundByIndex.map {
                "type=\($0.elementType.rawValue); label=\($0.label); value=\(String(describing: $0.value)); frame=\($0.frame)"
            }.joined(separator: "\n"))
            details.name = "settings-public-toggle-semantics"
            details.lifetime = .keepAlways
            add(details)
            try tap(named("Масштаб текста", in: app))
            stage = "settings-scale-dialog-read-only"
            _ = try visible(app.textViews)
            screenshot(app)
            try tap(app.buttons.matching(identifier: "Отмена"))
            try tap(named("Назад", in: app))
            _ = try visible(named("Ваши наборы", in: app))
        } catch {
            screenshot(app)
            XCTFail("\(stage): settings inspection incomplete; no save or preference reset permitted")
        }
    }

    func testExistingOwnedBoardAccessibilityAudit() throws {
        continueAfterFailure = false
        let app = productApplication()
        app.launch()
        stage = "accessibility-audit-existing-board"
        try openOwnedBoard(in: app)
        try assertCards(in: app)
        _ = try visible(named("Общее сообщение: ХОЧУ КУШАТЬ", in: app))
        screenshot(app)
        // Dynamic Type is a separate state-changing matrix; this audit does not change system settings.
        try app.performAccessibilityAudit(for: [.contrast, .elementDetection, .hitRegion,
            .sufficientElementDescription, .textClipped, .trait])
        _ = try visible(named("Общее сообщение: ХОЧУ КУШАТЬ", in: app))
    }

    func testExistingOwnedBoardAtSystemTextSize() throws {
        continueAfterFailure = false
        let app = productApplication()
        app.launch()
        do {
            stage = "system-text-existing-board-initial"
            try openOwnedBoard(in: app)
            let first = try visible(app.buttons.matching(identifier: "КУШАТЬ"))
            let details = XCTAttachment(string:
                "runnerPreferredCategory=\(UIApplication.shared.preferredContentSizeCategory.rawValue); " +
                "runnerBodyPointSize=\(UIFont.preferredFont(forTextStyle: .body).pointSize); " +
                "appFrame=\(app.frame); firstCardFrame=\(first.frame)")
            details.name = "system-text-public-geometry"
            details.lifetime = .keepAlways
            add(details)
            screenshot(app)
            try assertCardsReachableWithLargeText(in: app)
            _ = try visible(named("Общее сообщение: ХОЧУ КУШАТЬ", in: app))
            guard !app.buttons.matching(identifier: "Остановить").firstMatch.exists else { throw Unavailable() }
            stage = "system-text-all-nine-cards-reachable"
            screenshot(app)
            if UIApplication.shared.preferredContentSizeCategory.isAccessibilityCategory {
                let speak = app.buttons.matching(identifier: "Произнести")
                guard let audioBefore = try visible(speak).value as? String else { throw Unavailable() }
                let caption = try visible(app.buttons.matching(identifier: "ПИСАТЬ КАКАТЬ"))
                for _ in 0..<3 {
                    caption.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.80)).press(forDuration: 0.05,
                        thenDragTo: caption.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.48)))
                }
                stage = "system-text-caption-after-scroll"
                _ = try visible(named("Общее сообщение: ХОЧУ КУШАТЬ", in: app))
                guard !app.buttons.matching(identifier: "Остановить").firstMatch.exists,
                      (try visible(speak).value as? String) == audioBefore else { throw Unavailable() }
                screenshot(app)
            }
            try tap(named("Открыть библиотеку", in: app))
            _ = try visible(named("Ваши наборы", in: app))
        } catch {
            screenshot(app)
            XCTFail("\(stage): system text-size acceptance incomplete; do not change app scale or reset data")
        }
    }

    func testOwnedDarkThemeAndRestoreSession() throws {
        continueAfterFailure = false
        let app = productApplication()
        app.launch()
        do {
            stage = "dark-theme-original-light"
            try openSettings(in: app)
            let theme = app.switches.matching(identifier: "Тёмная тема")
            guard !(try visible(theme)).isSelected else { throw Unavailable() }
            try tap(theme)
            guard try visible(theme).isSelected else { throw Unavailable() }
            screenshot(app)
            try tap(named("Назад", in: app))
            try openOwnedBoard(in: app)
            stage = "dark-theme-board-audit"
            try assertCards(in: app)
            _ = try visible(named("Общее сообщение: ХОЧУ КУШАТЬ", in: app))
            screenshot(app)
            try app.performAccessibilityAudit(for: [.contrast, .elementDetection, .hitRegion,
                .sufficientElementDescription, .textClipped, .trait])
            try tap(named("Открыть библиотеку", in: app))
            try openSettings(in: app)
            guard try visible(theme).isSelected else { throw Unavailable() }
            try tap(theme)
            guard !(try visible(theme)).isSelected else { throw Unavailable() }
            try tap(named("Назад", in: app))
            try openOwnedBoard(in: app)
            stage = "dark-theme-original-light-restored"
            try assertCards(in: app)
            _ = try visible(named("Общее сообщение: ХОЧУ КУШАТЬ", in: app))
            screenshot(app)
        } catch {
            screenshot(app)
            XCTFail("\(stage): session-only theme check incomplete; no persisted setting save or library reset permitted")
        }
    }

    func testIsolatedRestoreReturnAndDeleteRecovery() throws {
        continueAfterFailure = false
        let environment = ProcessInfo.processInfo.environment
        guard let expected = environment["LINKA_RECOVERY_PROBE_UDID"],
              let actual = environment["SIMULATOR_UDID"], expected == actual,
              !["DD0F9A3F-DC32-4373-BA08-400FECE3A1CC", "D46F4A13-2815-41C6-B093-0548B56AFD9E"].contains(actual)
        else { throw Unavailable() }
        let app = productApplication()
        app.launch()
        do {
            stage = "recovery-probe-empty-library"
            _ = try visible(named("В библиотеке пока нет досок. Создайте доску или импортируйте файл .linka.", in: app))
            screenshot(app)
            try tap(app.buttons.matching(identifier: "Создать набор"))
            try tap(app.buttons.matching(identifier: "Создать карточку. Строка 1, столбец 1"))
            try tap(named("Пробел", in: app))
            try tap(app.buttons.matching(identifier: "Готово"))
            _ = try visible(app.buttons.matching(identifier: "Пробел. Строка 1, столбец 1"))
            try tap(app.buttons.matching(identifier: "Сохранить и выйти"))
            _ = try visible(app.buttons.matching(identifier: "Действия с набором «Новая доска»"))
            stage = "recovery-probe-sentinel-published"
            screenshot(app)
            try openTransfer(in: app)
            try revealTransfer(named("Точки восстановления нет.", in: app), in: app)
            try tapTransfer("Разрешить команды в этом экране", in: app, upwards: false)
            try tapTransfer("Выбрать резервную копию для проверки", in: app)
            stage = "recovery-probe-pick-owned-backup"
            let host = app.cells.containing(.any, identifier: "LinkaKeychainHost")
            if !host.firstMatch.waitForExistence(timeout: 5) {
                if !wait(named(localDeviceNames, in: app), timeout: 2) {
                    try tap(named(["Обзор", "Browse"], in: app))
                }
                if !wait(named(localDeviceNames, in: app), timeout: 3) {
                    try tap(named(["Обзор", "Browse"], in: app))
                }
                try tap(named(localDeviceNames, in: app))
            }
            try tap(host)
            try tap(app.cells.containing(.any, identifier: "recovery-input"))
            screenshot(app)
            try tap(app.cells.containing(NSPredicate(format: "label IN %@", ["library.linkabackup", "library"])))
            stage = "recovery-probe-restore-preview"
            try revealTransfer(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Досок: 1; черновиков: 0;")), in: app, timeout: 30)
            screenshot(app)
            try tapTransfer("Разрешить команды в этом экране", in: app, upwards: false)
            try tapTransfer("Подтверждаю замену ВСЕЙ библиотеки из резервной копии", in: app)
            stage = "recovery-probe-restored-library"
            try replacementLibrary(importedBoardName, absent: "Новая доска", in: app)
            try openOwnedBoard(in: app)
            try assertCards(in: app)
            _ = try visible(named("Общее сообщение: пусто", in: app))
            screenshot(app)
            try tap(named("Открыть библиотеку", in: app))
            try openTransfer(in: app)
            try revealTransfer(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Точка восстановления: досок 1, черновиков 0, байт медиа 0;")), in: app)
            stage = "recovery-probe-original-point-present"
            screenshot(app)
            try tapTransfer("Разрешить команды в этом экране", in: app, upwards: false)
            try tapTransfer("Проверить возврат к точке восстановления", in: app)
            try tapTransfer("Подтверждаю возврат всей библиотеки к точке восстановления", in: app)
            stage = "recovery-probe-returned-sentinel"
            try replacementLibrary("Новая доска", absent: importedBoardName, in: app)
            screenshot(app)
            try openTransfer(in: app)
            try revealTransfer(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Точка восстановления: досок 1, черновиков 0, байт медиа 3159856;")), in: app)
            try tapTransfer("Разрешить команды в этом экране", in: app, upwards: false)
            try tapTransfer("Подготовить удаление точки восстановления", in: app)
            stage = "recovery-probe-delete-confirmation"
            try revealTransfer(app.buttons.matching(identifier: "Подтверждаю безвозвратное удаление точки восстановления"), in: app)
            screenshot(app)
            try tapTransfer("Подтверждаю безвозвратное удаление точки восстановления", in: app)
            stage = "recovery-probe-point-deleted"
            try revealTransfer(named("Операция завершена.", in: app), in: app, timeout: 30)
            try revealTransfer(named("Точки восстановления нет.", in: app), in: app, upwards: false)
            screenshot(app)
            try tap(named("Назад", in: app))
            app.terminate()
            app.launch()
            _ = try visible(app.buttons.matching(identifier: "Действия с набором «Новая доска»"))
            guard !app.buttons.matching(identifier: "Действия с набором «\(importedBoardName)»").firstMatch.exists else { throw Unavailable() }
            stage = "recovery-probe-final-cold-library"
            screenshot(app)
        } catch {
            screenshot(app)
            XCTFail("\(stage): isolated restore/recovery incomplete; inspect state before replaying any destructive command")
        }
    }

    private func openTransfer(in app: XCUIApplication) throws {
        try tap(named("Меню библиотеки", in: app))
        try tap(named("Импорт, резервная копия и восстановление", in: app))
        _ = try visible(named("Файлы и восстановление", in: app))
    }

    private func replacementLibrary(_ name: String, absent: String, in app: XCUIApplication) throws {
        let menu = app.buttons.matching(identifier: "Действия с набором «\(name)»")
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            menu.allElementsBoundByIndex.contains { $0.exists && $0.isHittable }
        }, object: nil)
        guard XCTWaiter.wait(for: [ready], timeout: 30) == .completed,
              !app.buttons.matching(identifier: "Действия с набором «\(absent)»").firstMatch.exists
        else { throw Unavailable() }
    }

    func testOwnedLargeTextAndContrastRestoreOriginalSettings() throws {
        continueAfterFailure = false
        let app = productApplication()
        app.launch()
        do {
            stage = "settings-original-scale1-contrast-off"
            try openSettings(in: app)
            let contrast = try visible(app.switches.matching(identifier: "Высокий контраст"))
            guard !contrast.isSelected else { throw Unavailable() }
            try replaceScale("1.0", with: "2", in: app)
            try tap(app.switches.matching(identifier: "Высокий контраст"))
            guard try visible(app.switches.matching(identifier: "Высокий контраст")).isSelected else { throw Unavailable() }
            stage = "settings-save-scale2-contrast-on"
            try saveSettingsAndReadBack("2.0", in: app)
            guard try visible(app.switches.matching(identifier: "Высокий контраст")).isSelected else { throw Unavailable() }
            try finishSavedLargeTextCase(in: app)
        } catch {
            screenshot(app)
            XCTFail("\(stage): original settings were scale1.0/contrastOff; stop and inspect actual state, no blind repeat/save/reset")
        }
    }

    func testResumeSavedLargeTextContrastAndRestoreOriginal() throws {
        continueAfterFailure = false
        let app = productApplication()
        app.launch()
        do {
            stage = "settings-resume-saved-scale2-contrast-on"
            try openSettings(in: app)
            try revealTransfer(app.staticTexts.matching(identifier: "2.0"), in: app)
            guard try visible(app.switches.matching(identifier: "Высокий контраст")).isSelected else { throw Unavailable() }
            screenshot(app)
            try finishSavedLargeTextCase(in: app)
        } catch {
            screenshot(app)
            XCTFail("\(stage): resume-only settings flow incomplete; inspect state before any further save")
        }
    }

    private func finishSavedLargeTextCase(in app: XCUIApplication) throws {
        app.terminate()
        app.launch()
        stage = "settings-large-text-contrast-cold-board"
        try openOwnedBoard(in: app)
        try assertCardsReachableWithLargeText(in: app)
        _ = try visible(named("Общее сообщение: ХОЧУ КУШАТЬ", in: app))
        let speak = app.buttons.matching(identifier: "Произнести")
        guard let audioBefore = try visible(speak).value as? String else { throw Unavailable() }
        screenshot(app)
        let back = try visible(app.buttons.matching(identifier: "Открыть библиотеку")).frame
        let settings = try visible(app.buttons.matching(identifier: "Настройки доски")).frame
        let titleX = (back.maxX + settings.minX) / 2
        let origin = app.coordinate(withNormalizedOffset: .zero)
        // Target the title between its real controls, not XCTest's app-sized ScrollView ancestor.
        origin.withOffset(CGVector(dx: titleX, dy: back.midY + back.height * 0.4)).press(forDuration: 0.05,
            thenDragTo: origin.withOffset(CGVector(dx: titleX, dy: back.midY - back.height * 0.4)))
        let longCaption = try visible(app.buttons.matching(identifier: "ПИСАТЬ КАКАТЬ"))
        for _ in 0..<3 {
            // The bottom card inset is not part of the text viewport.
            longCaption.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.80)).press(forDuration: 0.05,
                thenDragTo: longCaption.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.48)))
        }
        stage = "settings-large-text-caption-scroll"
        _ = try visible(named("Общее сообщение: ХОЧУ КУШАТЬ", in: app))
        guard !app.buttons.matching(identifier: "Остановить").firstMatch.exists,
              (try visible(speak).value as? String) == audioBefore else { throw Unavailable() }
        screenshot(app)
        try tap(named("Открыть библиотеку", in: app))

        stage = "settings-restore-original-through-ui"
        try openSettings(in: app)
        guard try visible(app.switches.matching(identifier: "Высокий контраст")).isSelected else { throw Unavailable() }
        try replaceScale("2.0", with: "1.0", in: app)
        try tap(app.switches.matching(identifier: "Высокий контраст"))
        try saveSettingsAndReadBack("1.0", in: app)
        guard !(try visible(app.switches.matching(identifier: "Высокий контраст"))).isSelected else { throw Unavailable() }
        app.terminate()
        app.launch()
        stage = "settings-original-restored-cold-board"
        try openOwnedBoard(in: app)
        try assertCards(in: app)
        _ = try visible(named("Общее сообщение: ХОЧУ КУШАТЬ", in: app))
        screenshot(app)
    }

    private func openSettings(in app: XCUIApplication) throws {
        _ = try visible(named("Ваши наборы", in: app))
        try tap(named("Меню библиотеки", in: app))
        try tap(named("Настройки", in: app))
        try revealTransfer(named("Масштаб текста", in: app), in: app)
    }

    private func replaceScale(_ previous: String, with next: String, in app: XCUIApplication) throws {
        try tap(named("Масштаб текста", in: app))
        let field = try visible(app.textViews)
        guard app.textViews.count == 1, field.label == previous || (field.value as? String) == previous else { throw Unavailable() }
        field.tap()
        guard app.keyboards.firstMatch.waitForExistence(timeout: 5) else { throw Unavailable() }
        app.typeKey(XCUIKeyboardKey.downArrow.rawValue, modifierFlags: .command)
        app.typeKey(XCUIKeyboardKey.upArrow.rawValue, modifierFlags: [.command, .shift])
        app.typeText(next)
        _ = try visible(app.textViews.matching(identifier: next))
        try tap(app.buttons.matching(identifier: "Применить"))
    }

    private func saveSettingsAndReadBack(_ scale: String, in app: XCUIApplication) throws {
        try revealTransfer(app.buttons.matching(identifier: "Сохранить настройки"), in: app)
        try tap(app.buttons.matching(identifier: "Сохранить настройки"))
        let authorize = app.buttons.matching(identifier: "Разрешить команды в этом экране")
        if authorize.firstMatch.exists {
            try revealTransfer(authorize, in: app)
            try tap(authorize)
            try tap(app.buttons.matching(identifier: "Сохранить настройки"))
        }
        try tap(named("Назад", in: app))
        try openSettings(in: app)
        try revealTransfer(app.staticTexts.matching(identifier: scale), in: app)
        screenshot(app)
    }

    private func chooseOwnedOutputFolder(in app: XCUIApplication) throws {
        let folder = app.cells.containing(.any, identifier: "u14-fixtures")
        let host = app.cells.containing(.any, identifier: "LinkaKeychainHost")
        if !folder.firstMatch.waitForExistence(timeout: 5) {
            if !host.firstMatch.waitForExistence(timeout: 5) {
                try tap(named(localDeviceNames, in: app))
            }
            try tap(host)
        }
        try tap(folder)
        screenshot(app)
        try tap(app.buttons.matching(NSPredicate(format: "label IN %@ OR identifier IN %@",
            ["Открыть", "Open"], ["Открыть", "Open"])))
        _ = try visible(named("Файлы и восстановление", in: app))
    }

    func testExistingPhrasePlaybackStopAndReplay() throws {
        continueAfterFailure = false
        let app = productApplication()
        app.launch()
        do {
            stage = "audio-existing-phrase"
            try openOwnedBoard(in: app)
            try assertCards(in: app)
            _ = try visible(named("Общее сообщение: ХОЧУ КУШАТЬ", in: app))

            stage = "audio-first-completion"
            try tap(app.buttons.matching(identifier: "Произнести"))
            try audioState("Произнести", "Воспроизведение завершено.", in: app)
            screenshot(app)

            stage = "audio-stop"
            try tap(app.buttons.matching(identifier: "Произнести"))
            try audioState("Остановить", "Сообщение звучит.", in: app)
            // The toolbar does not move; do not spend the short recording waiting for a stable frame.
            app.buttons.matching(identifier: "Остановить").element.tap()
            try audioState("Произнести", "Воспроизведение остановлено.", in: app)
            screenshot(app)

            stage = "audio-replay-completion"
            try tap(app.buttons.matching(identifier: "Произнести"))
            try audioState("Произнести", "Воспроизведение завершено.", in: app)
            _ = try visible(named("Общее сообщение: ХОЧУ КУШАТЬ", in: app))
            screenshot(app)

            try tap(named("Открыть библиотеку", in: app))
            try openOwnedBoard(in: app)
            stage = "audio-reopened-phrase"
            _ = try visible(named("Общее сообщение: ХОЧУ КУШАТЬ", in: app))
            try audioState("Произнести", "Воспроизведение завершено.", in: app)
            screenshot(app)
        } catch {
            screenshot(app)
            XCTFail("\(stage): real playback/Stop/replay did not reach the required state; no reset or reimport")
        }
    }

    private func audioState(_ button: String, _ status: String, in app: XCUIApplication) throws {
        let reached = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            app.buttons.matching(identifier: button).allElementsBoundByIndex.contains {
                $0.exists && $0.isHittable && $0.isEnabled && ($0.value as? String) == status
            }
        }, object: nil)
        guard XCTWaiter.wait(for: [reached], timeout: 20) == .completed else {
            let labels = ["Произнести", "Остановить"]
            let controls = app.buttons.matching(NSPredicate(format: "label IN %@ OR identifier IN %@", labels, labels))
            let diagnostic = XCTAttachment(string: controls.allElementsBoundByIndex.map {
                "\($0.label): \(String(describing: $0.value).prefix(256))"
            }.joined(separator: "\n"))
            diagnostic.name = "audio-control-state"
            diagnostic.lifetime = .keepAlways
            add(diagnostic)
            throw Unavailable()
        }
    }

    private func composeAndReopen(in app: XCUIApplication) throws {
        try assertCards(in: app)
        if wait(named("Общее сообщение: пусто", in: app), timeout: 2) {
            try tap(app.buttons.matching(identifier: "ХОЧУ"))
            _ = try visible(named("Общее сообщение: ХОЧУ", in: app))
        }
        if wait(named("Общее сообщение: ХОЧУ", in: app), timeout: 2) {
            try tap(app.buttons.matching(identifier: "КУШАТЬ"))
        }
        stage = "13-filled-message"
        _ = try visible(named("Общее сообщение: ХОЧУ КУШАТЬ", in: app))
        guard !app.buttons.matching(identifier: "Остановить").allElementsBoundByIndex.contains(where: { $0.exists && $0.isHittable }) else {
            throw Unavailable()
        }
        screenshot(app)
        try tap(named("Открыть библиотеку", in: app))
        stage = "14-library-return"
        _ = try visible(named("Ваши наборы", in: app))
        screenshot(app)
        try openOwnedBoard(in: app)
        stage = "15-reopened-filled-message"
        try assertCards(in: app)
        _ = try visible(named("Общее сообщение: ХОЧУ КУШАТЬ", in: app))
        screenshot(app)
    }

    private func openOwnedBoard(in app: XCUIApplication) throws {
        _ = try visible(named("Ваши наборы", in: app))
        // Use the unique tile's actual menu, not the toolbar title or a search field named ХОЧУ.
        let menu = app.buttons.matching(identifier: "Действия с набором «\(importedBoardName)»")
        _ = try visible(menu)
        guard menu.count == 1 else { throw Unavailable() }
        try tap(menu)
        try tap(named("Открыть", in: app))
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

    private func assertCardsReachableWithLargeText(in app: XCUIApplication) throws {
        let first = try visible(app.buttons.matching(identifier: "КУШАТЬ")).frame
        let second = try visible(app.buttons.matching(identifier: "ПИТЬ")).frame
        let gutterX = (first.maxX + second.minX) / 2
        for label in cards {
            let query = app.buttons.matching(identifier: label)
            for _ in 0..<5 {
                if query.allElementsBoundByIndex.contains(where: { $0.exists && $0.isHittable && app.frame.contains($0.frame) }) { break }
                let origin = app.coordinate(withNormalizedOffset: .zero)
                origin.withOffset(CGVector(dx: gutterX, dy: app.frame.maxY - 60)).press(forDuration: 0.05,
                    thenDragTo: origin.withOffset(CGVector(dx: gutterX, dy: first.midY)))
            }
            guard query.allElementsBoundByIndex.contains(where: { $0.exists && $0.isHittable && app.frame.contains($0.frame) }) else {
                throw Unavailable()
            }
        }
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
        var lastFrame: CGRect?
        var unchangedSince = ProcessInfo.processInfo.systemUptime
        let enabled = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            guard let element = query.allElementsBoundByIndex.first(where: { $0.exists && $0.isHittable && $0.isEnabled }) else {
                lastFrame = nil
                return false
            }
            let frame = element.frame
            if frame != lastFrame {
                lastFrame = frame
                unchangedSince = ProcessInfo.processInfo.systemUptime
                return false
            }
            return ProcessInfo.processInfo.systemUptime - unchangedSince >= 0.25
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
            let scroll = app.scrollViews.allElementsBoundByIndex.filter { $0.exists && $0.isHittable }.max {
                $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height
            }
            guard let scroll = scroll else { throw Unavailable() }
            if upwards { scroll.swipeUp() } else { scroll.swipeDown() }
            if wait(query, timeout: 2) { return }
        }
        throw Unavailable()
    }

    private func screenshot(_ app: XCUIApplication) {
        guard !app.secureTextFields.allElementsBoundByIndex.contains(where: { $0.exists }) else { return }
        // App-scoped XCTest capture crops against stale portrait coordinates after rotation.
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = stage
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
