package com.capstone.snapspot.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Base64
import com.capstone.snapspot.core.Box
import com.capstone.snapspot.core.OcrLine
import com.capstone.snapspot.core.OcrPage
import com.capstone.snapspot.core.OcrWord
import com.capstone.snapspot.core.ReadRoute
import com.capstone.snapspot.core.maskBoxes
import com.capstone.snapspot.core.model.Extracted
import com.capstone.snapspot.core.needsImage
import com.capstone.snapspot.core.planRead
import com.capstone.snapspot.net.GeminiClient
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** The on-device step failed, so the screenshot was not sent anywhere. */
class RedactionFailed(cause: Throwable) : Exception("on-device scan failed", cause)

/** What reading one screenshot took: the answer, how it was read, and how much was hidden. */
data class ReadOutcome(val extracted: Extracted, val route: ReadRoute, val masked: Int)

/**
 * Reads a screenshot with as little leaving the phone as possible:
 * 1. ML Kit reads the text, barcodes and faces on the device.
 * 2. Personal information is found (`core/Redact.kt`) and hidden.
 * 3. A gift card the rules can read is finished here; otherwise only the redacted text goes to
 *    Gemini, and the masked image only when the text is not enough.
 * If the on-device step fails, nothing is sent (fail closed).
 */
class ShotReader(private val gemini: GeminiClient) {
    private val text by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }
    private val barcodes by lazy { BarcodeScanning.getClient() }
    private val faces by lazy { FaceDetection.getClient() }

    suspend fun read(shot: PreparedShot): ReadOutcome {
        val bitmap = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(Uri.parse(shot.fileUri).path) ?: error("이미지를 열 수 없어요.")
        }
        val page = try {
            scan(bitmap)
        } catch (e: Exception) {
            throw RedactionFailed(e)
        }
        val plan = planRead(page)
        when (plan.route) {
            ReadRoute.LOCAL -> return ReadOutcome(plan.local!!, ReadRoute.LOCAL, plan.masked)
            ReadRoute.TEXT -> {
                val ex = gemini.extractText(plan.text)
                if (!needsImage(ex)) return ReadOutcome(ex, ReadRoute.TEXT, plan.masked)
            }
            ReadRoute.IMAGE -> Unit
        }
        val masked = withContext(Dispatchers.Default) { maskedJpeg(bitmap, maskBoxes(page)) }
        return ReadOutcome(gemini.extract(masked), ReadRoute.IMAGE, plan.masked)
    }

    private suspend fun scan(bitmap: Bitmap): OcrPage {
        val image = InputImage.fromBitmap(bitmap, 0)
        val found = text.process(image).await()
        val lines = found.textBlocks.flatMap { block ->
            block.lines.map { line ->
                OcrLine(line.elements.mapNotNull { el -> el.boundingBox?.let { OcrWord(el.text, it.toBox()) } })
            }
        }.filter { it.words.isNotEmpty() }
        val codes = barcodes.process(image).await().mapNotNull { it.boundingBox?.toBox() }
        val people = faces.process(image).await().map { it.boundingBox.toBox() }
        return OcrPage(bitmap.width, bitmap.height, lines, codes, people)
    }

    private fun maskedJpeg(src: Bitmap, boxes: List<Box>): String {
        val copy = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(copy)
        val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        for (b in boxes) canvas.drawRect(Rect(b.left, b.top, b.right, b.bottom), paint)
        val bytes = ByteArrayOutputStream().use { out ->
            copy.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        }
        copy.recycle()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}

private fun Rect.toBox() = Box(left, top, right, bottom)
