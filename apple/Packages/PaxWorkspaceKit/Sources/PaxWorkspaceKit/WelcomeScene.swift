#if os(iOS)
  import SwiftUI
  import SceneKit
  import ImageIO

  struct WelcomeScene: UIViewRepresentable {
    let animated: Bool
    let departing: Bool
    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeUIView(context: Context) -> SCNView {
      let view = SCNView()
      view.backgroundColor = .clear
      view.antialiasingMode = .multisampling4X
      view.preferredFramesPerSecond = 30
      context.coordinator.build(view)
      return view
    }
    func updateUIView(_ view: SCNView, context: Context) {
      context.coordinator.update(animated: animated, departing: departing)
    }
    static func dismantleUIView(_ view: SCNView, coordinator: Coordinator) { coordinator.pause() }

    @MainActor final class Coordinator: NSObject {
      let scene = SCNScene()
      let camera = SCNNode()
      let earth = SCNNode()
      let clouds = SCNNode()
      let moon = SCNNode()
      let sunLight = SCNNode()
      var spacecraft: [SCNNode] = []
      var planets: [SCNNode] = []
      weak var view: SCNView?
      var display: CADisplayLink?
      var last: CFTimeInterval?
      var seconds: Double = 0
      var departure: Double = 0
      var departing = false
      var arrival: Double = 0
      func build(_ view: SCNView) {
        self.view = view
        view.scene = scene
        camera.camera = SCNCamera()
        camera.camera?.fieldOfView = 47
        camera.camera?.zFar = 500
        camera.position = SCNVector3(0, 0, 10)
        scene.rootNode.addChildNode(camera)
        view.pointOfView = camera
        earth.geometry = SCNSphere(radius: 1.34)
        (earth.geometry as? SCNSphere)?.segmentCount = 96
        let ground = SCNMaterial()
        ground.diffuse.contents = texture("earth-day")
        ground.emission.contents = texture("earth-night")
        ground.specular.contents = UIColor(white: 0.12, alpha: 1)
        // Day/night masks use the same world-space sunlight as the Moon and spacecraft.
        ground.shaderModifiers = [
          .surface: """
          #pragma arguments
          float3 sunlight;
          #pragma body
          float3 n = normalize(_surface.normal);
          float day = dot(n, normalize(sunlight));
          _surface.emission.rgb *= (1.0 - smoothstep(-0.12, 0.14, day)) * float3(1.6, 1.05, 0.48);
          """
        ]
        earth.geometry?.firstMaterial = ground
        scene.rootNode.addChildNode(earth)
        clouds.geometry = SCNSphere(radius: 1.356)
        (clouds.geometry as? SCNSphere)?.segmentCount = 96
        let cloud = SCNMaterial()
        cloud.diffuse.contents = UIColor.white
        cloud.transparent.contents = texture("earth-clouds")
        cloud.transparencyMode = .rgbZero
        cloud.transparency = 0.65
        cloud.writesToDepthBuffer = false
        clouds.geometry?.firstMaterial = cloud
        scene.rootNode.addChildNode(clouds)
        let atmosphere = SCNNode(geometry: SCNSphere(radius: 1.39))
        let air = SCNMaterial()
        air.diffuse.contents = UIColor.clear
        air.emission.contents = UIColor(red: 0.15, green: 0.5, blue: 1, alpha: 1)
        air.isDoubleSided = true
        air.writesToDepthBuffer = false
        air.blendMode = .add
        air.shaderModifiers = [
          .surface: """
          #pragma body
          float rim = pow(1.0 - abs(dot(normalize(_surface.normal), normalize(_surface.view))), 3.0);
          _surface.transparent.a = rim * 0.24;
          """
        ]
        atmosphere.geometry?.firstMaterial = air
        scene.rootNode.addChildNode(atmosphere)
        moon.geometry = SCNSphere(radius: 0.366)
        (moon.geometry as? SCNSphere)?.segmentCount = 48
        let lunar = SCNMaterial()
        lunar.diffuse.contents = moonTexture()
        lunar.roughness.contents = 1
        moon.geometry?.firstMaterial = lunar
        scene.rootNode.addChildNode(moon)
        for index in 0..<3 {
          let satellite = satellite(index)
          spacecraft.append(satellite)
          scene.rootNode.addChildNode(satellite)
        }
        sunLight.light = SCNLight()
        sunLight.light?.type = .directional
        sunLight.light?.intensity = 1200
        scene.rootNode.addChildNode(sunLight)
        let ambient = SCNNode()
        ambient.light = SCNLight()
        ambient.light?.type = .ambient
        ambient.light?.intensity = 55
        ambient.light?.color = UIColor(red: 0.5, green: 0.62, blue: 0.85, alpha: 1)
        scene.rootNode.addChildNode(ambient)
        let colors: [UIColor] = [
          .init(red: 1, green: 0.9, blue: 0.6, alpha: 1), .gray,
          .init(red: 0.78, green: 0.65, blue: 0.4, alpha: 1),
          .init(red: 0.66, green: 0.25, blue: 0.13, alpha: 1),
          .init(red: 0.72, green: 0.57, blue: 0.41, alpha: 1),
          .init(red: 0.8, green: 0.72, blue: 0.52, alpha: 1), .cyan, .systemBlue,
        ]
        for index in 0..<8 {
          let node = SCNNode(geometry: SCNSphere(radius: 1))
          (node.geometry as? SCNSphere)?.segmentCount = 24
          node.geometry?.firstMaterial?.diffuse.contents = colors[index]
          if index == 0 { node.geometry?.firstMaterial?.emission.contents = colors[index] }
          if index == 5 {
            let ring = SCNNode(geometry: SCNTorus(ringRadius: 1.5, pipeRadius: 0.17))
            ring.scale = SCNVector3(1, 0.12, 1)
            ring.eulerAngles.x = 0.35
            ring.geometry?.firstMaterial?.diffuse.contents = colors[index].withAlphaComponent(0.65)
            node.addChildNode(ring)
          }
          planets.append(node)
          scene.rootNode.addChildNode(node)
        }
        step()
      }
      func update(animated: Bool, departing: Bool) {
        self.departing = departing
        if animated {
          if display == nil {
            last = nil
            display = CADisplayLink(target: self, selector: #selector(tick))
            display?.preferredFrameRateRange = CAFrameRateRange(
              minimum: 15, maximum: 30, preferred: 30)
            display?.add(to: .main, forMode: .common)
          }
        } else {
          pause()
          arrival = 1
          departure = departing ? 1 : 0
          step()
        }
      }
      func pause() {
        display?.invalidate()
        display = nil
        last = nil
      }
      @objc func tick(_ link: CADisplayLink) {
        let delta = min(0.1, last.map { link.timestamp - $0 } ?? 0)
        last = link.timestamp
        seconds += delta
        arrival = min(1, arrival + delta / 2.2)
        departure = departing ? min(1, departure + delta / 1.05) : 0
        step()
      }
      func step() {
        let earthPosition = SolarSystem.cameraAxes(
          SolarSystem.earth.position(years: 0.3 + seconds / 120))
        let sunlight = -earthPosition
        sunLight.position = SCNVector3(sunlight * 10)
        sunLight.look(at: SCNVector3Zero)
        earth.geometry?.firstMaterial?.setValue(
          NSValue(scnVector3: SCNVector3(sunlight)), forKey: "sunlight")
        earth.eulerAngles = SCNVector3(0.1, -1.7 + seconds * 2 * .pi / 64, -0.15)
        clouds.eulerAngles = SCNVector3(0.1, -1.7 + seconds * 2 * .pi / 68, -0.15)
        moon.position = SCNVector3(SolarSystem.moon(seconds: seconds))
        moon.look(at: SCNVector3Zero)
        let orbits: [(Double, Double, Double, Double, Double)] = [
          (1.92, 1.12, -0.38, 2, 0.65), (2.1, 1.25, 1.02, -1, 2.7), (2.02, 0.95, -0.97, 1, 4.1),
        ]
        for (i, orbit) in orbits.enumerated() {
          let angle = seconds / 42 * 2 * .pi * orbit.3 + orbit.4
          spacecraft[i].position = SCNVector3(
            SolarSystem.orbit(radius: orbit.0, inclination: orbit.1, heading: orbit.2, angle: angle)
          )
          spacecraft[i].eulerAngles = SCNVector3(0.25, angle + 0.4, orbit.2)
        }
        let positions =
          [SIMD3<Double>.zero]
          + SolarSystem.planets.map {
            SolarSystem.cameraAxes($0.orbit.position(years: 0.3 + seconds / 120))
          }
        let radii = [0.056] + SolarSystem.planets.map(\.radius)
        for index in planets.indices {
          guard let projected = SolarSystem.project(positions[index] - earthPosition) else {
            planets[index].isHidden = true
            continue
          }
          planets[index].isHidden = false
          planets[index].position = SCNVector3(projected.x * 13, projected.y * 13, -120)
          let radius = projected.z * radii[index] * 13
          planets[index].scale = SCNVector3(radius, radius, radius)
          planets[index].opacity = 1 - departure
        }
        let ease = departure * departure * (3 - 2 * departure)
        camera.position.z = Float((10 + (1 - arrival) * 2) * pow(0.15, ease))
        view?.setNeedsDisplay()
      }
      // Black Marble and clouds are grayscale JPEGs. Normalize all texture uploads to
      // sRGB RGBA8; SceneKit's grayscale Metal upload is rejected by GPU validation.
      // Thumbnail decoding also bounds texture memory on phones.
      private func texture(_ name: String) -> UIImage? {
        guard let url = Bundle.module.url(forResource: name, withExtension: "jpg"),
          let source = CGImageSourceCreateWithURL(url as CFURL, nil),
          let image = CGImageSourceCreateThumbnailAtIndex(
            source, 0,
            [
              kCGImageSourceCreateThumbnailFromImageAlways: true,
              kCGImageSourceThumbnailMaxPixelSize: 2048,
              kCGImageSourceCreateThumbnailWithTransform: true,
            ] as CFDictionary),
          let space = CGColorSpace(name: CGColorSpace.sRGB),
          let bitmap = CGContext(
            data: nil, width: image.width, height: image.height,
            bitsPerComponent: 8, bytesPerRow: image.width * 4, space: space,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
              | CGBitmapInfo.byteOrder32Big.rawValue)
        else { return nil }
        bitmap.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
        return bitmap.makeImage().map { UIImage(cgImage: $0) }
      }
      private func satellite(_ kind: Int) -> SCNNode {
        let root = SCNNode()
        let body = SCNNode(
          geometry: SCNBox(width: 0.18, height: 0.15, length: 0.22, chamferRadius: 0.015))
        body.geometry?.firstMaterial?.diffuse.contents = UIColor(white: 0.7, alpha: 1)
        root.addChildNode(body)
        for side in [-1.0, 1.0] {
          let panel = SCNNode(
            geometry: SCNBox(
              width: kind == 1 ? 0.33 : 0.24, height: 0.012, length: 0.2, chamferRadius: 0))
          panel.position.x = Float(side * 0.23)
          panel.geometry?.firstMaterial?.diffuse.contents = UIColor(
            red: 0.07, green: 0.2, blue: 0.42, alpha: 1)
          root.addChildNode(panel)
        }
        if kind == 2 {
          let dish = SCNNode(geometry: SCNCone(topRadius: 0.12, bottomRadius: 0.025, height: 0.07))
          dish.position.y = 0.12
          dish.geometry?.firstMaterial?.diffuse.contents = UIColor.lightGray
          root.addChildNode(dish)
        }
        return root
      }
      /// Stable illustrative terrain, as on Android; this is not a geographic Moon map.
      private func moonTexture() -> UIImage {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 512, height: 256))
        return renderer.image { context in
          UIColor(white: 0.58, alpha: 1).setFill()
          context.fill(CGRect(x: 0, y: 0, width: 512, height: 256))
          for index in 0..<180 {
            let x = CGFloat((index * 7919 + 1969) % 512)
            let y = CGFloat((index * 3571 + 83) % 256)
            let radius = CGFloat(index < 8 ? 20 + index % 13 : 2 + index % 7)
            UIColor(white: index < 8 ? 0.37 : 0.48, alpha: 1).setFill()
            context.cgContext.fillEllipse(in: CGRect(x: x, y: y, width: radius * 2, height: radius))
            UIColor(white: 0.67, alpha: 0.6).setStroke()
            context.cgContext.strokeEllipse(
              in: CGRect(x: x, y: y, width: radius * 2, height: radius))
          }
        }
      }
    }
  }
  extension SCNVector3 {
    fileprivate init(_ value: SIMD3<Double>) { self.init(value.x, value.y, value.z) }
  }
#endif
