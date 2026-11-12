import XCTest
import UIKit

final class StoreScreenshotsTests: XCTestCase {
    func testCaptureStarterLibraryAndCommunicationWithoutSelection() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .landscapeLeft
        let app = productApplication()
        app.launch()
        assertLibrary(app)
        capture(app, "store-library")

        openCommunication(app)
        capture(app, "store-communication")
        XCTAssertEqual(app.buttons["Меню общения"].value as? String, "Составление сообщения")
        let page = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Страница 1 из 3")).firstMatch
        XCTAssertTrue(page.waitForExistence(timeout: 15))
        let message = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Общее сообщение: пусто")).firstMatch
        XCTAssertTrue(message.exists)
        let speak = app.buttons["Произнести"]
        XCTAssertTrue(speak.exists)
        let previous = app.buttons["Предыдущая страница"]
        let next = app.buttons["Следующая страница"]
        XCTAssertTrue(previous.exists)
        XCTAssertTrue(next.exists)
        let viewport = CGRect(x: app.frame.minX, y: speak.frame.maxY, width: app.frame.width,
                              height: min(page.frame.minY, previous.frame.minY, next.frame.minY) - speak.frame.maxY)
        let expectedCards: Set<String> = ["Да", "Нет", "Хочу", "Не хочу", "Помоги", "Больно", "Пить", "Туалет", "Хватит"]
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let cards = app.buttons.allElementsBoundByIndex.filter { viewport.contains($0.frame) && !$0.frame.isEmpty }
            return cards.count == 9 && Set(cards.map { $0.label }) == expectedCards && cards.allSatisfy {
                $0.isHittable && $0.isEnabled && $0.value as? String == "Изображение показано"
            }
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 30), .completed, "Nine fully visible decoded communication cards required")
        XCTAssertFalse(app.buttons["Остановить"].exists)
        capture(app, "store-communication-ready")
        app.buttons["Открыть библиотеку"].tap()
        assertLibrary(app)
        openCommunication(app)
        XCTAssertTrue(message.exists)
    }

    private func openCommunication(_ app: XCUIApplication) {
        app.buttons["Действия с набором «Общаюсь»"].tap()
        let open = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Открыть")).firstMatch
        XCTAssertTrue(open.waitForExistence(timeout: 15))
        open.tap()
        XCTAssertTrue(app.buttons["Меню общения"].waitForExistence(timeout: 30))
    }

    private func assertLibrary(_ app: XCUIApplication) {
        let title = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Ваши наборы")).firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 30))
        let names = ["Буквы", "Еда и питьё", "Занятия", "Общаюсь", "Самочувствие"]
        for name in names {
            XCTAssertTrue(app.buttons[name].exists)
            XCTAssertTrue(app.buttons[name].isHittable)
        }
        XCTAssertEqual(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Действия с набором «")).count, 5)
    }

    private func capture(_ app: XCUIApplication, _ name: String) {
        let png = XCTAttachment(data: XCUIScreen.main.screenshot().pngRepresentation, uniformTypeIdentifier: "public.png")
        png.name = name + ".png"
        png.lifetime = .keepAlways
        add(png)
        let tree = XCTAttachment(string: app.debugDescription)
        tree.name = name + "-ax"
        tree.lifetime = .keepAlways
        add(tree)
    }
}
