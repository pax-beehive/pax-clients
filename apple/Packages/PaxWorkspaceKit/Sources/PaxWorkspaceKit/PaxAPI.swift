import Foundation

struct ServerEvent: Sendable {
  let id: String
  let data: String
}
struct SSEParser {
  private var id = ""
  private var data = ""
  mutating func line(_ line: String) throws -> ServerEvent? {
    try require(line.utf8.count <= 1_048_576, "Event line is too large")
    if line.isEmpty {
      defer { data = "" }
      return data.isEmpty ? nil : ServerEvent(id: id, data: String(data.dropLast()))
    }
    let parts = line.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
    let field = String(parts[0])
    var value = parts.count > 1 ? String(parts[1]) : ""
    if value.hasPrefix(" ") { value.removeFirst() }
    if field == "id", !value.contains("\0") { id = value }
    if field == "data" {
      try require(data.utf8.count + value.utf8.count < 4_194_304, "Event is too large")
      data += value + "\n"
    }
    return nil
  }
}
protocol APITransport: Sendable {
  func json(_ request: URLRequest) async throws -> JSON
  func events(_ request: URLRequest) -> AsyncThrowingStream<ServerEvent, Error>
}
private final class NoRedirect: NSObject, URLSessionTaskDelegate, Sendable {
  func urlSession(
    _ session: URLSession, task: URLSessionTask,
    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
    completionHandler: @escaping @Sendable (URLRequest?) -> Void
  ) { completionHandler(nil) }
}
struct PaxAPI: APITransport {
  private func session() -> URLSession {
    let config = URLSessionConfiguration.ephemeral
    config.httpCookieStorage = nil
    config.urlCache = nil
    config.timeoutIntervalForRequest = 90
    return URLSession(configuration: config, delegate: NoRedirect(), delegateQueue: nil)
  }
  func json(_ request: URLRequest) async throws -> JSON {
    let session = session()
    defer { session.invalidateAndCancel() }
    let (bytes, response) = try await session.bytes(for: request)
    try Self.validate(response, type: "application/json")
    var data = Data()
    for try await byte in bytes {
      try Task.checkCancellation()
      try require(data.count < 8_388_608, "Response is too large")
      data.append(byte)
    }
    let result = try JSON.parse(data)
    if result["code"].int >= 400 {
      throw ClientFailure(
        message: result.text("message", "Request failed"), status: result["code"].int)
    }
    return result["data"] == .null ? result : result["data"]
  }
  func events(_ request: URLRequest) -> AsyncThrowingStream<ServerEvent, Error> {
    AsyncThrowingStream(bufferingPolicy: .bufferingOldest(128)) { continuation in
      let session = session()
      let task = Task {
        defer { session.invalidateAndCancel() }
        do {
          var request = request
          request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
          let (bytes, response) = try await session.bytes(for: request)
          try Self.validate(response, type: "text/event-stream")
          var parser = SSEParser()
          // AsyncBytes.lines omits blank lines on some platforms. Parse bytes to
          // preserve SSE dispatch boundaries and bound a malicious single line.
          var line = Data()
          var previousCR = false
          for try await byte in bytes {
            try Task.checkCancellation()
            if byte == 10 && previousCR {
              previousCR = false
              continue
            }
            previousCR = byte == 13
            if byte == 10 || byte == 13 {
              guard let value = String(data: line, encoding: .utf8) else {
                throw ClientFailure(message: "Invalid event encoding")
              }
              if let event = try parser.line(value) {
                if case .dropped = continuation.yield(event) {
                  throw ClientFailure(
                    message: "Event consumer fell behind; reconnect to recover", status: 503)
                }
              }
              line.removeAll(keepingCapacity: true)
            } else {
              try require(line.count < 1_048_576, "Event line is too large")
              line.append(byte)
            }
          }
          continuation.finish()  // Never commit an incomplete final event.
        } catch { continuation.finish(throwing: error) }
      }
      continuation.onTermination = { _ in
        task.cancel()
        session.invalidateAndCancel()
      }
    }
  }
  static func validate(_ response: URLResponse, type: String) throws {
    guard let response = response as? HTTPURLResponse else {
      throw ClientFailure(message: "Invalid server response")
    }
    guard (200..<300).contains(response.statusCode) else {
      let message: String
      switch response.statusCode {
      case 401: message = "Your access has expired. Sign in again."
      case 403: message = "Access was denied."
      case 429: message = "Too many requests. Please wait and reconnect."
      case 300..<400: message = "The service returned an unexpected redirect."
      default: message = "Server request failed (\(response.statusCode))."
      }
      throw ClientFailure(message: message, status: response.statusCode)
    }
    try require(
      response.value(forHTTPHeaderField: "Content-Type")?.contains(type) == true,
      "Unexpected server response")
  }
  static func request(
    base: String = UserAuth.apiOrigin, path: [String], token: String = "", method: String = "GET",
    body: JSON? = nil, query: [String: String] = [:], headers: [String: String] = [:]
  ) throws -> URLRequest {
    guard var url = URL(string: base), url.scheme == "https", url.user == nil, url.password == nil,
      url.query == nil, url.fragment == nil
    else { throw ClientFailure(message: "Invalid service URL") }
    for segment in path {
      try require(
        !segment.isEmpty && segment != "." && segment != ".." && !segment.contains("/")
          && !segment.contains("\\") && !segment.contains("\0"), "Invalid resource identifier")
      url.appendPathComponent(segment)
    }
    var components = URLComponents(url: url, resolvingAgainstBaseURL: false)!
    components.queryItems =
      query.isEmpty
      ? nil : query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
    var r = URLRequest(url: components.url!)
    r.httpMethod = method
    r.setValue("application/json", forHTTPHeaderField: "Accept")
    if !token.isEmpty { r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
    if method != "GET" {
      r.httpBody = try (body ?? .object([:])).data()
      r.setValue("application/json", forHTTPHeaderField: "Content-Type")
    }
    for (key, value) in headers { r.setValue(value, forHTTPHeaderField: key) }
    return r
  }
}
