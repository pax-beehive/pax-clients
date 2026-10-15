#if os(iOS)
  import AuthenticationServices
  import UIKit

  @MainActor final class BrowserSignIn: NSObject, ASWebAuthenticationPresentationContextProviding {
    private var session: ASWebAuthenticationSession?
    func start(url: URL, completion: @escaping @MainActor (URL?, Error?) -> Void) {
      session = ASWebAuthenticationSession(url: url, callbackURLScheme: "net.paxtech.workspace") {
        [weak self] url, error in
        Task { @MainActor in
          self?.session = nil
          completion(url, error)
        }
      }
      session?.presentationContextProvider = self
      session?.prefersEphemeralWebBrowserSession = false
      if session?.start() != true {
        session = nil
        completion(nil, ClientFailure(message: "Unable to open sign-in"))
      }
    }
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
      UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows)
        .first { $0.isKeyWindow } ?? ASPresentationAnchor()
    }
  }
#endif
