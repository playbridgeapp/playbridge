import XCTest

final class BrowserPopupUITests: XCTestCase {
    func testLibrarySearchEditAndImageActions() {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "com.playbridge.browser-startup-checks")
        app.launchEnvironment["BROWSER_FIXTURE"] = ProcessInfo.processInfo.environment["BROWSER_FIXTURE"]!
        app.launchEnvironment["LIBRARY_IMAGES_UI"] = "1"
        app.launch()
        XCTAssertTrue(app.buttons["Show History"].waitForExistence(timeout: 30), app.debugDescription)
        app.buttons["Show History"].tap()
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5), app.debugDescription)
        search.tap(); search.typeText("needle")
        XCTAssertTrue(app.staticTexts["Needle match"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["Other visit"].exists, "History search did not filter")
        app.buttons["Show Bookmarks"].tap()
        let row = app.staticTexts["Fixture bookmark"]
        XCTAssertTrue(row.waitForExistence(timeout: 5), app.debugDescription)
        row.swipeLeft()
        app.buttons["Edit"].tap()
        let title = app.textFields["bookmarkEditTitle"]
        XCTAssertTrue(title.waitForExistence(timeout: 5), app.debugDescription)
        replaceText(title, with: "Edited bookmark", in: app)
        let address = app.textFields["bookmarkEditAddress"]
        replaceText(address, with: app.launchEnvironment["BROWSER_FIXTURE"]! + "/identity", in: app)
        app.buttons["Save"].tap()
        XCTAssertTrue(app.staticTexts["Edited bookmark"].waitForExistence(timeout: 5), app.debugDescription)
        app.searchFields.firstMatch.tap(); app.searchFields.firstMatch.typeText("edited")
        XCTAssertTrue(app.staticTexts["Edited bookmark"].exists)
        app.buttons["Show Images"].tap()
        let image = app.webViews.images["Standalone fixture image"].firstMatch
        XCTAssertTrue(image.waitForExistence(timeout: 5), app.debugDescription)
        image.press(forDuration: 1)
        for action in ["Save Image", "Share Image", "Copy Image", "Open Image in New Tab"] {
            XCTAssertTrue(app.buttons[action].waitForExistence(timeout: 5), app.debugDescription)
        }
        app.buttons["Copy Image"].tap()
        let copied = app.staticTexts["imageCopyState"]
        expectation(for: NSPredicate(format: "label == %@", "Copied image ready"), evaluatedWith: copied)
        waitForExpectations(timeout: 10)
        image.press(forDuration: 1)
        app.buttons["Save Image"].tap()
        expectation(for: NSPredicate(format: "label == %@", "Photos saved: 1"), evaluatedWith: app.staticTexts["imagePhotoState"])
        waitForExpectations(timeout: 10)
        let linked = app.webViews.images["Linked fixture image"].firstMatch
        linked.press(forDuration: 1)
        XCTAssertTrue(app.buttons["Save Image"].waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(app.buttons["Copy Link"].exists, "Linked image lost the link actions")
        app.buttons["Share Image"].tap()
        let shareCopy = app.cells.matching(NSPredicate(format: "label == %@", "Copy")).firstMatch
        XCTAssertTrue(shareCopy.waitForExistence(timeout: 10), "Image file share sheet did not open: " + app.debugDescription)
        shareCopy.tap() // Dismiss without opening another app.
        // Google-style thumbnails: a transparent layer covers the <img>.
        let coveredLinked = app.webViews.images["Covered linked fixture image"].firstMatch
        XCTAssertTrue(coveredLinked.waitForExistence(timeout: 5), app.debugDescription)
        coveredLinked.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: 1)
        XCTAssertTrue(app.buttons["Save Image"].waitForExistence(timeout: 5), "Covered linked image lost image actions: " + app.debugDescription)
        XCTAssertTrue(app.buttons["Copy Link"].exists, "Covered linked image lost the link actions")
        app.buttons["Copy Link"].tap()
        let coveredStandalone = app.webViews.images["Covered standalone fixture image"].firstMatch
        coveredStandalone.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: 1)
        XCTAssertTrue(app.buttons["Save Image"].waitForExistence(timeout: 5), "Covered standalone image had no image actions: " + app.debugDescription)
        app.buttons["Cancel"].tap()
        image.press(forDuration: 1)
        app.buttons["Open Image in New Tab"].tap()
        expectation(for: NSPredicate(format: "label CONTAINS %@", "/protected.png?best=1"), evaluatedWith: app.staticTexts["imagePageAddress"])
        waitForExpectations(timeout: 10)
    }

    private func replaceText(_ field: XCUIElement, with text: String, in app: XCUIApplication) {
        field.tap()
        field.press(forDuration: 1.1)
        let selectAll = app.menuItems["Select All"]
        if selectAll.waitForExistence(timeout: 2) { selectAll.tap() }
        else if app.buttons["Select All"].exists { app.buttons["Select All"].tap() }
        field.typeText(text)
    }

    func testSiteInfoClipboardAndTopVisited() {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "com.playbridge.browser-startup-checks")
        app.launchEnvironment["BROWSER_FIXTURE"] = ProcessInfo.processInfo.environment["BROWSER_FIXTURE"]!
        app.launchEnvironment["BROWSER_TLS_FIXTURE"] = ProcessInfo.processInfo.environment["BROWSER_TLS_FIXTURE"]!
        app.launchEnvironment["SITE_TOOLS_UI"] = "1"
        app.launch()
        let security = app.buttons["browserSecurityIndicator"]
        XCTAssertTrue(security.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertEqual(security.value as? String, "Connection is secure")
        security.tap()
        XCTAssertTrue(app.staticTexts["siteInfoHost"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["siteInfoHost"].label, "127.0.0.1")
        XCTAssertTrue(app.staticTexts["Connection is secure"].exists, app.debugDescription)
        let settings = app.buttons["Site Settings"]
        let infoForm = app.collectionViews.firstMatch
        for _ in 0..<5 {
            if settings.exists && settings.isHittable { break }
            infoForm.swipeUp()
        }
        XCTAssertTrue(settings.waitForExistence(timeout: 5), app.debugDescription)
        settings.tap()
        XCTAssertTrue(app.navigationBars["Site Settings"].waitForExistence(timeout: 5))
        app.buttons["Done"].firstMatch.tap()
        app.buttons["Done"].firstMatch.tap()
        app.buttons["HTTP page"].tap()
        expectation(for: NSPredicate(format: "value == %@", "Connection is not secure"), evaluatedWith: security)
        waitForExpectations(timeout: 10)
        security.tap()
        XCTAssertTrue(app.staticTexts["Connection is not secure"].waitForExistence(timeout: 5), app.debugDescription)
        app.buttons["Done"].firstMatch.tap()
        app.buttons["New tab"].tap()
        XCTAssertFalse(security.exists, "Home must not show a connection indicator")
        XCTAssertTrue(app.staticTexts["Top Visited"].waitForExistence(timeout: 5), app.debugDescription)
        app.buttons["Most visited"].firstMatch.tap()
        let current = app.staticTexts["step3CurrentAddress"]
        expectation(for: NSPredicate(format: "label ENDSWITH %@", "/parent"), evaluatedWith: current)
        waitForExpectations(timeout: 10)
        XCTAssertTrue(app.webViews.links["Open"].firstMatch.waitForExistence(timeout: 10), "Top Visited did not load its document")
        app.buttons["New tab"].tap()
        app.buttons["Copy fixture link"].tap()
        let field = app.textFields["browserAddressField"]
        field.tap()
        let suggestion = app.buttons["browserClipboardSuggestion"]
        XCTAssertTrue(suggestion.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(suggestion.label.hasPrefix("Link you copied"), suggestion.label)
        XCTAssertTrue(app.buttons["browserClipboardPaste"].exists, "System Paste button missing")
        app.buttons["Dismiss copied suggestion"].tap()
        app.buttons["Cancel editing address"].tap()
        field.tap()
        XCTAssertFalse(suggestion.waitForExistence(timeout: 2), "Dismissed clipboard generation was re-offered")
        app.buttons["Cancel editing address"].tap()
        app.buttons["Copy fixture link"].tap()
        field.tap()
        XCTAssertTrue(suggestion.waitForExistence(timeout: 5), app.debugDescription)
        suggestion.tap()
        expectation(for: NSPredicate(format: "label ENDSWITH %@", "/identity?copied=1"), evaluatedWith: current)
        waitForExpectations(timeout: 10)
        XCTAssertTrue(app.webViews.staticTexts["Identity"].firstMatch.waitForExistence(timeout: 10), "Copied link did not load its document")
    }

    func testSharingMenuAndHistoryRelaunch() {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "com.playbridge.browser-startup-checks")
        app.launchEnvironment["BROWSER_FIXTURE"] = ProcessInfo.processInfo.environment["BROWSER_FIXTURE"]!
        app.launchEnvironment["SHARING_MENU_UI"] = "1"
        app.launchEnvironment["SHARING_RESET"] = "1"
        app.launch()
        let menu = app.buttons["Browser menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 30), app.debugDescription)
        app.terminate()
        app.launchEnvironment.removeValue(forKey: "SHARING_RESET")
        app.launch()
        XCTAssertTrue(menu.waitForExistence(timeout: 30), app.debugDescription)
        let title = app.staticTexts["sharingPageTitle"]
        XCTAssertEqual(title.label, "Identity")
        let back = app.buttons["History Back"]
        XCTAssertTrue(back.isEnabled, "Relaunch lost Back history")
        back.tap()
        expectation(for: NSPredicate(format: "label == %@", "Child"), evaluatedWith: title)
        waitForExpectations(timeout: 10)
        let forward = app.buttons["History Forward"]
        XCTAssertTrue(forward.isEnabled, "Relaunch lost Forward history")
        forward.tap()
        expectation(for: NSPredicate(format: "label == %@", "Identity"), evaluatedWith: title)
        waitForExpectations(timeout: 10)
        menu.tap()
        XCTAssertTrue(app.buttons["Share…"].waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(app.buttons["Open in Safari"].exists, app.debugDescription)
        app.buttons["Share…"].tap()
        let copy = app.cells.matching(NSPredicate(format: "label == %@", "Copy")).firstMatch
        XCTAssertTrue(copy.waitForExistence(timeout: 10), "System share sheet did not open: " + app.debugDescription)
        // iOS exposes share actions as cells, rather than buttons.
        // Copy dismisses the system sheet without opening an external app.
        copy.tap()
        if app.buttons["Close menu"].waitForExistence(timeout: 5) { app.buttons["Close menu"].tap() }
        for kind in ["Home page", "App page", "Non-web page"] {
            app.buttons[kind].tap()
            menu.tap()
            XCTAssertTrue(app.buttons["Bookmarks"].waitForExistence(timeout: 5), app.debugDescription)
            XCTAssertFalse(app.buttons["Share…"].exists, kind)
            XCTAssertFalse(app.buttons["Open in Safari"].exists, kind)
            app.buttons["Close menu"].tap()
        }
    }

    private func openContentBlocking(_ app: XCUIApplication) {
        let action = app.buttons["Content Blocking"]
        let menuScroll = app.scrollViews.containing(.button, identifier: "Bookmarks").firstMatch
        for _ in 0..<4 {
            if action.exists && action.isHittable { break }
            menuScroll.swipeUp()
        }
        XCTAssertTrue(action.waitForExistence(timeout: 5), app.debugDescription)
        action.tap()
    }

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
        openContentBlocking(app)
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
        openContentBlocking(app)
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
        openContentBlocking(app)
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
        openContentBlocking(app)
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
        openContentBlocking(app)
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
                               ("expired", "Delayed popup"), ("iframe", "Open link"),
                               ("cross", "Cross-site link"), ("under-script", "Continue"),
                               ("under-copy", "Continue"), ("overlay", "Overlay link"),
                               ("signin", "Continue"), ("under-allowed", "Allowed link")] {
            let ready = NSPredicate(format: "label == %@", "Ready: " + phase)
            expectation(for: ready, evaluatedWith: status)
            waitForExpectations(timeout: 25)
            if ["link", "iframe", "cross", "overlay", "under-allowed"].contains(phase) { app.webViews.links[title].tap() }
            else { app.webViews.buttons[title].tap() }
        }
        expectation(for: NSPredicate(format: "label == %@", "PASS: trusted popups"), evaluatedWith: status)
        waitForExpectations(timeout: 25)
    }
}
