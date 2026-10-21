package com.spendsnap.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** On-device OCR of a user-provided screenshot. The image is read once from its Uri and never saved. */
class OcrProcessor(private val context: Context) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun readLines(uri: Uri): List<String> {
        val image = InputImage.fromFilePath(context, uri)
        val result = suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        return toRows(result)
    }

    /**
     * Rebuilds visual rows so "Amount" and "₹1" printed side by side become one line,
     * which the label rules in the parser rely on.
     */
    private fun toRows(text: Text): List<String> {
        val lines = text.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
        val rows = mutableListOf<MutableList<Text.Line>>()
        for (line in lines.sortedBy { it.boundingBox!!.centerY() }) {
            val box = line.boundingBox!!
            val row = rows.lastOrNull()?.takeIf { r ->
                val ref = r.first().boundingBox!!
                kotlin.math.abs(ref.centerY() - box.centerY()) < minOf(ref.height(), box.height()) / 2
            }
            if (row != null) row += line else rows += mutableListOf(line)
        }
        return rows.map { row -> row.sortedBy { it.boundingBox!!.left }.joinToString(" ") { it.text } }
    }
}
