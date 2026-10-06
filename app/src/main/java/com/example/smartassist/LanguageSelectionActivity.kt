package com.example.smartassist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class LanguageSelectionActivity : AppCompatActivity() {

    private lateinit var tvWelcome: TextView
    private lateinit var tvInstruction: TextView
    private lateinit var userManager: UserManager
    private lateinit var database: AppDatabase
    private lateinit var repository: AppRepository
    private var englishTts: TextToSpeech? = null
    private var urduTts: TextToSpeech? = null
    private var englishTtsReady = false
    private var urduTtsReady = false
    private var gestureDetector: GestureDetector? = null
    private var currentStep = 0
    private var isProcessing = false
    private var hasSpokenWelcome = false
    private var selectedLanguage = ""  // Track selected language
    private var speechTimeoutHandler: Handler? = null
    private var speechTimeoutRunnable: Runnable? = null

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val PREFS_NAME = "app_prefs"
        private const val KEY_FIRST_LAUNCH = "is_first_launch"
        private const val SPEECH_TIMEOUT_MS = 3000L  // 3 seconds timeout for speech
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize database FIRST
        database = AppDatabase.getDatabase(this)
        repository = AppRepository(database.userSettingsDao(), database.sceneHistoryDao())

        userManager = UserManager(this)
        userManager.initRepository(database)

        // CHECK IF FIRST LAUNCH
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean(KEY_FIRST_LAUNCH, true)

        if (!isFirstLaunch) {
            goToMainScreen()
            return
        }

        setContentView(R.layout.activity_language_selection)

        if (userManager.isLanguageSelected() && hasAllPermissions()) {
            markFirstLaunchComplete()
            goToMainScreen()
            return
        }

        if (userManager.isLanguageSelected() && !hasAllPermissions()) {
            currentStep = 1
        }

        initViews()
        initTTS()
        setupGestureDetector()
        setupSpeechTimeoutHandler()

        // Show text immediately so user sees something
        tvInstruction.text = "👆 Double tap = English\n👇 Single tap = Urdu"
    }

    private fun setupSpeechTimeoutHandler() {
        speechTimeoutHandler = Handler(Looper.getMainLooper())
    }

    private fun cancelSpeechTimeout() {
        speechTimeoutRunnable?.let {
            speechTimeoutHandler?.removeCallbacks(it)
        }
        speechTimeoutRunnable = null
    }

    private fun startSpeechTimeout(callback: () -> Unit) {
        cancelSpeechTimeout()
        speechTimeoutRunnable = Runnable {
            android.util.Log.d("TTS", "Speech timeout - proceeding without callback")
            callback()
        }
        speechTimeoutHandler?.postDelayed(speechTimeoutRunnable!!, SPEECH_TIMEOUT_MS)
    }

    private fun markFirstLaunchComplete() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()
    }

    private fun initViews() {
        tvWelcome = findViewById(R.id.tvWelcome)
        tvInstruction = findViewById(R.id.tvInstruction)
        tvWelcome.text = "Welcome"
    }

    private fun initTTS() {
        // Initialize English TTS
        englishTts = TextToSpeech(this) { status ->
            englishTtsReady = (status == TextToSpeech.SUCCESS)
            if (englishTtsReady) {
                englishTts?.setLanguage(Locale.US)
                englishTts?.setSpeechRate(0.95f)
                englishTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        android.util.Log.d("TTS", "onStart: $utteranceId")
                    }

                    override fun onDone(utteranceId: String?) {
                        android.util.Log.d("TTS", "onDone: $utteranceId")
                        // Handle completion for both English and Urdu via English TTS listener
                        handleUtteranceComplete(utteranceId)
                    }

                    override fun onError(p0: String?) {
                        TODO("Not yet implemented")
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        android.util.Log.e("TTS", "onError: $utteranceId, errorCode: $errorCode")
                        handleUtteranceComplete(utteranceId)
                    }
                })
                android.util.Log.d("TTS", "English TTS initialized")

                // Speak welcome message only on first launch
                if (!hasSpokenWelcome && currentStep == 0 && !userManager.isLanguageSelected()) {
                    hasSpokenWelcome = true
                    speakWelcomeMessage()
                }
            } else {
                android.util.Log.e("TTS", "English TTS initialization failed")
                tvInstruction.text = "Double tap = English\nSingle tap = Urdu\n(TTS not available)"
            }
        }

        // Initialize Urdu TTS
        try {
            urduTts = TextToSpeech(this) { status ->
                urduTtsReady = (status == TextToSpeech.SUCCESS)
                if (urduTtsReady) {
                    val result = urduTts?.setLanguage(Locale("ur", "PK"))
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        urduTtsReady = false
                        android.util.Log.w("TTS", "Urdu TTS not supported")
                    } else {
                        urduTts?.setSpeechRate(0.95f)
                        android.util.Log.d("TTS", "Urdu TTS initialized")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TTS", "Urdu TTS failed: ${e.message}")
            urduTtsReady = false
        }
    }

    private fun handleUtteranceComplete(utteranceId: String?) {
        android.util.Log.d("TTS", "handleUtteranceComplete: $utteranceId, pending: $isProcessing, step: $currentStep")

        when (utteranceId) {
            "LANG_SELECTION_EN", "LANG_SELECTION_UR" -> {
                cancelSpeechTimeout()
                if (isProcessing && currentStep == 0) {
                    Handler(Looper.getMainLooper()).post {
                        android.util.Log.d("TTS", "Moving to permission step")
                        currentStep = 1
                        isProcessing = false
                        askForPermissions()
                    }
                }
            }
            "PERMISSION_ASK" -> {
                // Just logging, no action needed
                android.util.Log.d("TTS", "Permission ask completed")
            }
        }
    }

    private fun speakWelcomeMessage() {
        val message = "Welcome to Smart Assist. Double tap to select English. Single tap to select Urdu."
        if (englishTtsReady) {
            englishTts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "WELCOME")
            android.util.Log.d("TTS", "Welcome message spoken")
        } else {
            tvInstruction.text = "Double tap = English\nSingle tap = Urdu"
        }
    }

    private fun speakInEnglish(message: String, utteranceId: String = "ENG") {
        if (englishTtsReady) {
            englishTts?.stop()
            englishTts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            android.util.Log.d("TTS", "Speaking English: $message, id: $utteranceId")
        } else {
            android.util.Log.w("TTS", "English TTS not ready, cannot speak: $message")
            // If TTS not ready, proceed after short delay
            if (utteranceId == "LANG_SELECTION_EN" || utteranceId == "LANG_SELECTION_UR") {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isProcessing && currentStep == 0) {
                        currentStep = 1
                        isProcessing = false
                        askForPermissions()
                    }
                }, 1000)
            }
        }
    }

    private fun speakInUrdu(message: String, utteranceId: String = "URDU") {
        if (urduTtsReady) {
            urduTts?.stop()
            // Use a HashMap for parameters to ensure utterance ID works
            val params = HashMap<String, String>()
            params[TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID] = utteranceId
            urduTts?.speak(message, TextToSpeech.QUEUE_FLUSH, params)
            android.util.Log.d("TTS", "Speaking Urdu: $message, id: $utteranceId")

            // Also set a fallback timeout in case Urdu TTS doesn't trigger callback
            if (utteranceId == "LANG_SELECTION_UR") {
                startSpeechTimeout {
                    android.util.Log.d("TTS", "Urdu speech timeout - proceeding manually")
                    if (isProcessing && currentStep == 0) {
                        Handler(Looper.getMainLooper()).post {
                            currentStep = 1
                            isProcessing = false
                            askForPermissions()
                        }
                    }
                }
            }
        } else {
            // Fallback to English if Urdu not available
            android.util.Log.w("TTS", "Urdu TTS not ready, falling back to English")
            speakInEnglish(message, utteranceId)
        }
    }

    private fun askForPermissions() {
        android.util.Log.d("LanguageSelect", "askForPermissions called")
        val instruction = "Camera permission is needed. Double tap anywhere to grant permission."
        tvInstruction.text = "📷 Double tap anywhere to grant camera permission"

        val savedLang = userManager.getLanguage()
        if (savedLang == "ur" && urduTtsReady) {
            val urduInstruction = "کیمرے کی اجازت درکار ہے۔ اسکرین پر ڈبل ٹیپ کریں"
            speakInUrdu(urduInstruction, "PERMISSION_ASK")
        } else {
            speakInEnglish(instruction, "PERMISSION_ASK")
        }
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isProcessing) {
                    android.util.Log.d("Gesture", "Processing, ignoring single tap")
                    return false
                }
                if (currentStep == 0) {
                    android.util.Log.d("Gesture", "Single tap detected - selecting Urdu")
                    selectLanguage("ur")
                    return true
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isProcessing) {
                    android.util.Log.d("Gesture", "Processing, ignoring double tap")
                    return false
                }
                if (currentStep == 0) {
                    android.util.Log.d("Gesture", "Double tap detected - selecting English")
                    selectLanguage("en")
                    return true
                } else if (currentStep == 1) {
                    android.util.Log.d("Gesture", "Double tap detected - requesting permission")
                    requestCameraPermission()
                    return true
                }
                return false
            }

            override fun onDown(e: MotionEvent): Boolean {
                return true
            }
        })

        findViewById<View>(android.R.id.content).setOnTouchListener { _, event ->
            gestureDetector?.onTouchEvent(event)
            true
        }
    }

    private fun selectLanguage(language: String) {
        if (isProcessing) {
            android.util.Log.d("LanguageSelect", "Already processing, ignoring selection")
            return
        }

        isProcessing = true
        selectedLanguage = language

        // SAVE LANGUAGE IMMEDIATELY
        userManager.saveLanguage(language)

        android.util.Log.d("LanguageSelect", "Language selected: $language")

        // Speak the confirmation based on selected language
        if (language == "en") {
            tvInstruction.text = "English selected ✓"
            speakInEnglish("English selected.", "LANG_SELECTION_EN")
        } else {
            tvInstruction.text = "اردو منتخب کر لی گئی ✓"
            speakInUrdu("اردو منتخب کر لی گئی", "LANG_SELECTION_UR")
        }

        // Fallback timeout in case onDone is never called
        startSpeechTimeout {
            android.util.Log.d("LanguageSelect", "Speech timeout - proceeding to permissions")
            if (isProcessing && currentStep == 0) {
                Handler(Looper.getMainLooper()).post {
                    currentStep = 1
                    isProcessing = false
                    askForPermissions()
                }
            }
        }
    }

    private fun hasAllPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        if (isProcessing) {
            android.util.Log.d("Permission", "Already processing, ignoring request")
            return
        }

        isProcessing = true
        android.util.Log.d("Permission", "requestCameraPermission called")

        if (!hasAllPermissions()) {
            val savedLang = userManager.getLanguage()

            if (savedLang == "ur" && urduTtsReady) {
                speakInUrdu("اجازت کا ڈائیلاگ ظاہر ہوگا۔ اجازت دینے کے لیے اسکرین پر ڈبل ٹیپ کریں۔", "PERMISSION_REQUEST")
            } else {
                speakInEnglish("Permission dialog will appear. Double tap anywhere to grant permission.", "PERMISSION_REQUEST")
            }

            Handler(Looper.getMainLooper()).postDelayed({
                android.util.Log.d("Permission", "Showing permission dialog")
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA),
                    PERMISSION_REQUEST_CODE
                )
            }, 1500)
        } else {
            android.util.Log.d("Permission", "Permission already granted")
            isProcessing = false
            markFirstLaunchComplete()
            goToMainScreen()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            val savedLang = userManager.getLanguage()

            if (allGranted) {
                android.util.Log.d("Permission", "Permission granted")
                if (savedLang == "ur" && urduTtsReady) {
                    speakInUrdu("شکریہ۔ اجازت مل گئی۔ سمارٹ اسسٹ شروع ہو رہا ہے۔", "PERMISSION_GRANTED")
                } else {
                    speakInEnglish("Thank you. Permission granted. Starting Smart Assist.", "PERMISSION_GRANTED")
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    markFirstLaunchComplete()
                    goToMainScreen()
                }, 1500)
            } else {
                android.util.Log.d("Permission", "Permission denied")
                isProcessing = false
                if (savedLang == "ur" && urduTtsReady) {
                    speakInUrdu("اجازت نہیں دی گئی۔ کیمرے کی اجازت دینے کے لیے دوبارہ ڈبل ٹیپ کریں۔", "PERMISSION_DENIED")
                } else {
                    speakInEnglish("Permission not granted. Please double tap again to grant camera permission.", "PERMISSION_DENIED")
                }
                tvInstruction.text = "⚠️ Double tap to grant camera permission"
            }
        }
    }

    private fun goToMainScreen() {
        val savedLanguage = userManager.getLanguage()
        android.util.Log.d("LanguageSelect", "Proceeding to main screen with language: $savedLanguage")

        Toast.makeText(this, "Language: ${if (savedLanguage == "ur") "Urdu" else "English"}", Toast.LENGTH_SHORT).show()

        val intent = Intent(this, SceneActivity::class.java)
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelSpeechTimeout()
        englishTts?.stop()
        englishTts?.shutdown()
        urduTts?.stop()
        urduTts?.shutdown()
    }
}