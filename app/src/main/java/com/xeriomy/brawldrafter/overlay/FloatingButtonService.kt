package com.xeriomy.brawldrafter.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
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
 * Foreground service managing the floating overlay button and draft analysis panel.
 *
 * Two modes: "api_only" (Brawlify meta data) and "ai_plus_api" (vision + AI + meta).
 *
 * Layout (result overlay):
 *   Top-left:     GameMode - MapName
 *   Top-right:    Bans (smaller)
 *   Center-left:  Our team picks (+ recommended for empty slots)
 *   Center-right: Enemy picks
 *   Bottom-center: Top suggestion with reasoning
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

    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var ocrEngine: OcrEngine

    private val serviceScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
    )

    // Touch handling for dragging
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    // Store last draft state for result display
    private var lastDraftState: DraftState? = null

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
                if (!screenCaptureManager.isReady) {
                    val resultCode = it.getIntExtra("resultCode", 0)
                    val data = it.getParcelableExtra<Intent>("data")
                    if (data != null) {
                        screenCaptureManager.initProjection(resultCode, data)
                    }
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
        val buttonSize = (48 * density).toInt()

        val layout = LinearLayout(this).apply {
            setBackgroundColor(0xB000E676.toInt()) // semi-transparent green
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
            elevation = 4f
        }

        val modeLabel = TextView(this).apply {
            text = if (isAiMode) "AI" else "API"
            textSize = 7f
            setTextColor(0xFF000000.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        layout.addView(modeLabel)

        val icon = TextView(this).apply {
            text = "BD"
            textSize = 15f
            setTextColor(0xFF000000.toInt())
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
            x = 12
            y = 180
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
                        if (!isDragging) onFloatingButtonClicked()
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
            Toast.makeText(this, "Screen capture expired. Re-open the app to re-authorize.", Toast.LENGTH_LONG).show()
            return
        }

        // Dismiss previous results
        dismissResultPanel()

        serviceScope.launch {
            showScanOverlay()
            try {
                val result = performAnalysis()
                hideScanOverlay()
                if (result != null) {
                    showResultPanel(result, lastDraftState)
                } else {
                    showNotDraftToast()
                }
            } catch (e: Exception) {
                hideScanOverlay()
                val msg = e.message ?: "Unknown error"
                if (msg.contains("non-current", ignoreCase = true) || msg.contains("VirtualDisplay", ignoreCase = true)) {
                    Toast.makeText(this@FloatingButtonService, "Screen capture expired. Stop and restart overlay.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@FloatingButtonService, "Error: $msg", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showNotDraftToast() {
        Toast.makeText(this, "No draft detected. Open Brawl Stars draft screen first.", Toast.LENGTH_LONG).show()
    }

    /**
     * Full analysis pipeline.
     * CRITICAL: Hides ALL overlay views before screen capture to prevent
     * the overlay's own text from being OCR'd as fake draft data.
     */
    private suspend fun performAnalysis(): DraftAnalysis? {
        val sw = screenCaptureManager.screenWidth
        val sh = screenCaptureManager.screenHeight

        // Step 1: HIDE overlays, capture clean screen, then restore
        updateScanStep("Capturing", "Taking screenshot...", 0.1f)
        delay(200)

        // Hide overlay views so they don't appear in the capture
        scanOverlayRoot?.visibility = View.GONE
        floatingButton?.visibility = View.GONE
        delay(100) // Let the UI settle

        val bitmap = try {
            screenCaptureManager.captureScreen()
        } finally {
            // Always restore visibility
            scanOverlayRoot?.visibility = View.VISIBLE
            floatingButton?.visibility = View.VISIBLE
        } ?: return null

        // Step 2: OCR for map name and game mode text
        updateScanStep("Scanning", "Reading map and mode...", 0.25f)
        val textWithPositions = ocrEngine.analyzeWithPositions(bitmap)

        val ocrDraftState: DraftState = if (textWithPositions.isNotEmpty()) {
            DraftScreenParser.parseWithPositions(textWithPositions, sw, sh)
        } else {
            ocrEngine.analyzeDraftScreen(bitmap)
        }

        // Step 3: Identify brawlers (vision for AI mode, OCR fallback for API-only)
        val finalDraftState: DraftState = if (isAiMode) {
            updateScanStep("Analyzing", "Identifying brawlers from icons...", 0.45f)
            val app = application as? BrawlDrafterApp
            val engine = app?.currentEngine
            val visionId = engine?.createVisionIdentifier()

            if (visionId != null) {
                try {
                    val visionResult = visionId.identify(bitmap)
                    updateScanStep("Analyzing", "Brawlers identified!", 0.6f)
                    visionResult.toDraftState(ocrGameMode = ocrDraftState.mapGameMode)
                } catch (e: Exception) {
                    ocrDraftState
                }
            } else {
                ocrDraftState
            }
        } else {
            ocrDraftState
        }

        lastDraftState = finalDraftState

        // Step 4: Validate — is this actually a draft screen?
        if (!finalDraftState.isValidDraft) {
            updateScanStep("No Draft", "Switch to Brawl Stars draft screen", 1.0f)
            delay(1000)
            return null
        }

        // Step 5: Get recommendations
        val modeLabel = if (isAiMode) "AI + API" else "Meta Data"
        updateScanStep(modeLabel, "Generating recommendations...", 0.75f)

        val app = application as? BrawlDrafterApp
        val engine = app?.currentEngine

        val analysis = if (isAiMode) {
            engine?.analyze(finalDraftState) ?: return null
        } else {
            engine?.analyzeApiOnly(finalDraftState) ?: return null
        }

        updateScanStep("Done", "", 1.0f)
        delay(300)

        return analysis
    }

    // ========== Scan Overlay (minimal, transparent) ==========

    @SuppressLint("SetTextI18n")
    private fun showScanOverlay() {
        hideScanOverlay()

        val density = resources.displayMetrics.density
        val sw = screenCaptureManager.screenWidth
        val sh = screenCaptureManager.screenHeight

        val root = FrameLayout(this).apply {
            setBackgroundColor(0x60000000.toInt()) // 38% opaque dark
        }

        // Scan frame
        val frameW = (sw * 0.7f).toInt()
        val frameH = (sh * 0.35f).toInt()

        val scanFrame = ScanOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(frameW, frameH).apply {
                gravity = Gravity.CENTER
                topMargin = (-(30 * density)).toInt()
            }
        }
        root.addView(scanFrame)
        scanFrameView = scanFrame

        scanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                scanFrame.scanProgress = anim.animatedValue as Float
            }
        }.also { it.start() }

        // Step text (centered below scan frame)
        val stepTitle = TextView(this).apply {
            text = "Scanning..."
            textSize = 14f
            setTextColor(0xFF00E676.toInt())
            typeface = Typeface.DEFAULT_BOLD
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (sh / 2 + frameH / 2 - 10 * density).toInt()
            }
        }
        root.addView(stepTitle)
        stepTitleView = stepTitle

        val stepDetail = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(0xFF888899.toInt())
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (sh / 2 + frameH / 2 + 14 * density).toInt()
            }
        }
        root.addView(stepDetail)
        stepDetailView = stepDetail

        // Cancel button
        val cancelBtn = TextView(this).apply {
            text = "CANCEL"
            textSize = 11f
            setTextColor(0xFFFF5252.toInt())
            typeface = Typeface.DEFAULT_BOLD
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding((20 * density).toInt(), (6 * density).toInt(), (20 * density).toInt(), (6 * density).toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                bottomMargin = (50 * density).toInt()
            }
            setOnClickListener { serviceScope.launch { hideScanOverlay() } }
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
        stepDetailView?.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
        if (progress >= 1.0f) scanAnimator?.cancel()
    }

    private fun hideScanOverlay() {
        scanAnimator?.cancel()
        scanAnimator = null
        scanOverlayRoot?.let { windowManager.removeView(it) }
        scanOverlayRoot = null
        scanFrameView = null
        stepTitleView = null
        stepDetailView = null
    }

    // ========== Result Panel (new layout) ==========

    @SuppressLint("SetTextI18n")
    private fun showResultPanel(analysis: DraftAnalysis, draftState: DraftState?) {
        dismissResultPanel()

        val d = resources.displayMetrics.density
        val sw = screenCaptureManager.screenWidth

        // Main container — full screen, transparent
        val root = FrameLayout(this)

        // Content panel — positioned at center, semi-transparent dark
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xA0121225.toInt()) // ~63% opaque dark
            setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
        }

        // === ROW 1: Map info (left) + Bans (right) ===
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Left: GameMode - MapName
        val mapInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val gameModeText = draftState?.mapGameMode?.name?.replace("_", " ") ?: ""
        val mapNameText = draftState?.mapName?.ifBlank { null } ?: "Unknown Map"
        mapInfo.addView(TextView(this).apply {
            text = "${gameModeText.uppercase()} - $mapNameText"
            textSize = 13f
            setTextColor(0xFF00E676.toInt())
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        })
        topRow.addView(mapInfo)

        // Right: Bans (smaller)
        val allBans = (draftState?.teamBans ?: emptyList()) + (draftState?.enemyBans ?: emptyList())
        if (allBans.isNotEmpty()) {
            val bansView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
            }
            bansView.addView(TextView(this).apply {
                text = "BAN: ${allBans.joinToString(", ")}"
                textSize = 10f
                setTextColor(0xFFFF5252.toInt())
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
            })
            topRow.addView(bansView)
        }
        panel.addView(topRow)

        // Thin separator
        panel.addView(makeSeparator(d, 0xFF2A2A40.toInt()))

        // === ROW 2: Team picks (left) | Enemy picks (right) ===
        val teamsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        // Our team (left half)
        val teamSection = createTeamSection(
            title = "OUR TEAM",
            picks = draftState?.teamPicks ?: emptyList(),
            maxSlots = 3,
            recommendations = analysis.recommendations,
            isEnemy = false,
            density = d
        )
        teamSection.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        teamsRow.addView(teamSection)

        // Vertical divider
        teamsRow.addView(View(this).apply {
            setBackgroundColor(0xFF2A2A40.toInt())
            layoutParams = LinearLayout.LayoutParams((1 * d).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
                .apply { setMargins((6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt()) }
        })

        // Enemy team (right half)
        val enemySection = createTeamSection(
            title = "ENEMY",
            picks = draftState?.enemyPicks ?: emptyList(),
            maxSlots = 3,
            recommendations = analysis.recommendations,
            isEnemy = true,
            density = d
        )
        enemySection.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        teamsRow.addView(enemySection)

        panel.addView(teamsRow)

        // Thin separator
        panel.addView(makeSeparator(d, 0xFF2A2A40.toInt()))

        // === ROW 3: Top suggestion (bottom center) ===
        val topRec = analysis.recommendations.firstOrNull()
        if (topRec != null) {
            val suggestionBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0x30FFFFFF.toInt()) // subtle white tint
                setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // Suggestion header
            val sugHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            sugHeader.addView(TextView(this).apply {
                text = "PICK: "
                textSize = 11f
                setTextColor(0xFF00E676.toInt())
                typeface = Typeface.DEFAULT_BOLD
            })
            sugHeader.addView(TextView(this).apply {
                text = topRec.brawlerName
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
                typeface = Typeface.DEFAULT_BOLD
            })
            // Score badge
            val gradeColor = gradeColor(topRec.grade)
            sugHeader.addView(TextView(this).apply {
                text = "  ${topRec.grade} ${topRec.score.toInt()}%  "
                textSize = 11f
                setTextColor(gradeColor)
                typeface = Typeface.DEFAULT_BOLD
                setBackgroundColor(0x40FFFFFF.toInt())
                setPadding((4 * d).toInt(), (1 * d).toInt(), (4 * d).toInt(), (1 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = (6 * d).toInt() }
            })
            suggestionBox.addView(sugHeader)

            // Tags (counters/synergy/weak)
            val tags = mutableListOf<String>()
            if (topRec.counterTo.isNotEmpty()) tags.add("Counters ${topRec.counterTo.joinToString(", ")}")
            if (topRec.synergyWith.isNotEmpty()) tags.add("Synergy ${topRec.synergyWith.joinToString(", ")}")
            if (topRec.weakTo.isNotEmpty()) tags.add("Weak to ${topRec.weakTo.joinToString(", ")}")
            if (tags.isNotEmpty()) {
                suggestionBox.addView(TextView(this).apply {
                    text = tags.joinToString("  |  ")
                    textSize = 10f
                    setTextColor(0xFF00D2FF.toInt())
                    maxLines = 2
                    setPadding(0, (3 * d).toInt(), 0, 0)
                })
            }

            // Reasoning / strategy
            if (topRec.reasoning.isNotBlank() && !topRec.reasoning.startsWith("AI not configured")) {
                suggestionBox.addView(TextView(this).apply {
                    text = topRec.reasoning
                    textSize = 10f
                    setTextColor(0xFFB0B0C0.toInt())
                    maxLines = 3
                    setPadding(0, (3 * d).toInt(), 0, 0)
                })
            }

            panel.addView(suggestionBox)
        }

        // Close button
        panel.addView(TextView(this).apply {
            text = "tap anywhere to close"
            textSize = 9f
            setTextColor(0xFF666680.toInt())
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(0, (6 * d).toInt(), 0, 0)
        })

        // Wrap panel in a centered container
        val panelWidth = (minOf(sw * 0.92f, 420 * d)).toInt()
        panel.layoutParams = FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }

        root.addView(panel)

        // Tap root to dismiss
        root.setOnClickListener { dismissResultPanel() }
        panel.setOnClickListener { /* consume click on panel itself */ }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(root, params)
        resultPanel = root
    }

    @SuppressLint("SetTextI18n")
    private fun createTeamSection(
        title: String,
        picks: List<String>,
        maxSlots: Int,
        recommendations: List<Recommendation>,
        isEnemy: Boolean,
        density: Float
    ): LinearLayout {
        val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Section title
        section.addView(TextView(this).apply {
            text = title
            textSize = 9f
            setTextColor(if (isEnemy) 0xFFFF5252.toInt() else 0xFF00E676.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (3 * density).toInt())
        })

        // Fill slots: picked brawlers + recommended for empty slots
        val topRec = recommendations.firstOrNull()?.brawlerName ?: "???"
        for (i in 0 until maxSlots) {
            if (i < picks.size) {
                // Picked brawler
                section.addView(TextView(this).apply {
                    text = "  ${picks[i]}"
                    textSize = 13f
                    setTextColor(0xFFFFFFFF.toInt())
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
                })
            } else {
                // Empty slot — show recommended pick
                val recommended = if (!isEnemy) recommendations.firstOrNull() else null
                section.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
                    addView(TextView(this).apply {
                        text = if (recommended != null) "  -> ${recommended.brawlerName}" else "  ---"
                        textSize = 12f
                        setTextColor(if (recommended != null) 0xFFFFD740.toInt() else 0xFF555566.toInt())
                        typeface = if (recommended != null) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    })
                })
            }
        }

        return section
    }

    private fun makeSeparator(density: Float, color: Int): View {
        return View(this).apply {
            setBackgroundColor(color)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
            ).apply { setMargins(0, (4 * density).toInt(), 0, (4 * density).toInt()) }
        }
    }

    private fun gradeColor(grade: String): Int = when (grade) {
        "S" -> 0xFF00E676.toInt()
        "A" -> 0xFF00D2FF.toInt()
        "B" -> 0xFFFFD740.toInt()
        "C" -> 0xFFFF9100.toInt()
        else -> 0xFFFF5252.toInt()
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