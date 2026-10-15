import Foundation
import simd

/// Same fixed J2000 two-body elements and display clock as Android, not a live ephemeris.
struct SolarOrbit: Sendable {
  let axis: Double
  let eccentricity: Double
  let inclination: Double
  let longitude: Double
  let perihelion: Double
  let node: Double
  var period: Double { pow(axis, 1.5) }
  func position(years: Double) -> SIMD3<Double> {
    let radians = Double.pi / 180
    let mean =
      (longitude - perihelion) * radians + years.truncatingRemainder(dividingBy: period) / period
      * 2 * .pi
    var eccentric = mean
    for _ in 0..<8 {
      eccentric -=
        (eccentric - eccentricity * sin(eccentric) - mean) / (1 - eccentricity * cos(eccentric))
    }
    let x = axis * (cos(eccentric) - eccentricity)
    let y = axis * sqrt(1 - eccentricity * eccentricity) * sin(eccentric)
    let w = (perihelion - node) * radians
    let n = node * radians
    let i = inclination * radians
    return SIMD3(
      (cos(w) * cos(n) - sin(w) * sin(n) * cos(i)) * x
        + (-sin(w) * cos(n) - cos(w) * sin(n) * cos(i)) * y,
      (cos(w) * sin(n) + sin(w) * cos(n) * cos(i)) * x
        + (-sin(w) * sin(n) + cos(w) * cos(n) * cos(i)) * y,
      sin(w) * sin(i) * x + cos(w) * sin(i) * y)
  }
}
enum SolarSystem {
  static let earth = SolarOrbit(
    axis: 1.00000261, eccentricity: 0.01671123, inclination: -0.00001531, longitude: 100.46457166,
    perihelion: 102.93768193, node: 0)
  static let planets: [(name: String, orbit: SolarOrbit, radius: Double)] = [
    (
      "Mercury",
      .init(
        axis: 0.38709927, eccentricity: 0.20563593, inclination: 7.00497902,
        longitude: 252.25032350, perihelion: 77.45779628, node: 48.33076593), 0.0049
    ),
    (
      "Venus",
      .init(
        axis: 0.72333566, eccentricity: 0.00677672, inclination: 3.39467605,
        longitude: 181.97909950, perihelion: 131.60246718, node: 76.67984255), 0.0121
    ),
    (
      "Mars",
      .init(
        axis: 1.52371034, eccentricity: 0.09339410, inclination: 1.84969142, longitude: -4.55343205,
        perihelion: -23.94362959, node: 49.55953891), 0.0068
    ),
    (
      "Jupiter",
      .init(
        axis: 5.202887, eccentricity: 0.04838624, inclination: 1.30439695, longitude: 34.39644051,
        perihelion: 14.72847983, node: 100.47390909), 0.140
    ),
    (
      "Saturn",
      .init(
        axis: 9.53667594, eccentricity: 0.05386179, inclination: 2.48599187, longitude: 49.95424423,
        perihelion: 92.59887831, node: 113.66242448), 0.117
    ),
    (
      "Uranus",
      .init(
        axis: 19.18916464, eccentricity: 0.04725744, inclination: 0.77263783,
        longitude: 313.23810451, perihelion: 170.95427630, node: 74.01692503), 0.051
    ),
    (
      "Neptune",
      .init(
        axis: 30.06992276, eccentricity: 0.00859048, inclination: 1.77004347,
        longitude: -55.12002969, perihelion: 44.96476227, node: 131.78422574), 0.049
    ),
  ]
  static func cameraAxes(_ v: SIMD3<Double>) -> SIMD3<Double> {
    let longitude = 42 * Double.pi / 180
    let latitude = -22 * Double.pi / 180
    let roll = -15 * Double.pi / 180
    let right = -sin(longitude) * v.x + cos(longitude) * v.y
    let up = -sin(latitude) * (cos(longitude) * v.x + sin(longitude) * v.y) + cos(latitude) * v.z
    let back = -cos(latitude) * (cos(longitude) * v.x + sin(longitude) * v.y) - sin(latitude) * v.z
    return SIMD3(right * cos(roll) - up * sin(roll), right * sin(roll) + up * cos(roll), back)
  }
  static func project(_ relative: SIMD3<Double>) -> SIMD3<Double>? {
    let depth = 0.00017 - relative.z
    guard depth > 0.00001 else { return nil }
    let scale = 5 / depth
    return SIMD3(relative.x * scale, relative.y * scale, scale)
  }
  static func moon(seconds: Double) -> SIMD3<Double> {
    orbit(radius: 3.1, inclination: 1.05, heading: -0.32, angle: 0.45 + seconds / 80 * 2 * .pi)
  }
  static func orbit(radius: Double, inclination: Double, heading: Double, angle: Double) -> SIMD3<
    Double
  > {
    let x = radius * cos(angle)
    let y = radius * sin(angle) * cos(inclination)
    return SIMD3(
      x * cos(heading) - y * sin(heading), x * sin(heading) + y * cos(heading),
      radius * sin(angle) * sin(inclination))
  }
}
