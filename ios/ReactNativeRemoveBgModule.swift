import ExpoModulesCore
import Foundation

public class ReactNativeRemoveBgModule: Module {
  public func definition() -> ModuleDefinition {
    Name("ReactNativeRemoveBg")

    AsyncFunction("removeBackgroundAsync") { (uri: String, options: RemoveBgOptions) throws -> [String: Any] in
      let startedAt = Date()
      let image = try RemoveBgCore.loadImage(at: uri, maxSize: options.maxSize)
      let (engine, mask) = try ReactNativeRemoveBgModule.runSegmentation(
        engine: options.engine,
        image: image
      )
      let composited = try RemoveBgCore.composite(mask: mask, image: image, options: options.composite)
      let url = try RemoveBgCore.writePNG(composited.image)

      return [
        "uri": url.absoluteString,
        "width": composited.image.width,
        "height": composited.image.height,
        "engine": engine,
        "cropped": composited.cropped,
        "durationMs": Int(Date().timeIntervalSince(startedAt) * 1000),
      ]
    }

    Function("getCapabilities") { () -> [String: Any] in
      var engines: [String] = []
      if #available(iOS 17.0, *) {
        engines.append("subject")
      }
      engines.append(contentsOf: ["selfie", "person"])

      return [
        "platform": "ios",
        // ProcessInfo rather than UIDevice, which is documented as main-thread
        // only while this function may run on the JS thread.
        "osVersion": ProcessInfo.processInfo.operatingSystemVersionString,
        "engines": engines,
        "defaultEngine": defaultEngine,
        "requiresModelDownload": false,
      ]
    }

    AsyncFunction("prepare") { (engine: String?) throws -> [String: Any] in
      // Vision has no separate model to stage, so this just reports the engine
      // that a real call would end up using.
      return ["engine": try ReactNativeRemoveBgModule.resolveEngine(engine ?? "auto")]
    }
  }

  // MARK: - Engine resolution

  /// The engine `auto` picks, which is the most capable one this OS supports.
  private static var defaultEngine: String {
    // Vision's instance mask is the only general-subject API and it is iOS 17+.
    if #available(iOS 17.0, *) { return "subject" }
    return "person"
  }

  /// Maps the requested engine onto something this OS version can actually run.
  private static func resolveEngine(_ requested: String) throws -> String {
    switch requested {
    case "selfie", "person":
      return "person"
    case "subject", "auto":
      return defaultEngine
    default:
      throw RemoveBgError.engineUnavailable(requested)
    }
  }

  private static func runSegmentation(engine requested: String, image: CGImage) throws -> (String, Mask) {
    if #available(iOS 17.0, *) {
      if requested == "selfie" || requested == "person" {
        return ("person", try RemoveBgCore.personMask(for: image))
      }
      return ("subject", try RemoveBgCore.instanceMask(for: image))
    }

    // iOS 16 and older can only segment people.
    guard requested != "subject" else {
      // The caller explicitly asked for general subjects on an OS that cannot do
      // it, so fail loudly instead of silently returning a person-only cutout.
      throw RemoveBgError.engineUnavailable("subject")
    }
    return ("person", try RemoveBgCore.personMask(for: image))
  }
}

/// Typed options with framework-handled coercion and defaults.
///
/// Prefer this over a raw dictionary so a JavaScript `number` never arrives as
/// the wrong Swift type.
struct RemoveBgOptions: Record {
  @Field var engine: String = "auto"
  @Field var maxSize: Int = 4096
  @Field var threshold: Double = 0.5
  @Field var feather: Double = 0.08
  @Field var maskOnly: Bool = false
  @Field var crop: Bool = false

  var composite: CompositeOptions {
    CompositeOptions(
      threshold: Float(min(1, max(0, threshold))),
      feather: Float(min(0.5, max(0, feather))),
      maskOnly: maskOnly,
      crop: crop
    )
  }
}