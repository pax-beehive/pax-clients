#if os(iOS)
  import SwiftUI

  struct WelcomeStarfield: View {
    let animated: Bool
    var body: some View {
      TimelineView(.animation(minimumInterval: 1.0 / 15, paused: !animated)) { time in
        Canvas { context, size in
          let center = CGPoint(x: size.width * 0.5, y: size.height * 0.36)
          let radius = max(size.width, size.height) * 0.55
          context.fill(
            Path(
              ellipseIn: CGRect(
                x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2)),
            with: .radialGradient(
              Gradient(colors: [Color(red: 0.04, green: 0.09, blue: 0.15), .clear]), center: center,
              startRadius: 0, endRadius: radius))
          let count = min(520, max(180, Int(size.width * size.height / 1400)))
          let phase = animated ? time.date.timeIntervalSinceReferenceDate / 42 * 2 * Double.pi : 0
          for index in 0..<count {
            let x = Double((index * 7919 + 97) % 1009) / 1009
            let y = Double((index * 3571 + 53) % 997) / 997
            let strength = Double((index * 439 + 89) % 991) / 991
            let radius = strength > 0.94 ? 1.1 : 0.38 + strength * 0.38
            let exposure = 1 + 0.035 * sin(phase * 0.6 + Double(index) * 1.71)
            let opacity = (0.22 + strength * 0.52) * exposure * (y > 0.58 ? 0.48 : 1)
            let color =
              index % 7 == 0
              ? Color(red: 1, green: 0.89, blue: 0.73) : Color(red: 0.83, green: 0.89, blue: 1)
            context.fill(
              Path(
                ellipseIn: CGRect(
                  x: x * size.width, y: y * size.height, width: radius * 2, height: radius * 2)),
              with: .color(color.opacity(opacity)))
          }
        }
      }
    }
  }
#endif
