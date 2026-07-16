package com.xeriomy.brawldrafter.overlay

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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.xeriomy.brawldrafter.BrawlDrafterApp
import com.xeriomy.brawldrafter.data.model.DraftAnalysis
import com.xeriomy.brawldrafter.data.model.DraftState
import com.xeriomy.brawldrafter.data.model.Recommendation
import com.xeriomy.brawldrafter.capture.ScreenCaptureManager
import com.xeriomy.brawldrafter.accessibility.AppWatcherService
import com.xeriomy.brawldrafter.ocr.DraftScreenParser
import com.xeriomy.brawldrafter.ocr.OcrEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/**
 * Foreground service managing the floating overlay button and live draft panel.
 *
 * Two modes: "api_only" (Brawlify meta data) and "ai_plus_api" (vision + AI + meta).
 *
 * Behavior:
 * - Tap BD button → shows live overlay panel + starts auto-polling (every 2.5s)
 * - Overlay updates automatically as new picks are detected
 * - Tap close button or BD button again → stops polling, dismisses panel
 * - No scanning animation — results appear and update live
 *
 * Layout (live overlay):
 *   Top-left:     GameMode - MapName
 *   Top-right:    Bans (smaller)
 *   Center-left:  Our team picks (+ recommended for empty slots)
 *   Center-right: Enemy picks
 *   Bottom-center: Top suggestion with reasoning/strategy
 */
class FloatingButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingButton: View? = null
    private var livePanel: View? = null

    // Live panel text views that get updated in-place
    private var tvGameModeMap: TextView? = null
    private var tvBans: TextView? = null
    private var tvTeamSection: LinearLayout? = null
    private var tvEnemySection: LinearLayout? = null
    private var tvSuggestionName: TextView? = null
    private var tvSuggestionScore: TextView? = null
    private var tvSuggestionTags: TextView? = null
    private var tvSuggestionReason: TextView? = null
    private var tvStatusText: TextView? = null

    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var ocrEngine: OcrEngine

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var livePollingJob: Job? = null

    // Track last seen state to detect changes
    private var lastSeenPicksHash: Int = 0

    // Touch handling for dragging
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    // Store last analysis for display
    private var lastAnalysis: DraftAnalysis? = null
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
        stopLivePolling()
        serviceScope.cancel()
        floatingButton?.let { windowManager.removeView(it) }
        livePanel?.let { windowManager.removeView(it) }
        screenCaptureManager.release()
    }

    // ========== Floating Button ==========

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingButton() {
        val density = resources.displayMetrics.density
        val buttonSize = (48 * density).toInt()

        val layout = LinearLayout(this).apply {
            setBackgroundColor(0xB000E676.toInt())
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

    // ========== Toggle Live Mode ==========

    private fun onFloatingButtonClicked() {
        if (livePollingJob?.isActive == true) {
            // Already live — stop
            stopLivePolling()
            dismissLivePanel()
            lastAnalysis = null
            lastDraftState = null
            lastSeenPicksHash = 0
        } else {
            // Start live mode
            if (!screenCaptureManager.isReady) {
                Toast.makeText(this, "Screen capture expired. Re-open the app to re-authorize.", Toast.LENGTH_LONG).show()
                return
            }
            showLivePanel()
            startLivePolling()
        }
    }

    // ========== Live Polling ==========

    private fun startLivePolling() {
        livePollingJob?.cancel()
        livePollingJob = serviceScope.launch {
            while (true) {
                try {
                    val result = captureAndAnalyze()
                    if (result != null) {
                        val (analysis, draftState) = result
                        val newHash = (draftState.teamPicks + draftState.enemyPicks).hashCode()
                        if (newHash != lastSeenPicksHash) {
                            lastSeenPicksHash = newHash
                            lastAnalysis = analysis
                            lastDraftState = draftState
                            updateLivePanel(analysis, draftState)
                        }
                    } else {
                        // No valid draft detected — show waiting state
                        if (lastDraftState != null) {
                            // We had a draft before but lost it — draft ended
                            updateStatusText("Draft ended or screen changed")
                        } else {
                            updateStatusText("Waiting for draft screen...")
                        }
                    }
                } catch (e: Exception) {
                    val msg = e.message ?: ""
                    if (msg.contains("non-current", ignoreCase = true) || msg.contains("VirtualDisplay", ignoreCase = true)) {
                        updateStatusText("Capture expired — restart overlay")
                        stopLivePolling()
                        break
                    }
                    // Silently retry on other errors
                }
                delay(2500) // Poll every 2.5 seconds
            }
        }
    }

    private fun stopLivePolling() {
        livePollingJob?.cancel()
        livePollingJob = null
    }

    /**
     * Capture screen → crop → OCR/vision → validate → analyze.
     * Returns null if no valid draft detected.
     *
     * Guards:
     * 1. If AccessibilityService is enabled, only scans when Brawl Stars is foreground
     * 2. Crops status bar + nav bar off the screenshot before OCR
     * 3. Hides overlays during capture
     */
    private suspend fun captureAndAnalyze(): Pair<DraftAnalysis, DraftState>? {
        // GUARD 1: Skip if not in Brawl Stars (if accessibility service is available)
        if (AppWatcherService.isEnabled && !AppWatcherService.isBrawlStarsForeground()) {
            updateStatusText("Open Brawl Stars to scan")
            return null
        }

        val sw = screenCaptureManager.screenWidth
        val sh = screenCaptureManager.screenHeight

        // Hide overlays before capture to prevent OCR false positives
        livePanel?.visibility = View.GONE
        floatingButton?.visibility = View.GONE
        delay(100)

        val fullBitmap = try {
            screenCaptureManager.captureScreen()
        } finally {
            livePanel?.visibility = View.VISIBLE
            floatingButton?.visibility = View.VISIBLE
        } ?: return null

        // GUARD 2: Crop status bar (top ~6%) and nav bar (bottom ~4%)
        // This eliminates carrier names, VPN icons, time, battery, navigation buttons, etc.
        val cropTop = (sh * 0.06).toInt()
        val cropBottom = (sh * 0.04).toInt()
        val bitmap: Bitmap = if (cropTop + cropBottom < sh) {
            Bitmap.createBitmap(fullBitmap, 0, cropTop, sw, sh - cropTop - cropBottom)
        } else {
            fullBitmap
        }

        // Use cropped dimensions for spatial analysis
        val croppedH = bitmap.height

        // OCR for text on the cropped image
        val textWithPositions = ocrEngine.analyzeWithPositions(bitmap)

        // Recycle cropped bitmap if it's a copy
        if (bitmap !== fullBitmap) bitmap.recycle()

        val ocrDraftState: DraftState = if (textWithPositions.isNotEmpty()) {
            DraftScreenParser.parseWithPositions(textWithPositions, sw, croppedH)
        } else {
            DraftState()
        }

        // Recycle full bitmap
        fullBitmap.recycle()

        // Vision for AI mode — use the full (uncropped) bitmap for better icon recognition
        // Actually, re-capture is expensive. The cropped one is fine for vision too.
        // In AI mode, we skip cropping for vision since brawler icons are in the game area anyway.
        val finalDraftState: DraftState = if (isAiMode && ocrDraftState.isValidDraft) {
            val app = application as? BrawlDrafterApp
            val engine = app?.currentEngine
            val visionId = engine?.createVisionIdentifier()
            if (visionId != null) {
                try {
                    // Re-capture for vision (full screen, uncropped — icons need full context)
                    livePanel?.visibility = View.GONE
                    floatingButton?.visibility = View.GONE
                    delay(80)
                    val visionBitmap = try {
                        screenCaptureManager.captureScreen()
                    } finally {
                        livePanel?.visibility = View.VISIBLE
                        floatingButton?.visibility = View.VISIBLE
                    } ?: return@captureAndAnalyze ocrDraftState
                    try {
                        val visionResult = visionId.identify(visionBitmap)
                        visionBitmap.recycle()
                        visionResult.toDraftState(ocrGameMode = ocrDraftState.mapGameMode)
                    } catch (e: Exception) {
                        visionBitmap.recycle()
                        ocrDraftState
                    }
                } catch (e: Exception) {
                    ocrDraftState
                }
            } else {
                ocrDraftState
            }
        } else {
            ocrDraftState
        }

        // Validate
        if (!finalDraftState.isValidDraft) return null

        // Get recommendations
        val app = application as? BrawlDrafterApp
        val engine = app?.currentEngine ?: return null

        val analysis = if (isAiMode) {
            try {
                engine.analyze(finalDraftState)
            } catch (e: Exception) {
                engine.analyzeApiOnly(finalDraftState)
            }
        } else {
            engine.analyzeApiOnly(finalDraftState)
        }

        return analysis to finalDraftState
    }

    // ========== Live Panel (new transparent design) ==========

    @SuppressLint("SetTextI18n")
    private fun showLivePanel() {
        dismissLivePanel()

        val d = resources.displayMetrics.density
        val sw = screenCaptureManager.screenWidth

        // Root — full screen, fully transparent
        val root = FrameLayout(this)

        // Content panel — bottom-right corner, very transparent
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x50121218.toInt()) // ~31% opaque dark — much more transparent
            setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
        }

        // === ROW 1: GameMode+Map (left) | Bans (right) ===
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Left: GameMode - MapName
        val mapInfoLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvGameModeMap = TextView(this).apply {
            text = "SCANNING..."
            textSize = 11f
            setTextColor(0xCC00E676.toInt()) // slightly transparent green
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        mapInfoLayout.addView(tvGameModeMap)
        topRow.addView(mapInfoLayout)

        // Right: Bans
        tvBans = TextView(this).apply {
            text = ""
            textSize = 9f
            setTextColor(0xCCFF5252.toInt())
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = (8 * d).toInt() }
        }
        topRow.addView(tvBans)
        panel.addView(topRow)

        // Separator
        panel.addView(makeSep(d, 0x30FFFFFF.toInt()))

        // === ROW 2: Our Team (left) | Enemy (right) ===
        val teamsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        // Our team
        tvTeamSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val teamTitle = TextView(this).apply {
            text = "OUR TEAM"
            textSize = 8f
            setTextColor(0xCC00E676.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (2 * d).toInt())
        }
        tvTeamSection!!.addView(teamTitle)
        // 3 placeholder slots
        for (i in 0 until 3) {
            tvTeamSection!!.addView(createSlotView("---", 0x55FFFFFF.toInt(), d, isBold = false))
        }
        tvTeamSection!!.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        teamsRow.addView(tvTeamSection)

        // Vertical divider
        teamsRow.addView(View(this).apply {
            setBackgroundColor(0x30FFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams((1 * d).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
                .apply { setMargins((4 * d).toInt(), (2 * d).toInt(), (4 * d).toInt(), (2 * d).toInt()) }
        })

        // Enemy team
        tvEnemySection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val enemyTitle = TextView(this).apply {
            text = "ENEMY"
            textSize = 8f
            setTextColor(0xCCFF5252.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (2 * d).toInt())
        }
        tvEnemySection!!.addView(enemyTitle)
        for (i in 0 until 3) {
            tvEnemySection!!.addView(createSlotView("---", 0x55FFFFFF.toInt(), d, isBold = false))
        }
        tvEnemySection!!.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        teamsRow.addView(tvEnemySection)

        panel.addView(teamsRow)

        // Separator
        panel.addView(makeSep(d, 0x30FFFFFF.toInt()))

        // === ROW 3: Suggestion ===
        val suggestionBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x18FFFFFF.toInt()) // very subtle white tint
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
        }

        val sugHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sugHeader.addView(TextView(this).apply {
            text = "PICK: "
            textSize = 10f
            setTextColor(0xCC00E676.toInt())
            typeface = Typeface.DEFAULT_BOLD
        })
        tvSuggestionName = TextView(this).apply {
            text = "---"
            textSize = 14f
            setTextColor(0xEEFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
        }
        sugHeader.addView(tvSuggestionName)
        tvSuggestionScore = TextView(this).apply {
            text = ""
            textSize = 10f
            setTextColor(0xEEFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding((3 * d).toInt(), 0, (3 * d).toInt(), 0)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = (4 * d).toInt() }
        }
        sugHeader.addView(tvSuggestionScore)
        suggestionBox.addView(sugHeader)

        tvSuggestionTags = TextView(this).apply {
            text = ""
            textSize = 9f
            setTextColor(0xCC00D2FF.toInt())
            maxLines = 2
            setPadding(0, (2 * d).toInt(), 0, 0)
            visibility = View.GONE
        }
        suggestionBox.addView(tvSuggestionTags)

        tvSuggestionReason = TextView(this).apply {
            text = ""
            textSize = 9f
            setTextColor(0xAA888899.toInt())
            maxLines = 3
            setPadding(0, (2 * d).toInt(), 0, 0)
            visibility = View.GONE
        }
        suggestionBox.addView(tvSuggestionReason)

        panel.addView(suggestionBox)

        // Status text (bottom)
        tvStatusText = TextView(this).apply {
            text = "Waiting for draft screen..."
            textSize = 8f
            setTextColor(0x88666680.toInt())
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(0, (4 * d).toInt(), 0, 0)
        }
        panel.addView(tvStatusText)

        // Close button
        val closeBtn = TextView(this).apply {
            text = "CLOSE"
            textSize = 9f
            setTextColor(0xAAFF5252.toInt())
            typeface = Typeface.DEFAULT_BOLD
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding((16 * d).toInt(), (3 * d).toInt(), (16 * d).toInt(), (3 * d).toInt())
            setOnClickListener {
                stopLivePolling()
                dismissLivePanel()
                lastAnalysis = null
                lastDraftState = null
                lastSeenPicksHash = 0
            }
        }
        panel.addView(closeBtn)

        // Size and position: bottom-right, compact
        val panelWidth = (minOf(sw * 0.88f, 400 * d)).toInt()
        panel.layoutParams = FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = (60 * d).toInt()
            marginEnd = (8 * d).toInt()
        }

        root.addView(panel)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(root, params)
        livePanel = root
    }

    /**
     * Update the live panel in-place with new analysis data.
     * Only rebuilds the team sections — other views are updated via text.
     */
    @SuppressLint("SetTextI18n")
    private fun updateLivePanel(analysis: DraftAnalysis, draftState: DraftState) {
        val d = resources.displayMetrics.density

        // Update game mode + map name
        val gameModeText = draftState.mapGameMode.name.replace("_", " ")
        val mapNameText = draftState.mapName.ifBlank { "Unknown Map" }
        tvGameModeMap?.text = "${gameModeText.uppercase()} - $mapNameText"

        // Update bans
        val allBans = draftState.teamBans + draftState.enemyBans
        if (allBans.isNotEmpty()) {
            tvBans?.text = "BAN: ${allBans.joinToString(", ")}"
            tvBans?.visibility = View.VISIBLE
        } else {
            tvBans?.visibility = View.GONE
        }

        // Update team sections
        updateTeamSlots(tvTeamSection, "OUR TEAM", draftState.teamPicks, analysis.recommendations, isEnemy = false, d = d)
        updateTeamSlots(tvEnemySection, "ENEMY", draftState.enemyPicks, analysis.recommendations, isEnemy = true, d = d)

        // Update suggestion
        val topRec = analysis.recommendations.firstOrNull()
        if (topRec != null) {
            tvSuggestionName?.text = topRec.brawlerName
            val gradeColor = gradeColor(topRec.grade)
            tvSuggestionScore?.text = " ${topRec.grade} ${topRec.score.toInt()}% "
            tvSuggestionScore?.setTextColor(gradeColor)
            tvSuggestionScore?.visibility = View.VISIBLE

            // Tags
            val tags = mutableListOf<String>()
            if (topRec.counterTo.isNotEmpty()) tags.add("Counters ${topRec.counterTo.joinToString(", ")}")
            if (topRec.synergyWith.isNotEmpty()) tags.add("Synergy ${topRec.synergyWith.joinToString(", ")}")
            if (topRec.weakTo.isNotEmpty()) tags.add("Weak to ${topRec.weakTo.joinToString(", ")}")
            if (tags.isNotEmpty()) {
                tvSuggestionTags?.text = tags.joinToString("  |  ")
                tvSuggestionTags?.visibility = View.VISIBLE
            } else {
                tvSuggestionTags?.visibility = View.GONE
            }

            // Reasoning
            val reason = topRec.reasoning
            if (reason.isNotBlank() && !reason.startsWith("AI not configured")) {
                tvSuggestionReason?.text = reason
                tvSuggestionReason?.visibility = View.VISIBLE
            } else {
                tvSuggestionReason?.visibility = View.GONE
            }
        }

        updateStatusText("Live")
    }

    /**
     * Rebuild a team section's brawler slots in-place.
     */
    @SuppressLint("SetTextI18n")
    private fun updateTeamSlots(
        section: LinearLayout?,
        title: String,
        picks: List<String>,
        recommendations: List<Recommendation>,
        isEnemy: Boolean,
        d: Float
    ) {
        if (section == null) return
        section.removeAllViews()

        // Title
        section.addView(TextView(this).apply {
            text = title
            textSize = 8f
            setTextColor(if (isEnemy) 0xCCFF5252.toInt() else 0xCC00E676.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (2 * d).toInt())
        })

        // Fill 3 slots
        for (i in 0 until 3) {
            if (i < picks.size) {
                // Picked brawler
                section.addView(createSlotView("  ${picks[i]}", 0xEEFFFFFF.toInt(), d, isBold = true))
            } else {
                // Empty slot — show recommended pick for our team only
                val recommended = if (!isEnemy) recommendations.firstOrNull() else null
                if (recommended != null) {
                    section.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, (1 * d).toInt(), 0, (1 * d).toInt())
                        addView(TextView(this@FloatingButtonService).apply {
                            text = "  -> ${recommended.brawlerName}"
                            textSize = 11f
                            setTextColor(0xCCFFD740.toInt())
                            typeface = Typeface.DEFAULT_BOLD
                        })
                    })
                } else {
                    section.addView(createSlotView("  ---", 0x44FFFFFF.toInt(), d, isBold = false))
                }
            }
        }
    }

    private fun createSlotView(text: String, color: Int, d: Float, isBold: Boolean): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = if (isBold) 12f else 10f
            setTextColor(color)
            typeface = if (isBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setPadding(0, (1 * d).toInt(), 0, (1 * d).toInt())
        }
    }

    private fun updateStatusText(text: String) {
        tvStatusText?.text = text
    }

    private fun dismissLivePanel() {
        livePanel?.let { windowManager.removeView(it) }
        livePanel = null
        tvGameModeMap = null
        tvBans = null
        tvTeamSection = null
        tvEnemySection = null
        tvSuggestionName = null
        tvSuggestionScore = null
        tvSuggestionTags = null
        tvSuggestionReason = null
        tvStatusText = null
    }

    private fun makeSep(density: Float, color: Int): View {
        return View(this).apply {
            setBackgroundColor(color)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
            ).apply { setMargins(0, (3 * density).toInt(), 0, (3 * density).toInt()) }
        }
    }

    private fun gradeColor(grade: String): Int = when (grade) {
        "S" -> 0xFF00E676.toInt()
        "A" -> 0xFF00D2FF.toInt()
        "B" -> 0xFFFFD740.toInt()
        "C" -> 0xFFFF9100.toInt()
        else -> 0xFFFF5252.toInt()
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