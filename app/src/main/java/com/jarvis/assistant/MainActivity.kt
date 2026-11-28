package com.jarvis.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var statusText: TextView
    private lateinit var micButton: Button
    private lateinit var stopSpeakBtn: Button
    private lateinit var wakeWordBtn: Button
    private lateinit var pdfFolderBtn: Button
    private lateinit var floatingBubbleBtn: Button
    private lateinit var accessibilityBtn: Button
    private lateinit var commandEditText: EditText
    private lateinit var sendCmdBtn: Button
    private lateinit var tts: TextToSpeech

    private var speechRecognizer: SpeechRecognizer? = null
    private var isLiveMode = false
    private var isWakeWordMode = false
    private var isListeningNow = false
    private var useEnglishFallback = false
    private var activeQuizAnswer: String? = null
    private var focusTimer: CountDownTimer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val requiredPermissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.ANSWER_PHONE_CALLS)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val popupSpeechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val command = matches?.firstOrNull()?.lowercase(Locale.getDefault()) ?: ""
            if (command.isNotEmpty()) processCommand(command)
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val treeUri = result.data?.data ?: return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}

            getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("pdf_folder_uri", treeUri.toString())
                .apply()

            updatePdfFolderButtonState()
            val count = getPdfFilesFromLinkedFolder()?.size ?: 0
            speak("पीडीएफ फोल्डर सेव हो गया है, सर। इसमें $count पीडीएफ किताबें मिली हैं।")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        PDFBoxResourceLoader.init(applicationContext)

        statusText = findViewById(R.id.statusText)
        micButton = findViewById(R.id.micButton)
        stopSpeakBtn = findViewById(R.id.stopSpeakBtn)
        wakeWordBtn = findViewById(R.id.wakeWordBtn)
        pdfFolderBtn = findViewById(R.id.pdfFolderBtn)
        floatingBubbleBtn = findViewById(R.id.floatingBubbleBtn)
        accessibilityBtn = findViewById(R.id.accessibilityBtn)
        commandEditText = findViewById(R.id.commandEditText)
        sendCmdBtn = findViewById(R.id.sendCmdBtn)

        tts = TextToSpeech(this, this)
        setupSilentSpeechRecognizer()
        requestNeededPermissions()

        micButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 102)
                return@setOnClickListener
            }

            if (isLiveMode) {
                stopLiveMode("लाइव मोड बंद कर दिया गया है, सर।")
            } else {
                isWakeWordMode = false
                isLiveMode = true
                updateMicButtonUI()
                updateWakeWordButtonState()
                speak("लाइव मोड चालू है सर, बोलिए।")
            }
        }

        wakeWordBtn.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 102)
                return@setOnClickListener
            }
            if (isWakeWordMode) {
                isWakeWordMode = false
                stopLiveMode("हे जार्विस मोड बंद कर दिया गया है, सर।")
                updateWakeWordButtonState()
            } else {
                isLiveMode = false
                isWakeWordMode = true
                updateMicButtonUI()
                updateWakeWordButtonState()
                speak("हे जार्विस मोड चालू है, सर। जब काम हो तो Hey Jarvis बोलें।")
            }
        }

        stopSpeakBtn.setOnClickListener {
            activeQuizAnswer = null
            isWakeWordMode = false
            updateWakeWordButtonState()
            stopLiveMode("बोलना और सुनना बंद कर दिया गया है, सर।")
        }

        sendCmdBtn.setOnClickListener {
            val rawTyped = commandEditText.text.toString().trim()
            if (rawTyped.isNotEmpty()) {
                commandEditText.text.clear()
                if (rawTyped.lowercase(Locale.getDefault()).startsWith("set api key ")) {
                    val key = rawTyped.substringAfter("set api key ").trim()
                    getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
                        .edit().putString("gemini_api_key", key).apply()
                    speak("सर, एडवांस एआई की सेव हो गई है। अब आप कोई भी कठिन सवाल पूछ सकते हैं।")
                } else {
                    processCommand(rawTyped.lowercase(Locale.getDefault()))
                }
            }
        }

        pdfFolderBtn.setOnClickListener {
            isLiveMode = false
            isWakeWordMode = false
            updateMicButtonUI()
            updateWakeWordButtonState()
            speak("कृपया वह फोल्डर चुनें जहाँ आपकी पीडीएफ किताबें रखी हैं।")
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            folderPickerLauncher.launch(intent)
        }

        floatingBubbleBtn.setOnClickListener { toggleFloatingBubble() }

        accessibilityBtn.setOnClickListener {
            if (JarvisAccessibilityService.instance != null) {
                speak("सिस्टम कंट्रोल पहले से चालू है, सर।")
            } else {
                speak("एक्सेसिबिलिटी सेटिंग्स खोल रहा हूँ। कृपया जार्विस को चालू करें।")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("EXTRA_START_LIVE", false)) {
            isWakeWordMode = false
            isLiveMode = true
            updateWakeWordButtonState()
            updateMicButtonUI()
            speak("जी सर, बोलिए।")
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityButtonState()
        updatePdfFolderButtonState()
        updateFloatingBubbleButtonState()
        updateWakeWordButtonState()
        updateMicButtonUI()
    }

    override fun onPause() {
        super.onPause()
        isListeningNow = false
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
    }

    private fun toggleFloatingBubble() {
        if (!Settings.canDrawOverlays(this)) {
            speak("कृपया जार्विस को स्क्रीन के ऊपर दिखने की परमिशन दें, सर।")
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }

        val serviceIntent = Intent(this, FloatingJarvisService::class.java)
        if (FloatingJarvisService.isRunning) {
            stopService(serviceIntent)
            speak("फ्लोटिंग आइकन बंद कर दिया गया है, सर।")
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            speak("फ्लोटिंग जार्विस आइकन चालू हो गया है, सर।")
        }
        mainHandler.postDelayed({ updateFloatingBubbleButtonState() }, 300)
    }

    private fun updateWakeWordButtonState() {
        if (isWakeWordMode) {
            wakeWordBtn.text = "Hey Jarvis: ON"
            wakeWordBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#0D4735"))
            wakeWordBtn.setTextColor(Color.parseColor("#69F0AE"))
        } else {
            wakeWordBtn.text = "Hey Jarvis: OFF"
            wakeWordBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#10253F"))
            wakeWordBtn.setTextColor(Color.parseColor("#00F0FF"))
        }
    }

    private fun updateFloatingBubbleButtonState() {
        if (FloatingJarvisService.isRunning) {
            floatingBubbleBtn.text = "Bubble: ON"
            floatingBubbleBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#0D4735"))
            floatingBubbleBtn.setTextColor(Color.parseColor("#69F0AE"))
        } else {
            floatingBubbleBtn.text = "Bubble: OFF"
            floatingBubbleBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#10253F"))
            floatingBubbleBtn.setTextColor(Color.parseColor("#00F0FF"))
        }
    }

    private fun stopLiveMode(message: String) {
        isLiveMode = false
        isListeningNow = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        tts.stop()
        updateMicButtonUI()
        statusText.text = message
    }

    private fun updateMicButtonUI() {
        if (isLiveMode) {
            micButton.text = if (isListeningNow) "LISTENING..." else "LIVE: ON"
            micButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
            micButton.setTextColor(Color.parseColor("#050B14"))
        } else {
            micButton.text = "SPEAK"
            micButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00B8D4"))
            micButton.setTextColor(Color.parseColor("#050B14"))
        }
    }

    private fun setupSilentSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusText.text = "Silent mic unavailable; popup mode ready."
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListeningNow = true
                updateMicButtonUI()
                statusText.text = if (isWakeWordMode && !isLiveMode) {
                    "स्टैंडबाय: 'Hey Jarvis' बोलें..."
                } else {
                    "सुन रहा हूँ, सर... बोलिए!"
                }
            }

            override fun onBeginningOfSpeech() {
                statusText.text = "आवाज़ मिल रही है..."
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    statusText.text = "सुन रहा हूँ: \"$partial\""
                }
            }

            override fun onResults(results: Bundle?) {
                isListeningNow = false
                updateMicButtonUI()
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val heard = matches?.firstOrNull()?.lowercase(Locale.getDefault()) ?: ""

                if (isWakeWordMode && !isLiveMode) {
                    if (heard.contains("jarvis") || heard.contains("जार्विस")) {
                        try {
                            ToneGenerator(AudioManager.STREAM_MUSIC, 80)
                                .startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                        } catch (_: Exception) {}
                        val afterWake = heard
                            .replace("hey jarvis", "").replace("jarvis", "")
                            .replace("हे जार्विस", "").replace("जार्विस", "").trim()
                        isLiveMode = true
                        updateMicButtonUI()
                        if (afterWake.length > 2) processCommand(afterWake) else speak("जी सर, बोलिए।")
                    } else {
                        scheduleNextListen(400)
                    }
                    return
                }

                if (heard.isNotEmpty()) processCommand(heard)
                else if (isLiveMode) scheduleNextListen(400)
            }

            override fun onError(error: Int) {
                isListeningNow = false
                updateMicButtonUI()
                if (!isLiveMode && !isWakeWordMode) return

                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        statusText.text = "कुछ सुनाई नहीं दिया, फिर से सुन रहा हूँ..."
                        scheduleNextListen(450)
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> scheduleNextListen(900)
                    12, 13 -> {
                        if (!useEnglishFallback) {
                            useEnglishFallback = true
                            statusText.text = "Switching voice listener to English (India)..."
                            scheduleNextListen(400)
                        } else {
                            isLiveMode = false
                            isWakeWordMode = false
                            updateMicButtonUI()
                            updateWakeWordButtonState()
                            speak("सर, कृपया Google App me Offline Speech Pack डाउनलोड करें।")
                        }
                    }
                    SpeechRecognizer.ERROR_CLIENT,
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        isLiveMode = false
                        isWakeWordMode = false
                        updateMicButtonUI()
                        updateWakeWordButtonState()
                        launchVoicePopup()
                    }
                    else -> {
                        statusText.text = "Mic error ($error), retrying..."
                        scheduleNextListen(800)
                    }
                }
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isListeningNow = false
                updateMicButtonUI()
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun scheduleNextListen(delayMs: Long) {
        if (!isLiveMode && !isWakeWordMode) return
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if ((isLiveMode || isWakeWordMode) && !tts.isSpeaking) {
                startSilentListening()
            }
        }, delayMs)
    }

    private fun launchVoicePopup() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (useEnglishFallback) "en-IN" else "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "जार्विस को बोलें...")
        }
        try {
            popupSpeechLauncher.launch(intent)
        } catch (_: Exception) {
            speak("वॉइस सर्विस उपलब्ध नहीं है, कृपया नीचे टाइप करें।")
        }
    }

    private fun startSilentListening() {
        if (speechRecognizer == null) {
            launchVoicePopup()
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (useEnglishFallback) "en-IN" else "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            launchVoicePopup()
        }
    }

    private fun updatePdfFolderButtonState() {
        val savedUri = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
            .getString("pdf_folder_uri", null)
        if (savedUri != null) {
            pdfFolderBtn.text = "PDF Folder: LINKED (Tap to Change)"
            pdfFolderBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#0D4735"))
            pdfFolderBtn.setTextColor(Color.parseColor("#69F0AE"))
        } else {
            pdfFolderBtn.text = "Select PDF Folder"
            pdfFolderBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#10253F"))
            pdfFolderBtn.setTextColor(Color.parseColor("#00F0FF"))
        }
    }

    private fun updateAccessibilityButtonState() {
        if (JarvisAccessibilityService.instance != null) {
            accessibilityBtn.text = "System Control: ACTIVE"
            accessibilityBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#0D4735"))
            accessibilityBtn.setTextColor(Color.parseColor("#69F0AE"))
        } else {
            accessibilityBtn.text = "Enable System Control (Accessibility)"
            accessibilityBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#10253F"))
            accessibilityBtn.setTextColor(Color.parseColor("#00F0FF"))
        }
    }

    private fun requestNeededPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val hiResult = tts.setLanguage(Locale("hi", "IN"))
            if (hiResult == TextToSpeech.LANG_MISSING_DATA || hiResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.language = Locale("en", "IN")
            }
            tts.setSpeechRate(0.95f)

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        if (isLiveMode || isWakeWordMode) scheduleNextListen(350)
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    runOnUiThread {
                        if (isLiveMode || isWakeWordMode) scheduleNextListen(500)
                    }
                }
            })

            if (intent.getBooleanExtra("EXTRA_START_LIVE", false)) {
                isLiveMode = true
                updateMicButtonUI()
                speak("जी सर, बोलिए।")
            } else {
                speak("नमस्ते सर, मैं जार्विस हूँ। लाइव बात करने के लिए SPEAK दबाएं।")
            }
        }
    }

    private fun speak(message: String) {
        isListeningNow = false
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        updateMicButtonUI()
        statusText.text = message
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_ID")
    }

    private fun processCommand(cmd: String) {
        statusText.text = "आपने कहा: \"$cmd\""

        val pendingAns = activeQuizAnswer
        if (pendingAns != null && !cmd.contains("stop") && !cmd.contains("quiz")) {
            activeQuizAnswer = null
            if (cmd.contains(pendingAns.lowercase(Locale.getDefault()))) {
                speak("शाबाश सर! बिल्कुल सही जवाब: $pendingAns। अगला सवाल चाहिए तो quiz बोलें।")
            } else {
                speak("सर, आपका जवाब थोड़ा अलग था। सही जवाब '$pendingAns' है। अगला सवाल चाहिए तो quiz बोलें।")
            }
            return
        }

        when {
            cmd.contains("stop") || cmd.contains("chup") || cmd.contains("ruk") ||
            cmd.contains("चुप") || cmd.contains("रुक") || cmd.contains("स्टॉप") -> {
                activeQuizAnswer = null
                isWakeWordMode = false
                updateWakeWordButtonState()
                stopLiveMode("बोलना और लाइव मोड बंद कर दिया गया है, सर।")
            }

            cmd.contains("pause") || cmd.contains("play video") || cmd.contains("lecture roko") ||
            cmd.contains("lecture chalao") || cmd.contains("वीडियो रोको") || cmd.contains("वीडियो चलाओ") -> {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                speak("मीडिया प्ले-पॉज़ कर दिया है, सर।")
            }
            cmd.contains("next track") || cmd.contains("next lecture") || cmd.contains("next song") ||
            cmd.contains("अगला वीडियो") || cmd.contains("अगला गाना") -> {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                speak("अगला लेक्चर चला रहा हूँ, सर।")
            }
            cmd.contains("previous track") || cmd.contains("previous lecture") || cmd.contains("पिछला") -> {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                speak("पिछला लेक्चर चला रहा हूँ, सर।")
            }

            cmd.startsWith("set exam ") || cmd.contains("exam set") -> setExamCountdown(cmd)
            cmd.contains("exam countdown") || cmd.contains("kitne din") || cmd.contains("aaj ka plan") ||
            cmd.contains("study plan") || cmd.contains("आज का प्लान") || cmd.contains("परीक्षा") -> readStudyPlan()

            cmd.contains("wake word") || cmd.contains("हे जार्विस चालू") -> {
                isLiveMode = false
                isWakeWordMode = true
                updateMicButtonUI()
                updateWakeWordButtonState()
                speak("हे जार्विस स्टैंडबाय मोड चालू है, सर।")
            }

            cmd.contains("read screen") || cmd.contains("screen padho") || cmd.contains("स्क्रीन पढ़ो") -> {
                val svc = JarvisAccessibilityService.instance
                if (svc == null) {
                    speak("स्क्रीन पढ़ने के लिए कृपया पहले एक्सेसिबिलिटी में जार्विस को चालू करें।")
                } else {
                    val screenText = svc.readCurrentScreenText()
                    if (screenText.isEmpty()) speak("सर, अभी स्क्रीन पर कोई पढ़ने लायक टेक्स्ट नहीं मिला।")
                    else speak("स्क्रीन पर लिखा है: ${screenText.take(650)}")
                }
            }

            cmd.startsWith("note save") || cmd.startsWith("save note") || cmd.startsWith("note likho") ||
            cmd.contains("नोट लिखो") || cmd.contains("नोट सेव") -> {
                val noteContent = cmd
                    .replace("note save karo", "").replace("save note", "")
                    .replace("note likho", "").replace("नोट सेव करो", "")
                    .replace("नोट लिखो", "").replace(":", "").trim()
                saveVoiceNote(noteContent)
            }
            cmd.contains("clear notes") || cmd.contains("delete notes") || cmd.contains("नोट्स मिटाओ") -> clearVoiceNotes()
            cmd.contains("read notes") || cmd.contains("my notes") || cmd.contains("mere notes") ||
            cmd.contains("नोट्स सुनाओ") || cmd.contains("नोट्स पढ़ो") -> readSavedNotes()

            cmd.contains("focus mode off") || cmd.contains("फोकस मोड बंद") -> toggleFocusMode(false)
            cmd.contains("focus mode") || cmd.contains("study mode") || cmd.contains("फोकस मोड") -> toggleFocusMode(true)

            tryCalculateOrConvert(cmd) != null -> speak(tryCalculateOrConvert(cmd)!!)

            cmd.contains("quiz") || cmd.contains("test lo") || cmd.contains("सवाल पूछो") -> startPdfQuiz(cmd)

            cmd.contains("floating") || cmd.contains("bubble") -> toggleFloatingBubble()

            cmd.startsWith("ask ") || cmd.startsWith("search ") || cmd.contains("ढूंढो") -> handlePdfTopicSearch(cmd)
            cmd.startsWith("read ") || cmd.contains("पढ़ो") || cmd.contains("सुनाओ") -> handlePdfReadAloud(cmd)
            cmd.contains("list pdf") || cmd.contains("pdf list") || cmd.contains("book list") ||
            cmd.contains("कौन सी किताब") -> listSavedPdfs()
            cmd.contains("pdf") || cmd.contains("book") || cmd.contains("बुक") ||
            cmd.contains("पीडीएफ") || cmd.contains("किताब") -> openPdfByName(extractBookQuery(cmd))

            cmd.contains("time") || cmd.contains("समय") || cmd.contains("बजे") -> {
                val now = SimpleDateFormat("hh:mm a", Locale("en", "IN")).format(Date())
                speak("सर, अभी $now हुए हैं।")
            }
            cmd.contains("date") || cmd.contains("तारीख") || cmd.contains("दिन") -> {
                val today = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale("en", "IN")).format(Date())
                speak("सर, आज $today है।")
            }

            cmd.contains("timer") || cmd.contains("टाइमर") -> {
                val mins = Regex("\\d+").find(cmd)?.value?.toIntOrNull() ?: 10
                setStudyTimer(mins)
            }
            cmd.contains("alarm") || cmd.contains("अलार्म") -> {
                val hour = Regex("\\d+").find(cmd)?.value?.toIntOrNull() ?: 6
                setQuickAlarm(hour)
            }

            cmd.contains("torch on") || cmd.contains("flashlight on") || cmd.contains("टॉर्च चालू") -> toggleTorch(true)
            cmd.contains("torch off") || cmd.contains("flashlight off") || cmd.contains("टॉर्च बंद") -> toggleTorch(false)
            cmd.contains("volume up") || cmd.contains("आवाज बढ़ाओ") -> adjustVolume(AudioManager.ADJUST_RAISE)
            cmd.contains("volume down") || cmd.contains("आवाज कम") -> adjustVolume(AudioManager.ADJUST_LOWER)
            cmd.contains("mute") || cmd.contains("साइलेंट") -> adjustVolume(AudioManager.ADJUST_MUTE)

            cmd.contains("wifi") || cmd.contains("wi-fi") || cmd.contains("वाईफाई") -> {
                speak("वाई-फाई सेटिंग्स खोल रहा हूँ, सर।")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startActivity(Intent(Settings.Panel.ACTION_WIFI))
                } else {
                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                }
            }
            cmd.contains("bluetooth") || cmd.contains("ब्लूटूथ") -> {
                speak("ब्लूटूथ सेटिंग्स खोल रहा हूँ, सर।")
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }

            cmd.contains("receive call") || cmd.contains("answer call") || cmd.contains("pick up") ||
            cmd.contains("call uthao") || cmd.contains("कॉल उठाओ") || cmd.contains("फोन उठाओ") -> {
                isLiveMode = false
                updateMicButtonUI()
                answerIncomingCall()
            }
            cmd.contains("end call") || cmd.contains("cut call") || cmd.contains("call kato") ||
            cmd.contains("कॉल काटो") || cmd.contains("फोन काटो") -> endActiveCall()
            cmd.startsWith("call ") -> {
                isLiveMode = false
                updateMicButtonUI()
                callContactByName(cmd.removePrefix("call ").trim())
            }
            cmd.contains("कॉल करो") || cmd.contains("फोन करो") -> {
                isLiveMode = false
                updateMicButtonUI()
                val name = cmd.replace("को कॉल करो", "").replace("कॉल करो", "")
                    .replace("को फोन करो", "").replace("फोन करो", "").trim()
                callContactByName(name)
            }

            cmd.startsWith("open ") -> openAppOrPdfFallback(cmd.removePrefix("open ").trim())
            cmd.contains("खोलो") || cmd.contains("kholo") -> {
                val target = cmd.replace("खोलो", "").replace("kholo", "").replace("karo", "").trim()
                openAppOrPdfFallback(target)
            }

            cmd.contains("battery") || cmd.contains("बैटरी") -> {
                val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                speak("सर, आपके टैबलेट की बैटरी $level प्रतिशत है।")
            }

            cmd.contains("go home") || cmd.contains("home screen") || cmd.contains("होम") -> {
                if (JarvisAccessibilityService.instance != null) {
                    JarvisAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    speak("होम स्क्रीन पर जा रहा हूँ, सर।")
                } else {
                    speak("कृपया पहले एक्सेसिबिलिटी में जार्विस को चालू करें।")
                }
            }
            cmd.contains("go back") || cmd.contains("back") || cmd.contains("पीछे") -> {
                if (JarvisAccessibilityService.instance != null) {
                    JarvisAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    if (isLiveMode) scheduleNextListen(500)
                } else {
                    speak("कृपया पहले एक्सेसिबिलिटी में जार्विस को चालू करें।")
                }
            }
            cmd.contains("notifications") || cmd.contains("नोटिफिकेशन") -> {
                if (JarvisAccessibilityService.instance != null) {
                    JarvisAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                    speak("नोटिफिकेशन खोल रहा हूँ, सर।")
                } else {
                    speak("कृपया पहले एक्सेसिबिलिटी में जार्विस को चालू करें।")
                }
            }

            else -> askAdvancedAiBrain(cmd)
        }
    }

    private fun askAdvancedAiBrain(question: String) {
        val prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
        val geminiKey = prefs.getString("gemini_api_key", "") ?: ""

        statusText.text = "एडवांस एआई सोच रहा है..."
        Thread {
            if (geminiKey.isNotEmpty()) {
                try {
                    val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$geminiKey"
                    val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json")
                        connectTimeout = 8000
                        readTimeout = 12000
                        doOutput = true
                    }
                    val body = JSONObject().apply {
                        put("contents", JSONArray().put(JSONObject().apply {
                            put("parts", JSONArray().put(JSONObject().apply {
                                put("text", "You are J.A.R.V.I.S., a smart study assistant. Answer concisely in 2 to 4 sentences in simple Hindi/Hinglish: $question")
                            }))
                        }))
                    }
                    OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                    if (conn.responseCode == 200) {
                        val respText = conn.inputStream.bufferedReader().readText()
                        val json = JSONObject(respText)
                        val answer = json.getJSONArray("candidates")
                            .getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                            .getJSONObject(0)
                            .getString("text")
                            .replace("*", "")
                            .trim()
                        runOnUiThread { speak(answer) }
                        return@Thread
                    }
                } catch (_: Exception) {}
            }

            try {
                val localUrl = URL("http://127.0.0.1:8080/v1/chat/completions")
                val conn = (localUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 1500
                    readTimeout = 15000
                    doOutput = true
                }
                val body = JSONObject().apply {
                    put("messages", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Answer briefly in simple Hindi/English: $question")
                    }))
                    put("max_tokens", 180)
                }
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                if (conn.responseCode == 200) {
                    val respText = conn.inputStream.bufferedReader().readText()
                    val answer = JSONObject(respText)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                    runOnUiThread { speak(answer) }
                    return@Thread
                }
            } catch (_: Exception) {}

            runOnUiThread {
                speak("सर, एडवांस एआई जवाब के लिए नीचे बॉक्स में 'set api key आपकी_की' लिखकर सेव करें, या ऑफलाइन किताबों के लिए 'ask किताब टॉपिक' बोलें।")
            }
        }.start()
    }

    private fun setExamCountdown(cmd: String) {
        val dateMatch = Regex("(\\d{1,2}[-/]\\d{1,2}[-/]\\d{4})").find(cmd)
        if (dateMatch == null) {
            speak("सर, तारीख इस तरह लिखें या बोलें: set exam 20-12-2026 theory of machines")
            return
        }
        val dateStr = dateMatch.value.replace("/", "-")
        val subject = cmd.replace("set exam", "").replace(dateMatch.value, "").trim()
            .ifEmpty { "Exam" }

        getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE).edit()
            .putString("exam_date", dateStr)
            .putString("exam_subject", subject)
            .apply()
        speak("सर, $subject की परीक्षा तारीख $dateStr सेव हो गई है।")
    }

    private fun readStudyPlan() {
        val prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
        val dateStr = prefs.getString("exam_date", null)
        val subject = prefs.getString("exam_subject", "आपकी परीक्षा") ?: "आपकी परीक्षा"
        val notesArr = JSONArray(prefs.getString("voice_notes", "[]"))

        val countdownPart = if (dateStr != null) {
            try {
                val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.US)
                val target = sdf.parse(dateStr)
                val diffMs = (target?.time ?: 0L) - System.currentTimeMillis()
                val days = TimeUnit.MILLISECONDS.toDays(diffMs).coerceAtLeast(0)
                "$subject में $days दिन बाकी हैं। "
            } catch (_: Exception) {
                ""
            }
        } else {
            "अभी कोई एग्जाम डेट सेव नहीं है। "
        }

        val notesPart = if (notesArr.length() > 0) {
            "आज के लिए आपके ${notesArr.length()} नोट्स/टास्क सेव हैं।"
        } else {
            "आज फोकस मोड ऑन करके कम से कम 2 चैप्टर रिवाइज करें सर।"
        }
        speak("सर, $countdownPart$notesPart")
    }

    private fun sendMediaKey(keyCode: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun saveVoiceNote(text: String) {
        if (text.isBlank()) {
            speak("सर, नोट में क्या लिखना है वह भी बोलें।")
            return
        }
        val prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("voice_notes", "[]"))
        arr.put(text)
        prefs.edit().putString("voice_notes", arr.toString()).apply()
        speak("नोट सेव हो गया है, सर: $text")
    }

    private fun readSavedNotes() {
        val prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("voice_notes", "[]"))
        if (arr.length() == 0) {
            speak("सर, अभी कोई नोट सेव नहीं है।")
            return
        }
        val sb = StringBuilder("सर, आपके ${arr.length()} नोट्स हैं: ")
        for (i in 0 until arr.length()) {
            sb.append("${i + 1}: ${arr.getString(i)}. ")
        }
        speak(sb.toString())
    }

    private fun clearVoiceNotes() {
        getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
            .edit().putString("voice_notes", "[]").apply()
        speak("सर, सभी सेव किए हुए नोट्स मिटा दिए गए हैं।")
    }

    private fun toggleFocusMode(enable: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            speak("फोकस मोड के लिए कृपया जार्विस को Do Not Disturb की परमिशन दें, सर।")
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return
        }
        focusTimer?.cancel()
        if (enable) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            speak("फोकस मोड चालू है, सर। 25 मिनट का स्टडी सेशन शुरू हो गया है, कोई नोटिफिकेशन डिस्टर्ब नहीं करेगा।")
            focusTimer = object : CountDownTimer(25 * 60 * 1000L, 60000L) {
                override fun onTick(millisUntilFinished: Long) {}
                override fun onFinish() {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                    speak("सर, आपके 25 मिनट का स्टडी सेशन पूरा हो गया है! अब 5 मिनट का ब्रेक ले लें।")
                }
            }.start()
        } else {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            speak("फोकस मोड बंद कर दिया गया है, सर।")
        }
    }

    private fun tryCalculateOrConvert(cmd: String): String? {
        val nums = Regex("\\d+(?:\\.\\d+)?").findAll(cmd).map { it.value.toDouble() }.toList()
        if (nums.isEmpty()) return null

        if (cmd.contains("percent") || cmd.contains("%") || cmd.contains("प्रतिशत")) {
            if (nums.size >= 2) {
                val pct = (nums[0] * nums[1]) / 100.0
                return "सर, इसका जवाब ${formatNum(pct)} होगा।"
            }
        }

        val v = nums[0]
        if (cmd.contains("celsius") && cmd.contains("fahrenheit")) {
            return if (cmd.indexOf("celsius") < cmd.indexOf("fahrenheit")) {
                "सर, ${formatNum(v)} डिग्री सेल्सियस बराबर ${formatNum((v * 9.0 / 5.0) + 32.0)} फारेनहाइट होता है।"
            } else {
                "सर, ${formatNum(v)} फारेनहाइट बराबर ${formatNum((v - 32.0) * 5.0 / 9.0)} डिग्री सेल्सियस होता है।"
            }
        }
        if ((cmd.contains("km") || cmd.contains("kilometer")) && cmd.contains("mile")) {
            return "सर, ${formatNum(v)} किलोमीटर बराबर ${formatNum(v * 0.621371)} मील होता है।"
        }
        if (cmd.contains("kg") && (cmd.contains("pound") || cmd.contains("lbs"))) {
            return "सर, ${formatNum(v)} किलोग्राम बराबर ${formatNum(v * 2.20462)} पाउंड होता है।"
        }
        if (cmd.contains("inch") && cmd.contains("cm")) {
            return if (cmd.indexOf("inch") < cmd.indexOf("cm")) {
                "सर, ${formatNum(v)} इंच बराबर ${formatNum(v * 2.54)} सेंटीमीटर होता है।"
            } else {
                "सर, ${formatNum(v)} सेंटीमीटर बराबर ${formatNum(v / 2.54)} इंच होता है।"
            }
        }

        if (nums.size >= 2) {
            val a = nums[0]
            val b = nums[1]
            val res: Double? = when {
                cmd.contains("+") || cmd.contains("plus") || cmd.contains("जोड़") -> a + b
                cmd.contains("-") || cmd.contains("minus") || cmd.contains("घटा") -> a - b
                cmd.contains("*") || cmd.contains("x") || cmd.contains("into") || cmd.contains("multiply") || cmd.contains("गुणा") -> a * b
                cmd.contains("/") || cmd.contains("divide") || cmd.contains("भाग") -> if (b != 0.0) a / b else null
                else -> null
            }
            if (res != null) return "सर, उत्तर ${formatNum(res)} है।"
        }
        return null
    }

    private fun formatNum(value: Double): String {
        return if (value % 1.0 == 0.0) value.toLong().toString()
        else String.format(Locale.US, "%.2f", value)
    }

    private fun startPdfQuiz(cmd: String) {
        val pdfs = getPdfFilesFromLinkedFolder()
        if (pdfs == null || pdfs.isEmpty()) {
            speak("सर, क्विज के लिए पहले Select PDF Folder से अपनी किताबों वाला फोल्डर जोड़ें।")
            return
        }

        val cleanQuery = extractBookQuery(cmd.replace("quiz", "").replace("test", "").replace("सवाल", ""))
        val matchedBook = findBestMatchingPdf(pdfs, cleanQuery) ?: pdfs.first()
        speak("${matchedBook.first} से आपके लिए सवाल तैयार कर रहा हूँ, सर।")

        Thread {
            try {
                contentResolver.openInputStream(matchedBook.second)?.use { stream ->
                    PDDocument.load(stream).use { doc ->
                        val randomPage = Random.nextInt(1, doc.numberOfPages + 1)
                        val stripper = PDFTextStripper().apply {
                            startPage = randomPage
                            endPage = randomPage
                        }
                        val raw = stripper.getText(doc).replace("\\s+".toRegex(), " ").trim()
                        val sentences = raw.split(Regex("(?<=[.?!])\\s+"))
                            .filter { it.length in 45..180 }

                        val chosen = sentences.randomOrNull()
                        runOnUiThread {
                            if (chosen == null) {
                                speak("सर, पेज $randomPage पर कोई अच्छा वाक्य नहीं मिला। एक बार फिर quiz बोलें।")
                            } else {
                                val words = chosen.split(" ")
                                val targetWord = words.filter { it.length >= 6 && it.all { ch -> ch.isLetter() } }
                                    .maxByOrNull { it.length }
                                if (targetWord != null) {
                                    activeQuizAnswer = targetWord
                                    val blanked = chosen.replaceFirst(targetWord, "खाली स्थान")
                                    speak("सवाल पेज $randomPage से: $blanked — बताइए खाली स्थान में क्या आएगा?")
                                } else {
                                    speak("पेज $randomPage का मुख्य बिंदु सुनिए: $chosen")
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                runOnUiThread { speak("क्विज बनाने में दिक्कत आई, सर।") }
            }
        }.start()
    }

    private fun extractBookQuery(cmd: String): String {
        val junk = setOf(
            "open", "pdf", "book", "ki", "ka", "ke", "ko", "karo", "kholo", "wala", "wali",
            "पीडीएफ", "बुक", "किताब", "की", "का", "के", "को", "खोलो", "करो"
        )
        return cmd.replace(Regex("[^a-z0-9\\u0900-\\u097F\\s]"), " ")
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() && it !in junk }
            .joinToString(" ")
    }

    private fun getPdfFilesFromLinkedFolder(): List<Pair<String, Uri>>? {
        val savedUriStr = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
            .getString("pdf_folder_uri", null) ?: return null

        val treeUri = Uri.parse(savedUriStr)
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val result = mutableListOf<Pair<String, Uri>>()

        fun scanDir(parentDocId: String, depth: Int) {
            if (depth > 2) return
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            try {
                contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0)
                        val name = cursor.getString(1) ?: ""
                        val mime = cursor.getString(2) ?: ""
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            scanDir(id, depth + 1)
                        } else if (mime == "application/pdf" || name.lowercase(Locale.getDefault()).endsWith(".pdf")) {
                            val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                            result.add(Pair(name, fileUri))
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        scanDir(rootDocId, 0)
        return result
    }

    private fun findBestMatchingPdf(pdfs: List<Pair<String, Uri>>, query: String): Pair<String, Uri>? {
        val cleanQuery = query.lowercase(Locale.getDefault()).trim()
        if (cleanQuery.isEmpty()) return null
        val queryWords = cleanQuery.split("\\s+".toRegex()).filter { it.length >= 2 }

        var bestMatch: Pair<String, Uri>? = null
        var bestScore = 0

        for (item in pdfs) {
            val normalizedName = item.first.lowercase(Locale.getDefault())
                .replace("_", " ").replace("-", " ").replace(".pdf", "")
            if (normalizedName.contains(cleanQuery)) return item

            var score = 0
            for (w in queryWords) {
                if (normalizedName.contains(w)) score++
            }
            if (score > bestScore) {
                bestScore = score
                bestMatch = item
            }
        }
        return if (bestScore > 0) bestMatch else null
    }

    private fun listSavedPdfs() {
        val pdfs = getPdfFilesFromLinkedFolder()
        if (pdfs == null) {
            speak("सर, कृपया पहले नीचे Select PDF Folder बटन दबाकर अपना फोल्डर चुनें।")
            return
        }
        if (pdfs.isEmpty()) {
            speak("सर, इस फोल्डर में कोई पीडीएफ फाइल नहीं मिली।")
            return
        }
        val names = pdfs.take(5).joinToString(", ") { it.first.removeSuffix(".pdf") }
        speak("सर, इस फोल्डर में ${pdfs.size} पीडीएफ फाइलें हैं, जैसे: $names")
    }

    private fun openPdfByName(query: String) {
        val pdfs = getPdfFilesFromLinkedFolder()
        if (pdfs == null) {
            speak("सर, कृपया पहले Select PDF Folder बटन दबाकर अपना डाउनलोड फोल्डर चुनें।")
            return
        }
        if (query.isEmpty()) {
            listSavedPdfs()
            return
        }
        val match = findBestMatchingPdf(pdfs, query)
        if (match != null) {
            speak("${match.first} खोल रहा हूँ, सर।")
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(match.second, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(viewIntent)
            } catch (e: ActivityNotFoundException) {
                speak("सर, टैबलेट में पीडीएफ खोलने वाला कोई ऐप नहीं मिला।")
            }
        } else {
            speak("माफ कीजिए सर, फोल्डर में $query नाम की कोई पीडीएफ नहीं मिली।")
        }
    }

    private fun handlePdfReadAloud(cmd: String) {
        val pdfs = getPdfFilesFromLinkedFolder()
        if (pdfs == null || pdfs.isEmpty()) {
            speak("सर, पहले Select PDF Folder से अपनी किताबों वाला फोल्डर जोड़ें।")
            return
        }

        val pageNum = Regex("page\\s*(\\d+)|पेज\\s*(\\d+)").find(cmd)
            ?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: 1

        val matchedBook = findBestMatchingPdf(pdfs, extractBookQuery(cmd)) ?: pdfs.first()
        speak("${matchedBook.first} का पेज $pageNum पढ़ रहा हूँ, सर।")

        Thread {
            try {
                contentResolver.openInputStream(matchedBook.second)?.use { stream ->
                    PDDocument.load(stream).use { doc ->
                        val safePage = pageNum.coerceIn(1, doc.numberOfPages)
                        val stripper = PDFTextStripper().apply {
                            startPage = safePage
                            endPage = safePage
                        }
                        val text = stripper.getText(doc).replace("\\s+".toRegex(), " ").trim()
                        runOnUiThread {
                            if (text.isEmpty()) {
                                speak("सर, पेज $safePage में कोई पढ़ने लायक टेक्स्ट नहीं मिला।")
                            } else {
                                speak("पेज $safePage: ${text.take(600)}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { speak("माफ कीजिए सर, यह पीडीएफ पढ़ने में दिक्कत आई।") }
            }
        }.start()
    }

    private fun handlePdfTopicSearch(cmd: String) {
        val pdfs = getPdfFilesFromLinkedFolder()
        if (pdfs == null || pdfs.isEmpty()) {
            speak("सर, पहले Select PDF Folder से अपनी किताबों वाला फोल्डर जोड़ें।")
            return
        }

        val matchedBook = findBestMatchingPdf(pdfs, extractBookQuery(cmd)) ?: pdfs.first()
        val stopWords = setOf("ask", "search", "about", "in", "from", "pdf", "book", "me", "mein", "batao", "ढूंढो", "बताओ")
        val bookWords = matchedBook.first.removeSuffix(".pdf").lowercase(Locale.getDefault()).split(" ", "_", "-").toSet()
        val keywords = cmd.split("\\s+".toRegex())
            .filter { it.length > 2 && it !in stopWords && it !in bookWords }

        if (keywords.isEmpty()) {
            speak("सर, कृपया टॉपिक का नाम भी बोलें, जैसे: ask science about photosynthesis.")
            return
        }

        speak("${matchedBook.first} में ${keywords.joinToString(" ")} ढूंढ रहा हूँ, सर।")

        Thread {
            try {
                contentResolver.openInputStream(matchedBook.second)?.use { stream ->
                    PDDocument.load(stream).use { doc ->
                        var bestPage = -1
                        var bestSnippet = ""
                        var bestScore = 0
                        val stripper = PDFTextStripper()

                        for (p in 1..doc.numberOfPages) {
                            stripper.startPage = p
                            stripper.endPage = p
                            val pageText = stripper.getText(doc)
                            val lower = pageText.lowercase(Locale.getDefault())
                            var score = 0
                            keywords.forEach { kw -> if (lower.contains(kw)) score += 1 }
                            if (score > bestScore) {
                                bestScore = score
                                bestPage = p
                                val firstIdx = keywords.mapNotNull { kw ->
                                    val idx = lower.indexOf(kw)
                                    if (idx >= 0) idx else null
                                }.minOrNull() ?: 0
                                val start = (firstIdx - 60).coerceAtLeast(0)
                                val end = (start + 450).coerceAtMost(pageText.length)
                                bestSnippet = pageText.substring(start, end).replace("\\s+".toRegex(), " ").trim()
                            }
                        }

                        runOnUiThread {
                            if (bestPage != -1) {
                                speak("सर, ${matchedBook.first} के पेज $bestPage पर मिला: $bestSnippet")
                            } else {
                                speak("सर, ${matchedBook.first} में यह टॉपिक नहीं मिला।")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { speak("पीडीएफ स्कैन करने में समस्या आई, सर।") }
            }
        }.start()
    }

    private fun setStudyTimer(minutes: Int) {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
            putExtra(AlarmClock.EXTRA_MESSAGE, "J.A.R.V.I.S. Study Timer")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        try {
            startActivity(intent)
            speak("सर, $minutes मिनट का स्टडी टाइमर लगा दिया है।")
        } catch (e: Exception) {
            speak("सर, क्लॉक ऐप में टाइमर सेट नहीं हो पाया।")
        }
    }

    private fun setQuickAlarm(hour: Int) {
        val safeHour = hour.coerceIn(0, 23)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, safeHour)
            putExtra(AlarmClock.EXTRA_MINUTES, 0)
            putExtra(AlarmClock.EXTRA_MESSAGE, "J.A.R.V.I.S. Alarm")
        }
        try {
            startActivity(intent)
            speak("सर, $safeHour बजे का अलार्म सेट कर रहा हूँ।")
        } catch (e: Exception) {
            speak("सर, अलार्म सेट नहीं हो पाया।")
        }
    }

    private fun toggleTorch(turnOn: Boolean) {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId != null) {
                cm.setTorchMode(cameraId, turnOn)
                speak(if (turnOn) "टॉर्च चालू कर दी है, सर।" else "टॉर्च बंद कर दी है, सर।")
            } else {
                speak("सर, इस टैबलेट में फ्लैशलाइट उपलब्ध नहीं है।")
            }
        } catch (e: Exception) {
            speak("टॉर्च कंट्रोल करने में दिक्कत आई, सर।")
        }
    }

    private fun adjustVolume(direction: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        speak("वॉल्यूम एडजस्ट कर दिया है, सर।")
    }

    @SuppressLint("MissingPermission")
    private fun answerIncomingCall() {
        val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
            tm.acceptRingingCall()
            speak("कॉल रिसीव कर रहा हूँ, सर।")
        } else {
            speak("कृपया कॉल उठाने की परमिशन दें, सर।")
        }
    }

    @SuppressLint("MissingPermission")
    private fun endActiveCall() {
        val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
            tm.endCall()
            speak("कॉल काट दी गई है, सर।")
        }
    }

    private fun callContactByName(targetName: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED
        ) {
            speak("कृपया पहले कॉन्टैक्ट्स और फोन की परमिशन दें।")
            requestNeededPermissions()
            return
        }

        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$targetName%"),
            null
        )

        var phoneNumber: String? = null
        var matchedName: String? = null
        cursor?.use {
            if (it.moveToFirst()) {
                matchedName = it.getString(0)
                phoneNumber = it.getString(1)
            }
        }

        if (phoneNumber != null) {
            speak("$matchedName को कॉल कर रहा हूँ, सर।")
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber"))
            startActivity(callIntent)
        } else {
            speak("माफ कीजिए सर, कॉन्टैक्ट्स में $targetName नाम नहीं मिला।")
        }
    }

    private fun openAppOrPdfFallback(targetName: String) {
        val pm = packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (pkg in packages) {
            val label = pm.getApplicationLabel(pkg).toString().lowercase(Locale.getDefault())
            if (label.contains(targetName)) {
                val launchIntent = pm.getLaunchIntentForPackage(pkg.packageName)
                if (launchIntent != null) {
                    speak("$label खोल रहा हूँ, सर।")
                    startActivity(launchIntent)
                    return
                }
            }
        }
        val pdfs = getPdfFilesFromLinkedFolder()
        if (pdfs != null) {
            val pdfMatch = findBestMatchingPdf(pdfs, extractBookQuery(targetName))
            if (pdfMatch != null) {
                openPdfByName(extractBookQuery(targetName))
                return
            }
        }
        speak("सर, टैबलेट में $targetName नाम का ऐप या किताब नहीं मिली।")
    }

    override fun onDestroy() {
        super.onDestroy()
        focusTimer?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        tts.shutdown()
    }
}
