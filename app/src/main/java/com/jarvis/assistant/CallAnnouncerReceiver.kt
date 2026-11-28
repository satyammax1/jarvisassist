package com.jarvis.assistant

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.util.Locale

class CallAnnouncerReceiver : BroadcastReceiver() {

    private var tts: TextToSpeech? = null

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        if (state != TelephonyManager.EXTRA_STATE_RINGING) return

        @Suppress("DEPRECATION")
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val callerName = if (!incomingNumber.isNullOrBlank()) {
            lookupContactName(context, incomingNumber) ?: incomingNumber
        } else {
            "किसी अनजान नंबर"
        }

        val pendingResult = goAsync()
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale("hi", "IN"))
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale("en", "IN")
                }
                tts?.speak(
                    "सर, $callerName का कॉल आ रहा है।",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "CALL_ANNOUNCE"
                )
            }
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    tts?.shutdown()
                } catch (_: Exception) {}
                pendingResult.finish()
            }, 4500)
        }
    }

    private fun lookupContactName(context: Context, phoneNumber: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return null

        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
