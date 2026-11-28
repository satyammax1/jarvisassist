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
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
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
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var statusText: TextView
    private lateinit var micButton: Button
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
            speak("पीडीएफ फोल्डर सेव हो गया है, सर। अब आप किसी भी किताब का नाम बोलकर उसे खोल सकते हैं।")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        micButton = findViewById(R.id.micButton)
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
            cmd.contains("list pdf") || cmd.contains("pdf list") || cmd.contains("कौन सी किताब") -> {
                listSavedPdfs()
            }
            cmd.contains("pdf") || cmd.contains("पीडीएफ") || cmd.contains("किताब") -> {
                val cleanName = cmd
                    .replace("open", "")
                    .replace("pdf", "")
                    .replace("पीडीएफ", "")
                    .replace("किताब", "")
                    .replace("खोलो", "")
                    .replace("करो", "")
                    .replace("को", "")
                    .trim()
                openPdfByName(cleanName)
            }
            cmd.contains("receive call") || cmd.contains("answer call") || cmd.contains("pick up") ||
            cmd.contains("call uthao") || cmd.contains("कॉल उठाओ") || cmd.contains("फोन उठाओ") -> {
                answerIncomingCall()
            }
            cmd.contains("end call") || cmd.contains("cut call") || cmd.contains("call kato") ||
            cmd.contains("कॉल काटो") || cmd.contains("फोन काटो") -> {
                endActiveCall()
            }
            cmd.startsWith("call ") -> {
                val name = cmd.removePrefix("call ").trim()
                callContactByName(name)
            }
            cmd.contains("कॉल करो") || cmd.contains("फोन करो") -> {
                val name = cmd.replace("को कॉल करो", "").replace("कॉल करो", "")
                    .replace("को फोन करो", "").replace("फोन करो", "").trim()
                callContactByName(name)
            }
            cmd.startsWith("open ") -> {
                val appName = cmd.removePrefix("open ").trim()
                openInstalledApp(appName)
            }
            cmd.contains("खोलो") -> {
                val appName = cmd.replace("खोलो", "").trim()
                openInstalledApp(appName)
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
                speak("सर, आपने कहा $cmd। आप यूट्यूब खोलो, पीडीएफ खोलो, बैटरी बताओ, या कॉल करो बोल सकते हैं।")
            }
        }
    }

    private fun getPdfFilesFromLinkedFolder(): List<Pair<String, Uri>>? {
        val savedUriStr = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
            .getString("pdf_folder_uri", null) ?: return null

        val treeUri = Uri.parse(savedUriStr)
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val result = mutableListOf<Pair<String, Uri>>()

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val name = cursor.getString(1) ?: ""
                val mime = cursor.getString(2) ?: ""
                if (mime == "application/pdf" || name.lowercase(Locale.getDefault()).endsWith(".pdf")) {
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    result.add(Pair(name, fileUri))
                }
            }
        }
        return result
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

        val match = pdfs.firstOrNull {
            it.first.lowercase(Locale.getDefault()).contains(query.lowercase(Locale.getDefault()))
        }

        if (match != null) {
            speak("${match.first} खोल रहा हूँ, सर।")
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(match.second, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(viewIntent)
            } catch (e: ActivityNotFoundException) {
                speak("सर, टैबलेट में पीडीएफ खोलने वाला कोई ऐप नहीं मिला। कृपया एक पीडीएफ व्यूअर इंस्टॉल करें।")
            }
        } else {
            speak("माफ कीजिए सर, फोल्डर में $query नाम की कोई पीडीएफ नहीं मिली।")
        }
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

    private fun openInstalledApp(appName: String) {
        val pm = packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (pkg in packages) {
            val label = pm.getApplicationLabel(pkg).toString().lowercase(Locale.getDefault())
            if (label.contains(appName)) {
                val launchIntent = pm.getLaunchIntentForPackage(pkg.packageName)
                if (launchIntent != null) {
                    speak("$label खोल रहा हूँ, सर।")
                    startActivity(launchIntent)
                    return
                }
            }
        }
        speak("सर, टैबलेट में $appName नाम का ऐप नहीं मिला।")
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
    }
}
