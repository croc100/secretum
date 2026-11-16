package net.crode.secretum.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import kotlin.math.max

object JpegUtil {
    /**
     * 폰 카메라 원본(수 MB) → 회전 보정 + 긴 변 maxDim 축소 + 재압축.
     * 업로드 크기/VLM 토큰 비용이 해상도에 비례하므로 1024px이면 충분.
     */
    fun normalize(jpeg: ByteArray, rotationDegrees: Int, maxDim: Int = 1024, quality: Int = 82): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2

        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return jpeg
        val scale = maxDim.toFloat() / max(decoded.width, decoded.height)
        val m = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
        }
        val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        return ByteArrayOutputStream().use { bos ->
            out.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            if (out !== decoded) out.recycle()
            decoded.recycle()
            bos.toByteArray()
        }
    }
}
