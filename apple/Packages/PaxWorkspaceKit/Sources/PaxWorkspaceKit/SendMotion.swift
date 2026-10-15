import Foundation

enum SendMotionPolicy {
  static func allows(_ text: String, reducedMotion: Bool, accessibilityText: Bool) -> Bool {
    guard !reducedMotion, !accessibilityText, !text.contains("```"), !text.contains("~~~"),
      text.utf16.count <= 2000
    else { return false }
    return !text.unicodeScalars.contains {
      (0x0590...0x08FF).contains($0.value) || (0xFB1D...0xFDFF).contains($0.value)
        || (0xFE70...0xFEFF).contains($0.value) || (0x202A...0x202E).contains($0.value)
        || (0x2066...0x2069).contains($0.value) || (0x10800...0x10FFF).contains($0.value)
    }
  }
}
#if os(iOS)
  import SwiftUI
  import UIKit
  import Observation

  struct SendGeometry: PreferenceKey {
    static let defaultValue: [String: CGRect] = [:]
    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
      value.merge(nextValue(), uniquingKeysWith: { _, new in new })
    }
  }
  extension View {
    func sendGeometry(_ key: String) -> some View {
      background {
        GeometryReader { geometry in
          Color.clear.preference(
            key: SendGeometry.self,
            value: [key: geometry.frame(in: .named("send-host"))])
        }
      }
    }
  }
  @MainActor @Observable final class SendMotion {
    struct Flight {
      let id: String
      let text: String
      let origin: CGRect
      var turn: String
      var destination: CGRect?
    }
    var flight: Flight?
    private var geometry: [String: CGRect] = [:]
    private var timeout: Task<Void, Never>?
    func launch(_ item: ChatItem, enabled: Bool) {
      finish()
      guard enabled, let origin = geometry["composer"], origin.width > 80 else { return }
      flight = Flight(id: item.id, text: item.text, origin: origin, turn: item.turn)
      timeout = Task { [weak self] in
        try? await Task.sleep(for: .milliseconds(250))
        guard !Task.isCancelled, let self else { return }
        if flight?.destination == nil { finish() }
      }
    }
    func matches(_ item: ChatItem) -> Bool {
      guard let flight else { return false }
      return item.id == flight.id
        || (!flight.turn.hasPrefix("local:") && item.turn == flight.turn
          && item.role == "user" && item.text == flight.text)
    }
    func observe(_ messages: [ChatItem]) {
      if let item = messages.first(where: { $0.id == flight?.id }) { flight?.turn = item.turn }
      if flight != nil && !messages.contains(where: matches) { finish() }
    }
    func place(_ values: [String: CGRect], messages: [ChatItem]) {
      if let old = geometry["viewport"], let new = values["viewport"],
        abs(old.width - new.width) > 1 || abs(old.minX - new.minX) > 1
      {
        finish()
      }
      geometry = values
      guard let flight, let viewport = values["viewport"],
        let item = messages.first(where: matches), let target = values[item.id]
      else { return }
      guard viewport.contains(target), target.height < viewport.height * 0.7 else {
        if flight.destination != nil { finish() }
        return
      }
      self.flight?.destination = target
    }
    func finish() {
      timeout?.cancel()
      timeout = nil
      flight = nil
    }
  }
  struct SendFlightOverlay: UIViewRepresentable {
    let motion: SendMotion
    func makeUIView(context: Context) -> FlightDrawingView {
      let view = FlightDrawingView()
      view.isUserInteractionEnabled = false
      view.isAccessibilityElement = false
      view.accessibilityElementsHidden = true
      view.backgroundColor = .clear
      view.clipsToBounds = true
      return view
    }
    func updateUIView(_ view: FlightDrawingView, context: Context) {
      guard let flight = motion.flight, let target = flight.destination else {
        view.reset()
        return
      }
      view.present(flight, target: target) { motion.finish() }
    }
    static func dismantleUIView(_ view: FlightDrawingView, coordinator: ()) { view.reset() }
  }
  /// TextKit shapes only the two endpoint layouts. Drawing clips shared glyph fragments;
  /// there is no per-frame layout or duplicate fading text, and no dependency on networking.
  final class FlightDrawingView: UIView {
    private struct Layout {
      let storage: NSTextStorage
      let manager: NSLayoutManager
      let container: NSTextContainer
      init(_ text: String, width: CGFloat) {
        storage = NSTextStorage(
          string: text,
          attributes: [
            .font: UIFont.preferredFont(forTextStyle: .body), .foregroundColor: UIColor.label,
          ])
        manager = NSLayoutManager()
        container = NSTextContainer(size: CGSize(width: max(1, width), height: 10000))
        container.lineFragmentPadding = 0
        manager.addTextContainer(container)
        storage.addLayoutManager(manager)
        manager.ensureLayout(for: container)
      }
      var size: CGSize { manager.usedRect(for: container).size }
    }
    private struct Fragment {
      let source: CGRect
      let target: CGRect
    }
    private var identity: String?
    private var origin = CGRect.zero
    private var destination = CGRect.zero
    private var layout: Layout?
    private var fragments: [Fragment] = []
    private var link: CADisplayLink?
    private var began: CFTimeInterval = 0
    private var amount: CGFloat = 0
    private var completion: (@MainActor @Sendable () -> Void)?
    func present(
      _ flight: SendMotion.Flight, target: CGRect,
      completion: @escaping @MainActor @Sendable () -> Void
    ) {
      if identity == flight.id {
        // A fast reply can move the landing row while the bubble is still in flight.
        if abs(destination.width - target.width) > 1 {
          reset()
          DispatchQueue.main.async(execute: completion)
        } else {
          destination = target
        }
        return
      }
      reset()
      let from = Layout(flight.text, width: flight.origin.width - 22 - 60)
      let to = Layout(flight.text, width: target.width - 28)
      guard from.manager.numberOfGlyphs == to.manager.numberOfGlyphs,
        from.size.height <= flight.origin.height - 20,
        to.size.height <= target.height - 20
      else {
        DispatchQueue.main.async(execute: completion)
        return
      }
      identity = flight.id
      origin = flight.origin
      destination = target
      layout = from
      self.completion = completion
      var ends = Set([0, from.manager.numberOfGlyphs])
      for endpoint in [from, to] {
        endpoint.manager.enumerateLineFragments(
          forGlyphRange: NSRange(location: 0, length: endpoint.manager.numberOfGlyphs)
        ) { _, _, _, range, _ in
          ends.insert(range.location)
          ends.insert(NSMaxRange(range))
        }
      }
      let sorted = ends.sorted()
      fragments = zip(sorted, sorted.dropFirst()).compactMap { start, end in
        let range = NSRange(location: start, length: end - start)
        guard end <= to.manager.numberOfGlyphs, range.length > 0 else { return nil }
        return Fragment(
          source: from.manager.boundingRect(forGlyphRange: range, in: from.container),
          target: to.manager.boundingRect(forGlyphRange: range, in: to.container))
      }
      began = CACurrentMediaTime()
      link = CADisplayLink(target: self, selector: #selector(tick))
      link?.add(to: .main, forMode: .common)
      setNeedsDisplay()
    }
    @objc private func tick() {
      let t = min(1, (CACurrentMediaTime() - began) / 0.42)
      amount = 1 - pow(1 - t, 3)
      setNeedsDisplay()
      if t >= 1 {
        let done = completion
        reset()
        done?()
      }
    }
    func reset() {
      link?.invalidate()
      link = nil
      identity = nil
      layout = nil
      fragments = []
      completion = nil
      amount = 0
      setNeedsDisplay()
    }
    override func draw(_ rect: CGRect) {
      guard let context = UIGraphicsGetCurrentContext(), let layout else { return }
      func mix(_ a: CGFloat, _ b: CGFloat) -> CGFloat { a + (b - a) * amount }
      let box = CGRect(
        x: mix(origin.minX, destination.minX), y: mix(origin.minY, destination.minY),
        width: mix(origin.width, destination.width), height: mix(origin.height, destination.height))
      UIColor.secondarySystemBackground.setFill()
      UIBezierPath(roundedRect: box, cornerRadius: mix(27, 20)).fill()
      let base = CGPoint(x: box.minX + mix(22, 14), y: box.minY + mix(17, 14))
      context.saveGState()
      context.clip(to: box)
      for fragment in fragments {
        let dx = fragment.target.minX - fragment.source.minX
        let dy = fragment.target.minY - fragment.source.minY
        let early = min(1, amount / 0.55)
        let late = max(0, (amount - 0.45) / 0.55)
        let shift = CGPoint(
          x: dx * (dy == 0 ? amount : dx > 0 ? early : late),
          y: dy * (dy == 0 ? amount : dx > 0 ? late : early))
        context.saveGState()
        context.clip(
          to: fragment.source.offsetBy(dx: base.x + shift.x, dy: base.y + shift.y).insetBy(
            dx: -0.5, dy: -0.5))
        layout.manager.drawGlyphs(
          forGlyphRange: NSRange(location: 0, length: layout.manager.numberOfGlyphs),
          at: CGPoint(x: base.x + shift.x, y: base.y + shift.y))
        context.restoreGState()
      }
      context.restoreGState()
    }
  }
#endif
