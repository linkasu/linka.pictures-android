import SharedAppHostTests
import XCTest

final class KeychainTests: XCTestCase {
    func testBytesReopenUpdateAndRevocation() {
        XCTAssertNil(KeychainAppHostChecks.shared.bytesReopenUpdateAndRevocation())
    }

    func testBoundsAndServiceValidation() {
        XCTAssertNil(KeychainAppHostChecks.shared.boundsAndServiceValidation())
    }
}
