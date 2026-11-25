package com.jarvis.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.ContactsContract
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
                statusText.text = "Didn't catch that. Tap SPEAK again."
            }
        } else {
            statusText.text = "Voice cancelled. Tap SPEAK or type below."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        micButton = findViewById(R.id.micButton)
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

        accessibilityBtn.setOnClickListener {
            if (JarvisAccessibilityService.instance != null) {
                speak("System control is already active, sir.")
            } else {
                speak("Opening accessibility settings. Please turn on J.A.R.V.I.S., sir.")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityButtonState()
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
            tts.language = Locale("en", "IN")
            speak("J.A.R.V.I.S. online and ready, sir.")
        }
    }

    private fun speak(message: String) {
        statusText.text = message
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_ID")
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to J.A.R.V.I.S.")
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            speak("Voice service not available on this tablet. Please type your command below, sir.")
        }
    }

    private fun processCommand(cmd: String) {
        statusText.text = "You said: \"$cmd\""

        when {
            cmd.contains("receive call") || cmd.contains("answer call") || cmd.contains("pick up") || cmd.contains("call uthao") -> {
                answerIncomingCall()
            }
            cmd.contains("end call") || cmd.contains("cut call") || cmd.contains("call kato") -> {
                endActiveCall()
            }
            cmd.startsWith("call ") -> {
                val name = cmd.removePrefix("call ").trim()
                callContactByName(name)
            }
            cmd.startsWith("open ") -> {
                val appName = cmd.removePrefix("open ").trim()
                openInstalledApp(appName)
            }
            cmd.contains("battery") -> {
                val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                speak("Sir, battery is at $level percent.")
            }
            cmd.contains("go home") || cmd.contains("home screen") -> {
                if (JarvisAccessibilityService.instance != null) {
                    JarvisAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    speak("Going to home screen, sir.")
                } else {
                    speak("Please enable J.A.R.V.I.S. in Accessibility settings first, sir.")
                }
            }
            cmd.contains("go back") || cmd.contains("back") -> {
                if (JarvisAccessibilityService.instance != null) {
                    JarvisAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                } else {
                    speak("Please enable J.A.R.V.I.S. in Accessibility settings first, sir.")
                }
            }
            cmd.contains("notifications") -> {
                if (JarvisAccessibilityService.instance != null) {
                    JarvisAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                    speak("Opening notifications, sir.")
                } else {
                    speak("Please enable J.A.R.V.I.S. in Accessibility settings first, sir.")
                }
            }
            else -> {
                speak("Sir, you said $cmd. Try saying Open YouTube, Call Mom, Answer call, or Battery status.")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun answerIncomingCall() {
        val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
            tm.acceptRingingCall()
            speak("Answering the incoming call, sir.")
        } else {
            speak("Sir, please grant Answer Phone Calls permission.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun endActiveCall() {
        val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
            tm.endCall()
            speak("Call disconnected, sir.")
        }
    }

    private fun callContactByName(targetName: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED
        ) {
            speak("Sir, please allow Contacts and Phone permissions first.")
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
            speak("Calling $matchedName, sir.")
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber"))
            startActivity(callIntent)
        } else {
            speak("Sorry sir, I could not find $targetName in your contacts.")
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
                    speak("Opening $label, sir.")
                    startActivity(launchIntent)
                    return
                }
            }
        }
        speak("Sir, I could not find an app named $appName on this tablet.")
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
    }
}
