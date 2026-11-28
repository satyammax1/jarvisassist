package com.jarvis.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var statusText: TextView
    private lateinit var micButton: Button
    private lateinit var stopSpeakBtn: Button
    private lateinit var pdfFolderBtn: Button
    private lateinit var accessibilityBtn: Button
    private lateinit var commandEditText: EditText
    private lateinit var sendCmdBtn: Button
    private lateinit var tts: TextToSpeech

    private val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CONTACTS
    )

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val command = matches?.firstOrNull()?.lowercase(Locale.getDefault()) ?: ""
            if (command.isNotEmpty()) {
                processCommand(command)
            } else {
                statusText.text = "आवाज़ समझ नहीं आई, फिर से बोलें।"
            }
        } else {
            statusText.text = "माइक बंद हुआ। SPEAK दबाएं या नीचे टाइप करें।"
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
        pdfFolderBtn = findViewById(R.id.pdfFolderBtn)
        accessibilityBtn = findViewById(R.id.accessibilityBtn)
        commandEditText = findViewById(R.id.commandEditText)
        sendCmdBtn = findViewById(R.id.sendCmdBtn)

        tts = TextToSpeech(this, this)
        requestNeededPermissions()

        micButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    102
                )
            } else {
                startListening()
            }
        }

        stopSpeakBtn.setOnClickListener {
            tts.stop()
            statusText.text = "बोलना बंद कर दिया गया है, सर।"
        }

        sendCmdBtn.setOnClickListener {
            val typed = commandEditText.text.toString().trim().lowercase(Locale.getDefault())
            if (typed.isNotEmpty()) {
                commandEditText.text.clear()
                processCommand(typed)
            }
        }

        pdfFolderBtn.setOnClickListener {
            speak("कृपया वह फोल्डर चुनें जहाँ आपकी पीडीएफ किताबें रखी हैं।")
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            folderPickerLauncher.launch(intent)
        }

        accessibilityBtn.setOnClickListener {
            if (JarvisAccessibilityService.instance != null) {
                speak("सिस्टम कंट्रोल पहले से चालू है, सर।")
            } else {
                speak("एक्सेसिबिलिटी सेटिंग्स खोल रहा हूँ। कृपया जार्विस को चालू करें।")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityButtonState()
        updatePdfFolderButtonState()
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
            speak("नमस्ते सर, मैं जार्विस हूँ। बताइए क्या सेवा करूँ?")
        }
    }

    private fun speak(message: String) {
        statusText.text = message
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_ID")
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "जार्विस को बोलें...")
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            speak("वॉइस सर्विस उपलब्ध नहीं है, कृपया नीचे टाइप करें।")
        }
    }

    private fun processCommand(cmd: String) {
        statusText.text = "आपने कहा: \"$cmd\""

        when {
            // Stop TTS immediately
            cmd.contains("stop") || cmd.contains("chup") || cmd.contains("ruk") ||
            cmd.contains("चुप") || cmd.contains("रुक") || cmd.contains("स्टॉप") -> {
                tts.stop()
                statusText.text = "बोलना बंद कर दिया गया है, सर।"
            }

            // PDF Topic Search
            cmd.startsWith("ask ") || cmd.startsWith("search ") || cmd.contains("ढूंढो") -> {
                handlePdfTopicSearch(cmd)
            }

            // PDF Read Aloud
            cmd.startsWith("read ") || cmd.contains("पढ़ो") || cmd.contains("सुनाओ") -> {
                handlePdfReadAloud(cmd)
            }

            // PDF / Book List or Open
            cmd.contains("list pdf") || cmd.contains("pdf list") || cmd.contains("book list") ||
            cmd.contains("कौन सी किताब") -> {
                listSavedPdfs()
            }
            cmd.contains("pdf") || cmd.contains("book") || cmd.contains("बुक") ||
            cmd.contains("पीडीएफ") || cmd.contains("किताब") -> {
                openPdfByName(extractBookQuery(cmd))
            }

            // Time & Date
            cmd.contains("time") || cmd.contains("समय") || cmd.contains("बजे") -> {
                val now = SimpleDateFormat("hh:mm a", Locale("en", "IN")).format(Date())
                speak("सर, अभी $now हुए हैं।")
            }
            cmd.contains("date") || cmd.contains("तारीख") || cmd.contains("दिन") -> {
                val today = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale("en", "IN")).format(Date())
                speak("सर, आज $today है।")
            }

            // Timer & Alarm
            cmd.contains("timer") || cmd.contains("टाइमर") -> {
                val mins = Regex("\\d+").find(cmd)?.value?.toIntOrNull() ?: 10
                setStudyTimer(mins)
            }
            cmd.contains("alarm") || cmd.contains("अलार्म") -> {
                val hour = Regex("\\d+").find(cmd)?.value?.toIntOrNull() ?: 6
                setQuickAlarm(hour)
            }

            // Torch / Flashlight
            cmd.contains("torch on") || cmd.contains("flashlight on") || cmd.contains("टॉर्च चालू") -> toggleTorch(true)
            cmd.contains("torch off") || cmd.contains("flashlight off") || cmd.contains("टॉर्च बंद") -> toggleTorch(false)

            // Volume Controls
            cmd.contains("volume up") || cmd.contains("आवाज बढ़ाओ") -> adjustVolume(AudioManager.ADJUST_RAISE)
            cmd.contains("volume down") || cmd.contains("आवाज कम") -> adjustVolume(AudioManager.ADJUST_LOWER)
            cmd.contains("mute") || cmd.contains("साइलेंट") -> adjustVolume(AudioManager.ADJUST_MUTE)

            // Wi-Fi & Bluetooth Quick Settings
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

            // Calls
            cmd.contains("receive call") || cmd.contains("answer call") || cmd.contains("pick up") ||
            cmd.contains("call uthao") || cmd.contains("कॉल उठाओ") || cmd.contains("फोन उठाओ") -> answerIncomingCall()
            cmd.contains("end call") || cmd.contains("cut call") || cmd.contains("call kato") ||
            cmd.contains("कॉल काटो") || cmd.contains("फोन काटो") -> endActiveCall()
            cmd.startsWith("call ") -> callContactByName(cmd.removePrefix("call ").trim())
            cmd.contains("कॉल करो") || cmd.contains("फोन करो") -> {
                val name = cmd.replace("को कॉल करो", "").replace("कॉल करो", "")
                    .replace("को फोन करो", "").replace("फोन करो", "").trim()
                callContactByName(name)
            }

            // Open Installed App (with automatic PDF fallback if no app matches)
            cmd.startsWith("open ") -> openAppOrPdfFallback(cmd.removePrefix("open ").trim())
            cmd.contains("खोलो") || cmd.contains("kholo") -> {
                val target = cmd.replace("खोलो", "").replace("kholo", "").replace("karo", "").trim()
                openAppOrPdfFallback(target)
            }

            // Battery Status
            cmd.contains("battery") || cmd.contains("बैटरी") -> {
                val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                speak("सर, आपके टैबलेट की बैटरी $level प्रतिशत है।")
            }

            // Accessibility Navigation
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
            else -> {
                speak("सर, आपने कहा $cmd। आप 'theory of machines book open karo', 'list pdf', या 'battery status' बोल सकते हैं।")
            }
        }
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
                runOnUiThread {
                    speak("माफ कीजिए सर, यह पीडीएफ पढ़ने में दिक्कत आई।")
                }
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
                            keywords.forEach { kw ->
                                if (lower.contains(kw)) score += 1
                            }
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
                runOnUiThread {
                    speak("पीडीएफ स्कैन करने में समस्या आई, सर।")
                }
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
        // If no app matched, check if a PDF book matches that name
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
        tts.shutdown()
    }
}
