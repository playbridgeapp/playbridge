import XCTest
import UIKit

/// Drives the shipping SwiftUI app in an isolated simulator installation.
final class BridgedAppUITests: XCTestCase {
    override func setUp() {
        super.setUp()
        XCUIDevice.shared.orientation = .portrait
    }

    override func tearDown() {
        XCUIDevice.shared.orientation = .portrait
        super.tearDown()
    }

    func testInstallOpenRemoteReturnAndRemove() {
        continueAfterFailure = false
        let base = ProcessInfo.processInfo.environment["BROWSER_FIXTURE"]!
        let app = XCUIApplication(bundleIdentifier: "com.playbridge.bridged-app-ui-checks")
        app.launch()
        let address = app.textFields["Search or enter address"]
        XCTAssertTrue(address.waitForExistence(timeout: 20), app.debugDescription)
        address.tap()
        address.typeText(base + "/detection\n")
        let menu = app.buttons["Browser menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 10))
        menu.tap()
        let install = app.buttons["Add Bridged App"]
        XCTAssertTrue(install.waitForExistence(timeout: 15), app.debugDescription)
        install.tap()
        XCTAssertTrue(app.staticTexts["Fixture added to Dashboard"].waitForExistence(timeout: 5))
        app.buttons["Done"].tap()
        app.buttons["Dashboard"].tap()
        // Wait for the incoming Dashboard before tapping its page control.
        // The control can appear in the accessibility tree during the transition.
        XCTAssertTrue(app.buttons["Apps and history tiles"].waitForExistence(timeout: 10))
        app.buttons["Apps and history tiles"].tap()
        let tile = app.buttons["bridged-app-\(base)/"]
        XCTAssertTrue(tile.waitForExistence(timeout: 10), app.debugDescription)
        tile.tap()
        let edge = app.buttons["Bridged app menu"]
        XCTAssertTrue(edge.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertFalse(app.textFields["Search or enter address"].exists, "App mode retained browser chrome")
        let page = app.webViews.firstMatch
        XCTAssertTrue(page.waitForExistence(timeout: 10))
        let screen = app.frame
        XCTAssertEqual(page.frame.minX, screen.minX, accuracy: 1, "App retained a left safe-area band")
        XCTAssertEqual(page.frame.maxX, screen.maxX, accuracy: 1, "App retained a right safe-area band")
        XCTAssertEqual(page.frame.minY, screen.minY, accuracy: 1, "App retained the top safe-area band")
        XCTAssertEqual(page.frame.maxY, screen.maxY, accuracy: 1, "App retained the bottom safe-area band")
        XCTAssertEqual(edge.frame.maxX, screen.maxX, accuracy: 1, "Portrait edge button is inset")
        for orientation in [UIDeviceOrientation.landscapeLeft, .landscapeRight] {
            XCUIDevice.shared.orientation = orientation
            let rotationFinished = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                app.frame.width > app.frame.height &&
                abs(page.frame.width - app.frame.width) < 1 &&
                abs(page.frame.height - app.frame.height) < 1 &&
                (orientation == .landscapeLeft
                    ? abs(edge.frame.maxX - app.frame.maxX) < 1
                    : abs(edge.frame.minX - app.frame.minX) < 1)
            }, object: app)
            XCTAssertEqual(XCTWaiter.wait(for: [rotationFinished], timeout: 10), .completed,
                           "Landscape layout did not place the button opposite the camera: app \(app.frame), page \(page.frame), edge \(edge.frame)")
            let rotated = app.frame
            XCTAssertGreaterThan(rotated.width, rotated.height, "App did not rotate to landscape")
            XCTAssertEqual(page.frame.minX, rotated.minX, accuracy: 1, "Landscape page left edge is inset")
            XCTAssertEqual(page.frame.maxX, rotated.maxX, accuracy: 1, "Landscape page right edge is inset")
            if orientation == .landscapeLeft {
                XCTAssertEqual(edge.frame.maxX, rotated.maxX, accuracy: 1, "Camera on left: button must be on right")
            } else {
                XCTAssertEqual(edge.frame.minX, rotated.minX, accuracy: 1, "Camera on right: button must be on left")
            }
            XCTAssertTrue(edge.isHittable, "Landscape edge button cannot be tapped")
            edge.tap()
            app.buttons["Reload"].tap()
        }
        XCUIDevice.shared.orientation = .portrait
        func waitForEdge(_ visible: Bool) {
            let changed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                edge.exists == visible
            }, object: app)
            XCTAssertEqual(XCTWaiter.wait(for: [changed], timeout: 10), .completed,
                           "Edge menu did not become \(visible ? "visible" : "hidden")")
        }
        let inline = page.buttons["Play inline canvas"]
        XCTAssertTrue(inline.waitForExistence(timeout: 10))
        inline.tap()
        XCTAssertTrue(edge.exists, "Inline playback hid the edge menu")
        page.buttons["Enter canvas fullscreen"].tap()
        waitForEdge(false)
        page.buttons["Pause canvas"].tap()
        XCTAssertFalse(edge.exists, "Pausing fullscreen restored the edge menu")
        page.buttons["Exit canvas fullscreen"].tap()
        waitForEdge(true)
        page.buttons["Enter canvas fullscreen"].tap()
        waitForEdge(false)
        page.buttons["Close canvas player"].tap()
        waitForEdge(true)
        edge.tap()
        app.buttons["Reload"].tap()
        XCTAssertTrue(inline.waitForExistence(timeout: 10))
        page.buttons["Enter canvas fullscreen"].tap()
        waitForEdge(false)
        page.links["Leave canvas page"].tap()
        waitForEdge(true)
        // Return to the start page for the remaining navigation checks.
        edge.tap()
        app.buttons["Back"].tap()
        XCTAssertTrue(inline.waitForExistence(timeout: 10))
        // WebKit may restore the player's fullscreen class from its page cache.
        page.buttons["Exit canvas fullscreen"].tap()
        waitForEdge(true)
        edge.tap()
        app.buttons["Remote"].tap()
        let remoteReturn = app.buttons["remote-return"]
        XCTAssertTrue(remoteReturn.waitForExistence(timeout: 10), app.debugDescription)
        remoteReturn.tap()
        XCTAssertTrue(edge.waitForExistence(timeout: 10), "Remote did not reopen app mode")
        edge.tap()
        app.buttons["Dashboard"].tap()
        app.buttons["Close dashboard"].tap()
        XCTAssertTrue(edge.waitForExistence(timeout: 10), "Closing Dashboard did not reopen app mode")
        // At the start URL, Back exits to Dashboard.
        edge.tap()
        app.buttons["Back"].tap()
        XCTAssertTrue(app.buttons["Apps and history tiles"].waitForExistence(timeout: 10))
        app.buttons["Apps and history tiles"].tap()
        tile.press(forDuration: 1.2)
        let remove = app.buttons["Remove Bridged App"]
        XCTAssertTrue(remove.waitForExistence(timeout: 5), app.debugDescription)
        remove.tap()
        app.alerts["Remove Bridged App?"].buttons["Remove"].tap()
        XCTAssertFalse(tile.exists, "Removal left the tile installed")
        app.buttons["Dashboard tiles"].tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Browser")).firstMatch.tap()
        XCTAssertTrue(app.buttons["Tabs, 1 open"].waitForExistence(timeout: 10), "App session leaked into the browser tab count")
        menu.tap()
        XCTAssertTrue(install.waitForExistence(timeout: 10), "Removal did not make the website installable again")
    }
}
