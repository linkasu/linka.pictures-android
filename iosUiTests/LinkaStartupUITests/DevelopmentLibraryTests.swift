import XCTest

final class DevelopmentLibraryTests: XCTestCase {
    private var editorStage = "editor-launch"

    func testEnglishLibraryThenRussianRelaunchWithoutWrites() {
        continueAfterFailure = false
        for (language, locale, title, menu) in [
            ("en", "en_US", "Your boards", "Library menu"),
            ("ru", "ru_RU", "Ваши наборы", "Меню библиотеки"),
        ] {
            let app = productApplication(language: language)
            XCTAssertEqual(app.launchArguments, ["-AppleLanguages", "(\(language))", "-AppleLocale", locale])
            app.launch()
            defer { app.terminate() }

            let heading = app.staticTexts.matching(identifier: title).firstMatch
            XCTAssertTrue(heading.waitForExistence(timeout: 15), "\(language): library must open without migration/recovery actions")
            XCTAssertTrue(heading.isHittable, "\(language): library title must be visible")
            XCTAssertTrue(app.buttons.matching(identifier: menu).firstMatch.exists)
            let startupFailure = app.staticTexts.matching(NSPredicate(
                format: "label BEGINSWITH %@ OR label BEGINSWITH %@ OR label BEGINSWITH %@",
                "The application could not be opened safely", "Could not safely open the app:",
                "Не удалось безопасно открыть приложение"
            ))
            XCTAssertEqual(startupFailure.count, 0, "\(language): startup failure must not be hidden")
            attachScreenshot(of: app, stage: "library-language-\(language)-read-only")
        }
    }

    func testDevelopmentLibrarySurvivesRelaunch() {
        let app = productApplication()
        app.launch()
        guard assertLibrary(in: app, stage: "Initial launch") else { return }

        app.terminate()
        app.launch()
        _ = assertLibrary(in: app, stage: "Relaunch")
    }

    func testOwnedBoardNativeInputSaveCardPublishAndRelaunch() throws {
        continueAfterFailure = false
        let name = "LINKa-EDITOR-iOS17-20261106"
        let renamed = name + "-RENAMED"
        let label = "NATIVE-CARD-17"
        let app = productApplication()
        app.launch()
        do {
            guard assertLibrary(in: app, stage: editorStage) else { return }
            guard app.buttons.matching(identifier: "Действия с набором «\(name)»").count == 0,
                  app.buttons.matching(identifier: "Действия с набором «\(renamed)»").count == 0 else {
                XCTFail("Own fixture already published; use reopen-only verification, never create another copy")
                return
            }
            editorStage = "editor-create"
            try editorTap(app.buttons.matching(identifier: "Создать набор"))
            try editorTap(editorNamed("Меню редактора", in: app))
            try editorTap(editorNamed("Параметры доски", in: app))
            try finishOwnedEditor(in: app, initialTitle: "Новая доска", name: name, label: label)
        } catch {
            if !app.secureTextFields.allElementsBoundByIndex.contains(where: { $0.exists }) {
                attachScreenshot(of: app, stage: editorStage)
            }
            XCTFail("\(editorStage): native editor flow incomplete; preserve own draft/board, no reset or blind rerun")
        }
    }

    func testResumeOwnedEditorDraftNativeInputAndFinish() throws {
        continueAfterFailure = false
        let name = "LINKa-EDITOR-iOS17-20261106"
        let app = productApplication()
        app.launch()
        do {
            editorStage = "editor-resume-existing-own-draft"
            guard assertLibrary(in: app, stage: editorStage) else { return }
            try editorTap(editorNamed("Меню библиотеки", in: app))
            try editorTap(editorNamed("Сохранённые черновики", in: app))
            let query = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@ AND label CONTAINS %@", "Продолжить «", name))
            let resume = try editorVisible(query)
            guard query.count == 1, resume.label.hasSuffix("»") else { throw EditorUnavailable() }
            let initialTitle = String(resume.label.dropFirst("Продолжить «".count).dropLast())
            resume.tap()
            try editorTap(editorNamed("Меню редактора", in: app))
            try editorTap(editorNamed("Параметры доски", in: app))
            try finishOwnedEditor(in: app, initialTitle: initialTitle, name: name, label: "NATIVE-CARD-17")
        } catch {
            if !app.secureTextFields.allElementsBoundByIndex.contains(where: { $0.exists }) {
                attachScreenshot(of: app, stage: editorStage)
            }
            XCTFail("\(editorStage): resume-only flow incomplete; never create a replacement or clear the library")
        }
    }

    func testResumeOwnedOpenCardFormAndFinish() throws {
        continueAfterFailure = false
        let name = "LINKa-EDITOR-iOS17-20261106"
        let app = productApplication()
        app.launch()
        do {
            editorStage = "editor-resume-owned-open-card"
            guard assertLibrary(in: app, stage: editorStage) else { return }
            try editorTap(editorNamed("Меню библиотеки", in: app))
            try editorTap(editorNamed("Сохранённые черновики", in: app))
            try editorTap(app.buttons.matching(identifier: "Продолжить «\(name)»"))
            _ = try editorVisible(editorNamed("Тип карточки", in: app))
            _ = try editorVisible(app.textViews.matching(identifier: "NATIVE-CARD-17"))
            try finishCardAndPublish(in: app, name: name, label: "NATIVE-CARD-17")
        } catch {
            if !app.secureTextFields.allElementsBoundByIndex.contains(where: { $0.exists }) {
                attachScreenshot(of: app, stage: editorStage)
            }
            XCTFail("\(editorStage): preserve existing open card; never replace or discard the draft")
        }
    }

    func testRenameOwnedPublishedBoardAndColdReopen() throws {
        continueAfterFailure = false
        let app = productApplication()
        app.launch()
        do {
            editorStage = "editor-existing-published-board"
            guard assertLibrary(in: app, stage: editorStage) else { return }
            try renameAndColdReopen(in: app, name: "LINKa-EDITOR-iOS17-20261106", label: "NATIVE-CARD-17")
        } catch {
            if !app.secureTextFields.allElementsBoundByIndex.contains(where: { $0.exists }) {
                attachScreenshot(of: app, stage: editorStage)
            }
            XCTFail("\(editorStage): published board preserved; use rename/reopen-only, never create another board")
        }
    }

    func testEditOwnedPublishedCardCancelAndKeepDraft() throws {
        continueAfterFailure = false
        let name = "LINKa-EDITOR-iOS17-20261106-RENAMED"
        let label = "NATIVE-CARD-17"
        let app = productApplication()
        app.launch()
        do {
            editorStage = "editor-existing-published-edit"
            guard assertLibrary(in: app, stage: editorStage) else { return }
            try editorTap(app.buttons.matching(identifier: "Действия с набором «\(name)»"))
            try editorTap(editorNamed("Редактировать", in: app))
            _ = try editorVisible(app.buttons.matching(identifier: "\(label). Строка 1, столбец 1"))
            attachScreenshot(of: app, stage: editorStage)
            try editorTap(app.buttons.matching(identifier: "\(label). Строка 1, столбец 1"))
            editorStage = "editor-existing-card-cancel-input"
            try replaceEditorText(label, with: "CANCEL-CHECK-17", in: app)
            if app.keyboards.firstMatch.exists {
                try editorTap(app.keyboards.descendants(matching: .any).matching(NSPredicate(
                    format: "label IN %@ OR identifier IN %@", ["Готово", "Done"], ["Готово", "Done"]
                )))
            }
            attachScreenshot(of: app, stage: editorStage)
            let cancel = app.buttons.matching(identifier: "Отмена")
            try editorReveal(cancel, in: app)
            try editorTap(cancel)
            editorStage = "editor-original-card-after-cancel"
            _ = try editorVisible(app.buttons.matching(identifier: "\(label). Строка 1, столбец 1"))
            guard !app.buttons.matching(identifier: "CANCEL-CHECK-17. Строка 1, столбец 1").firstMatch.exists else { throw EditorUnavailable() }
            attachScreenshot(of: app, stage: editorStage)
            try editorTap(editorNamed("Меню редактора", in: app))
            try editorTap(editorNamed("Состояние черновика", in: app))
            _ = try editorVisible(editorNamed("Черновик сохранён на устройстве", in: app))
            try editorTap(editorNamed("Понятно", in: app))
            try editorTap(editorNamed("Назад с сохранённым черновиком", in: app))
            try editorOpen(name, in: app)
            editorStage = "editor-published-card-unchanged-after-cancel"
            _ = try editorVisible(app.buttons.matching(identifier: label))
            _ = try editorVisible(editorNamed("Общее сообщение: ХОЧУ КУШАТЬ", in: app))
            attachScreenshot(of: app, stage: editorStage)
        } catch {
            if !app.secureTextFields.allElementsBoundByIndex.contains(where: { $0.exists }) {
                attachScreenshot(of: app, stage: editorStage)
            }
            XCTFail("\(editorStage): keep owned draft/board and inspect; do not publish, delete or recreate to force success")
        }
    }

    func testResumeOwnedChangedCardFormCancelWithoutPublishing() throws {
        continueAfterFailure = false
        let name = "LINKa-EDITOR-iOS17-20261106-RENAMED"
        let label = "NATIVE-CARD-17"
        let app = productApplication()
        app.launch()
        do {
            editorStage = "editor-resume-own-changed-card-form"
            guard assertLibrary(in: app, stage: editorStage) else { return }
            try editorTap(editorNamed("Меню библиотеки", in: app))
            try editorTap(editorNamed("Сохранённые черновики", in: app))
            try editorTap(app.buttons.matching(identifier: "Продолжить «\(name)»"))
            _ = try editorVisible(editorNamed("Тип карточки", in: app))
            let field = try editorVisible(app.textViews)
            guard app.textViews.count == 1, field.label != label else { throw EditorUnavailable() }
            attachScreenshot(of: app, stage: editorStage)
            try editorTap(app.buttons.matching(identifier: "Отмена"))
            editorStage = "editor-cancel-restored-original-draft-card"
            _ = try editorVisible(app.buttons.matching(identifier: "\(label). Строка 1, столбец 1"))
            try editorTap(editorNamed("Меню редактора", in: app))
            try editorTap(editorNamed("Состояние черновика", in: app))
            _ = try editorVisible(editorNamed("Черновик сохранён на устройстве", in: app))
            attachScreenshot(of: app, stage: editorStage)
            try editorTap(editorNamed("Понятно", in: app))
            try editorTap(editorNamed("Назад с сохранённым черновиком", in: app))
            try editorOpen(name, in: app)
            editorStage = "editor-cancel-published-card-and-message-unchanged"
            _ = try editorVisible(app.buttons.matching(identifier: label))
            _ = try editorVisible(editorNamed("Общее сообщение: ХОЧУ КУШАТЬ", in: app))
            attachScreenshot(of: app, stage: editorStage)
        } catch {
            if !app.secureTextFields.allElementsBoundByIndex.contains(where: { $0.exists }) {
                attachScreenshot(of: app, stage: editorStage)
            }
            XCTFail("\(editorStage): do not publish or recreate the own draft to force cancellation proof")
        }
    }

    func testOwnedSavedDraftNativeKeyboardSelectionProbe() throws {
        continueAfterFailure = false
        let name = "LINKa-EDITOR-iOS17-20261106-RENAMED"
        let label = "NATIVE-CARD-17"
        let app = productApplication()
        func captureField(_ stage: String) throws {
            editorStage = stage
            let field = try editorVisible(app.textViews)
            guard app.textViews.count == 1 else { throw EditorUnavailable() }
            let frame = field.frame
            let data = try JSONSerialization.data(withJSONObject: [
                "stage": stage,
                "label": field.label,
                "value": field.value as? String ?? "",
                "frame": ["x": frame.origin.x, "y": frame.origin.y,
                          "width": frame.width, "height": frame.height],
            ], options: [.prettyPrinted, .sortedKeys])
            let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.json")
            attachment.name = stage + "-field"
            attachment.lifetime = .keepAlways
            add(attachment)
            attachScreenshot(of: app, stage: stage)
            guard field.label == label,
                  (field.value as? String ?? "").isEmpty || field.value as? String == label
            else { throw EditorUnavailable() }
        }
        app.launch()
        do {
            editorStage = "selection-probe-v2-library"
            guard assertLibrary(in: app, stage: editorStage) else { return }
            try editorTap(editorNamed("Меню библиотеки", in: app))
            try editorTap(editorNamed("Сохранённые черновики", in: app))
            try editorTap(app.buttons.matching(identifier: "Продолжить «\(name)»"))
            if !editorNamed("Тип карточки", in: app).firstMatch.exists {
                try editorTap(app.buttons.matching(identifier: "\(label). Строка 1, столбец 1"))
            }
            try captureField("selection-probe-v2-original")
            let field = try editorVisible(app.textViews.matching(identifier: label))
            editorStage = "selection-probe-v2-keyboard-caret"
            field.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
            guard app.keyboards.firstMatch.waitForExistence(timeout: 5) else { throw EditorUnavailable() }
            try captureField("selection-probe-v2-keyboard-caret")
            editorStage = "selection-probe-v2-command-down"
            app.typeKey(XCUIKeyboardKey.downArrow.rawValue, modifierFlags: .command)
            try captureField(editorStage)
            editorStage = "selection-probe-v2-command-shift-up"
            app.typeKey(XCUIKeyboardKey.upArrow.rawValue, modifierFlags: [.command, .shift])
            try captureField(editorStage)
            editorStage = "selection-probe-v2-command-a"
            app.typeKey("a", modifierFlags: .command)
            try captureField(editorStage)
            editorStage = "selection-probe-v2-cancel-original-preserved"
            try editorTap(app.buttons.matching(identifier: "Отмена"))
            let original = try editorVisible(app.buttons.matching(identifier: "\(label). Строка 1, столбец 1"))
            XCTAssertEqual(original.label, "\(label). Строка 1, столбец 1")
            XCTAssertFalse(editorNamed("Тип карточки", in: app).firstMatch.exists)
            attachScreenshot(of: app, stage: editorStage)
            try editorTap(editorNamed("Меню редактора", in: app))
            try editorTap(editorNamed("Состояние черновика", in: app))
            _ = try editorVisible(editorNamed("Черновик сохранён на устройстве", in: app))
            attachScreenshot(of: app, stage: "selection-probe-v2-draft-saved")
            try editorTap(editorNamed("Понятно", in: app))
            try editorTap(editorNamed("Назад с сохранённым черновиком", in: app))
            _ = assertLibrary(in: app, stage: "selection-probe-v2-returned-library")
        } catch {
            attachScreenshot(of: app, stage: editorStage + "-stopped")
            XCTFail("\(editorStage): stop and preserve actual form; no replacement input, save, publish or reset")
        }
    }

    func testOwnedCardReplacementThroughSystemKeyboardThenCancel() throws {
        try verifyOwnedCardSelectionAndCancel { app in
            var replacement = ""
            for key in ["ф", "ы", "в"] {
                let letter = try editorVisible(app.keyboards.keys.matching(NSPredicate(format: "label ==[c] %@", key)))
                replacement += letter.label
                letter.tap()
                _ = try editorVisible(app.textViews.matching(identifier: replacement))
            }
            return replacement
        }
    }

    func testOwnedCardReplacementThroughHardwareKeyEventsThenCancel() throws {
        try verifyOwnedCardSelectionAndCancel { app in
            for (key, expected) in [("1", "1"), ("2", "12"), (XCUIKeyboardKey.delete.rawValue, "1")] {
                app.typeKey(key, modifierFlags: [])
                _ = try editorVisible(app.textViews.matching(identifier: expected))
            }
            app.typeKey("a", modifierFlags: .command)
            app.typeKey("3", modifierFlags: [])
            _ = try editorVisible(app.textViews.matching(identifier: "3"))
            return "3"
        }
    }

    func testOwnedCardNativeBackspaceAndHardwareSelectAllThenCancel() throws {
        try verifyOwnedCardSelectionAndCancel { app in
            for (key, expected) in [("1", "1"), ("2", "12")] {
                app.typeKey(key, modifierFlags: [])
                _ = try editorVisible(app.textViews.matching(identifier: expected))
            }
            try editorTap(app.keyboards.keys.matching(NSPredicate(format: "label IN %@ OR identifier IN %@",
                ["delete", "Delete", "Удалить", "⌫"], ["delete", "Delete", "Удалить", "⌫"])))
            _ = try editorVisible(app.textViews.matching(identifier: "1"))
            app.typeKey("a", modifierFlags: .command)
            app.typeKey("3", modifierFlags: [])
            _ = try editorVisible(app.textViews.matching(identifier: "3"))
            return "3"
        }
    }

    private func verifyOwnedCardSelectionAndCancel(_ replace: (XCUIApplication) throws -> String) throws {
        continueAfterFailure = false
        let name = "LINKa-EDITOR-iOS17-20261106-RENAMED"
        let original = "NATIVE-CARD-17"
        let app = productApplication()
        app.launch()
        do {
            editorStage = "system-selection-open-existing-draft"
            guard assertLibrary(in: app, stage: editorStage) else { return }
            try editorTap(editorNamed("Меню библиотеки", in: app))
            try editorTap(editorNamed("Сохранённые черновики", in: app))
            try editorTap(app.buttons.matching(identifier: "Продолжить «\(name)»"))
            if editorNamed("Тип карточки", in: app).firstMatch.exists,
               ["NANCEL-CHECK-17ATIVE-CARD-17", "12"].contains(app.textViews.firstMatch.label) {
                try editorTap(app.buttons.matching(identifier: "Отмена"))
                _ = try editorVisible(app.buttons.matching(identifier: "\(original). Строка 1, столбец 1"))
            }
            if !editorNamed("Тип карточки", in: app).firstMatch.exists {
                try editorTap(app.buttons.matching(identifier: "\(original). Строка 1, столбец 1"))
            }
            let field = try editorVisible(app.textViews.matching(identifier: original))
            guard app.textViews.count == 1 else { throw EditorUnavailable() }
            field.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
            guard app.keyboards.firstMatch.waitForExistence(timeout: 5) else { throw EditorUnavailable() }
            editorStage = "system-selection-context-menu"
            let focused = try editorVisible(app.textViews.matching(identifier: original))
            focused.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.5)).doubleTap()
            attachScreenshot(of: app, stage: editorStage)
            try editorTap(app.descendants(matching: .any).matching(NSPredicate(
                format: "label IN %@ OR identifier IN %@", ["Выбрать всё", "Выбрать все", "Select All"],
                ["Выбрать всё", "Выбрать все", "Select All"])))
            editorStage = "system-selection-before-replacement"
            attachScreenshot(of: app, stage: editorStage)
            editorStage = "system-selection-replacement-result"
            let replacement = try replace(app)
            XCTAssertEqual(app.textViews.firstMatch.label, replacement)
            attachScreenshot(of: app, stage: editorStage)
            try editorTap(app.buttons.matching(identifier: "Отмена"))
            _ = try editorVisible(app.buttons.matching(identifier: "\(original). Строка 1, столбец 1"))
            try editorTap(editorNamed("Меню редактора", in: app))
            try editorTap(editorNamed("Состояние черновика", in: app))
            _ = try editorVisible(editorNamed("Черновик сохранён на устройстве", in: app))
            try editorTap(editorNamed("Понятно", in: app))
            try editorTap(editorNamed("Назад с сохранённым черновиком", in: app))
            try editorOpen(name, in: app)
            _ = try editorVisible(app.buttons.matching(identifier: original))
            _ = try editorVisible(editorNamed("Общее сообщение: ХОЧУ КУШАТЬ", in: app))
            editorStage = "system-selection-cancel-published-unchanged"
            attachScreenshot(of: app, stage: editorStage)
        } catch {
            attachScreenshot(of: app, stage: editorStage + "-stopped")
            XCTFail("\(editorStage): preserve actual form; no publish, reset or blind replacement retry")
        }
    }

    private func finishOwnedEditor(in app: XCUIApplication, initialTitle: String, name: String, label: String) throws {
        editorStage = "editor-title-native-select-all"
        try replaceEditorText(initialTitle, with: name, in: app)
        attachScreenshot(of: app, stage: editorStage)
        try editorTap(app.buttons.matching(identifier: "Готово"))

        editorStage = "editor-card-native-input"
        try editorTap(app.buttons.matching(identifier: "Создать карточку. Строка 1, столбец 1"))
        _ = try editorVisible(editorNamed("Тип карточки", in: app))
        let fields = app.textViews.allElementsBoundByIndex.filter { $0.exists && $0.isHittable && $0.isEnabled }
        guard fields.count == 1 else { throw EditorUnavailable() }
        fields[0].coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.5)).tap()
        guard app.keyboards.firstMatch.waitForExistence(timeout: 5) else { throw EditorUnavailable() }
        app.typeText(label)
        _ = try editorVisible(app.textViews.matching(identifier: label))
        try finishCardAndPublish(in: app, name: name, label: label)
    }

    private func finishCardAndPublish(in app: XCUIApplication, name: String, label: String) throws {
        if app.keyboards.firstMatch.exists {
            let keyboardDone = app.keyboards.descendants(matching: .any).matching(
                NSPredicate(format: "label IN %@ OR identifier IN %@", ["Готово", "Done"], ["Готово", "Done"])
            )
            try editorTap(keyboardDone)
        }
        if !app.buttons.matching(identifier: "Без звука ▾").firstMatch.exists {
            let speech = app.buttons.matching(identifier: "Локальный TTS ▾")
            try editorReveal(speech, in: app)
            try editorTap(speech)
            try editorTap(editorNamed("Без звука", in: app))
        }
        attachScreenshot(of: app, stage: editorStage)
        try editorTap(app.buttons.matching(identifier: "Готово"))

        editorStage = "editor-card-is-draft-only"
        _ = try editorVisible(app.buttons.matching(identifier: "\(label). Строка 1, столбец 1"))
        _ = try editorVisible(app.buttons.matching(identifier: "Сохранить и выйти"))
        attachScreenshot(of: app, stage: editorStage)
        try editorTap(app.buttons.matching(identifier: "Сохранить и выйти"))
        editorStage = "editor-published"
        _ = try editorVisible(app.buttons.matching(identifier: "Действия с набором «\(name)»"), timeout: 30)
        attachScreenshot(of: app, stage: editorStage)

        try editorOpen(name, in: app)
        _ = try editorVisible(app.buttons.matching(identifier: label))
        _ = try editorVisible(editorNamed("Общее сообщение: ХОЧУ КУШАТЬ", in: app))
        try editorTap(editorNamed("Открыть библиотеку", in: app))
        try renameAndColdReopen(in: app, name: name, label: label)
    }

    private func renameAndColdReopen(in app: XCUIApplication, name: String, label: String) throws {
        editorStage = "editor-published-rename-native-input"
        let renamed = name + "-RENAMED"
        try editorTap(app.buttons.matching(identifier: "Действия с набором «\(name)»"))
        try editorTap(editorNamed("Переименовать", in: app))
        try replaceEditorText(name, with: renamed, in: app)
        let saveName = editorNamed("Сохранить название «\(name)»", in: app)
        try editorReveal(saveName, in: app)
        try editorTap(saveName)
        _ = try editorVisible(app.buttons.matching(identifier: "Действия с набором «\(renamed)»"))
        app.terminate()
        app.launch()
        editorStage = "editor-cold-reopened"
        try editorOpen(renamed, in: app)
        _ = try editorVisible(app.buttons.matching(identifier: label))
        _ = try editorVisible(editorNamed("Общее сообщение: ХОЧУ КУШАТЬ", in: app))
        attachScreenshot(of: app, stage: editorStage)
    }

    private func replaceEditorText(_ previous: String, with text: String, in app: XCUIApplication) throws {
        let field = try editorVisible(app.textViews.matching(identifier: previous))
        field.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
        guard app.keyboards.firstMatch.waitForExistence(timeout: 5) else { throw EditorUnavailable() }
        // Darwin text mapping: document end, then select to document start; independent of keyboard alphabet.
        let focused = try editorVisible(app.textViews.matching(identifier: previous))
        focused.typeKey(XCUIKeyboardKey.downArrow.rawValue, modifierFlags: .command)
        focused.typeKey(XCUIKeyboardKey.upArrow.rawValue, modifierFlags: [.command, .shift])
        app.typeText(text)
        _ = try editorVisible(app.textViews.matching(identifier: text))
    }

    private struct EditorUnavailable: Error {}

    private func editorNamed(_ label: String, in app: XCUIApplication) -> XCUIElementQuery {
        app.descendants(matching: .any).matching(NSPredicate(format: "label == %@ OR identifier == %@", label, label))
    }

    private func editorVisible(_ query: XCUIElementQuery, timeout: TimeInterval = 15) throws -> XCUIElement {
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            query.allElementsBoundByIndex.contains { $0.exists && $0.isHittable && $0.isEnabled }
        }, object: nil)
        guard XCTWaiter.wait(for: [ready], timeout: timeout) == .completed,
              let element = query.allElementsBoundByIndex.first(where: { $0.exists && $0.isHittable && $0.isEnabled })
        else { throw EditorUnavailable() }
        return element
    }

    private func editorTap(_ query: XCUIElementQuery) throws {
        try editorVisible(query).tap()
    }

    private func editorReveal(_ query: XCUIElementQuery, in app: XCUIApplication) throws {
        for _ in 0..<4 {
            if query.allElementsBoundByIndex.contains(where: { $0.exists && $0.isHittable }) { return }
            guard let scroll = app.scrollViews.allElementsBoundByIndex.first(where: { $0.exists && $0.isHittable }) else {
                throw EditorUnavailable()
            }
            scroll.swipeUp()
        }
        throw EditorUnavailable()
    }

    private func editorOpen(_ name: String, in app: XCUIApplication) throws {
        try editorTap(app.buttons.matching(identifier: "Действия с набором «\(name)»"))
        try editorTap(editorNamed("Открыть", in: app))
    }

    private func assertLibrary(in app: XCUIApplication, stage: String) -> Bool {
        let elements: [(String, XCUIElementQuery)] = [
            ("Ваши наборы", app.staticTexts.matching(identifier: "Ваши наборы")),
            ("Меню библиотеки", app.descendants(matching: .any).matching(
                NSPredicate(format: "identifier == %@ OR label == %@", "Меню библиотеки", "Меню библиотеки")
            )),
            ("Создать набор", app.buttons.matching(identifier: "Создать набор")),
        ]

        for (label, query) in elements {
            let visible = XCTNSPredicateExpectation(
                predicate: NSPredicate { _, _ in
                    query.allElementsBoundByIndex.contains { $0.exists && $0.isHittable && $0.isEnabled }
                },
                object: nil
            )
            let result = XCTWaiter.wait(for: [visible], timeout: 15)
            if result != .completed {
                attachScreenshot(of: app, stage: stage)
                XCTFail("\(stage): library element did not become visible, hittable and enabled: \(label)")
                return false
            }
        }

        attachScreenshot(of: app, stage: stage)
        return true
    }

    private func attachScreenshot(of app: XCUIApplication, stage: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = stage
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
