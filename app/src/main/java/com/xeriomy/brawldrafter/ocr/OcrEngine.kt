package com.xeriomy.brawldrafter.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.xeriomy.brawldrafter.data.model.DraftState
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * OCR engine using Google ML Kit for on-device text recognition.
 * 
 * Flow: Screenshot bitmap → ML Kit text recognition → DraftScreenParser → DraftState
 */
class OcrEngine {

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * Perform OCR on a screenshot bitmap and parse into a DraftState.
     * 
     * @param bitmap The screen capture of the Brawl Stars draft screen
     * @return Parsed DraftState with detected brawler picks, map name, etc.
     */
    suspend fun analyzeDraftScreen(bitmap: Bitmap): DraftState {
        val image = InputImage.fromBitmap(bitmap, 0)
        val visionText = recognizeText(image)
        return DraftScreenParser.parse(visionText, bitmap.width, bitmap.height)
    }

    /**
     * Run ML Kit text recognition asynchronously.
     */
    private suspend fun recognizeText(image: InputImage): String = suspendCancellableCoroutine { cont ->
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                cont.resume(visionText.text)
            }
            .addOnFailureListener { e ->
                // Return empty on failure - parser will handle gracefully
                cont.resume("")
            }
    }

    /**
     * Get OCR results with bounding boxes for more precise parsing.
     * Returns list of (text, bounds) pairs for spatial analysis.
     */
    suspend fun analyzeWithPositions(bitmap: Bitmap): List<Pair<String, Rect>> {
        val image = InputImage.fromBitmap(bitmap, 0)
        return suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val results: List<Pair<String, Rect>> = visionText.textBlocks.flatMap { block ->
                        block.lines.flatMap { line ->
                            line.elements.map { element ->
                                element.text to (element.boundingBox ?: Rect(0, 0, 0, 0))
                            }
                        }
                    }
                    cont.resume(results)
                }
                .addOnFailureListener {
                    cont.resume(emptyList())
                }
        }
    }
}