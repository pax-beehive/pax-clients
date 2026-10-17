import Foundation
import Testing

@testable import PaxWorkspaceKit

@Test func upgradeRequiresConfirmedRunningVersion() throws {
  let dispatched = try JSON.parse(
    Data(#"{"command":{"status":"succeeded","result":{"phase":"shutdown_committed"}}}"#.utf8))
  #expect(!UpgradeProgress(dispatched).terminal)
  let confirmed = try JSON.parse(Data(#"{"maintenance_confirmation":{"status":"confirmed"}}"#.utf8))
  #expect(UpgradeProgress(confirmed).succeeded)
  let mismatch = try JSON.parse(
    Data(#"{"maintenance_confirmation":{"status":"version_mismatch"}}"#.utf8))
  #expect(UpgradeProgress(mismatch).terminal)
  #expect(!UpgradeProgress(mismatch).succeeded)
}

@Test func directoryPageAndPendingUpgradeAreBackwardCompatible() throws {
  let data = Data(
    #"{"home":"/Users/test","path":"/Users/test","entries":[{"name":"My Projects","path":"/Users/test/My Projects"}],"next_after":"My Projects"}"#
      .utf8)
  let page = try JSONDecoder().decode(DirectoryPage.self, from: data)
  #expect(page.parent == nil)
  #expect(page.entries.count == 1)
  #expect(page.next_after == "My Projects")
  let old = try JSONDecoder().decode(VaultData.self, from: Data(#"{"keys":[],"pairings":[]}"#.utf8))
  #expect(old.pendingUpgrades == nil)
  var saved = old
  saved.pendingUpgrades = ["user:node": PendingUpgrade(commandID: "upgrade_same", version: "1.2.3")]
  let restored = try JSONDecoder().decode(VaultData.self, from: JSONEncoder().encode(saved))
  #expect(restored.pendingUpgrades?["user:node"]?.commandID == "upgrade_same")
}
