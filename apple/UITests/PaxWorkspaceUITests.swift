import XCTest

final class PaxWorkspaceUITests: XCTestCase {
  @MainActor func testHomeDirectoryPickerAndDaemonUpgrade() throws {
    let app = XCUIApplication()
    app.launchArguments = ["--pax-ui-fixture"]
    app.launch()
    XCTAssertTrue(app.buttons["create-menu"].waitForExistence(timeout: 15))
    app.buttons["create-menu"].tap()
    app.buttons["New session"].tap()
    app.buttons["session-project"].tap()
    app.buttons["PaxWorkspace"].tap()
    app.buttons["Browse device folders"].tap()
    XCTAssertTrue(app.navigationBars["Choose folder"].waitForExistence(timeout: 5))
    XCTAssertFalse(app.buttons["Up"].isEnabled)
    app.buttons["folder:/Users/preview/Projects"].tap()
    XCTAssertTrue(app.staticTexts["No subfolders"].waitForExistence(timeout: 5))
    attach(app, "Home folder picker")
    app.buttons["Select this folder"].tap()
    XCTAssertEqual(
      app.textFields["Absolute working directory"].value as? String, "/Users/preview/Projects")
    app.buttons["Cancel"].tap()
    app.buttons["Settings and devices"].tap()
    app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Studio Mac")).firstMatch.tap()
    XCTAssertTrue(app.buttons["Upgrade paxd"].waitForExistence(timeout: 5))
    app.buttons["Upgrade paxd"].tap()
    app.alerts.buttons["Upgrade when idle"].tap()
    XCTAssertTrue(app.staticTexts["Upgrade complete"].waitForExistence(timeout: 10))
    XCTAssertTrue(app.buttons["Up to date"].exists)
    attach(app, "Confirmed daemon upgrade")
  }

  @MainActor func testWelcome() throws {
    let app = XCUIApplication()
    app.launch()
    XCTAssertTrue(app.buttons["sign-in"].waitForExistence(timeout: 15))
    XCTAssertFalse(
      app.staticTexts.matching(
        NSPredicate(format: "label CONTAINS %@", "Secure storage is unavailable")
      ).firstMatch.exists)
    attach(app, "Welcome")
  }
  @MainActor func testConversationAndEncryptedBoundary() throws {
    let app = XCUIApplication()
    app.launchArguments = ["--pax-ui-fixture"]
    app.launch()
    XCTAssertTrue(app.staticTexts["Build something great"].waitForExistence(timeout: 15))
    attach(app, "Workspace")
    app.staticTexts["Build something great"].firstMatch.tap()
    let composer = app.textFields["message-composer"]
    XCTAssertTrue(composer.waitForExistence(timeout: 10))
    attach(app, "Conversation")
    composer.tap()
    composer.typeText("Hello from iOS")
    app.buttons["send-message"].tap()
    XCTAssertTrue(app.staticTexts["Hello from iOS"].waitForExistence(timeout: 5))
    XCTAssertTrue(
      app.staticTexts["Message received. Your iOS workspace is streaming this reply in real time."]
        .waitForExistence(timeout: 15))
    attach(app, "Reply and keyboard")
    app.buttons["Session actions"].tap()
    app.buttons["Approvals"].tap()
    XCTAssertTrue(app.staticTexts["Run workspace checks"].waitForExistence(timeout: 5))
    app.buttons["Allow once"].tap()
    app.buttons["Confirm"].tap()
    XCTAssertTrue(app.staticTexts["All clear"].waitForExistence(timeout: 5))
    app.buttons["Done"].tap()
    if !app.staticTexts["Private research"].exists {
      app.navigationBars.buttons.element(boundBy: 0).tap()
    }
    app.staticTexts["Private research"].tap()
    XCTAssertTrue(app.buttons["Authorize key access"].waitForExistence(timeout: 5))
    XCTAssertFalse(app.buttons["send-message"].exists)
    attach(app, "Encrypted boundary")
  }
  @MainActor func testProjectAndSessionCreation() throws {
    let app = XCUIApplication()
    app.launchArguments = ["--pax-ui-fixture"]
    app.launch()
    XCTAssertTrue(app.buttons["create-menu"].waitForExistence(timeout: 15))
    app.buttons["create-menu"].tap()
    app.buttons["New project"].tap()
    let name = app.textFields["e.g. My next idea"]
    XCTAssertTrue(name.waitForExistence(timeout: 5))
    name.tap()
    name.typeText("iOS Project")
    app.buttons["create-project"].tap()
    XCTAssertTrue(app.buttons["create-menu"].waitForExistence(timeout: 5))
    app.buttons["create-menu"].tap()
    app.buttons["New session"].tap()
    XCTAssertTrue(app.navigationBars["New session"].waitForExistence(timeout: 5))
    app.buttons["session-project"].tap()
    app.buttons["PaxWorkspace"].tap()
    let cwd = app.textFields["Absolute working directory"]
    XCTAssertTrue(cwd.waitForExistence(timeout: 5))
    XCTAssertEqual(cwd.value as? String, "/Users/preview/work")
    app.textFields["Name (optional)"].tap()
    app.textFields["Name (optional)"].typeText("Created on iOS")
    attach(app, "New session")
    app.buttons["create-session"].tap()
    XCTAssertTrue(app.textFields["message-composer"].waitForExistence(timeout: 10))
  }
  @MainActor func testLargeTextConversation() throws {
    let app = XCUIApplication()
    app.launchArguments = [
      "--pax-ui-fixture", "-UIPreferredContentSizeCategoryName",
      "UICTContentSizeCategoryAccessibilityXXXL",
    ]
    app.launch()
    XCTAssertTrue(app.staticTexts["Build something great"].waitForExistence(timeout: 15))
    app.staticTexts["Build something great"].firstMatch.tap()
    let composer = app.textFields["message-composer"]
    XCTAssertTrue(composer.waitForExistence(timeout: 10))
    composer.tap()
    composer.typeText("Large text check")
    XCTAssertTrue(app.buttons["send-message"].isHittable)
    attach(app, "Accessibility text and keyboard")
    app.buttons["send-message"].tap()
    XCTAssertTrue(
      app.staticTexts["Message received. Your iOS workspace is streaming this reply in real time."]
        .waitForExistence(timeout: 15))
    // At accessibility sizes a complete reply exceeds the viewport. The user
    // bubble must remain reachable above it, rather than visible simultaneously.
    let timeline = app.scrollViews["conversation-timeline"]
    for _ in 0..<6 {
      if app.staticTexts["Large text check"].exists { break }
      timeline.swipeDown()
    }
    XCTAssertTrue(app.staticTexts["Large text check"].exists)
    if UIDevice.current.userInterfaceIdiom == .pad {
      XCUIDevice.shared.orientation = .landscapeLeft
      let landscape = NSPredicate { _, _ in app.frame.width > app.frame.height }
      expectation(for: landscape, evaluatedWith: app)
      waitForExpectations(timeout: 5)
      attach(app, "iPad landscape")
      XCUIDevice.shared.orientation = .portrait
    }
  }
  @MainActor func testKeychainRestore() throws {
    let app = XCUIApplication()
    app.launchArguments = ["--pax-keychain-fixture"]
    app.launch()
    XCTAssertTrue(app.staticTexts["Build something great"].waitForExistence(timeout: 15))
    app.terminate()
    app.launchArguments.append("--pax-keychain-read-only")
    app.launch()
    XCTAssertTrue(app.staticTexts["Build something great"].waitForExistence(timeout: 15))
    app.buttons["Settings and devices"].tap()
    XCTAssertTrue(
      app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "preview@example.invalid"))
        .firstMatch.waitForExistence(timeout: 5))
    attach(app, "Keychain restored account")
  }
  @MainActor func testWelcomeEntryAndSessionDetails() throws {
    let app = XCUIApplication()
    app.launchArguments = ["--pax-ui-fixture", "--pax-welcome-fixture"]
    app.launch()
    XCTAssertTrue(app.buttons["Enter workspace"].waitForExistence(timeout: 15))
    attach(app, "Signed-in orbital welcome")
    app.buttons["Enter workspace"].tap()
    XCTAssertTrue(app.staticTexts["Build something great"].waitForExistence(timeout: 10))
    app.staticTexts["Build something great"].firstMatch.tap()
    app.buttons["Session actions"].tap()
    app.buttons["Session details"].tap()
    XCTAssertTrue(
      app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Studio Mac")).firstMatch
        .waitForExistence(timeout: 5))
    attach(app, "Session details")
    app.buttons["Done"].tap()
  }
  @MainActor func testSendPresentationAndMarkdown() throws {
    let app = XCUIApplication()
    app.launchArguments = ["--pax-ui-fixture"]
    app.launch()
    XCTAssertTrue(app.staticTexts["Build something great"].waitForExistence(timeout: 15))
    app.staticTexts["Build something great"].firstMatch.tap()
    let composer = app.textFields["message-composer"]
    XCTAssertTrue(composer.waitForExistence(timeout: 10))
    attach(app, "Rich Markdown")
    for text in [
      "A message that wraps from the composer into its conversation bubble.",
      "```swift\nlet x = 1\n```", "مرحبا بالعالم",
    ] {
      composer.tap()
      composer.typeText(text)
      app.buttons["send-message"].tap()
      XCTAssertTrue(app.staticTexts[text].waitForExistence(timeout: 5))
      XCTAssertTrue(app.buttons["send-message"].waitForExistence(timeout: 15))
    }
    attach(app, "Code and bidi sends")
  }
  @MainActor private func attach(_ app: XCUIApplication, _ name: String) {
    let attachment = XCTAttachment(screenshot: app.screenshot())
    attachment.name = name
    attachment.lifetime = .keepAlways
    add(attachment)
  }
}
