import SharedAppHostTests
import Foundation
import XCTest

final class AudioTests: XCTestCase {
    func testCompatibleRuntimeVersion() {
        XCTAssertEqual(AudioAppHostChecks.shared.runtimeVersion(),
            ProcessInfo.processInfo.environment["LINKA_EXPECTED_FFMPEG_VERSION"] ?? "8.1.2")
    }

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
