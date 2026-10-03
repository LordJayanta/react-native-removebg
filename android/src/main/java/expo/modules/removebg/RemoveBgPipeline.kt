package expo.modules.removebg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

internal data class CompositeOptions(
  val threshold: Float = 0.5f,
  val feather: Float = 0.08f,
  val maskOnly: Boolean = false,
  val crop: Boolean = false,
)

internal data class CutoutResult(val bitmap: Bitmap, val cropped: Boolean)

internal object RemoveBgPipeline {

  // MARK: - Decoding

  fun decode(context: Context, uri: Uri, maxSize: Int): Bitmap {
    val bitmap =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        decodeModern(context, uri, maxSize)
      } else {
        decodeLegacy(context, uri, maxSize)
      }
    if (bitmap.config != Bitmap.Config.ARGB_8888) {
      val converted = bitmap.copy(Bitmap.Config.ARGB_8888, false)
      if (converted != null) {
        bitmap.recycle()
        return converted
      }
    }
    return bitmap
  }

  /** `ImageDecoder` applies the EXIF orientation for us. */
  private fun decodeModern(context: Context, uri: Uri, maxSize: Int): Bitmap {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
      decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      val width = info.size.width
      val height = info.size.height
      val longest = max(width, height)
      if (maxSize > 0 && longest > maxSize) {
        val scale = maxSize.toFloat() / longest
        decoder.setTargetSize(
          (width * scale).roundToInt().coerceAtLeast(1),
          (height * scale).roundToInt().coerceAtLeast(1),
        )
      }
    }
  }

  private fun decodeLegacy(context: Context, uri: Uri, maxSize: Int): Bitmap {
    val resolver = context.contentResolver

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

    val options = BitmapFactory.Options().apply {
      inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxSize)
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
      ?: throw RemoveBgException("could not decode the image at \"$uri\"")

    return applyExifOrientation(context, uri, decoded)
  }

  private fun sampleSizeFor(width: Int, height: Int, maxSize: Int): Int {
    if (maxSize <= 0 || width <= 0 || height <= 0) return 1
    var longest = max(width, height)
    var sample = 1
    while (longest / 2 >= maxSize) {
      longest /= 2
      sample *= 2
    }
    return sample
  }

  private fun applyExifOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
    val orientation = try {
      context.contentResolver.openInputStream(uri)?.use { stream ->
        ExifInterface(stream).getAttributeInt(
          ExifInterface.TAG_ORIENTATION,
          ExifInterface.ORIENTATION_NORMAL,
        )
      } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: Exception) {
      ExifInterface.ORIENTATION_NORMAL
    }

    val matrix = Matrix()
    when (orientation) {
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      else -> return bitmap
    }

    val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (transformed != bitmap) bitmap.recycle()
    return transformed
  }

  // MARK: - Compositing

  fun composite(
    source: Bitmap,
    lookup: MaskLookup,
    options: CompositeOptions,
  ): CutoutResult {
    val width = source.width
    val height = source.height
    if (width <= 0 || height <= 0) {
      throw RemoveBgException("the decoded image has no pixels")
    }

    val low = options.threshold - options.feather
    val high = options.threshold + options.feather

    // A single pass over the mask so the (small) model output is sampled once,
    // while the alpha bytes are kept for the bbox and the colour pass.
    val alphas = ByteArray(width * height)
    var minX = width
    var minY = height
    var maxX = -1
    var maxY = -1

    for (y in 0 until height) {
      val rowStart = y * width
      for (x in 0 until width) {
        val alpha = (matte(lookup, x, y, low, high) * 255f).roundToInt().coerceIn(0, 255)
        alphas[rowStart + x] = alpha.toByte()
        if (alpha > 128) {
          if (x < minX) minX = x
          if (x > maxX) maxX = x
          if (y < minY) minY = y
          if (y > maxY) maxY = y
        }
      }
    }

    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)

    for (i in pixels.indices) {
      val alpha = alphas[i].toInt() and 0xFF
      pixels[i] =
        if (options.maskOnly) {
          // Opaque grayscale matte.
          (0xFF shl 24) or (alpha shl 16) or (alpha shl 8) or alpha
        } else {
          // `getPixels` yields non-premultiplied colours, so only alpha changes.
          val sourceAlpha = (pixels[i] ushr 24) and 0xFF
          val outAlpha = ((sourceAlpha * alpha) / 255f).roundToInt().coerceIn(0, 255)
          (outAlpha shl 24) or (pixels[i] and 0x00FFFFFF)
        }
    }

    val full = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

    val hasBox = options.crop && maxX >= minX && maxY >= minY
    if (!hasBox) return CutoutResult(full, false)

    val cropped = Bitmap.createBitmap(full, minX, minY, maxX - minX + 1, maxY - minY + 1)
    if (cropped != full) full.recycle()
    return CutoutResult(cropped, true)
  }

  private fun matte(lookup: MaskLookup, x: Int, y: Int, low: Float, high: Float): Float {
    val confidence = lookup.confidenceAt(x, y)
    if (high <= low) return if (confidence >= high) 1f else 0f
    val t = ((confidence - low) / (high - low)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
  }

  // MARK: - Output

  fun writePng(context: Context, bitmap: Bitmap): String {
    val directory = File(context.cacheDir, "react-native-removebg")
    if (!directory.exists() && !directory.mkdirs()) {
      throw RemoveBgException("could not create the cache directory at \"$directory\"")
    }

    val file = File(directory, "removebg-${UUID.randomUUID()}.png")
    FileOutputStream(file).use { stream ->
      if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
        throw RemoveBgException("failed to encode the result as PNG")
      }
    }
    return Uri.fromFile(file).toString()
  }
}