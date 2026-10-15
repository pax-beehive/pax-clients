import Foundation
import Markdown

struct MarkdownSpan: Equatable, Sendable {
  var text: String
  var bold = false
  var italic = false
  var strike = false
  var code = false
  var link: URL?
}
struct MarkdownBlock: Identifiable, Equatable, Sendable {
  enum Kind: Equatable, Sendable {
    case text
    case heading(Int)
    case code(String)
    case quote
    case item(String, Bool?)
    case table, rule
    case image(String)
  }
  let id: String
  var kind: Kind
  var text = ""
  var spans: [MarkdownSpan] = []
  var children: [MarkdownBlock] = []
  var rows: [[[MarkdownSpan]]] = []
  var alignments: [Int] = []
}
enum MarkdownParser {
  static func safeLink(_ value: String) -> URL? {
    guard let url = URL(string: value.trimmingCharacters(in: .whitespacesAndNewlines)),
      let scheme = url.scheme?.lowercased()
    else { return nil }
    if scheme == "mailto" { return url.absoluteString.count > 7 ? url : nil }
    guard ["https", "http"].contains(scheme), let host = url.host, !host.isEmpty,
      url.user == nil, url.password == nil
    else { return nil }
    return url
  }
  static func blocks(_ source: String) -> [MarkdownBlock] {
    // Same bound as Android: very large output remains selectable without parsing.
    guard source.utf8.count <= 150_000 else {
      return [MarkdownBlock(id: "plain", kind: .text, text: source, spans: [.init(text: source)])]
    }
    return convert(Document(parsing: source), path: "0")
  }
  private static func inline(_ node: any Markup, style: MarkdownSpan = .init(text: ""))
    -> [MarkdownSpan]
  {
    var style = style
    switch node {
    case let node as Markdown.Text:
      style.text = node.string
      return [style]
    case let node as InlineCode:
      style.text = node.code
      style.code = true
      return [style]
    case let node as InlineHTML:
      style.text = node.rawHTML
      return [style]
    case is SoftBreak:
      style.text = "\n"
      return [style]
    case is LineBreak:
      style.text = "\n"
      return [style]
    case is Strong: style.bold = true
    case is Emphasis: style.italic = true
    case is Strikethrough: style.strike = true
    case let node as Markdown.Link: style.link = safeLink(node.destination ?? "")
    case let node as Markdown.Image:
      style.text = "[Image: \(node.plainText)]"
      return [style]
    default: break
    }
    return node.children.flatMap { inline($0, style: style) }
  }
  private static func images(_ node: any Markup, path: String) -> [MarkdownBlock] {
    if let image = node as? Markdown.Image {
      return [MarkdownBlock(id: path, kind: .image(image.source ?? ""), text: image.plainText)]
    }
    return node.children.enumerated().flatMap { images($0.element, path: "\(path).\($0.offset)") }
  }
  private static func convert(_ node: any Markup, path: String, depth: Int = 0) -> [MarkdownBlock] {
    // Bound renderer nesting for adversarial or accidentally enormous indentation.
    guard depth < 32 else {
      return [MarkdownBlock(id: path, kind: .text, spans: [.init(text: node.format())])]
    }
    func children() -> [MarkdownBlock] {
      node.children.enumerated().flatMap {
        convert($0.element, path: "\(path).\($0.offset)", depth: depth + 1)
      }
    }
    switch node {
    case let code as CodeBlock:
      return [
        MarkdownBlock(
          id: path, kind: .code(code.language ?? ""),
          text: code.code.hasSuffix("\n") ? String(code.code.dropLast()) : code.code)
      ]
    case let heading as Heading:
      return [MarkdownBlock(id: path, kind: .heading(heading.level), spans: inline(node))]
    case is Paragraph:
      return [MarkdownBlock(id: path, kind: .text, spans: inline(node))]
        + images(node, path: path + ".image")
    case is BlockQuote:
      return [MarkdownBlock(id: path, kind: .quote, children: children())]
    case is ThematicBreak: return [MarkdownBlock(id: path, kind: .rule)]
    case let table as Markdown.Table:
      let rows = [Array(table.head.children)] + table.body.children.map { Array($0.children) }
      return [
        MarkdownBlock(
          id: path, kind: .table, rows: rows.map { $0.map { inline($0) } },
          alignments: table.columnAlignments.map { $0 == .right ? 2 : $0 == .center ? 1 : 0 })
      ]
    case let list as OrderedList:
      return list.children.enumerated().map { index, child in
        MarkdownBlock(
          id: "\(path).\(index)", kind: .item("\(list.startIndex + UInt(index)).", nil),
          children: convert(child, path: "\(path).\(index).body", depth: depth + 1))
      }
    case is UnorderedList:
      return node.children.enumerated().map { index, child in
        let check = (child as? ListItem)?.checkbox.map { $0 == .checked }
        return MarkdownBlock(
          id: "\(path).\(index)", kind: .item("•", check),
          children: convert(child, path: "\(path).\(index).body", depth: depth + 1))
      }
    case let html as HTMLBlock:
      return [MarkdownBlock(id: path, kind: .text, spans: [.init(text: html.rawHTML)])]
    default: return children()
    }
  }
}
#if os(iOS)
  import SwiftUI
  struct MarkdownView: View {
    let source: String
    @State private var blocks: [MarkdownBlock] = []
    var body: some View {
      Group {
        if blocks.isEmpty { SwiftUI.Text(source) } else { MarkdownBlocks(blocks: blocks) }
      }.frame(maxWidth: .infinity, alignment: .leading).textSelection(.enabled)
        .task(id: source) {
          let input = source
          let work = Task.detached(priority: .userInitiated) { MarkdownParser.blocks(input) }
          let result = await work.value
          if !Task.isCancelled { blocks = result }
        }
        .environment(
          \.openURL,
          OpenURLAction { url in
            MarkdownParser.safeLink(url.absoluteString) == nil ? .discarded : .systemAction
          })
    }
  }
  private func styled(_ spans: [MarkdownSpan]) -> AttributedString {
    spans.reduce(into: AttributedString()) { result, span in
      var text = AttributedString(span.text)
      var font = Font.body
      if span.code {
        font = .system(.body, design: .monospaced)
        text.backgroundColor = .secondary.opacity(0.1)
      }
      if span.bold { font = font.bold() }
      if span.italic { font = font.italic() }
      text.font = font
      if span.strike { text.strikethroughStyle = .single }
      text.link = span.link
      result.append(text)
    }
  }
  private struct MarkdownBlocks: View {
    let blocks: [MarkdownBlock]
    var body: some View {
      VStack(alignment: .leading, spacing: 12) {
        ForEach(blocks) { block in MarkdownBlockView(block: block) }
      }
    }
  }
  private struct MarkdownBlockView: View {
    let block: MarkdownBlock
    var body: some View {
      switch block.kind {
      case .text: SwiftUI.Text(styled(block.spans)).lineSpacing(4)
      case .heading(let level):
        // Heading size is applied to every run, preserving inline links/emphasis.
        SwiftUI.Text(heading(block.spans, level: level)).padding(.top, 5)
      case .quote:
        HStack(alignment: .top, spacing: 12) {
          Rectangle().fill(.secondary.opacity(0.3)).frame(width: 3)
          MarkdownBlocks(blocks: block.children).foregroundStyle(.secondary)
        }.fixedSize(horizontal: false, vertical: true)
      case .item(let marker, let checked):
        HStack(alignment: .firstTextBaseline, spacing: 9) {
          if let checked {
            Image(systemName: checked ? "checkmark.square" : "square")
              .accessibilityLabel(checked ? "Completed" : "Incomplete")
          } else {
            SwiftUI.Text(marker).monospacedDigit().frame(minWidth: 22, alignment: .trailing)
          }
          MarkdownBlocks(blocks: block.children).frame(maxWidth: .infinity, alignment: .leading)
        }
      case .rule: Divider()
      case .code(let language): CodePanel(code: block.text, language: language)
      case .image(let source): MarkdownImage(source: source, alt: block.text)
      case .table:
        ScrollView(.horizontal) {
          Grid(alignment: .leading, horizontalSpacing: 18, verticalSpacing: 10) {
            ForEach(Array(block.rows.enumerated()), id: \.offset) { index, row in
              GridRow {
                ForEach(Array(row.enumerated()), id: \.offset) { column, cell in
                  SwiftUI.Text(index == 0 ? heading(cell, level: 4) : styled(cell))
                    .frame(maxWidth: 280, alignment: alignment(column)).fixedSize(
                      horizontal: false, vertical: true)
                }
              }
              if index == 0 { Divider().gridCellUnsizedAxes(.horizontal) }
            }
          }.padding(12)
        }.background(
          Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 10))
      }
    }
    private func alignment(_ column: Int) -> Alignment {
      guard block.alignments.indices.contains(column) else { return .leading }
      return block.alignments[column] == 2
        ? .trailing : block.alignments[column] == 1 ? .center : .leading
    }
    private func heading(_ spans: [MarkdownSpan], level: Int) -> AttributedString {
      var text = styled(spans)
      text.font = level == 1 ? .title2.bold() : level == 2 ? .title3.bold() : .headline
      return text
    }
  }
  private struct MarkdownImage: View {
    let source: String
    let alt: String
    @State private var show = false
    var body: some View {
      let url = MarkdownParser.safeLink(source).flatMap {
        $0.scheme?.lowercased() == "https" ? $0 : nil
      }
      VStack(alignment: .leading) {
        if show, let url {
          AsyncImage(url: url) { phase in
            switch phase {
            case .success(let image):
              image.resizable().scaledToFit().frame(maxHeight: 320).accessibilityLabel(alt)
            case .failure: Label("Image unavailable: \(alt)", systemImage: "photo")
            default: ProgressView("Loading image…")
            }
          }
        } else {
          Button(url == nil ? "\(alt) · \(source)" : "Load image · \(alt.isEmpty ? "Image" : alt)")
          { show = true }.disabled(url == nil)
        }
      }.id(source)
    }
  }
  private struct CodePanel: View {
    let code: String
    let language: String
    @State private var copied = false
    var body: some View {
      VStack(alignment: .leading, spacing: 0) {
        HStack {
          SwiftUI.Text(language.isEmpty ? "Code" : String(language.prefix(40))).font(.caption)
          Spacer()
          Button(copied ? "Copied" : "Copy code") {
            UIPasteboard.general.string = code
            copied = true
          }.font(.caption).accessibilityLabel("Copy code")
        }.foregroundStyle(.secondary).padding(10)
        Divider()
        if code.split(separator: "\n", omittingEmptySubsequences: false).contains(where: {
          $0.count > 2000
        }) {
          SwiftUI.Text(CodeHighlight.attributed(code, language: language)).padding(12)
        } else {
          ScrollView(.horizontal) {
            SwiftUI.Text(CodeHighlight.attributed(code, language: language)).padding(12).fixedSize(
              horizontal: true, vertical: true)
          }
        }
      }.font(.system(.callout, design: .monospaced)).textSelection(.enabled)
        .background(
          Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12)
        )
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(.primary.opacity(0.06)))
        .task(id: copied) {
          if copied {
            try? await Task.sleep(for: .milliseconds(1800))
            if !Task.isCancelled { copied = false }
          }
        }
    }
  }
#endif
