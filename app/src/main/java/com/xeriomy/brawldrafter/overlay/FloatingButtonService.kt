package com.xeriomy.brawldrafter.overlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.xeriomy.brawldrafter.R
import com.xeriomy.brawldrafter.ai.RecommendationEngine
import com.xeriomy.brawldrafter.capture.ScreenCaptureManager
import com.xeriomy.brawldrafter.data.model.DraftAnalysis
import com.xeriomy.brawldrafter.data.model.DraftState
import com.xeriomy.brawldrafter.data.model.Recommendation
import com.xeriomy.brawldrafter.ocr.DraftScreenParser
import com.xeriomy.brawldrafter.ocr.OcrEngine
import kotlinx.coroutines.*

/**
 * Foreground service that manages the floating overlay button and results panel.
 * 
 * Lifecycle:
 * 1. App starts → requests overlay + media projection permissions
 * 2. User grants permissions → service starts → floating button appears
 * 3. User taps floating button while in Brawl Stars draft:
 *    a. Screen is captured
 *    b. OCR runs on the capture
 *    c. Draft state is parsed
 *    d. AI recommendation engine runs
 *    e. Results panel appears with top picks
 * 4. User taps X or swipes panel away → panel dismisses
 * 5. User stops from notification → service stops → overlay removed
 */
class FloatingButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingButton: View? = null
    private var resultPanel: View? = null
    private var loadingIndicator: View? = null

    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var ocrEngine: OcrEngine
    private var recommendationEngine: RecommendationEngine? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Touch handling for dragging the floating button
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "brawldrafter_overlay"

        /** Start the overlay service */
        fun start(context: Context) {
            val intent = Intent(context, FloatingButtonService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stop the overlay service */
        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingButtonService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        screenCaptureManager = ScreenCaptureManager(this)
        ocrEngine = OcrEngine()

        // Create notification for foreground service
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // Show floating button
        showFloatingButton()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle media projection result from activity
        intent?.let {
            if (it.hasExtra("resultCode") && it.hasExtra("data")) {
                val resultCode = it.getIntExtra("resultCode", 0)
                val data = it.getParcelableExtra<Intent>("data")
                if (data != null) {
                    screenCaptureManager.initProjection(resultCode, data)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        floatingButton?.let { windowManager.removeView(it) }
        resultPanel?.let { windowManager.removeView(it) }
        loadingIndicator?.let { windowManager.removeView(it) }
        screenCaptureManager.release()
    }

    // ========== Floating Button ==========

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingButton() {
        val buttonSize = (56 * resources.displayMetrics.density).toInt()

        val layout = LinearLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@FloatingButtonService, R.color.accent_cyan))
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
            elevation = 8f
        }

        val icon = TextView(this).apply {
            text = "BD"
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@FloatingButtonService, R.color.bg_dark))
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            setPadding(8, 8, 8, 8)
        }
        layout.addView(icon)

        val params = WindowManager.LayoutParams(
            buttonSize,
            buttonSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 200
        }

        layout.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isDragging = true
                            params.x = initialX - dx
                            params.y = initialY + dy
                            windowManager.updateViewLayout(layout, params)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            onFloatingButtonClicked()
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(layout, params)
        floatingButton = layout
    }

    // ========== Main Analysis Pipeline ==========

    private fun onFloatingButtonClicked() {
        if (!screenCaptureManager.isReady) {
            Toast.makeText(this, "Screen capture not ready. Re-open the app to grant permission.", Toast.LENGTH_SHORT).show()
            return
        }

        serviceScope.launch {
            showLoading("Scanning draft...")
            try {
                val result = performAnalysis()
                hideLoading()
                if (result != null) {
                    showResultPanel(result)
                } else {
                    Toast.makeText(this@FloatingButtonService, "Could not detect draft screen. Make sure the draft is visible.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                hideLoading()
                Toast.makeText(this@FloatingButtonService, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Full analysis pipeline:
     * 1. Capture screen
     * 2. Run OCR
     * 3. Parse draft state
     * 4. Get AI recommendations
     */
    private suspend fun performAnalysis(): DraftAnalysis? {
        // Step 1: Capture screen
        val bitmap = screenCaptureManager.captureScreen()
            ?: return null

        // Step 2: OCR with positions for spatial analysis
        val textWithPositions = ocrEngine.analyzeWithPositions(bitmap)
        
        if (textWithPositions.isEmpty()) {
            // Fallback to basic OCR
            val draftState = ocrEngine.analyzeDraftScreen(bitmap)
            return if (draftState.allPicks.isNotEmpty() || draftState.mapName.isNotBlank()) {
                getRecommendations(draftState)
            } else null
        }

        // Step 3: Parse with spatial info for accurate team/enemy separation
        val draftState = DraftScreenParser.parseWithPositions(
            textWithPositions,
            screenCaptureManager.screenWidth,
            screenCaptureManager.screenHeight
        )

        if (draftState.allPicks.isEmpty() && draftState.mapName.isBlank()) {
            return null
        }

        // Step 4: Get AI recommendations
        return getRecommendations(draftState)
    }

    private suspend fun getRecommendations(draftState: DraftState): DraftAnalysis {
        val engine = recommendationEngine
        return if (engine != null) {
            engine.analyze(draftState)
        } else {
            // Offline fallback - basic analysis without AI
            DraftAnalysis(
                recommendations = draftState.unpicked.take(5).map {
                    Recommendation(brawlerName = it, score = 50.0, reasoning = "AI not configured. Configure API key in settings.")
                },
                mapAnalysis = draftState.mapName.ifBlank { "Unknown map" },
                overallAdvice = "Configure your LLM API key in the app settings for AI-powered recommendations."
            )
        }
    }

    // ========== Result Panel ==========

    @SuppressLint("SetTextI18n")
    private fun showResultPanel(analysis: DraftAnalysis) {
        dismissResultPanel()

        val density = resources.displayMetrics.density
        val panelWidth = (340 * density).toInt()
        val panelHeight = WindowManager.LayoutParams.WRAP_CONTENT

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@FloatingButtonService, R.color.bg_dark))
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
            elevation = 16f
        }

        // Header
        val header = TextView(this).apply {
            text = "DRAFT ANALYSIS"
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@FloatingButtonService, R.color.accent_cyan))
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        panel.addView(header)

        // Map info
        if (analysis.mapAnalysis.isNotBlank()) {
            val mapText = TextView(this).apply {
                text = analysis.mapAnalysis
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@FloatingButtonService, R.color.text_secondary))
                setPadding(0, 0, 0, (8 * density).toInt())
            }
            panel.addView(mapText)
        }

        // Recommendations
        analysis.recommendations.forEachIndexed { index, rec ->
            val recView = createRecommendationView(rec, index, density)
            panel.addView(recView)
        }

        // Overall advice
        if (analysis.overallAdvice.isNotBlank()) {
            val adviceText = TextView(this).apply {
                text = analysis.overallAdvice
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@FloatingButtonService, R.color.accent_yellow))
                setPadding(0, (8 * density).toInt(), 0, 0)
            }
            panel.addView(adviceText)
        }

        // Close button
        val closeBtn = TextView(this).apply {
            text = "Close (tap here)"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@FloatingButtonService, R.color.text_secondary))
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(0, (12 * density).toInt(), 0, 0)
            setOnClickListener { dismissResultPanel() }
        }
        panel.addView(closeBtn)

        val params = WindowManager.LayoutParams(
            panelWidth,
            panelHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        windowManager.addView(panel, params)
        resultPanel = panel
    }

    @SuppressLint("SetTextI18n")
    private fun createRecommendationView(rec: Recommendation, index: Int, density: Float): LinearLayout {
        val gradeColor = when (rec.grade) {
            "S" -> R.color.accent_green
            "A" -> R.color.accent_cyan
            "B" -> R.color.accent_yellow
            "C" -> R.color.accent_orange
            else -> R.color.accent_red
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())

            // Grade badge
            addView(TextView(this@FloatingButtonService).apply {
                text = rec.grade
                textSize = 18f
                setTextColor(ContextCompat.getColor(this@FloatingButtonService, gradeColor))
                setPadding((8 * density).toInt(), 0, (12 * density).toInt(), 0)
            })

            // Brawler info
            addView(LinearLayout(this@FloatingButtonService).apply {
                orientation = LinearLayout.VERTICAL

                addView(TextView(this@FloatingButtonService).apply {
                    text = "${index + 1}. ${rec.brawlerName}  (${rec.score.toInt()}%)"
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(this@FloatingButtonService, R.color.text_bright))
                })

                if (rec.winRateOnMap > 0) {
                    addView(TextView(this@FloatingButtonService).apply {
                        text = "Map WR: ${"%.1f".format(rec.winRateOnMap)}%"
                        textSize = 11f
                        setTextColor(ContextCompat.getColor(this@FloatingButtonService, R.color.text_secondary))
                    })
                }

                addView(TextView(this@FloatingButtonService).apply {
                    val tags = mutableListOf<String>()
                    if (rec.counterTo.isNotEmpty()) tags.add("Counters: ${rec.counterTo.joinToString(", ")}")
                    if (rec.synergyWith.isNotEmpty()) tags.add("Synergy: ${rec.synergyWith.joinToString(", ")}")
                    text = tags.joinToString(" | ")
                    textSize = 10f
                    setTextColor(ContextCompat.getColor(this@FloatingButtonService, R.color.accent_cyan))
                    setPadding(0, (2 * density).toInt(), 0, 0)
                })

                if (rec.reasoning.isNotBlank()) {
                    addView(TextView(this@FloatingButtonService).apply {
                        text = rec.reasoning
                        textSize = 10f
                        setTextColor(ContextCompat.getColor(this@FloatingButtonService, R.color.text_secondary))
                        setPadding(0, (2 * density).toInt(), 0, 0)
                        maxLines = 3
                    })
                }
            })
        }
    }

    // ========== Loading Indicator ==========

    private fun showLoading(message: String) {
        hideLoading()

        val density = resources.displayMetrics.density
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@FloatingButtonService, R.color.bg_card))
            gravity = Gravity.CENTER
            setPadding((24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), (16 * density).toInt())
            elevation = 8f
        }

        val text = TextView(this).apply {
            text = message
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@FloatingButtonService, R.color.text_bright))
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
        }
        view.addView(text)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        windowManager.addView(view, params)
        loadingIndicator = view
    }

    private fun hideLoading() {
        loadingIndicator?.let { windowManager.removeView(it) }
        loadingIndicator = null
    }

    private fun dismissResultPanel() {
        resultPanel?.let { windowManager.removeView(it) }
        resultPanel = null
    }

    // ========== Notification (required for foreground service) ==========

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "BrawlDrafter Overlay",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): android.app.Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("BrawlDrafter")
                .setContentText("Overlay active - tap the floating button to scan")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build()
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
                .setContentTitle("BrawlDrafter")
                .setContentText("Overlay active")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build()
        }
    }

    // ========== Dependency injection ==========

    fun setRecommendationEngine(engine: RecommendationEngine) {
        recommendationEngine = engine
    }
}