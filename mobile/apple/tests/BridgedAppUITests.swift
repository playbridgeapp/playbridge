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

    func testDashboardReorderPopupMovesTilesAndDismisses() {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "com.playbridge.bridged-app-ui-checks")
        app.launch()
        let reorder = app.buttons["dashboard-reorder"]
        XCTAssertTrue(reorder.waitForExistence(timeout: 20), app.debugDescription)
        reorder.tap()
        let popup = app.otherElements["dashboard-reorder-popup"]
        XCTAssertTrue(popup.waitForExistence(timeout: 10), app.debugDescription)
        // SwiftUI reports its accessibility group's frame as the whole overlay.
        // The native navigation bar reports the editor's actual onscreen bounds.
        let toolbar = app.navigationBars["Reorder Tiles"]
        XCTAssertTrue(toolbar.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertGreaterThan(toolbar.frame.minX, app.frame.minX + 8, "Popup has no left margin")
        XCTAssertLessThan(toolbar.frame.maxX, app.frame.maxX - 8, "Popup has no right margin")
        XCTAssertGreaterThan(toolbar.frame.minY, app.frame.minY + 70, "Popup is not vertically inset")
        let snapshot = XCTAttachment(screenshot: app.screenshot())
        snapshot.name = "Centered dashboard reorder popup"
        snapshot.lifetime = .keepAlways
        add(snapshot)

        app.buttons["Position 1, Browser"].tap()
        XCTAssertTrue(app.buttons["2 · Connection"].waitForExistence(timeout: 5))
        app.buttons["Back"].tap()
        XCTAssertTrue(app.buttons["Position 1, Browser"].waitForExistence(timeout: 5),
                      "Cancelling exact-position selection changed the order")
        app.buttons["Position 1, Browser"].tap()
        app.buttons["2 · Connection"].tap()
        XCTAssertTrue(app.buttons["Position 2, Browser"].waitForExistence(timeout: 5))
        app.buttons["Done"].tap()
        XCTAssertFalse(popup.exists)

        reorder.tap()
        XCTAssertTrue(app.buttons["Position 2, Browser"].waitForExistence(timeout: 5))
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.5)).tap()
        XCTAssertFalse(popup.exists, "Tapping outside did not close the popup")
        app.terminate()
        app.launch()
        XCTAssertTrue(reorder.waitForExistence(timeout: 20))
        reorder.tap()
        XCTAssertTrue(app.buttons["Position 2, Browser"].waitForExistence(timeout: 5),
                      "Popup order did not persist across relaunch")
        // The suite shares its isolated app installation. Restore the default order
        // so the following install/reorder test still starts with Connection at #2.
        app.buttons["Position 2, Browser"].tap()
        app.buttons["1 · Connection"].tap()
        XCTAssertTrue(app.buttons["Position 1, Browser"].waitForExistence(timeout: 5))
        app.buttons["Done"].tap()
    }

    func testColdLaunchStartsOnDashboardAndKeepsBrowserTabs() {
        continueAfterFailure = false
        let base = ProcessInfo.processInfo.environment["BROWSER_FIXTURE"]!
        let app = XCUIApplication(bundleIdentifier: "com.playbridge.bridged-app-ui-checks")
        app.launch()
        let dashboard = app.buttons["dashboard-reorder"]
        XCTAssertTrue(dashboard.waitForExistence(timeout: 20), app.debugDescription)
        let address = app.textFields["Search or enter address"]
        XCTAssertFalse(address.exists, "Fresh launch opened Browser")
        app.buttons["dashboard-browser"].tap()
        XCTAssertTrue(address.waitForExistence(timeout: 10))
        address.tap()
        address.typeText(base + "/detection\n")
        XCTAssertTrue(app.buttons["Browser menu"].waitForExistence(timeout: 10))
        app.buttons["Dashboard"].tap()
        XCTAssertTrue(dashboard.waitForExistence(timeout: 10))
        app.terminate()
        app.launch()
        XCTAssertTrue(dashboard.waitForExistence(timeout: 20), "Relaunch did not return to Dashboard")
        XCTAssertFalse(address.exists)
        app.buttons["dashboard-browser"].tap()
        XCTAssertTrue(address.waitForExistence(timeout: 10))
        XCTAssertTrue((address.value as? String)?.contains("/detection") == true,
                      "Opening Browser from the launch hub lost the saved tab")
    }

    func testInstallOpenRemoteReturnAndRemove() {
        continueAfterFailure = false
        let base = ProcessInfo.processInfo.environment["BROWSER_FIXTURE"]!
        let app = XCUIApplication(bundleIdentifier: "com.playbridge.bridged-app-ui-checks")
        app.launch()
        XCTAssertTrue(app.buttons["dashboard-reorder"].waitForExistence(timeout: 20), app.debugDescription)
        app.buttons["dashboard-browser"].tap()
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
        app.buttons["Close menu"].tap()
        app.buttons["Dashboard"].tap()
        let reorder = app.buttons["dashboard-reorder"]
        XCTAssertTrue(reorder.waitForExistence(timeout: 10), app.debugDescription)
        let tile = app.buttons["bridged-app-\(base)/"]
        XCTAssertTrue(tile.waitForExistence(timeout: 10), app.debugDescription)
        reorder.tap()
        let fixtureRow = app.buttons["Position 8, Fixture"]
        // The centered popup intentionally shows fewer rows than the old sheet.
        for _ in 0..<4 {
            if fixtureRow.exists && fixtureRow.isHittable { break }
            app.collectionViews.firstMatch.swipeUp()
        }
        XCTAssertTrue(fixtureRow.waitForExistence(timeout: 10), app.debugDescription)
        fixtureRow.tap()
        let secondPosition = app.buttons["2 · Connection"]
        XCTAssertTrue(secondPosition.waitForExistence(timeout: 5), app.debugDescription)
        secondPosition.tap()
        XCTAssertTrue(app.buttons["Position 2, Fixture"].waitForExistence(timeout: 5))
        app.buttons["Done"].tap()
        let browserTile = app.buttons["dashboard-browser"]
        let connectionTile = app.buttons["dashboard-connection"]
        XCTAssertEqual(tile.frame.height, browserTile.frame.height, accuracy: 1)
        XCTAssertGreaterThan(tile.frame.height, connectionTile.frame.height)
        XCTAssertEqual(tile.frame.minY, browserTile.frame.minY, accuracy: 1)
        let dashboardSnapshot = XCTAttachment(screenshot: app.screenshot())
        dashboardSnapshot.name = "Dashboard with Bridged App in second large slot"
        dashboardSnapshot.lifetime = .keepAlways
        add(dashboardSnapshot)
        // A fresh process must restore the same order, not just the current View state.
        app.terminate()
        app.launch()
        XCTAssertTrue(reorder.waitForExistence(timeout: 20), "Relaunch did not return to Dashboard")
        XCTAssertFalse(address.exists, "Relaunch opened Browser instead of Dashboard")
        reorder.tap()
        XCTAssertTrue(app.buttons["Position 2, Fixture"].waitForExistence(timeout: 5))
        app.buttons["Done"].tap()
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
        XCTAssertTrue(reorder.waitForExistence(timeout: 10))
        reorder.tap()
        XCTAssertTrue(app.buttons["Position 2, Fixture"].waitForExistence(timeout: 5), "Dashboard lost the saved tile order")
        app.buttons["Done"].tap()
        tile.press(forDuration: 1.2)
        let remove = app.buttons["Remove Bridged App"]
        XCTAssertTrue(remove.waitForExistence(timeout: 5), app.debugDescription)
        remove.tap()
        app.alerts["Remove Bridged App?"].buttons["Remove"].tap()
        XCTAssertFalse(tile.exists, "Removal left the tile installed")
        app.buttons["dashboard-browser"].tap()
        XCTAssertTrue(app.buttons["Tabs, 1 open"].waitForExistence(timeout: 10), "App session leaked into the browser tab count")
        menu.tap()
        XCTAssertTrue(install.waitForExistence(timeout: 10), "Removal did not make the website installable again")
    }
}
