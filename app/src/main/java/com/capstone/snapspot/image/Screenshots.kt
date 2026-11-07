package com.capstone.snapspot.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** A screenshot copied into app storage. Only [ShotReader] decides what of it leaves the phone. */
data class PreparedShot(val fileUri: String)

/** Copies a picked or captured image into app storage upright and at most 1024px wide. */
object Screenshots {
    private const val WIDTH = 1024

    suspend fun prepare(ctx: Context, uri: Uri): PreparedShot = withContext(Dispatchers.IO) {
        try {
            val orientation = runCatching {
                ctx.contentResolver.openInputStream(uri).use { stream ->
                    ExifInterface(requireNotNull(stream)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            val rotated = orientation in setOf(ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSVERSE, ExifInterface.ORIENTATION_ROTATE_270)
            val width = if (rotated) bounds.outHeight else bounds.outWidth
            var sample = 1
            while (width / (sample * 2) >= WIDTH) sample *= 2
            val decoded = ctx.contentResolver.openInputStream(uri).use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: error("이미지를 열 수 없어요.")
            val upright = orient(decoded, orientation)
            if (upright !== decoded) decoded.recycle()
            val scaled = if (upright.width > WIDTH) {
                upright.scale(WIDTH, upright.height * WIDTH / upright.width)
            } else upright
            if (scaled !== upright) upright.recycle()
            val bytes = try {
                ByteArrayOutputStream().use { out ->
                    check(scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)) { "사진을 저장하지 못했어요." }
                    out.toByteArray()
                }
            } finally {
                scaled.recycle()
            }
            val dir = File(ctx.filesDir, "shots").apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}.jpg").apply { writeBytes(bytes) }
            PreparedShot(Uri.fromFile(file).toString())
        } finally {
            // A camera result is temporary. Gallery/shared images belong to their original provider.
            CameraPhotos.discard(ctx, uri)
        }
    }

    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                else -> return bitmap
            }
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
