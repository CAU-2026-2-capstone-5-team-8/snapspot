package com.capstone.snapspot.image

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Shares only temporary camera output, never the app's saved places or coupons. */
class CaptureFileProvider : FileProvider()

object CameraPhotos {
    private fun authority(ctx: Context) = "${ctx.packageName}.camera"

    fun create(ctx: Context): Uri {
        val directory = File(ctx.cacheDir, "camera").apply { mkdirs() }
        val file = File.createTempFile("photo_", ".jpg", directory)
        return try {
            FileProvider.getUriForFile(ctx, authority(ctx), file)
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    /** Delete our own camera output after cancellation or after the durable copy is made. */
    fun discard(ctx: Context, uri: Uri) {
        if (uri.authority != authority(ctx) || uri.pathSegments.firstOrNull() != "camera_images") return
        runCatching { ctx.contentResolver.delete(uri, null, null) }
        runCatching { ctx.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    }
}
