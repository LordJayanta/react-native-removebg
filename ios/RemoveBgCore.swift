import CoreGraphics
import Foundation
import ImageIO
import Vision

enum RemoveBgError: LocalizedError {
  case fileNotFound(String)
  case unreadableImage
  case noSubjectFound
  case emptyMask
  case contextUnavailable
  case writeFailed
  case engineUnavailable(String)

  var errorDescription: String? {
    switch self {
    case .fileNotFound(let uri):
      return "react-native-removebg: cannot read an image at \"\(uri)\"."
    case .unreadableImage:
      return "react-native-removebg: the file could not be decoded as an image."
    case .noSubjectFound:
      return "react-native-removebg: no foreground subject was detected in this image."
    case .emptyMask:
      return "react-native-removebg: the segmentation mask came back empty."
    case .contextUnavailable:
      return "react-native-removebg: could not allocate an image buffer."
    case .writeFailed:
      return "react-native-removebg: failed to write the result to disk."
    case .engineUnavailable(let name):
      return "react-native-removebg: engine \"\(name)\" is not available on this device."
    }
  }
}

/// A grayscale foreground-confidence mask, normalized to row-major 0...1 values.
///
/// Kept independent of the pixel format Vision hands back so both the iOS 17+
/// instance mask and the person-segmentation fallback can share one pipeline.
struct Mask {
  let width: Int
  let height: Int
  let values: [Float]

  init?(pixelBuffer: CVPixelBuffer) {
    let width = CVPixelBufferGetWidth(pixelBuffer)
    let height = CVPixelBufferGetHeight(pixelBuffer)
    guard width > 0, height > 0 else { return nil }

    CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
    defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }

    guard let base = CVPixelBufferGetBaseAddress(pixelBuffer) else { return nil }
    let rowBytes = CVPixelBufferGetBytesPerRow(pixelBuffer)
    var values = [Float](repeating: 0, count: width * height)

    switch CVPixelBufferGetPixelFormatType(pixelBuffer) {
    case kCVPixelFormatType_OneComponent8:
      let bytes = base.assumingMemoryBound(to: UInt8.self)
      for y in 0..<height {
        let row = bytes.advanced(by: y * rowBytes)
        for x in 0..<width {
          values[y * width + x] = Float(row[x]) / 255.0
        }
      }
    case kCVPixelFormatType_OneComponent32Float:
      let floats = base.assumingMemoryBound(to: Float.self)
      let stride = rowBytes / MemoryLayout<Float>.size
      for y in 0..<height {
        let row = floats.advanced(by: y * stride)
        for x in 0..<width {
          values[y * width + x] = row[x]
        }
      }
    default:
      return nil
    }

    self.width = width
    self.height = height
    self.values = values
  }

  /// Bilinearly samples the mask. `u`/`v` are normalized 0...1 coordinates, with
  /// `v = 0` meaning the *first* row of `values` (the top of the Vision buffer).
  func sample(u: Float, v: Float) -> Float {
    guard width > 1, height > 1 else { return values.first ?? 0 }

    let fx = max(0, min(1, u)) * Float(width - 1)
    let fy = max(0, min(1, v)) * Float(height - 1)
    let x0 = Int(fx)
    let y0 = Int(fy)
    let x1 = min(x0 + 1, width - 1)
    let y1 = min(y0 + 1, height - 1)
    let tx = fx - Float(x0)
    let ty = fy - Float(y0)

    let topLeft = values[y0 * width + x0]
    let topRight = values[y0 * width + x1]
    let bottomLeft = values[y1 * width + x0]
    let bottomRight = values[y1 * width + x1]

    let top = topLeft + (topRight - topLeft) * tx
    let bottom = bottomLeft + (bottomRight - bottomLeft) * tx
    return top + (bottom - top) * ty
  }
}

struct CompositeOptions {
  var threshold: Float = 0.5
  var feather: Float = 0.08
  var maskOnly: Bool = false
  var crop: Bool = false
}

/// Returns the composited image together with the size actually produced.
struct CompositeResult {
  let image: CGImage
  let cropped: Bool
}

enum RemoveBgCore {
  // MARK: - Input

  /// Decodes the image, applying the EXIF orientation and capping the longest edge.
  static func loadImage(at uri: String, maxSize: Int) throws -> CGImage {
    guard let url = resolveURL(uri), let source = CGImageSourceCreateWithURL(url as CFURL, nil) else {
      throw RemoveBgError.fileNotFound(uri)
    }

    var options: [CFString: Any] = [
      kCGImageSourceCreateThumbnailFromImageAlways: true,
      // Handles EXIF rotation so the mask and the pixels agree on orientation.
      kCGImageSourceCreateThumbnailWithTransform: true,
      kCGImageSourceShouldCacheImmediately: true,
    ]
    if maxSize > 0 {
      options[kCGImageSourceThumbnailMaxPixelSize] = maxSize
    }

    guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
      throw RemoveBgError.unreadableImage
    }
    return image
  }

  static func resolveURL(_ uri: String) -> URL? {
    if let url = URL(string: uri), url.scheme != nil {
      return url
    }
    return URL(fileURLWithPath: uri)
  }

  // MARK: - Segmentation

  /// General subject cutout. Requires iOS 17.
  @available(iOS 17.0, *)
  static func instanceMask(for image: CGImage) throws -> Mask {
    let request = VNGenerateForegroundInstanceMaskRequest()
    let handler = VNImageRequestHandler(cgImage: image, options: [:])
    try handler.perform([request])

    guard let observation = request.results?.first as? VNInstanceMaskObservation else {
      throw RemoveBgError.noSubjectFound
    }
    let instances = observation.allInstances
    guard !instances.isEmpty else { throw RemoveBgError.noSubjectFound }

    let buffer = try observation.generateScaledMaskForImage(forInstances: instances, from: handler)
    guard let mask = Mask(pixelBuffer: buffer) else { throw RemoveBgError.emptyMask }
    return mask
  }

  /// People-only fallback used on iOS 16 and older.
  static func personMask(for image: CGImage) throws -> Mask {
    let request = VNGeneratePersonSegmentationRequest()
    request.qualityLevel = .balanced
    request.outputPixelFormat = kCVPixelFormatType_OneComponent8

    let handler = VNImageRequestHandler(cgImage: image, options: [:])
    try handler.perform([request])

    guard
      let observation = request.results?.first as? VNPixelBufferObservation,
      let mask = Mask(pixelBuffer: observation.pixelBuffer)
    else {
      throw RemoveBgError.noSubjectFound
    }
    return mask
  }

  // MARK: - Compositing

  static func composite(
    mask: Mask,
    image: CGImage,
    options: CompositeOptions
  ) throws -> CompositeResult {
    let width = image.width
    let height = image.height
    guard width > 0, height > 0 else { throw RemoveBgError.unreadableImage }

    // A bitmap context is bottom-up: memory row 0 is the *bottom* of the image,
    // while Vision masks are top-down, hence the vertical flip when sampling.
    let info = CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue)
    guard
      let context = CGContext(
        data: nil,
        width: width,
        height: height,
        bitsPerComponent: 8,
        bytesPerRow: width * 4,
        space: CGColorSpaceCreateDeviceRGB(),
        bitmapInfo: info.rawValue
      ),
      let raw = context.data
    else {
      throw RemoveBgError.contextUnavailable
    }

    context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))

    let pixelCount = width * height
    var pixels = [UInt8](repeating: 0, count: pixelCount * 4)
    pixels.withUnsafeMutableBytes { destination in
      guard let base = destination.baseAddress else { return }
      raw.copyMemory(from: base, byteCount: pixels.count)
    }

    // Confidence -> alpha with a smooth transition band around the threshold.
    let low = options.threshold - options.feather
    let high = options.threshold + options.feather
    var alphas = [UInt8](repeating: 0, count: pixelCount)
    for y in 0..<height {
      let v = 1.0 - (height == 1 ? 0 : Float(y) / Float(height - 1))
      for x in 0..<width {
        let u = width == 1 ? 0 : Float(x) / Float(width - 1)
        let matte = smoothstep(low, high, mask.sample(u: u, v: v))
        alphas[y * width + x] = UInt8(max(0, min(255, (matte * 255).rounded())))
      }
    }

    if options.maskOnly {
      for i in 0..<pixelCount {
        let offset = i * 4
        let grey = alphas[i]
        pixels[offset] = grey
        pixels[offset + 1] = grey
        pixels[offset + 2] = grey
        pixels[offset + 3] = 255
      }
    } else {
      for i in 0..<pixelCount {
        let offset = i * 4
        let matte = Float(alphas[i]) / 255.0
        // The context is premultiplied, so scaling RGB by the matte both
        // un-premultiplies against the source alpha and re-premultiplies.
        pixels[offset] = UInt8((Float(pixels[offset]) * matte).rounded())
        pixels[offset + 1] = UInt8((Float(pixels[offset + 1]) * matte).rounded())
        pixels[offset + 2] = UInt8((Float(pixels[offset + 2]) * matte).rounded())
        pixels[offset + 3] = UInt8((Float(pixels[offset + 3]) * matte).rounded())
      }
    }

    pixels.withUnsafeBytes { source in
      guard let base = source.baseAddress else { return }
      raw.copyMemory(from: base, byteCount: pixels.count)
    }

    var cropped = false
    if options.crop, let box = boundingBox(of: alphas, width: width, height: height) {
      // Coordinates are already bottom-up, which is what `crop(to:)` expects.
      context.crop(to: box)
      cropped = true
    }

    guard let output = context.makeImage() else { throw RemoveBgError.writeFailed }
    return CompositeResult(image: output, cropped: cropped)
  }

  private static func boundingBox(of alphas: [UInt8], width: Int, height: Int) -> CGRect? {
    var minX = width
    var minY = height
    var maxX = -1
    var maxY = -1

    for y in 0..<height {
      let rowStart = y * width
      for x in 0..<width where alphas[rowStart + x] > 128 {
        if x < minX { minX = x }
        if x > maxX { maxX = x }
        if y < minY { minY = y }
        if y > maxY { maxY = y }
      }
    }

    guard maxX >= minX, maxY >= minY else { return nil }
    return CGRect(x: minX, y: minY, width: maxX - minX + 1, height: maxY - minY + 1)
  }

  private static func smoothstep(_ edge0: Float, _ edge1: Float, _ x: Float) -> Float {
    guard edge1 > edge0 else { return x >= edge1 ? 1 : 0 }
    let t = max(0, min(1, (x - edge0) / (edge1 - edge0)))
    return t * t * (3 - 2 * t)
  }

  // MARK: - Output

  static func writePNG(_ image: CGImage) throws -> URL {
    let folder = FileManager.default.temporaryDirectory
      .appendingPathComponent("react-native-removebg", isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)

    let url = folder.appendingPathComponent("removebg-\(UUID().uuidString).png")
    guard
      let destination = CGImageDestinationCreateWithURL(
        url as CFURL,
        "public.png" as CFString,
        1,
        nil
      )
    else {
      throw RemoveBgError.writeFailed
    }

    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else { throw RemoveBgError.writeFailed }
    return url
  }
}