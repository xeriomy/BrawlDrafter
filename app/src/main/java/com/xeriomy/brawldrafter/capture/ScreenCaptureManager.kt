package com.xeriomy.brawldrafter.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Manages screen capture via MediaProjection API.
 * 
 * Usage flow:
 * 1. Request MediaProjection permission (one-time per session)
 * 2. Create VirtualDisplay from the projection
 * 3. Capture frames via ImageReader
 * 4. Convert to Bitmap for OCR processing
 */
class ScreenCaptureManager(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displayMetrics = DisplayMetrics().also {
        windowManager.defaultDisplay.getRealMetrics(it)
    }

    val screenWidth: Int get() = displayMetrics.widthPixels
    val screenHeight: Int get() = displayMetrics.heightPixels

    companion object {
        const val REQUEST_CODE = 1001
        private const val DENSITY = 1
    }

    /**
     * Create the MediaProjection intent that must be launched via startActivityForResult.
     * Call this from an Activity, then pass the result to initProjection().
     */
    fun createCaptureIntent(): Intent {
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return projectionManager.createScreenCaptureIntent()
    }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Initialize MediaProjection from Activity result.
     */
    fun initProjection(resultCode: Int, data: Intent) {
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, data)
        // Register callback BEFORE any capture — required by MediaProjection API
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                virtualDisplay?.release()
                virtualDisplay = null
                imageReader?.close()
                imageReader = null
            }
        }, handler)
        mediaProjection = projection
    }

    /**
     * Initialize from an already-obtained MediaProjection.
     */
    fun initProjection(projection: MediaProjection) {
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                virtualDisplay?.release()
                virtualDisplay = null
                imageReader?.close()
                imageReader = null
            }
        }, handler)
        mediaProjection = projection
    }

    /**
     * Capture a single frame from the screen as a Bitmap.
     * 
     * This method:
     * 1. Sets up an ImageReader for one frame
     * 2. Creates a VirtualDisplay
     * 3. Waits for the image
     * 4. Cleans up resources
     * 
     * @return Bitmap of the current screen, or null if capture fails
     */
    suspend fun captureScreen(): Bitmap? = suspendCancellableCoroutine { cont ->
        val projection = mediaProjection ?: run {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        // Guard: only resume the continuation once even if onImageAvailable fires multiple times
        val captured = AtomicBoolean(false)

        // Create image reader for one frame
        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        reader.setOnImageAvailableListener({ reader ->
            if (!captured.compareAndSet(false, true)) {
                // Already got our frame — discard extras
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            val image: Image? = reader.acquireLatestImage()
            val bitmap = image?.toBitmap()
            image?.close()
            reader.close()

            // Stop virtual display after capture
            virtualDisplay?.release()
            virtualDisplay = null

            cont.resume(bitmap)
        }, handler)

        // Create virtual display
        virtualDisplay = projection.createVirtualDisplay(
            "BrawlDrafterCapture",
            screenWidth,
            screenHeight,
            displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null, null
        )

        // Timeout fallback
        cont.invokeOnCancellation {
            virtualDisplay?.release()
            virtualDisplay = null
            reader.close()
        }
    }

    /**
     * Release all resources.
     */
    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    /**
     * Check if screen capture is ready.
     */
    val isReady: Boolean get() = mediaProjection != null

    /**
     * Convert Image to Bitmap.
     */
    private fun Image.toBitmap(): Bitmap {
        val planes = this.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        // Crop to actual size if there's padding
        return if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, width, height)
        } else {
            bitmap
        }
    }
}