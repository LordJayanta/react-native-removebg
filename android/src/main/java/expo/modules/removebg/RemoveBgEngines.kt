package expo.modules.removebg

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions

internal class RemoveBgException(message: String, cause: Throwable? = null) :
  Exception(message, cause)

/**
 * Thin wrapper over the two ML Kit segmenters the module supports.
 *
 * `subject` separates arbitrary foreground objects but the model is downloaded on
 * demand by Play services. `selfie` is bundled in the APK, works fully offline
 * and without Play services, but only segments people.
 */
internal object RemoveBgEngines {

  const val ENGINE_SUBJECT = "subject"
  const val ENGINE_SELFIE = "selfie"

  /** `subject` first, then the always-available bundled `selfie` model. */
  val AUTO_CHAIN = listOf(ENGINE_SUBJECT, ENGINE_SELFIE)

  fun selfie(bitmap: Bitmap): MaskLookup {
    val options = SelfieSegmenterOptions.Builder()
      // Each call is an unrelated still image, so temporal smoothing is undesirable.
      .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
      .build()

    val segmenter = Segmentation.getClient(options)
    return try {
      val result = Tasks.await(segmenter.process(InputImage.fromBitmap(bitmap, 0)))
      val mask = ConfidenceMask.fromByteBuffer(result.buffer, result.width, result.height)
      MaskLookup.fullImage(mask)
    } catch (error: Exception) {
      throw RemoveBgException("selfie segmentation failed: ${error.message}", error)
    }
  }

  /**
   * Uses per-subject masks because ML Kit exposes their size and offset
   * explicitly, which removes any guesswork about the raw mask resolution.
   */
  fun subject(bitmap: Bitmap): MaskLookup {
    val subjectOptions = SubjectSegmenterOptions.SubjectResultOptions.Builder()
      .enableConfidenceMask()
      .build()

    val options = SubjectSegmenterOptions.Builder()
      .enableMultipleSubjects(subjectOptions)
      .build()

    val segmenter = SubjectSegmentation.getClient(options)
    return try {
      val result = Tasks.await(segmenter.process(InputImage.fromBitmap(bitmap, 0)))
      val placed = result.subjects.mapNotNull { subject ->
        val buffer = subject.confidenceMask ?: return@mapNotNull null
        if (subject.width <= 0 || subject.height <= 0) return@mapNotNull null
        PlacedMask(
          ConfidenceMask.fromFloatBuffer(buffer, subject.width, subject.height),
          subject.startX,
          subject.startY,
        )
      }
      MaskLookup(placed)
    } catch (error: Exception) {
      throw RemoveBgException("subject segmentation failed: ${error.message}", error)
    }
  }

  /** Runs one engine by name. */
  fun run(engine: String, bitmap: Bitmap): MaskLookup = when (engine) {
    ENGINE_SUBJECT -> subject(bitmap)
    ENGINE_SELFIE -> selfie(bitmap)
    else -> throw RemoveBgException("unknown engine \"$engine\"")
  }

  /**
   * Runs the first engine of [chain] that succeeds, so a missing Play services
   * model degrades to the bundled one instead of failing the whole call.
   */
  fun runFirstAvailable(chain: List<String>, bitmap: Bitmap): Pair<String, MaskLookup> {
      var lastError: Exception? = null
      for (engine in chain) {
        try {
          return engine to run(engine, bitmap)
        } catch (error: Exception) {
          lastError = error
        }
      }
      throw RemoveBgException(
        "every segmentation engine failed (${chain.joinToString()}): ${lastError?.message}",
        lastError,
      )
    }

  /**
   * Segments a throwaway bitmap so model initialisation happens ahead of the
   * first real request. With `throwOnFailure = false` errors are swallowed,
   * because `removeBackground` resolves the engine again at call time anyway.
   */
  fun warmUp(chain: List<String>, throwOnFailure: Boolean = false) {
    val probe = Bitmap.createBitmap(
      SEGMENTATION_MIN_EDGE,
      SEGMENTATION_MIN_EDGE,
      Bitmap.Config.ARGB_8888,
    )
    try {
      runFirstAvailable(chain, probe)
    } catch (error: Exception) {
      if (throwOnFailure) throw error
    } finally {
      probe.recycle()
    }
  }

  /**
   * ML Kit reports noticeably better results above this edge length, so callers
   * should upscale very small inputs before segmenting.
   */
  const val SEGMENTATION_MIN_EDGE = 256
}