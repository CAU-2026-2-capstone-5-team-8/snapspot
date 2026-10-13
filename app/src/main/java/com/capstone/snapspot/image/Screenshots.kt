package com.capstone.snapspot.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

data class PreparedShot(val fileUri: String, val base64Jpeg: String)

/** Copies a picked screenshot into app storage at 1024px wide (what Gemini gets, and what the app keeps). */
object Screenshots {
    private const val WIDTH = 1024

    suspend fun prepare(ctx: Context, uri: Uri): PreparedShot = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= WIDTH) sample *= 2
        val decoded = ctx.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("이미지를 열 수 없어요.")
        val scaled = if (decoded.width > WIDTH) {
            Bitmap.createScaledBitmap(decoded, WIDTH, decoded.height * WIDTH / decoded.width, true)
        } else decoded
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        }
        val dir = File(ctx.filesDir, "shots").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg").apply { writeBytes(bytes) }
        PreparedShot(Uri.fromFile(file).toString(), Base64.encodeToString(bytes, Base64.NO_WRAP))
    }
}
