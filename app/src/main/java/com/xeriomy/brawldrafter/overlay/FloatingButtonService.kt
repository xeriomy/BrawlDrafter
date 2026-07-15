package com.xeriomy.brawldrafter.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.xeriomy.brawldrafter.BrawlDrafterApp
import com.xeriomy.brawldrafter.R
import com.xeriomy.brawldrafter.data.model.DraftAnalysis
import com.xeriomy.brawldrafter.data.model.DraftState
import com.xeriomy.brawldrafter.data.model.Recommendation
import com.xeriomy.brawldrafter.capture.ScreenCaptureManager
import com.xeriomy.brawldrafter.ocr.DraftScreenParser
import com.xeriomy.brawldrafter.ocr.OcrEngine
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that manages the floating overlay button and results panel.
 *
 * Supports two analysis modes (set in app settings):
 * - "api_only"   — pure data-driven scoring from Brawlify API
 * - "ai_plus_api" — hybrid AI + API analysis
 *
 * Analysis pipeline:
 * 1. User taps floating BD button
 * 2. Scan overlay appears with animated green scan line
 * 3. Screen captured → OCR → draft parsed → recommendations fetched
 * 4. Results panel shown with ranked picks
 */
class FloatingButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingButton: View? = null
    private var resultPanel: View? = null

    // Scan overlay views
    private var scanOverlayRoot: FrameLayout? = null
    private var scanFrameView: ScanOverlayView? = null
    private var stepTitleView: TextView? = null
    private var stepDetailView: TextView? = null
    private var scanAnimator: ValueAnimator? = null
    private var detectedInfoView: TextView? = null

    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var ocrEngine: OcrEngine

    private val serviceScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
    )

    // Touch handling for dragging the floating button
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "brawldrafter_overlay"
        const val MODE_API_ONLY = "api_only"
        const val MODE_AI_PLUS_API = "ai_plus_api"

        fun start(context: Context) {
            val intent = Intent(context, FloatingButtonService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingButtonService::class.java))
        }
    }

    // Read current mode from shared preferences
    private val analysisMode: String
        get() = getSharedPreferences("brawldrafter", MODE_PRIVATE)
            .getString("mode", MODE_API_ONLY) ?: MODE_API_ONLY

    private val isAiMode: Boolean
        get() = analysisMode == MODE_AI_PLUS_API

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        screenCaptureManager = ScreenCaptureManager(this)
        ocrEngine = OcrEngine()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        showFloatingButton()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        scanAnimator?.cancel()
        floatingButton?.let { windowManager.removeView(it) }
        resultPanel?.let { windowManager.removeView(it) }
        scanOverlayRoot?.let { windowManager.removeView(it) }
        screenCaptureManager.release()
    }

    // ========== Floating Button ==========

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingButton() {
        val density = resources.displayMetrics.density
        val buttonSize = (52 * density).toInt()

        val layout = LinearLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@FloatingButtonService, R.color.accent_cyan))
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
            elevation = 8f
        }

        // Top line showing mode
        val modeLabel = TextView(this).apply {
            text = if (isAiMode) "AI" else "API"
            textSize = 8f
            setTextColor(ContextCompat.getColor(this@FloatingButtonService, R.color.bg_dark))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        layout.addView(modeLabel)

        val icon = TextView(this).apply {
            text = "BD"
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@FloatingButtonService, R.color.bg_dark))
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (2 * density).toInt())
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
                        if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
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
            Toast.makeText(this, "Screen capture not ready. Re-open the app.", Toast.LENGTH_SHORT).show()
            return
        }

        serviceScope.launch {
            showScanOverlay()
            try {
                val result = performAnalysis()
                hideScanOverlay()

                if (result != null) {
                    showResultPanel(result)
                } else {
                    showNotDraftToast()
                }
            } catch (e: Exception) {
                hideScanOverlay()
                Toast.makeText(this@FloatingButtonService, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showNotDraftToast() {
        Toast.makeText(
            this,
            "No draft detected. Make sure Brawl Stars draft screen is visible.",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Full analysis pipeline with step-by-step scan overlay updates.
     */
    private suspend fun performAnalysis(): DraftAnalysis? {
        val sw = screenCaptureManager.screenWidth
        val sh = screenCaptureManager.screenHeight

        // Step 1: Capture screen
        updateScanStep("Capturing Screen", "Taking screenshot...", 0.1f)
        delay(400)
        val bitmap = screenCaptureManager.captureScreen()
            ?: return null

        // Step 2: Run OCR for map name and game mode (text is shown on draft screen)
        updateScanStep("Running OCR", "Scanning for map and mode text...", 0.25f)
        val textWithPositions = ocrEngine.analyzeWithPositions(bitmap)

        val ocrDraftState: DraftState = if (textWithPositions.isNotEmpty()) {
            DraftScreenParser.parseWithPositions(textWithPositions, sw, sh)
        } else {
            ocrEngine.analyzeDraftScreen(bitmap)
        }

        // Step 3: Identify brawlers (vision for AI mode, or OCR fallback)
        val finalDraftState: DraftState = if (isAiMode) {
            // AI+API: use vision API to identify brawlers from their portrait icons
            updateScanStep("Analyzing Draft", "Identifying brawlers from icons...", 0.45f)
            val app = application as? BrawlDrafterApp
            val engine = app?.currentEngine
            val visionId = engine?.createVisionIdentifier()

            if (visionId != null) {
                try {
                    val visionResult = visionId.identify(bitmap)
                    updateScanStep("Analyzing Draft", "Brawlers identified!", 0.6f)
                    visionResult.toDraftState(ocrGameMode = ocrDraftState.mapGameMode)
                } catch (e: Exception) {
                    // Vision failed, fall back to OCR results
                    ocrDraftState
                }
            } else {
                ocrDraftState
            }
        } else {
            // API-only: rely on OCR for map/mode, brawler picks won't be available from icons
            ocrDraftState
        }

        // Step 4: Validate — is this actually a draft screen?
        // API-only: map name or game mode is enough (recommends best map brawlers)
        // AI+API: vision may have found brawlers, or map info from OCR
        val hasBrawlers = finalDraftState.allPicks.isNotEmpty()
        val hasMapInfo = finalDraftState.mapName.isNotBlank() ||
                finalDraftState.mapGameMode != com.xeriomy.brawldrafter.data.model.MapInfo.GameMode.UNKNOWN

        if (!hasBrawlers && !hasMapInfo) {
            updateScanStep("No Draft Found", "Switch to Brawl Stars draft screen", 1.0f)
            delay(1200)
            return null
        }

        // Show detected info
        val detectedText = buildString {
            if (finalDraftState.teamPicks.isNotEmpty()) append("Team: ${finalDraftState.teamPicks.joinToString(", ")}  ")
            if (finalDraftState.enemyPicks.isNotEmpty()) append("Enemy: ${finalDraftState.enemyPicks.joinToString(", ")}  ")
            if (finalDraftState.mapName.isNotBlank()) append("Map: ${finalDraftState.mapName}")
            if (!hasBrawlers && hasMapInfo) append("  (map-based recommendations)")
        }
        showDetectedInfo(detectedText.trim())

        // Step 5: Get recommendations
        val modeLabel = if (isAiMode) "AI + API Analysis" else "Meta Data Analysis"
        updateScanStep(modeLabel, "Generating pick recommendations...", 0.75f)

        val app = application as? BrawlDrafterApp
        val engine = app?.currentEngine

        val analysis = if (isAiMode) {
            engine?.analyze(finalDraftState) ?: engine?.analyzeApiOnly(finalDraftState) ?: return null
        } else {
            engine?.analyzeApiOnly(finalDraftState) ?: return null
        }

        updateScanStep("Complete", "Preparing results...", 1.0f)
        delay(500)

        return analysis
    }

    // ========== Scan Overlay ==========

    @SuppressLint("SetTextI18n")
    private fun showScanOverlay() {
        hideScanOverlay()

        val density = resources.displayMetrics.density
        val sw = screenCaptureManager.screenWidth
        val sh = screenCaptureManager.screenHeight

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt()) // 80% opaque black
        }

        // Scan frame (custom animated view)
        val frameW = (sw * 0.72f).toInt()
        val frameH = (sh * 0.38f).toInt()

        val scanFrame = ScanOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(frameW, frameH).apply {
                gravity = Gravity.CENTER
                topMargin = (-(40 * density)).toInt() // Shift up a bit
            }
        }
        root.addView(scanFrame)
        scanFrameView = scanFrame

        // Start scan line animation
        scanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                scanFrame.scanProgress = anim.animatedValue as Float
            }
        }.also { it.start() }

        // Top bar: BD logo + mode badge
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * density).toInt(), (16 * density).toInt(), (20 * density).toInt(), 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val logoText = TextView(this).apply {
            text = "BD"
            textSize = 18f
            setTextColor(0xFF00E676.toInt())
            typeface = Typeface.DEFAULT_BOLD
        }
        topBar.addView(logoText)

        // Mode badge
        val modeBadge = TextView(this).apply {
            text = "  ${if (isAiMode) "AI + API" else "API ONLY"}  "
            textSize = 10f
            setTextColor(0xFF000000.toInt())
            setBackgroundColor(if (isAiMode) 0xFF00D2FF.toInt() else 0xFF00E676.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = (12 * density).toInt()
            }
        }
        topBar.addView(modeBadge)
        root.addView(topBar)

        // Step title
        val stepTitle = TextView(this).apply {
            text = "Initializing..."
            textSize = 16f
            setTextColor(0xFF00E676.toInt())
            typeface = Typeface.DEFAULT_BOLD
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (sh / 2 + frameH / 2 - 20 * density).toInt()
            }
        }
        root.addView(stepTitle)
        stepTitleView = stepTitle

        // Step detail
        val stepDetail = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(0xFFA0A0B0.toInt())
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (sh / 2 + frameH / 2 + 8 * density).toInt()
            }
        }
        root.addView(stepDetail)
        stepDetailView = stepDetail

        // Detected info (hidden initially)
        val detectedInfo = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(0xFF00D2FF.toInt())
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (sh / 2 + frameH / 2 + 40 * density).toInt()
            }
            visibility = View.GONE
        }
        root.addView(detectedInfo)
        detectedInfoView = detectedInfo

        // Cancel button
        val cancelBtn = TextView(this).apply {
            text = "CANCEL"
            textSize = 12f
            setTextColor(0xFFFF5252.toInt())
            typeface = Typeface.DEFAULT_BOLD
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), (8 * density).toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                bottomMargin = (60 * density).toInt()
            }
            setOnClickListener {
                serviceScope.launch {
                    hideScanOverlay()
                }
            }
        }
        root.addView(cancelBtn)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(root, params)
        scanOverlayRoot = root
    }

    private fun updateScanStep(title: String, detail: String, progress: Float) {
        stepTitleView?.text = title
        stepDetailView?.text = detail
        // Optionally stop animation near completion
        if (progress >= 1.0f) {
            scanAnimator?.cancel()
        }
    }

    private fun showDetectedInfo(text: String) {
        if (text.isBlank()) return
        detectedInfoView?.text = text
        detectedInfoView?.visibility = View.VISIBLE
    }

    private fun hideScanOverlay() {
        scanAnimator?.cancel()
        scanAnimator = null
        scanOverlayRoot?.let { windowManager.removeView(it) }
        scanOverlayRoot = null
        scanFrameView = null
        stepTitleView = null
        stepDetailView = null
        detectedInfoView = null
    }

    // ========== Result Panel ==========

    @SuppressLint("SetTextI18n")
    private fun showResultPanel(analysis: DraftAnalysis) {
        dismissResultPanel()

        val density = resources.displayMetrics.density
        val panelWidth = (minOf(screenCaptureManager.screenWidth * 0.88f, 380 * density)).toInt()

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE61A1A2E.toInt())
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
            elevation = 16f
        }

        // Header row
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val header = TextView(this).apply {
            text = "DRAFT ANALYSIS"
            textSize = 14f
            setTextColor(0xFF00D2FF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        headerRow.addView(header)

        // Mode badge in results
        val modeBadge = TextView(this).apply {
            text = if (isAiMode) "AI+API" else "API"
            textSize = 9f
            setTextColor(0xFF000000.toInt())
            setBackgroundColor(if (isAiMode) 0xFF00D2FF.toInt() else 0xFF00E676.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding((5 * density).toInt(), (1 * density).toInt(), (5 * density).toInt(), (1 * density).toInt())
        }
        headerRow.addView(modeBadge)
        panel.addView(headerRow)

        // Team / Enemy info
        val prefs = getSharedPreferences("brawldrafter", MODE_PRIVATE)

        // Map info
        if (analysis.mapAnalysis.isNotBlank() && analysis.mapAnalysis != "Map not detected") {
            val mapText = TextView(this).apply {
                text = analysis.mapAnalysis
                textSize = 12f
                setTextColor(0xFFA0A0B0.toInt())
                setPadding(0, (4 * density).toInt(), 0, (6 * density).toInt())
            }
            panel.addView(mapText)
        }

        // Separator
        val sep = View(this).apply {
            setBackgroundColor(0xFF333355.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
            ).apply { setMargins(0, (4 * density).toInt(), 0, (8 * density).toInt()) }
        }
        panel.addView(sep)

        // Recommendations
        analysis.recommendations.forEachIndexed { index, rec ->
            val recView = createRecommendationView(rec, index, density)
            panel.addView(recView)
        }

        // Overall advice
        if (analysis.overallAdvice.isNotBlank()) {
            val adviceSep = View(this).apply {
                setBackgroundColor(0xFF333355.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
                ).apply { setMargins(0, (6 * density).toInt(), 0, (6 * density).toInt()) }
            }
            panel.addView(adviceSep)

            val adviceText = TextView(this).apply {
                text = analysis.overallAdvice
                textSize = 11f
                setTextColor(0xFFFFD740.toInt())
                setPadding(0, 0, 0, 0)
                maxLines = 4
            }
            panel.addView(adviceText)
        }

        // Close button
        val closeBtn = TextView(this).apply {
            text = "Close"
            textSize = 12f
            setTextColor(0xFFA0A0B0.toInt())
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(0, (12 * density).toInt(), 0, (4 * density).toInt())
            setOnClickListener { dismissResultPanel() }
        }
        panel.addView(closeBtn)

        val params = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
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
            "S" -> 0xFF00E676.toInt()
            "A" -> 0xFF00D2FF.toInt()
            "B" -> 0xFFFFD740.toInt()
            "C" -> 0xFFFF9100.toInt()
            else -> 0xFFFF5252.toInt()
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (5 * density).toInt(), 0, (5 * density).toInt())

            // Rank number
            addView(TextView(this@FloatingButtonService).apply {
                text = "${index + 1}"
                textSize = 12f
                setTextColor(0xFF666680.toInt())
                setPadding((4 * density).toInt(), 0, (8 * density).toInt(), 0)
                gravity = Gravity.CENTER_VERTICAL
            })

            // Grade badge
            addView(TextView(this@FloatingButtonService).apply {
                text = rec.grade
                textSize = 20f
                setTextColor(gradeColor)
                typeface = Typeface.DEFAULT_BOLD
                setPadding((4 * density).toInt(), 0, (10 * density).toInt(), 0)
                gravity = Gravity.CENTER_VERTICAL
            })

            // Brawler info
            addView(LinearLayout(this@FloatingButtonService).apply {
                orientation = LinearLayout.VERTICAL

                addView(TextView(this@FloatingButtonService).apply {
                    text = "${rec.brawlerName}  ${rec.score.toInt()}%"
                    textSize = 14f
                    setTextColor(0xFFFFFFFF.toInt())
                    typeface = Typeface.DEFAULT_BOLD
                })

                if (rec.winRateOnMap > 0) {
                    addView(TextView(this@FloatingButtonService).apply {
                        text = "Map WR: ${"%.1f".format(rec.winRateOnMap)}%"
                        textSize = 11f
                        setTextColor(0xFFA0A0B0.toInt())
                    })
                }

                // Tags line
                val tags = mutableListOf<String>()
                if (rec.counterTo.isNotEmpty()) tags.add("Counters: ${rec.counterTo.joinToString(", ")}")
                if (rec.synergyWith.isNotEmpty()) tags.add("Synergy: ${rec.synergyWith.joinToString(", ")}")
                if (rec.weakTo.isNotEmpty()) tags.add("Weak to: ${rec.weakTo.joinToString(", ")}")
                if (tags.isNotEmpty()) {
                    addView(TextView(this@FloatingButtonService).apply {
                        text = tags.joinToString("  |  ")
                        textSize = 10f
                        setTextColor(0xFF00D2FF.toInt())
                        setPadding(0, (2 * density).toInt(), 0, 0)
                        maxLines = 2
                    })
                }

                if (rec.reasoning.isNotBlank() && rec.reasoning != "AI not configured. Configure API key in settings.") {
                    addView(TextView(this@FloatingButtonService).apply {
                        text = rec.reasoning
                        textSize = 10f
                        setTextColor(0xFFA0A0B0.toInt())
                        setPadding(0, (2 * density).toInt(), 0, 0)
                        maxLines = 3
                    })
                }
            })
        }
    }

    private fun dismissResultPanel() {
        resultPanel?.let { windowManager.removeView(it) }
        resultPanel = null
    }

    // ========== Notification ==========

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
                .setContentText("Overlay active - tap BD during draft")
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
}