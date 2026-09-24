import XCTest

final class BrowserPopupUITests: XCTestCase {
    func testBlockElementOnLiveImage() {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "com.playbridge.browser-startup-checks")
        app.launchEnvironment["BROWSER_FIXTURE"] = ProcessInfo.processInfo.environment["BROWSER_FIXTURE"]!
        app.launchEnvironment["PICKER_MENU_UI"] = "1"
        app.launchEnvironment["PICKER_LIVE_URL"] = "https://tube.perverzija.com/"
        app.launch()
        let menu = app.buttons["Browser menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 40), app.debugDescription)
        menu.tap()
        app.buttons["Block Element"].tap()
        let state = app.staticTexts["pickerState"]
        expectation(for: NSPredicate(format: "label == %@", "Picker active"), evaluatedWith: state)
        waitForExpectations(timeout: 10)
        app.webViews.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.75, dy: 0.2)).tap()
        let block = app.buttons["Block selected element"]
        XCTAssertTrue(block.waitForExistence(timeout: 5), app.debugDescription)
        expectation(for: NSPredicate(format: "enabled == true"), evaluatedWith: block)
        waitForExpectations(timeout: 5)
        block.tap()
        expectation(for: NSPredicate(format: "label == %@", "Picker inactive"), evaluatedWith: state)
        waitForExpectations(timeout: 10)
        menu.tap()
        app.buttons["Block Element"].tap()
        expectation(for: NSPredicate(format: "label == %@", "Picker active"), evaluatedWith: state)
        waitForExpectations(timeout: 10)
        app.buttons["Cancel picker"].tap()
        expectation(for: NSPredicate(format: "label == %@", "Picker inactive"), evaluatedWith: state)
        waitForExpectations(timeout: 10)
    }

    func testBlockElementFromMenu() {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "com.playbridge.browser-startup-checks")
        app.launchEnvironment["BROWSER_FIXTURE"] = ProcessInfo.processInfo.environment["BROWSER_FIXTURE"]!
        app.launchEnvironment["PICKER_MENU_UI"] = "1"
        app.launch()
        let menu = app.buttons["Browser menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 25), app.debugDescription)
        menu.tap()
        let block = app.buttons["Block Element"]
        XCTAssertTrue(block.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(block.isEnabled, "Block Element should be enabled on a loaded web page")
        block.tap()
        let state = app.staticTexts["pickerState"]
        expectation(for: NSPredicate(format: "label == %@", "Picker active"), evaluatedWith: state)
        waitForExpectations(timeout: 10)
        XCTAssertTrue(app.buttons["Cancel picker"].waitForExistence(timeout: 5), app.debugDescription)
        app.buttons["Cancel picker"].tap()
        expectation(for: NSPredicate(format: "label == %@", "Picker inactive"), evaluatedWith: state)
        waitForExpectations(timeout: 10)
        menu.tap()
        block.tap()
        expectation(for: NSPredicate(format: "label == %@", "Picker active"), evaluatedWith: state)
        waitForExpectations(timeout: 10)
        let link = app.webViews.links["Open"]
        XCTAssertTrue(link.exists, app.debugDescription)
        link.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(app.staticTexts["#link"].waitForExistence(timeout: 5),
                      "A real touch did not select the page element")
        app.buttons["Cancel picker"].tap()
        expectation(for: NSPredicate(format: "label == %@", "Picker inactive"), evaluatedWith: state)
        waitForExpectations(timeout: 10)
        menu.tap()
        block.tap()
        expectation(for: NSPredicate(format: "label == %@", "Picker active"), evaluatedWith: state)
        waitForExpectations(timeout: 10)
        link.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(app.staticTexts["#link"].waitForExistence(timeout: 5))
        app.buttons["Block selected element"].tap()
        expectation(for: NSPredicate(format: "label == %@", "Picker inactive"), evaluatedWith: state)
        waitForExpectations(timeout: 10)
    }

    func testTabRows() {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "com.playbridge.browser-startup-checks")
        app.launchEnvironment["BROWSER_FIXTURE"] = ProcessInfo.processInfo.environment["BROWSER_FIXTURE"]!
        app.launchEnvironment["TABS_UI"] = "1"
        app.launch()
        let search = app.textFields["Search tabs"]
        XCTAssertTrue(search.waitForExistence(timeout: 25))
        let activeRow = app.buttons["Open tab Example video with a longer title that wraps in the selected tab"].firstMatch
        expectation(for: NSPredicate(format: "hittable == true"), evaluatedWith: activeRow)
        waitForExpectations(timeout: 5)
        app.scrollViews.firstMatch.swipeUp()
        let down = app.buttons["Scroll to bottom"]
        XCTAssertTrue(down.exists, app.debugDescription)
        XCTAssertFalse(app.scrollViews.buttons["Scroll to bottom"].exists, "Jump control must sit outside scroll gesture handling")
        down.tap()
        let up = app.buttons["Scroll to top"]
        XCTAssertTrue(up.exists)
        up.tap()
        let firstURL = app.launchEnvironment["BROWSER_FIXTURE"]! + "/tab/0"
        XCTAssertTrue(app.staticTexts[firstURL].firstMatch.isHittable)
        search.tap(); search.typeText("Example video")
        let row = app.buttons["Open tab Example video with a longer title that wraps in the selected tab"].firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.press(forDuration: 1)
        app.buttons["Duplicate tab"].tap()
        let matchingRows = app.buttons.matching(identifier: "Open tab Example video with a longer title that wraps in the selected tab")
        XCTAssertEqual(matchingRows.count, 2)
        XCTAssertGreaterThan(matchingRows.element(boundBy: 0).frame.height,
                             matchingRows.element(boundBy: 1).frame.height + 10,
                             "Selected title should wrap; inactive title should stay on one line")
        app.buttons["Tab actions"].tap()
        app.buttons["Select tabs"].tap()
        app.buttons["Select all"].tap()
        app.buttons["Close selected tabs"].tap()
        app.buttons["Close tabs"].tap()
        XCTAssertTrue(app.staticTexts["No matching tabs"].waitForExistence(timeout: 5))
        app.buttons["Tab actions"].tap()
        app.buttons["Close all tabs"].tap()
        app.buttons["Close tabs"].tap()
        XCTAssertTrue(app.navigationBars["1 Tab"].waitForExistence(timeout: 5))
    }

    func testNetworkLogDomainConfirmation() {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "com.playbridge.browser-startup-checks")
        app.launchEnvironment["BROWSER_FIXTURE"] = ProcessInfo.processInfo.environment["BROWSER_FIXTURE"]!
        app.launchEnvironment["NETWORK_LOG_UI"] = "1"
        app.launch()
        XCTAssertTrue(app.buttons["Start detailed capture and reload"].waitForExistence(timeout: 5))
        let entry = app.staticTexts["ads.example"]
        XCTAssertTrue(entry.waitForExistence(timeout: 25))
        entry.tap()
        let action = app.buttons["network-domain-action"]
        for _ in 0..<3 { if action.exists { break }; app.swipeUp() }
        XCTAssertTrue(action.waitForExistence(timeout: 5), app.debugDescription)
        action.tap()
        let confirm = app.buttons["Block domain"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        XCTAssertTrue(app.navigationBars["Request details"].exists)
        confirm.tap()
        expectation(for: NSPredicate(format: "label == %@", "Unblock ads.example"), evaluatedWith: action)
        waitForExpectations(timeout: 5)
        action.tap()
        app.buttons["Unblock domain"].tap()
        expectation(for: NSPredicate(format: "label == %@", "Block ads.example"), evaluatedWith: action)
        waitForExpectations(timeout: 5)
    }

    func testTrustedPopups() {
        let app = XCUIApplication(bundleIdentifier: "com.playbridge.browser-startup-checks")
        app.launchEnvironment["BROWSER_FIXTURE"] = ProcessInfo.processInfo.environment["BROWSER_FIXTURE"]!
        app.launchEnvironment["POPUP_TOUCH"] = "1"
        app.launch()
        let status = app.staticTexts["popupStatus"]
        for (phase, title) in [("link", "Open link"), ("form", "Submit form"),
                               ("script", "Script popup"), ("burst", "Burst popup"),
                               ("expired", "Delayed popup"), ("iframe", "Open link")] {
            let ready = NSPredicate(format: "label == %@", "Ready: " + phase)
            expectation(for: ready, evaluatedWith: status)
            waitForExpectations(timeout: 25)
            if phase == "link" || phase == "iframe" { app.webViews.links[title].tap() }
            else { app.webViews.buttons[title].tap() }
        }
        expectation(for: NSPredicate(format: "label == %@", "PASS: trusted popups"), evaluatedWith: status)
        waitForExpectations(timeout: 25)
    }
}
