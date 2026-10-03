package expo.modules.removebg

import android.net.Uri
import android.os.Build
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import java.io.File

class ReactNativeRemoveBgModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("ReactNativeRemoveBg")

    AsyncFunction("removeBackgroundAsync") { uri: String, options: RemoveBgOptions ->
      val startedAt = System.currentTimeMillis()
      val context = requireContext()
      val resolved = options.resolve()
      val parsed = parseUri(uri)

      val source = RemoveBgPipeline.decode(context, parsed, resolved.maxSize)
      try {
        val (engine, lookup) = RemoveBgEngines.runFirstAvailable(resolved.chain, source)
        if (lookup.isEmpty) {
          throw RemoveBgException("no foreground subject was detected in this image")
        }

        val cutout = RemoveBgPipeline.composite(source, lookup, resolved.composite)
        try {
          mapOf(
            "uri" to RemoveBgPipeline.writePng(context, cutout.bitmap),
            "width" to cutout.bitmap.width,
            "height" to cutout.bitmap.height,
            "engine" to engine,
            "cropped" to cutout.cropped,
            "durationMs" to (System.currentTimeMillis() - startedAt),
          )
        } finally {
          if (!cutout.bitmap.isRecycled) cutout.bitmap.recycle()
        }
      } finally {
        if (!source.isRecycled) source.recycle()
      }
    }

    Function("getCapabilities") {
      val playServicesAvailable =
        GoogleApiAvailability.getInstance()
          .isGooglePlayServicesAvailable(requireContext()) == ConnectionResult.SUCCESS

      mapOf(
        "platform" to "android",
        "osVersion" to Build.VERSION.RELEASE,
        "engines" to listOf(RemoveBgEngines.ENGINE_SUBJECT, RemoveBgEngines.ENGINE_SELFIE),
        "defaultEngine" to "auto",
        "requiresModelDownload" to true,
        "playServicesAvailable" to playServicesAvailable,
      )
    }

    AsyncFunction("prepare") { engine: String? ->
      val requested = engine ?: "auto"
      val chain = chainForEngine(requested)
      // Only surface failures when a specific engine was requested; for "auto"
      // removeBackground resolves the chain again at call time anyway.
      RemoveBgEngines.warmUp(chain, throwOnFailure = requested != "auto")
      mapOf("engine" to chain.first())
    }
  }

  private fun requireContext() =
    appContext.reactContext ?: throw RemoveBgException("no React context is attached yet")

  private companion object {
    fun parseUri(uri: String): Uri =
      if (uri.contains("://")) Uri.parse(uri) else Uri.fromFile(File(uri))
  }
}

/**
 * Typed options with framework-handled coercion and defaults.
 *
 * Prefer this over a raw map so a JavaScript `number` never arrives as the wrong
 * Kotlin type.
 */
internal class RemoveBgOptions : Record {
  @Field
  val engine: String = "auto"

  @Field
  val maxSize: Int = 4096

  @Field
  val threshold: Double = 0.5

  @Field
  val feather: Double = 0.08

  @Field
  val maskOnly: Boolean = false

  @Field
  val crop: Boolean = false
}

internal data class ResolvedOptions(
  val chain: List<String>,
  val maxSize: Int,
  val composite: CompositeOptions,
)

internal fun RemoveBgOptions.resolve() = ResolvedOptions(
  chain = chainForEngine(engine),
  maxSize = maxSize.coerceAtLeast(0),
  composite = CompositeOptions(
    threshold = threshold.toFloat().coerceIn(0f, 1f),
    feather = feather.toFloat().coerceIn(0f, 0.5f),
    maskOnly = maskOnly,
    crop = crop,
  ),
)

/** `person` is the iOS name for people-only segmentation; Android calls it `selfie`. */
internal fun chainForEngine(engine: String): List<String> = when (engine) {
  "auto" -> RemoveBgEngines.AUTO_CHAIN
  "subject" -> listOf(RemoveBgEngines.ENGINE_SUBJECT)
  "selfie", "person" -> listOf(RemoveBgEngines.ENGINE_SELFIE)
  else -> throw RemoveBgException("unknown engine \"$engine\"")
}