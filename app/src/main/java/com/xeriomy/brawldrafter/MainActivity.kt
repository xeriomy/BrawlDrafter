package com.xeriomy.brawldrafter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xeriomy.brawldrafter.data.api.LlmProvider
import com.xeriomy.brawldrafter.overlay.FloatingButtonService
import com.xeriomy.brawldrafter.ui.theme.BrawlDrafterTheme

class MainActivity : ComponentActivity() {

    private var mediaProjectionResultCode = 0
    private var mediaProjectionData: Intent? = null

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            mediaProjectionResultCode = result.resultCode
            mediaProjectionData = result.data
            startOverlayService()
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BrawlDrafterTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF1A1A2E)
                ) {
                    MainScreen(
                        onStartClick = { startWithPermissions() },
                        onStopClick = { stopOverlayService() },
                        context = this
                    )
                }
            }
        }
    }

    private fun startWithPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Grant overlay permission, then tap Start again", Toast.LENGTH_LONG).show()
            return
        }

        val captureManager = com.xeriomy.brawldrafter.capture.ScreenCaptureManager(this)
        val captureIntent = captureManager.createCaptureIntent()
        mediaProjectionLauncher.launch(captureIntent)
    }

    private fun startOverlayService() {
        val intent = Intent(this, FloatingButtonService::class.java).apply {
            putExtra("resultCode", mediaProjectionResultCode)
            putExtra("data", mediaProjectionData)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopOverlayService() {
        FloatingButtonService.stop(this)
        Toast.makeText(this, "Overlay stopped", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    context: Context
) {
    // Load saved settings
    val prefs = remember { context.getSharedPreferences("brawldrafter", Context.MODE_PRIVATE) }
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var selectedProvider by remember { mutableStateOf(prefs.getString("provider", "OpenAI") ?: "OpenAI") }
    var selectedMode by remember { mutableStateOf(prefs.getString("mode", "api_only") ?: "api_only") }
    var isRunning by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))

        // Logo / Title
        Text(
            text = "BRAWLDRAFTER",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF00D2FF)
        )
        Text(
            text = "AI-Powered Draft Assistant",
            fontSize = 14.sp,
            color = Color(0xFFA0A0B0)
        )

        Spacer(Modifier.height(32.dp))

        // Status card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF16213E)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isRunning) "Overlay Active" else "Overlay Inactive",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isRunning) Color(0xFF00E676) else Color.White
                    )
                }

                if (isRunning) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Switch to Brawl Stars and tap the floating BD button during draft.",
                        fontSize = 12.sp,
                        color = Color(0xFFA0A0B0)
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Mode selector
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF16213E)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    text = "Analysis Mode",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )

                Spacer(Modifier.height(12.dp))

                val isApiOnly = selectedMode == "api_only"
                val isAiPlusApi = !isApiOnly

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // API Only mode
                    OutlinedButton(
                        onClick = { selectedMode = "api_only" },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isApiOnly) Color(0xFF0F3460) else Color.Transparent,
                            contentColor = if (isApiOnly) Color(0xFF00E676) else Color(0xFFA0A0B0)
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("API Only", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("No API key needed", fontSize = 10.sp)
                        }
                    }

                    // AI + API mode
                    OutlinedButton(
                        onClick = { selectedMode = "ai_plus_api" },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isAiPlusApi) Color(0xFF0F3460) else Color.Transparent,
                            contentColor = if (isAiPlusApi) Color(0xFF00D2FF) else Color(0xFFA0A0B0)
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("AI + API", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Requires API key", fontSize = 10.sp)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (isApiOnly)
                        "Uses live meta data (win rates, counters, synergies) to score picks. Works without any API key."
                    else
                        "Combines AI reasoning with live meta data for the best recommendations.",
                    fontSize = 11.sp,
                    color = Color(0xFF666680)
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // AI Configuration (only shown in AI+API mode)
        val aiAlpha = if (selectedMode == "ai_plus_api") 1f else 0.4f
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF16213E)),
            modifier = Modifier.fillMaxWidth().alpha(aiAlpha)
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    text = "AI Configuration",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )

                Spacer(Modifier.height(12.dp))

                // Provider selector
                Text("LLM Provider", fontSize = 12.sp, color = Color(0xFFA0A0B0))
                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("OpenAI", "Gemini", "Claude").forEach { provider ->
                        val isSelected = selectedProvider == provider
                        OutlinedButton(
                            onClick = { selectedProvider = provider },
                            enabled = selectedMode == "ai_plus_api",
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isSelected) Color(0xFF0F3460) else Color.Transparent,
                                contentColor = if (isSelected) Color(0xFF00D2FF) else Color(0xFFA0A0B0)
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(provider, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // API Key input
                Text("API Key", fontSize = 12.sp, color = Color(0xFFA0A0B0))
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    placeholder = { Text("sk-... or API key", fontSize = 13.sp, color = Color(0xFF666680)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedMode == "ai_plus_api",
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00D2FF),
                        unfocusedBorderColor = Color(0xFF333355)
                    )
                )

                Spacer(Modifier.height(4.dp))
                Text(
                    text = when (selectedProvider) {
                        "OpenAI" -> "Get your key at platform.openai.com"
                        "Gemini" -> "Get your key at aistudio.google.com"
                        "Claude" -> "Get your key at console.anthropic.com"
                        else -> ""
                    },
                    fontSize = 11.sp,
                    color = Color(0xFF666680)
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // Start / Stop buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    // Save all settings
                    prefs.edit()
                        .putString("api_key", apiKey)
                        .putString("provider", selectedProvider)
                        .putString("mode", selectedMode)
                        .apply()

                    // Update the engine in the app
                    val app = (context.applicationContext as BrawlDrafterApp)
                    val provider = when (selectedProvider) {
                        "Gemini" -> LlmProvider.GEMINI
                        "Claude" -> LlmProvider.CLAUDE
                        else -> LlmProvider.OPENAI
                    }
                    app.updateEngine(
                        apiKey = if (selectedMode == "ai_plus_api") apiKey else null,
                        provider = provider,
                        mode = selectedMode
                    )

                    isRunning = true
                    onStartClick()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00D2FF)),
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            ) {
                Text("Start Overlay", color = Color(0xFF1A1A2E), fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = {
                    isRunning = false
                    onStopClick()
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252)),
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            ) {
                Text("Stop")
            }
        }

        Spacer(Modifier.height(24.dp))

        // Instructions
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF16213E)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("How to Use", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
                Spacer(Modifier.height(12.dp))

                val steps = if (selectedMode == "api_only") listOf(
                    "1. Select 'API Only' mode above (no API key needed!)",
                    "2. Tap 'Start Overlay' and grant permissions",
                    "3. Open Brawl Stars and enter a draft",
                    "4. Tap the floating 'BD' button on screen",
                    "5. View data-driven pick recommendations!",
                    "6. The button is draggable - move it anywhere"
                ) else listOf(
                    "1. Set your LLM API key above (GPT-4o-mini recommended)",
                    "2. Tap 'Start Overlay' and grant permissions",
                    "3. Open Brawl Stars and enter a draft",
                    "4. Tap the floating 'BD' button on screen",
                    "5. View AI-powered pick recommendations!",
                    "6. The button is draggable - move it anywhere"
                )
                steps.forEach { step ->
                    Text(step, fontSize = 12.sp, color = Color(0xFFA0A0B0), lineHeight = 18.sp)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        Spacer(Modifier.height(48.dp))
    }
}