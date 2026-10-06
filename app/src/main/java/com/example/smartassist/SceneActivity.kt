package com.example.smartassist

// ═══════════════════════════════════════════════════════════════════════════
//  SceneActivity — Unified Vision Pipeline WITH GESTURE CONTROL & URDU SUPPORT
//                   & SIMPLIFIED BATTERY OPTIMIZATION
// ═══════════════════════════════════════════════════════════════════════════

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SceneActivity : AppCompatActivity(), ObjectDetectorHelper.DetectorListener {

    // ─── Views ────────────────────────────────────────────────────────────────
    private lateinit var previewView: PreviewView
    private lateinit var sceneStatus: TextView
    private lateinit var tvSceneText: TextView
    private lateinit var featurePanel: View
    private lateinit var btnReadAloud: Button
    private lateinit var textPreviewScroll: ScrollView
    private lateinit var btnCapture: ImageButton
    private lateinit var btnUpload: ImageButton
    private lateinit var muteIndicator: TextView
    private lateinit var btnLanguageToggle: Button

    // ─── Language Support ─────────────────────────────────────────────────────
    private var currentLanguage = "en"
    private lateinit var urduTranslator: UrduTranslator
    private lateinit var userManager: UserManager

    // ─── TTS ──────────────────────────────────────────────────────────────────
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isSpeaking = false
    private var urduTts: TextToSpeech? = null
    private var urduTtsReady = false

    // ─── Camera ───────────────────────────────────────────────────────────────
    private var imageCapture: ImageCapture? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val CAPTURE_INTERVAL_MS = 9_000L
    @Volatile private var isProcessing = false
    @Volatile private var isAppInFront = false
    @Volatile private var gptBlocked = false
    private var currentPipelineId = 0
    private var isCameraStarted = false
    private var permissionRequested = false

    private val executor = Executors.newSingleThreadExecutor()
    private var lastSceneText = ""

    // ─── Gesture Control ──────────────────────────────────────────────────────
    private lateinit var gestureController: GestureController
    private var isNarrationMuted = false

    // ─── Danger Cooldown ──────────────────────────────────────────────────────
    private var lastDangerHash = 0L
    private var lastDangerTime = 0L
    private val DANGER_COOLDOWN_MS = 60_000L

    // ─── Battery Optimization ─────────────────────────────────────────────────
    private lateinit var batteryOptimizer: BatteryOptimizer
    private var lastBatteryAlertTime = 0L
    private val BATTERY_ALERT_COOLDOWN_MS = 300_000L

    // ─── Database ────────────────────────────────────────────────────────────
    private lateinit var database: AppDatabase
    private lateinit var repository: AppRepository
    private var isDbInitialized = false

    // ─── Distance Estimator ─────────────────────────────────────────────────
    private lateinit var distanceEstimator: DistanceEstimator

    // ─── OpenAI ───────────────────────────────────────────────────────────────
    private val OPENAI_API_KEY = ""
    private val OPENAI_MODEL = "gpt-4o-mini"
    private val OPENAI_URL = "https://api.openai.com/v1/chat/completions"

    private val SCENE_PROMPT = """
        You are the eyes of a visually impaired person. Describe what you see in maximum 3 sentences only.
        
        CRITICAL RULES:
        1. Estimate distance in STEPS for each object. 1 step = 70 centimeters.
        2. Distance: very close (<1 step), 1-2 steps, 3-4 steps, 5-6 steps, far (7+ steps)
        3. Mention position: directly ahead, on your left, on your right
        4. For DANGEROUS objects, START with "DANGER:" then object, distance, position, and action
        5. DO NOT describe: colors, lighting, atmosphere, feelings, design, or patterns
        6. MAXIMUM 3 SENTENCES ONLY. Be concise.
        
        Now describe this image in 3 sentences or less:
""".trimIndent()

    private val SCENE_PROMPT_URDU = """
        آپ نابینا شخص کی آنکھیں ہیں۔ جو دیکھتے ہیں زیادہ سے زیادہ 3 جملوں میں بیان کریں۔
        
        اہم قواعد:
        1. ہر چیز کا فاصلہ قدموں میں بتائیں۔ 1 قدم = 70 سینٹی میٹر
        2. فاصلہ: بہت قریب (1 قدم سے کم)، 1-2 قدم، 3-4 قدم، 5-6 قدم، دور (7+ قدم)
        3. پوزیشن: سیدھے سامنے، بائیں طرف، دائیں طرف
        4. خطرناک چیز ہو تو "خطرہ:" سے شروع کریں
        5. رنگ، روشنی، ماحول، جذبات، ڈیزائن کے بارے میں نہ بتائیں
        6. زیادہ سے زیادہ 3 جملے ہی بولیں
        
        اب تصویر 3 جملوں میں بیان کریں:
""".trimIndent()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private var lastImageHash = 0L
    private val SCENE_CHANGE_THRESHOLD = 8.0

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private var toneGenerator: ToneGenerator? = null

    private var objectDetectorHelper: ObjectDetectorHelper? = null
    @Volatile private var latestDetections: MutableList<Detection>? = null
    @Volatile private var latestImageHeight = 0
    @Volatile private var latestImageWidth = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_scene)

        userManager = UserManager(this)

        // Initialize Database FIRST
        initializeDatabase()

        // Now get language from database
        currentLanguage = userManager.getLanguage()

        Log.d("LanguageDebug", "Language loaded in SceneActivity: $currentLanguage")
        Log.d("DeviceDebug", "Android ID: ${getAndroidId()}")

        urduTranslator = UrduTranslator(this)

        // Initialize Distance Estimator
        distanceEstimator = DistanceEstimator(this)
        distanceEstimator.updateLanguage(currentLanguage)

        initializeViews()
        setupClickListeners()
        initTTS()
        initUrduTTS()

        batteryOptimizer = BatteryOptimizer(this, object : BatteryOptimizer.BatteryEventListener {
            override fun onLowBattery(level: Int) {
                Log.w("Battery", "Low battery: $level%")
                speakBatteryAlert(level, false)
            }

            override fun onCriticalBattery(level: Int) {
                Log.w("Battery", "Critical battery: $level%")
                speakBatteryAlert(level, true)
                // Screen dimming is
                // handled by BatteryOptimizer
            }

            override fun onBatteryHealthy(level: Int) {
                Log.d("Battery", "Battery healthy: $level%")
                // Brightness restored by BatteryOptimizer
            }

            override fun onChargingStarted() {
                Log.d("Battery", "Charging started")
                val msg = if (currentLanguage == "ur") "چارجنگ شروع" else "Charging started"
                Toast.makeText(this@SceneActivity, msg, Toast.LENGTH_SHORT).show()
            }

            override fun onChargingStopped() {
                Log.d("Battery", "Charging stopped")
            }
        })

        try {
            objectDetectorHelper = ObjectDetectorHelper(context = this, listener = this)
        } catch (e: Exception) {
            Log.e("Scene", "Detector init error: ${e.message}")
        }

        checkAndRequestPermissions()
    }

    /**
     * Get the device's Android ID (unique identifier)
     */
    private fun getAndroidId(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    }

    private fun initializeDatabase() {
        if (isDbInitialized) return

        database = AppDatabase.getDatabase(this)
        repository = AppRepository(database.userSettingsDao(), database.sceneHistoryDao())

        userManager.initRepository(database)

        isDbInitialized = true
        Log.d("Database", "Database initialized successfully")
    }

    private fun initializeViews() {
        previewView = findViewById(R.id.previewViewScene)
        sceneStatus = findViewById(R.id.sceneStatus)
        tvSceneText = findViewById(R.id.tvSceneText)
        featurePanel = findViewById(R.id.featurePanelScene)
        btnReadAloud = findViewById(R.id.btnReadAloudScene)
        textPreviewScroll = findViewById(R.id.textPreviewScrollScene)
        btnCapture = findViewById(R.id.btnCaptureScene)
        btnUpload = findViewById(R.id.btnUploadScene)
        btnLanguageToggle = findViewById(R.id.btnLanguageToggle)

        updateLanguageButtonIcon()

        featurePanel.visibility = View.GONE
        sceneStatus.text = "Starting..."

        muteIndicator = TextView(this).apply {
            text = "🔇 MUTED"
            setTextColor(resources.getColor(android.R.color.white, null))
            setBackgroundColor(resources.getColor(android.R.color.holo_red_dark, null))
            visibility = View.GONE
            textSize = 14f
            setPadding(16, 8, 16, 8)
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.scene_main)) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }
    }

    private fun setupClickListeners() {

        btnReadAloud.setOnClickListener {
            if (!isNarrationMuted && lastSceneText.isNotBlank()) {
                stopCurrentSpeech()
                speak(lastSceneText)
            }
        }

        btnLanguageToggle.setOnClickListener {
            currentLanguage = if (currentLanguage == "en") "ur" else "en"
            userManager.saveLanguage(currentLanguage)
            updateLanguageButtonIcon()
            distanceEstimator.updateLanguage(currentLanguage)
            Toast.makeText(this, if (currentLanguage == "ur") "زبان اردو" else "Language English", Toast.LENGTH_SHORT).show()
            gptBlocked = false
        }

        gestureController = GestureController(
            view = findViewById(R.id.scene_main),
            onMuteStateChanged = { isMuted ->
                isNarrationMuted = isMuted
                updateMuteIndicator(isMuted)

                if (isMuted) {
                    stopCurrentSpeech()
                    val muteMessage = if (currentLanguage == "ur") "آواز بند کر دی گئی" else "Voice muted"
                    speakWithLanguage(muteMessage)
                    Toast.makeText(this, if (currentLanguage == "ur") "آواز بند" else "Muted", Toast.LENGTH_SHORT).show()
                } else {
                    val unmuteMessage = if (currentLanguage == "ur") "آواز آن کر دی گئی" else "Voice unmuted"
                    speakWithLanguage(unmuteMessage)
                    Toast.makeText(this, if (currentLanguage == "ur") "آواز آن" else "Unmuted", Toast.LENGTH_SHORT).show()
                }
            }
        )

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (e: Exception) {
            Log.e("Scene", "ToneGenerator error: ${e.message}")
        }
    }

    fun getBatteryOptimizer(): BatteryOptimizer = batteryOptimizer

    // ─── Battery Alert Speaking ──────────────────────────────────────────────

    /**
     * Speak battery alert with retry mechanism
     * Ensures voice is heard even if TTS initializes late
     */
    private fun speakBatteryAlert(level: Int, isCritical: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastBatteryAlertTime < BATTERY_ALERT_COOLDOWN_MS) return
        lastBatteryAlertTime = now

        val message = if (currentLanguage == "ur") {
            if (isCritical) "بیٹری ${level} فیصد ہے۔ براہ کرم فوری طور پر چارج کریں"
            else "بیٹری ${level} فیصد ہے۔ براہ کرم چارج کریں"
        } else {
            if (isCritical) "Battery $level%. Please charge immediately"
            else "Battery $level%. Please charge your device"
        }

        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            sceneStatus.text = if (isCritical) "⚠️ CRITICAL: $level%" else "⚠️ LOW BATTERY: $level%"

            // Use retry mechanism to ensure voice is heard
            speakMessageWithRetry(message, 3)
        }
        Log.w("BatteryTest", "BATTERY ALERT: $message")
    }

    /**
     * Try to speak message with retries if TTS is not ready yet
     * This ensures battery alerts are heard even if TTS initializes late
     */
    private fun speakMessageWithRetry(message: String, retries: Int) {
        if (retries <= 0) {
            Log.e("BatteryAlert", "❌ Failed to speak battery alert after all retries")
            return
        }

        val isTtsReady = if (currentLanguage == "ur") urduTtsReady else ttsReady

        if (isTtsReady) {
            // Stop any ongoing speech before speaking alert
            stopCurrentSpeech()
            speakWithLanguage(message)
            Log.d("BatteryAlert", "✅ Battery alert spoken successfully")
        } else {
            Log.d("BatteryAlert", "⏳ TTS not ready, retrying... ($retries retries left)")
            Handler(Looper.getMainLooper()).postDelayed({
                speakMessageWithRetry(message, retries - 1)
            }, 500)
        }
    }

    private fun checkAndRequestPermissions() {
        if (hasCameraPermission()) {
            Log.d("PERMISSION", "Camera permission already granted - starting camera")
            startCamera()
        } else {
            if (!permissionRequested) {
                Log.d("PERMISSION", "Requesting camera permission for the first time")
                permissionRequested = true
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 0)
            }
        }
    }

    private fun stopCurrentSpeech() {
        if (currentLanguage == "ur" && urduTtsReady) {
            urduTts?.stop()
        } else if (ttsReady) {
            tts?.stop()
        }
        isSpeaking = false
    }

    private fun updateLanguageButtonIcon() {
        if (::btnLanguageToggle.isInitialized) {
            btnLanguageToggle.text = if (currentLanguage == "en") "🇺🇸 EN" else "🇵🇰 اردو"
        }
    }

    private fun initUrduTTS() {
        try {
            urduTts = TextToSpeech(this) { status ->
                urduTtsReady = (status == TextToSpeech.SUCCESS)
                if (urduTtsReady) {
                    val result = urduTts?.setLanguage(Locale("ur", "PK"))
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        urduTtsReady = false
                    } else {
                        urduTts?.setSpeechRate(0.95f)
                        urduTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) { isSpeaking = true }
                            override fun onDone(utteranceId: String?) { isSpeaking = false; finishSpeaking() }
                            override fun onError(utteranceId: String?) { isSpeaking = false; finishSpeaking() }
                        })
                    }
                }
            }
        } catch (e: Exception) {
            urduTtsReady = false
        }
    }

    private fun updateMuteIndicator(isMuted: Boolean) {
        runOnUiThread {
            val muteCard = findViewById<com.google.android.material.card.MaterialCardView>(R.id.muteIndicatorCard)
            val muteText = findViewById<TextView>(R.id.muteIndicatorText)

            if (isMuted) {
                muteCard?.visibility = View.VISIBLE
                muteCard?.alpha = 0f
                muteCard?.animate()?.alpha(1f)?.setDuration(300)?.start()
                muteText?.text = if (currentLanguage == "ur") "آواز بند" else "MUTED"

                if (::muteIndicator.isInitialized) {
                    muteIndicator.visibility = View.GONE
                }
            } else {
                muteCard?.animate()?.alpha(0f)?.setDuration(300)?.withEndAction {
                    muteCard?.visibility = View.GONE
                }?.start()

                if (::muteIndicator.isInitialized) {
                    muteIndicator.visibility = View.GONE
                }
            }
        }
    }

    private fun initTTS() {
        tts = TextToSpeech(this) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
            if (ttsReady) {
                val result = tts?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    ttsReady = false
                } else {
                    tts?.setSpeechRate(0.95f)
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(u: String?) { isSpeaking = true }
                        override fun onDone(u: String?) { isSpeaking = false; finishSpeaking() }
                        override fun onError(u: String?) { isSpeaking = false; finishSpeaking() }
                    })
                    sceneStatus.text = if (currentLanguage == "ur") "تیار" else "Ready"
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isAppInFront = true
        gptBlocked = false
        lastImageHash = 0L
    }

    override fun onPause() {
        super.onPause()
        isAppInFront = false
        mainHandler.removeCallbacksAndMessages(null)
        stopCurrentSpeech()
        isProcessing = false
        gestureController.reset()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        stopCurrentSpeech()
        tts?.shutdown()
        urduTts?.shutdown()
        objectDetectorHelper?.close()
        gestureController.destroy()
        batteryOptimizer.unregisterReceiver()
        try { textRecognizer.close() } catch (_: Exception) {}
        try { toneGenerator?.release() } catch (_: Exception) {}
        executor.shutdownNow()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 0) {
            if (grantResults.isNotEmpty() && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                Log.d("PERMISSION", "Permission granted by user - starting camera")
                startCamera()
            } else {
                Log.e("PERMISSION", "Permission denied by user")
                runOnUiThread {
                    sceneStatus.text = if (currentLanguage == "ur") "کیمرے کی اجازت ضروری ہے" else "Camera permission required"
                    Toast.makeText(this, if (currentLanguage == "ur") "براہ کرم کیمرے کی اجازت دیں" else "Please grant camera permission", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onError(msg: String) { Log.e("Scene", "Detector error: $msg") }
    override fun onResults(results: MutableList<Detection>?, imageHeight: Int, imageWidth: Int) {
        latestDetections = results
        latestImageHeight = imageHeight
        latestImageWidth = imageWidth
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        if (isCameraStarted) {
            Log.d("Scene", "Camera already starting/started, skipping")
            return
        }
        isCameraStarted = true

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                runOnUiThread {
                    sceneStatus.text = if (currentLanguage == "ur") "کیمرہ تیار" else "Camera ready"
                }
                mainHandler.postDelayed({ triggerCapture() }, 2000L)
            } catch (e: Exception) {
                runOnUiThread { sceneStatus.text = "Camera failed: ${e.message}" }
                isCameraStarted = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun triggerCapture() {
        if (!isAppInFront || isProcessing) return

        val imageCapture = this.imageCapture ?: run {
            scheduleNextCapture()
            return
        }
        isProcessing = true
        runOnUiThread { sceneStatus.text = if (currentLanguage == "ur") "منظر لیا جا رہا ہے" else "Capturing scene" }

        imageCapture.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            @RequiresApi(Build.VERSION_CODES.O)
            override fun onCaptureSuccess(imageProxy: ImageProxy) {
                try {
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    val originalBitmap = imageProxy.toBitmap()
                    val bitmap = originalBitmap.copy(originalBitmap.config, true)
                    originalBitmap.recycle()
                    imageProxy.close()

                    if (bitmap != null) {
                        currentPipelineId++
                        val currentId = currentPipelineId
                        lifecycleScope.launch(Dispatchers.IO) {
                            runPipeline(bitmap, rotationDegrees, currentId)
                        }
                    } else {
                        isProcessing = false
                        scheduleNextCapture()
                    }
                } catch (e: Exception) {
                    isProcessing = false
                    scheduleNextCapture()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                isProcessing = false
                runOnUiThread { sceneStatus.text = if (currentLanguage == "ur") "کیپچر ناکام" else "Capture failed" }
                scheduleNextCapture()
            }
        })
    }

    private fun scheduleNextCapture() {
        if (!isAppInFront) return
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (isAppInFront && !isProcessing) triggerCapture()
        }, CAPTURE_INTERVAL_MS)
    }

    private fun shouldUseGptInsteadOfYolo(detectionCount: Int): Boolean = detectionCount == 0

    private fun getDistanceText(label: String, boundingBox: RectF): String {
        return distanceEstimator.getDistanceDescription(label, boundingBox)
    }

    private fun runDetectionAtMultipleScales(
        helper: ObjectDetectorHelper,
        bitmap: Bitmap,
        rotationDegrees: Int
    ): MutableList<Detection>? {
        helper.detect(bitmap, rotationDegrees)
        var results = latestDetections

        if ((results?.size ?: 0) <= 2) {
            val upscaled = Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * 1.4).toInt(),
                (bitmap.height * 1.4).toInt(), true)
            helper.detect(upscaled, rotationDegrees)
            val upscaledResults = latestDetections
            upscaled.recycle()

            if (upscaledResults != null && results != null) {
                val existingLabels = results.map { it.categories[0].label.lowercase() }.toSet()
                for (det in upscaledResults) {
                    val label = det.categories[0].label.lowercase()
                    if (!existingLabels.contains(label)) {
                        results.add(det)
                    }
                }
            } else if (upscaledResults != null) {
                results = upscaledResults
            }
        }

        return results
    }

    private fun getPositionText(cx: Float, imgW: Int): String {
        return when {
            cx < imgW * 0.35f -> if (currentLanguage == "ur") "آپ کی بائیں طرف" else "on your left"
            cx > imgW * 0.65f -> if (currentLanguage == "ur") "آپ کی دائیں طرف" else "on your right"
            else -> if (currentLanguage == "ur") "سیدھے سامنے" else "directly ahead"
        }
    }

    private fun extractDangerObject(dangerText: String): String {
        val lowerText = dangerText.lowercase()
        val dangerKeywords = setOf(
            "gun", "handgun", "rifle", "shotgun", "firearm", "pistol",
            "knife", "kitchen knife", "dagger", "sword", "axe",
            "chainsaw", "saw", "scissors", "blade",
            "hammer", "screwdriver", "wrench", "drill",
            "fire", "flame", "candle", "lighter", "torch",
            "snake", "scorpion", "lion", "tiger", "bear", "shark", "crocodile",
            "broken glass", "wire", "cable"
        )
        for (keyword in dangerKeywords) {
            if (lowerText.contains(keyword)) return keyword
        }
        return dangerText
    }

    private fun extractPositionFromDangerText(dangerText: String): String {
        val lowerText = dangerText.lowercase()
        return when {
            lowerText.contains("on your left") -> "آپ کی بائیں طرف"
            lowerText.contains("on your right") -> "آپ کی دائیں طرف"
            lowerText.contains("directly ahead") -> "سیدھے سامنے"
            else -> ""
        }
    }

    private fun extractDistanceFromDangerText(dangerText: String): String {
        val lowerText = dangerText.lowercase()
        return when {
            lowerText.contains("extremely close") || lowerText.contains("less than 1 step") -> "بہت قریب، ایک قدم سے بھی کم"
            lowerText.contains("very close") -> "بہت قریب"
            lowerText.contains("1 step") -> "تقریباً 1 قدم دور"
            lowerText.contains("2 steps") -> "تقریباً 2 قدم دور"
            lowerText.contains("3 steps") || lowerText.contains("3-4 steps") -> "تقریباً 3-4 قدم دور"
            lowerText.contains("5 steps") || lowerText.contains("5-6 steps") -> "تقریباً 5-6 قدم دور"
            else -> ""
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun runPipeline(bitmap: Bitmap, rotationDegrees: Int, pipelineId: Int) {
        if (pipelineId != currentPipelineId) {
            bitmap.recycle()
            return
        }

        try {
            withContext(Dispatchers.Main) {
                sceneStatus.text = if (currentLanguage == "ur") "تجزیہ" else "Analyzing"
            }

            val fingerprint = repository.generateImageFingerprint(bitmap)
            val cachedDescription = repository.findSimilarScene(fingerprint, currentLanguage, 0.97f)
            val detection = runOnDeviceDetection(bitmap, rotationDegrees)

            var dangerDetected = false
            var dangerText = ""

            if (detection.dangerAlert.isNotBlank()) {
                dangerDetected = true
                dangerText = detection.dangerAlert
            }

            val keyOk = OPENAI_API_KEY.isNotBlank() && OPENAI_API_KEY.startsWith("sk-") && OPENAI_API_KEY.length > 20
            val yoloDetectionCount = objectDetectorHelper?.lastDetectionCount ?: 0

            // ✅ GPT is only used if battery allows it
            val shouldUseGpt = !dangerDetected &&
                    cachedDescription == null &&
                    keyOk &&
                    !gptBlocked &&
                    isAppInFront &&
                    shouldUseGptInsteadOfYolo(yoloDetectionCount)

            var gptResult: String? = null

            if (shouldUseGpt) {
                withContext(Dispatchers.Main) {
                    sceneStatus.text = if (currentLanguage == "ur") "منظر پہچانا جا رہا ہے" else "Recognizing scene"
                }
                val gptBitmap = bitmap.copy(bitmap.config, true)
                gptResult = callGptFast(gptBitmap)
                gptBitmap.recycle()
                if (pipelineId != currentPipelineId) return
            }

            // DANGER HANDLING - ALWAYS speaks even when muted
            if (dangerDetected) {
                val currentDangerHash = dangerText.hashCode().toLong()
                val now = System.currentTimeMillis()
                if (currentDangerHash != lastDangerHash || (now - lastDangerTime) > DANGER_COOLDOWN_MS) {
                    triggerDangerAlarm()
                    val finalWarningText = if (currentLanguage == "ur") {
                        urduTranslator.getUrduDangerAlert(dangerText)
                    } else {
                        dangerText
                    }
                    lastSceneText = finalWarningText
                    lastDangerHash = currentDangerHash
                    lastDangerTime = now
                    speakSafetyAlert(finalWarningText)
                }
                return
            }

            // MUTED MODE - Show text on screen but don't speak
            if (isNarrationMuted) {
                if (cachedDescription != null) {
                    lastSceneText = cachedDescription
                } else if (gptResult != null && gptResult.isNotBlank()) {
                    lastSceneText = gptResult.take(200)
                    saveSceneToDatabase(fingerprint, lastSceneText, dangerDetected)
                } else {
                    var finalText = detection.description
                    val signboardText = runOnDeviceSignboardDetection(bitmap)
                    if (signboardText.isNotBlank()) {
                        finalText = if (currentLanguage == "ur") {
                            "$finalText۔ $signboardText"
                        } else {
                            "$finalText. $signboardText"
                        }
                    }
                    lastSceneText = finalText
                    saveSceneToDatabase(fingerprint, lastSceneText, dangerDetected)
                }

                withContext(Dispatchers.Main) {
                    sceneStatus.text = if (currentLanguage == "ur") "خاموش - منظر محفوظ" else "Muted - Scene captured"
                    tvSceneText.text = lastSceneText
                    featurePanel.visibility = View.VISIBLE
                    textPreviewScroll.post { textPreviewScroll.fullScroll(ScrollView.FOCUS_UP) }
                }

                isProcessing = false
                scheduleNextCapture()
                return
            }

            // NORMAL MODE - Speak the description
            stopCurrentSpeech()

            if (cachedDescription != null) {
                lastSceneText = cachedDescription
                speak(cachedDescription)
                saveSceneToDatabase(fingerprint, lastSceneText, dangerDetected)
            } else if (gptResult != null && gptResult.isNotBlank()) {
                val cleanResult = gptResult.take(200)
                lastSceneText = cleanResult
                saveSceneToDatabase(fingerprint, lastSceneText, dangerDetected)
                speak(cleanResult)
            } else {
                var finalText = detection.description
                val signboardText = runOnDeviceSignboardDetection(bitmap)
                if (signboardText.isNotBlank()) {
                    finalText = if (currentLanguage == "ur") {
                        "$finalText۔ $signboardText"
                    } else {
                        "$finalText. $signboardText"
                    }
                }
                lastSceneText = finalText
                saveSceneToDatabase(fingerprint, lastSceneText, dangerDetected)
                speak(finalText)
            }

        } catch (e: Exception) {
            Log.e("Scene", "Pipeline error: ${e.message}")
        } finally {
            try { bitmap.recycle() } catch (_: Exception) {}
            isProcessing = false
            scheduleNextCapture()
        }
    }

    private fun saveSceneToDatabase(fingerprint: IntArray, description: String, wasDanger: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                repository.saveScene(fingerprint, description, currentLanguage, wasDanger)
                Log.d("Database", "Scene saved successfully")
            } catch (e: Exception) {
                Log.e("Database", "Failed to save scene: ${e.message}")
            }
        }
    }

    private fun callGptFast(bitmap: Bitmap): String? {
        return try {
            val maxDim = 320
            val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            } else bitmap
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 40, stream)
            val b64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            val prompt = if (currentLanguage == "ur") SCENE_PROMPT_URDU else SCENE_PROMPT
            val body = JSONObject().apply {
                put("model", OPENAI_MODEL)
                put("max_tokens", 100)
                put("temperature", 0.4)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().apply {
                            put(JSONObject().apply { put("type", "text"); put("text", prompt) })
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply { put("url", "data:image/jpeg;base64,$b64"); put("detail", "low") })
                            })
                        })
                    })
                })
            }.toString()
            val response = httpClient.newCall(
                Request.Builder().url(OPENAI_URL)
                    .addHeader("Authorization", "Bearer $OPENAI_API_KEY")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()
            val responseBody = response.body?.string() ?: ""
            if (response.code == 200) {
                val text = JSONObject(responseBody)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
                text.take(200).takeIf { it.isNotBlank() }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun buildOfflineNarration(raw: List<Detection>, imgW: Int, imgH: Int): String {
        if (raw.isEmpty() || imgW <= 0 || imgH <= 0) {
            return if (currentLanguage == "ur") "اردگرد کو دیکھ رہا ہوں" else "Looking around"
        }
        if (currentLanguage == "ur" && urduTranslator.isAvailable()) {
            return urduTranslator.createUrduNarration(raw, imgW, imgH)
        }
        val imgArea = imgW.toFloat() * imgH.toFloat()
        val sorted = raw
            .mapNotNull { d ->
                val best = d.categories.maxByOrNull { it.score } ?: return@mapNotNull null
                val box = d.boundingBox
                val ratio = (box.width() * box.height()) / imgArea
                Triple(best.label, box, ratio)
            }
            .filter { (_, _, ratio) -> ratio >= 0.005f }
            .sortedByDescending { (_, _, ratio) -> ratio }
            .take(3)
        if (sorted.isEmpty()) {
            return if (currentLanguage == "ur") "اردگرد کو دیکھ رہا ہوں" else "Looking around"
        }
        val sentences = mutableListOf<String>()
        for ((label, box, ratio) in sorted) {
            val cx = (box.left + box.right) / 2f
            val position = getPositionText(cx, imgW)
            val distance = getDistanceText(label, box)
            val objectName = label.replaceFirstChar { it.uppercase() }
            sentences.add("$objectName $position, $distance")
        }
        val description = sentences.joinToString(". ")
        return if (currentLanguage == "ur") {
            "میں دیکھ رہا ہوں $description."
        } else {
            "I see $description."
        }
    }

    private fun speakSafetyAlert(text: String) {
        saveToFile(text)
        runOnUiThread {
            sceneStatus.text = if (currentLanguage == "ur") "خطرہ" else "DANGER"
            tvSceneText.text = text
            featurePanel.visibility = View.VISIBLE
            textPreviewScroll.post { textPreviewScroll.fullScroll(ScrollView.FOCUS_UP) }
            if (text.isNotBlank()) speakWithLanguage(text)
        }
    }

    private fun finishSpeaking() {
        isSpeaking = false
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun triggerDangerAlarm() {
        Thread {
            try {
                toneGenerator?.let { tone ->
                    for (i in 1..3) {
                        tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400)
                        Thread.sleep(500)
                    }
                }
            } catch (e: Exception) { }
        }.start()
        try {
            (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.let {
                if (it.hasVibrator()) it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 300, 600), -1))
            }
        } catch (e: Exception) { }
    }

    data class DetectionResult(val description: String, val dangerAlert: String, val detections: List<Detection> = emptyList())

    private fun runOnDeviceDetection(bitmap: Bitmap, rotationDegrees: Int): DetectionResult {
        val helper = objectDetectorHelper ?: return DetectionResult(
            if (currentLanguage == "ur") "کیمرہ شروع ہو رہا ہے" else "Camera initializing", ""
        )
        return try {
            latestDetections = null
            latestImageHeight = 0
            latestImageWidth = 0
            val raw = runDetectionAtMultipleScales(helper, bitmap, rotationDegrees)
            val imgW = latestImageWidth
            val imgH = latestImageHeight
            val imgArea = imgW.toFloat() * imgH.toFloat()
            if (raw.isNullOrEmpty() || imgArea <= 0f) {
                return DetectionResult(
                    if (currentLanguage == "ur") "اردگرد کو دیکھ رہا ہوں" else "Looking around", ""
                )
            }
            val dangerParts = mutableListOf<String>()
            val DANGER_KEYWORDS = setOf(
                "gun", "handgun", "rifle", "shotgun", "firearm", "pistol",
                "knife", "kitchen knife", "dagger", "sword", "axe",
                "chainsaw", "saw", "scissors", "blade",
                "fire", "flame", "burning", "smoke", "candle", "lighter", "torch",
                "snake", "scorpion", "lion", "tiger", "bear", "shark", "crocodile",
                "broken glass", "hammer", "screwdriver", "wrench", "drill", "syringe",
                "wire", "cable"
            )
            val SCORE_THRESHOLD = 0.10f
            val SIZE_RATIO_THRESHOLD = 0.001f
            for (detection in raw) {
                val bestCat = detection.categories.maxByOrNull { it.score } ?: continue
                val labelRaw = bestCat.label.trim()
                val label = labelRaw.lowercase()
                val score = bestCat.score
                val box = detection.boundingBox
                val ratio = (box.width() * box.height()) / imgArea
                val isDanger = DANGER_KEYWORDS.any { keyword ->
                    label.contains(keyword) || keyword.contains(label)
                }
                val DANGER_SCORE_THRESHOLD = 0.06f
                val DANGER_SIZE_THRESHOLD = 0.0005f
                val finalScoreThreshold = if (isDanger) DANGER_SCORE_THRESHOLD else SCORE_THRESHOLD
                val finalSizeThreshold = if (isDanger) DANGER_SIZE_THRESHOLD else SIZE_RATIO_THRESHOLD
                if (isDanger && score >= finalScoreThreshold && ratio >= finalSizeThreshold) {
                    val cx = (box.left + box.right) / 2f
                    val position = when {
                        cx < imgW * 0.35f -> if (currentLanguage == "ur") "آپ کی بائیں طرف" else "on your left"
                        cx > imgW * 0.65f -> if (currentLanguage == "ur") "آپ کی دائیں طرف" else "on your right"
                        else -> if (currentLanguage == "ur") "سیدھے سامنے" else "directly ahead"
                    }
                    val distance = getDistanceText(labelRaw, box)
                    val displayName = if (currentLanguage == "ur" && urduTranslator.isAvailable()) {
                        urduTranslator.translate(labelRaw)
                    } else {
                        labelRaw.replaceFirstChar { it.uppercase() }
                    }
                    dangerParts.add("$displayName $position, $distance")
                }
            }
            val dangerAlert = dangerParts.take(3).joinToString(". ")
            val safeDesc = buildOfflineNarration(raw, imgW, imgH)
            DetectionResult(
                description = if (dangerAlert.isNotBlank()) {
                    if (currentLanguage == "ur") "خطرہ! $dangerAlert" else "DANGER! $dangerAlert"
                } else safeDesc,
                dangerAlert = dangerAlert,
                detections = raw
            )
        } catch (e: Exception) {
            Log.e("Scene", "Detection error: ${e.message}")
            DetectionResult(
                if (currentLanguage == "ur") "پتہ لگانے میں خرابی" else "Detection error", ""
            )
        }
    }

    // ============================================
    // SIGNBOARD TEXT DETECTION ONLY
    // ============================================

    private fun runOnDeviceSignboardDetection(bitmap: Bitmap): String {
        if (!isAppInFront) return ""
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val latch = CountDownLatch(1)
            val allTextBlocks = mutableListOf<com.google.mlkit.vision.text.Text.TextBlock>()

            textRecognizer.process(image).addOnSuccessListener { visionText ->
                for (block in visionText.textBlocks) {
                    allTextBlocks.add(block)
                }
                latch.countDown()
            }.addOnFailureListener {
                Log.e("SignboardDetection", "OCR failed")
                latch.countDown()
            }

            latch.await(1, TimeUnit.SECONDS)

            if (allTextBlocks.isEmpty()) return ""

            // Filter and group signboard texts with proper line ordering
            val signboardGroups = groupSignboardTexts(allTextBlocks, bitmap.height, bitmap.width)

            return formatSignboardTexts(signboardGroups)

        } catch (e: Exception) {
            Log.e("SignboardDetection", "Error: ${e.message}")
            return ""
        }
    }

    /**
     * Groups text blocks that belong to the same signboard and preserves reading order
     */
    private fun groupSignboardTexts(
        blocks: List<com.google.mlkit.vision.text.Text.TextBlock>,
        imageHeight: Int,
        imageWidth: Int
    ): List<String> {
        // First, filter potential signboard texts
        val signboardBlocks = blocks.filter { block ->
            val text = block.text.trim()
            if (text.isBlank() || text.length < 2) return@filter false
            isSignboardText(block, text, imageHeight, imageWidth)
        }

        if (signboardBlocks.isEmpty()) return emptyList()

        // Group blocks that are close to each other (same signboard)
        val groups = mutableListOf<MutableList<com.google.mlkit.vision.text.Text.TextBlock>>()
        val used = BooleanArray(signboardBlocks.size)

        for (i in signboardBlocks.indices) {
            if (used[i]) continue

            val group = mutableListOf<com.google.mlkit.vision.text.Text.TextBlock>()
            group.add(signboardBlocks[i])
            used[i] = true

            val currentBox = signboardBlocks[i].boundingBox ?: continue

            // Find nearby blocks (same signboard)
            for (j in (i + 1) until signboardBlocks.size) {
                if (used[j]) continue
                val otherBox = signboardBlocks[j].boundingBox ?: continue

                // Check if blocks are near each other (within 2x the average height)
                val avgHeight = (currentBox.height() + otherBox.height()) / 2f
                val verticalDistance = kotlin.math.abs(
                    (currentBox.centerY() - otherBox.centerY()).toFloat()
                )
                val horizontalDistance = kotlin.math.abs(
                    (currentBox.centerX() - otherBox.centerX()).toFloat()
                )

                // More flexible grouping - allows slightly more vertical spacing
                if (verticalDistance < avgHeight * 4 && horizontalDistance < imageWidth * 0.6f) {
                    group.add(signboardBlocks[j])
                    used[j] = true
                }
            }

            // Sort group by vertical position (top to bottom), then horizontal (left to right)
            group.sortWith(compareBy<com.google.mlkit.vision.text.Text.TextBlock> { block ->
                block.boundingBox?.top ?: 0
            }.thenBy { block ->
                block.boundingBox?.left ?: 0
            })

            groups.add(group)
        }

        // Build complete text for each signboard group
        return groups.map { group ->
            group.joinToString(" ") { it.text.trim() }
        }.filter { it.isNotEmpty() }
    }

    private fun isSignboardText(
        block: com.google.mlkit.vision.text.Text.TextBlock,
        text: String,
        imageHeight: Int,
        imageWidth: Int
    ): Boolean {
        // Combined English + Urdu keywords
        val signKeywords = listOf(
            // English
            "SHOP", "STORE", "CAFE", "RESTAURANT", "HOTEL", "BANK", "HOSPITAL",
            "SCHOOL", "PARK", "MUSEUM", "GALLERY", "THEATER", "CINEMA", "MALL",
            "BAKERY", "PHARMACY", "LIBRARY", "STATION", "AIRPORT", "SUPERMARKET",
            "EXIT", "ENTRANCE", "STREET", "ROAD", "AVE", "BLVD", "WAY", "DRIVE",
            "WELCOME", "OPEN", "CLOSED", "HOURS", "SALE", "OFFICE", "GRILL",
            "PIZZA", "BURGER", "COFFEE", "DONUT", "SALON", "SPA", "GYM",
            "WARNING", "CAUTION", "DANGER", "NOTICE", "INFORMATION",
            "HELP", "EMERGENCY", "PUSH", "PULL", "ENTER", "NO", "SMOKING",
            "PARKING", "ENTRY", "WASHROOM", "TOILET", "RESTROOM", "ELEVATOR",
            "STAIRS", "ESCALATOR", "TICKET", "BOOKING", "REGISTRATION", "RECEPTION",
            "LOUNGE", "BAR", "GRILL", "BAKERY", "DELI", "MARKET", "PHARMACY",
            // ✅ Urdu
            "دکان", "اسٹور", "کیفے", "ریستوران", "ہوٹل", "بینک", "ہسپتال",
            "اسکول", "پارک", "میوزیم", "گیلری", "تھیٹر", "سنیما", "مال",
            "بیکری", "فارمیسی", "لائبریری", "اسٹیشن", "ایئرپورٹ", "سپر مارکیٹ",
            "خروج", "داخلی", "گلی", "سڑک", "راہ", "ڈرائیو",
            "خوش آمدید", "کھلا", "بند", "گھنٹے", "فروخت", "دفتر", "گریل",
            "پیزا", "برگر", "کافی", "ڈونٹ", "سالون", "سپا", "جم",
            "انتباہ", "خبردار", "خطرہ", "نوٹس", "معلومات",
            "مدد", "ایمرجنسی", "دھکیلو", "کھینچو", "داخل",
            "ممنوع", "پارکنگ", "واش روم", "ٹکٹ", "بکنگ", "استقبالیہ"
        )

        fun distance(p1: android.graphics.Point, p2: android.graphics.Point): Float {
            val dx = (p1.x - p2.x).toFloat()
            val dy = (p1.y - p2.y).toFloat()
            return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        }

        val boundingBox = block.boundingBox ?: return false
        val cornerPoints = block.cornerPoints

        val area = boundingBox.width() * boundingBox.height()
        val imageArea = imageWidth * imageHeight
        val sizeRatio = area.toFloat() / imageArea

        // ✅ MORE FLEXIBLE: Lower size threshold (0.05% instead of 0.1%)
        if (sizeRatio < 0.0005f) return false

        // Check for rectangular shape (but more lenient)
        var rectangularScore = 0f
        if (cornerPoints != null && cornerPoints.size >= 4) {
            val p1 = cornerPoints[0]; val p2 = cornerPoints[1]
            val p3 = cornerPoints[2]; val p4 = cornerPoints[3]
            val topEdge = distance(p1, p2)
            val bottomEdge = distance(p3, p4)
            val leftEdge = distance(p1, p4)
            val rightEdge = distance(p2, p3)

            if (topEdge > 0f && bottomEdge > 0f && leftEdge > 0f && rightEdge > 0f) {
                rectangularScore = ((topEdge / bottomEdge).coerceAtMost(1f) +
                        (leftEdge / rightEdge).coerceAtMost(1f)) / 2f
            }
        }

        var perspectiveScore = 0f
        if (cornerPoints != null && cornerPoints.size >= 4) {
            val p1 = cornerPoints[0]; val p2 = cornerPoints[1]
            val angle = Math.toDegrees(Math.atan2((p2.y - p1.y).toDouble(), (p2.x - p1.x).toDouble())).toFloat()
            perspectiveScore = 1f - (angle.coerceIn(0f, 45f) / 45f)
        }

        // ✅ MORE LENIENT: Lower geometric threshold
        val isGeometricallySignboard = rectangularScore > 0.5f && perspectiveScore > 0.4f

        // Check keywords (case-insensitive)
        val uppercaseText = text.uppercase()
        val hasSignKeyword = signKeywords.any { keyword ->
            uppercaseText.contains(keyword.uppercase()) ||
                    uppercaseText.contains(keyword)
        }

        // ✅ REMOVED: Position restriction (signs can be anywhere - top, middle, bottom)
        // ✅ NEW: Check if text is readable length (not single random character)
        val hasReasonableLength = text.length >= 2

        // ✅ NEW: Check if text has readable characters (not just symbols)
        val hasReadableChars = text.any { it.isLetterOrDigit() }

        // ✅ FLEXIBLE CRITERIA: Pass if any combination is met
        val isLargeEnough = sizeRatio > 0.01f  // 1% of image

        return hasReasonableLength &&
                hasReadableChars &&
                (
                        // Must meet at least ONE of these:
                        isGeometricallySignboard ||
                                hasSignKeyword ||
                                isLargeEnough
                        )
    }

    private fun formatSignboardTexts(signboardGroups: List<String>): String {
        if (signboardGroups.isEmpty()) return ""

        // Each group is a complete signboard with all its lines preserved in order
        val formattedGroups = signboardGroups.map { text ->
            // Clean up extra spaces and normalize
            text.replace(Regex("\\s+"), " ").trim()
        }.filter { it.isNotEmpty() }

        if (formattedGroups.isEmpty()) return ""

        // Combine multiple signboards if they exist (max 2)
        val combinedText = formattedGroups.take(2).joinToString(". ")

        // Truncate if too long
        val truncatedText = if (combinedText.length > 200) {
            combinedText.take(197) + "..."
        } else {
            combinedText
        }

        return if (currentLanguage == "ur") {
            "نشان کی تحریر: $truncatedText"
        } else {
            "Signboard text: $truncatedText"
        }
    }

    private fun speakWithLanguage(text: String) {
        if (text.isBlank()) return

        val isTtsReady = if (currentLanguage == "ur") urduTtsReady else ttsReady

        if (!isTtsReady) {
            Log.w("TTS", "⚠️ TTS not ready, cannot speak: $text")
            // Try to re-initialize TTS
            if (currentLanguage == "ur") {
                initUrduTTS()
            } else {
                initTTS()
            }
            return
        }

        if (currentLanguage == "ur" && urduTtsReady) {
            urduTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "URDU")
            Log.d("TTS", "✅ Speaking Urdu: $text")
        } else if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ENG")
            Log.d("TTS", "✅ Speaking English: $text")
        } else {
            Log.e("TTS", "❌ No TTS engine available")
        }
    }

    private fun speak(text: String) {
        saveToFile(text)
        runOnUiThread {
            sceneStatus.text = if (currentLanguage == "ur") "بیان" else "Speaking"
            tvSceneText.text = text
            featurePanel.visibility = View.VISIBLE
            textPreviewScroll.post { textPreviewScroll.fullScroll(ScrollView.FOCUS_UP) }
            if (text.isNotBlank()) speakWithLanguage(text)
        }
    }

    private fun saveToFile(text: String) {
        thread {
            try {
                openFileOutput("scene_${System.currentTimeMillis()}.txt", MODE_PRIVATE)
                    .use { it.write(text.toByteArray(Charsets.UTF_8)) }
            } catch (e: Exception) { }
        }
    }
}