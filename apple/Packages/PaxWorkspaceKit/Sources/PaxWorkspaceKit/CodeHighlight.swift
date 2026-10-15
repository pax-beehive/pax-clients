import Foundation

enum CodeHighlight {
  enum Kind: Sendable { case comment, string, number, keyword }
  struct Token: Sendable {
    let range: NSRange
    let kind: Kind
  }
  static func tokens(_ code: String, language: String) -> [Token] {
    guard code.utf8.count <= 20_000 else { return [] }
    let language = language.lowercased().split(separator: " ").first.map(String.init) ?? ""
    let cFamily = [
      "swift", "kotlin", "kt", "java", "javascript", "js", "typescript", "ts", "jsx", "tsx", "go",
      "golang", "rust", "rs", "c", "cpp", "c++", "cs", "csharp", "json", "jsonc",
    ]
    let hashFamily = [
      "python", "py", "ruby", "rb", "sh", "bash", "shell", "zsh", "yaml", "yml", "toml",
    ]
    guard cFamily.contains(language) || hashFamily.contains(language) || language == "sql" else {
      return []
    }
    let comment =
      hashFamily.contains(language)
      ? "#[^\\n]*"
      : language == "sql"
        ? "--[^\\n]*|/\\*[\\s\\S]*?(?:\\*/|$)" : "//[^\\n]*|/\\*[\\s\\S]*?(?:\\*/|$)"
    let strings =
      #""""[\s\S]*?(?:"""|$)|'''[\s\S]*?(?:'''|$)|"(?:\\.|[^"\\])*(?:"|$)|'(?:\\.|[^'\\])*(?:'|$)|`(?:\\.|[^`\\])*(?:`|$)"#
    let keywords =
      #"\b(?:let|var|const|func|fun|function|class|struct|enum|interface|protocol|extension|import|package|export|from|return|if|else|for|while|switch|case|default|break|continue|guard|throw|throws|try|catch|finally|async|await|public|private|internal|static|final|override|open|new|this|self|super|nil|null|true|false|def|in|is|as|with|lambda|yield|None|True|False|fn|impl|pub|mut|use|match|select|SELECT|FROM|WHERE|INSERT|UPDATE|DELETE|CREATE|TABLE|JOIN|AND|OR|NOT|NULL)\b"#
    let pattern =
      "(\(comment))|(\(strings))|(\\b(?:0[xX][0-9a-fA-F]+|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\b)|(\(keywords))"
    guard let expression = try? NSRegularExpression(pattern: pattern) else { return [] }
    return expression.matches(in: code, range: NSRange(code.startIndex..., in: code)).map { match in
      let group = (1...4).first { match.range(at: $0).location != NSNotFound } ?? 4
      return Token(range: match.range, kind: [.comment, .string, .number, .keyword][group - 1])
    }
  }
}
#if os(iOS)
  import SwiftUI
  extension CodeHighlight {
    static func attributed(_ code: String, language: String) -> AttributedString {
      var result = AttributedString(code)
      for token in tokens(code, language: language) {
        guard let range = Range(token.range, in: code),
          let start = AttributedString.Index(range.lowerBound, within: result),
          let end = AttributedString.Index(range.upperBound, within: result)
        else { continue }
        switch token.kind {
        case .comment: result[start..<end].foregroundColor = .secondary
        case .string: result[start..<end].foregroundColor = Color(uiColor: .systemGreen)
        case .number: result[start..<end].foregroundColor = Color(uiColor: .systemOrange)
        case .keyword: result[start..<end].foregroundColor = Color(uiColor: .systemPurple)
        }
      }
      return result
    }
  }
#endif
