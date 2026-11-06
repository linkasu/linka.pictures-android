import SharedAppHostTests
import XCTest

final class AudioTests: XCTestCase {
    func testClipCompletesThroughNativePlayerCallback() {
        let completed = expectation(description: "Native clip terminal callback and cleanup")
        AudioAppHostChecks.shared.clipCompletes { failure in
            XCTAssertNil(failure)
            completed.fulfill()
        }
        wait(for: [completed], timeout: 30)
    }

    func testInstalledVoiceSpeechCompletesThroughNativeCallback() {
        let completed = expectation(description: "Native speech terminal callback and cleanup")
        AudioAppHostChecks.shared.installedVoiceSpeechCompletes { failure in
            XCTAssertNil(failure)
            completed.fulfill()
        }
        wait(for: [completed], timeout: 30)
    }
}
