package expo.modules.removebg

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** A row-major buffer of foreground confidences in `0..1`. */
internal class ConfidenceMask(
  val width: Int,
  val height: Int,
  val values: FloatArray,
) {
  /** Bilinearly samples at normalized `0..1` coordinates. */
  fun sample(u: Float, v: Float): Float {
    if (width <= 0 || height <= 0) return 0f
    if (width == 1 || height == 1) return values.firstOrNull() ?: 0f

    val fx = u.coerceIn(0f, 1f) * (width - 1)
    val fy = v.coerceIn(0f, 1f) * (height - 1)
    val x0 = fx.toInt()
    val y0 = fy.toInt()
    val x1 = minOf(x0 + 1, width - 1)
    val y1 = minOf(y0 + 1, height - 1)
    val tx = fx - x0
    val ty = fy - y0

    val topLeft = values[y0 * width + x0]
    val topRight = values[y0 * width + x1]
    val bottomLeft = values[y1 * width + x0]
    val bottomRight = values[y1 * width + x1]

    val top = topLeft + (topRight - topLeft) * tx
    val bottom = bottomLeft + (bottomRight - bottomLeft) * tx
    return top + (bottom - top) * ty
  }

  companion object {
    /**
     * ML Kit exposes the selfie mask as a `ByteBuffer` of floats. It is read in
     * native order because ML Kit writes it with the device's byte order rather
     * than the network order.
     */
    fun fromByteBuffer(buffer: ByteBuffer, width: Int, height: Int): ConfidenceMask {
      buffer.order(ByteOrder.nativeOrder())
      buffer.rewind()
      val values = FloatArray(width * height)
      val limit = minOf(values.size, buffer.remaining() / Float.SIZE_BYTES)
      for (i in 0 until limit) {
        values[i] = buffer.getFloat()
      }
      return ConfidenceMask(width, height, values)
    }

    /** ML Kit exposes each subject mask as a `FloatBuffer`. */
    fun fromFloatBuffer(buffer: FloatBuffer, width: Int, height: Int): ConfidenceMask {
      buffer.rewind()
      val values = FloatArray(width * height)
      val limit = minOf(values.size, buffer.remaining())
      for (i in 0 until limit) {
        // FloatBuffer exposes `get()`, unlike ByteBuffer's `getFloat()`.
        values[i] = buffer.get()
      }
      return ConfidenceMask(width, height, values)
    }
  }
}

/** A mask positioned inside full-image pixel coordinates. */
internal class PlacedMask(
  val mask: ConfidenceMask,
  val offsetX: Int,
  val offsetY: Int,
)

/**
 * Foreground confidence for any pixel of the source image.
 *
 * Holds a reference to the (small) model output instead of a full-resolution
 * copy, which keeps peak memory close to two bitmaps.
 */
internal class MaskLookup(
  private val masks: List<PlacedMask>,
) {
  val isEmpty: Boolean get() = masks.isEmpty()

  fun confidenceAt(x: Int, y: Int): Float {
    var best = 0f
    for (placed in masks) {
      val localX = x - placed.offsetX
      val localY = y - placed.offsetY
      val mask = placed.mask
      if (localX < 0 || localY < 0 || localX >= mask.width || localY >= mask.height) continue

      val u = if (mask.width > 1) localX.toFloat() / (mask.width - 1) else 0f
      val v = if (mask.height > 1) localY.toFloat() / (mask.height - 1) else 0f
      val confidence = mask.sample(u, v)
      if (confidence > best) best = confidence
    }
    return best
  }

  companion object {
    /** A single mask covering the whole image, as returned by selfie segmentation. */
    fun fullImage(mask: ConfidenceMask) = MaskLookup(listOf(PlacedMask(mask, 0, 0)))
  }
}