package dev.otpilot.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import dev.otpilot.OtpilotApp
import dev.otpilot.worker.OtpUploadWorker

/**
 * Event-driven SMS listener.
 *
 * This class does NOT run in the background. Android wakes it for ~200ms
 * when an SMS arrives, then kills it. Zero battery drain when idle.
 *
 * Same mechanism as Truecaller, Google Messages, and bank OTP auto-read.
 */
class OtpReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "OtpReceiver"
        private val DEFAULT_KEYWORDS = listOf(
            "otp", "code", "pin", "verification", "verify",
            "delivery", "parcel", "order"
        )

        private val APP_PATTERNS = mapOf(
            "amazon" to Regex("amazon|amzn", RegexOption.IGNORE_CASE),
            "flipkart" to Regex("flipkart|fkrt", RegexOption.IGNORE_CASE),
            "swiggy" to Regex("swiggy", RegexOption.IGNORE_CASE),
            "zomato" to Regex("zomato", RegexOption.IGNORE_CASE),
            "blinkit" to Regex("blinkit", RegexOption.IGNORE_CASE),
            "zepto" to Regex("zepto", RegexOption.IGNORE_CASE),
            "myntra" to Regex("myntra", RegexOption.IGNORE_CASE),
            "dunzo" to Regex("dunzo", RegexOption.IGNORE_CASE),
            "bigbasket" to Regex("bigbasket", RegexOption.IGNORE_CASE),
            "meesho" to Regex("meesho", RegexOption.IGNORE_CASE),
            "uber" to Regex("\\buber\\b", RegexOption.IGNORE_CASE),
            "ola" to Regex("\\bola\\b", RegexOption.IGNORE_CASE),
            "rapido" to Regex("rapido", RegexOption.IGNORE_CASE),
            "phonepe" to Regex("phonepe", RegexOption.IGNORE_CASE),
            "paytm" to Regex("paytm", RegexOption.IGNORE_CASE),
            "gpay" to Regex("gpay|googlepay", RegexOption.IGNORE_CASE),
            "hdfc" to Regex("hdfc", RegexOption.IGNORE_CASE),
            "icici" to Regex("icici", RegexOption.IGNORE_CASE),
            "sbi" to Regex("\\bsbi\\b", RegexOption.IGNORE_CASE),
            "axis" to Regex("axis", RegexOption.IGNORE_CASE),
            "kotak" to Regex("kotak", RegexOption.IGNORE_CASE),
        )

        private val OTP_REGEX = listOf(
            // Numeric code near OTP keywords
            Regex("\\b(\\d{4,8})\\b.*(?:otp|code|pin|verification|verify)", RegexOption.IGNORE_CASE),
            Regex("(?:otp|code|pin|verification|verify).*\\b(\\d{4,8})\\b", RegexOption.IGNORE_CASE),
            // Alphanumeric code near OTP keywords (e.g. E9394, AB1234, 2F8G)
            Regex("\\b([A-Z0-9]{4,8})\\b.*(?:otp|code|pin|verification|verify)", RegexOption.IGNORE_CASE),
            Regex("(?:otp|code|pin|verification|verify).*\\b([A-Z0-9]{4,8})\\b", RegexOption.IGNORE_CASE),
            // Standalone 6-digit fallback
            Regex("\\b(\\d{6})\\b", RegexOption.IGNORE_CASE),
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive called, action=${intent.action}")
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // Check if app is configured
        val prefs = context.getSharedPreferences(OtpilotApp.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains("relay") || !prefs.contains("memberToken")) {
            Log.d(TAG, "App not configured, skipping")
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) {
            Log.d(TAG, "No messages in intent")
            return
        }

        val from = messages[0].originatingAddress ?: "unknown"
        val body = messages.joinToString("") { it.messageBody ?: "" }
        val bodyLower = body.lowercase()
        Log.d(TAG, "SMS from=$from body=$body")

        // Load filter keywords from prefs (user-configurable)
        val keywordsStr = prefs.getString("filterKeywords", null)
        val keywords = if (keywordsStr != null) {
            keywordsStr.split("\n").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        } else {
            DEFAULT_KEYWORDS
        }
        Log.d(TAG, "Keywords: $keywords")

        // Load blocked senders
        val blockedStr = prefs.getString("blockedSenders", "")
        val blocked = blockedStr?.split("\n")?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() } ?: emptyList()

        // Check blocked senders
        if (blocked.any { from.lowercase().contains(it) }) {
            Log.d(TAG, "Sender blocked")
            return
        }

        // Check keywords
        val isOtp = keywords.any { bodyLower.contains(it) }
        if (!isOtp) {
            Log.d(TAG, "No keyword match, skipping")
            return
        }

        val otpCode = extractOtp(body)
        val app = detectApp(from, body)
        Log.d(TAG, "OTP detected: code=$otpCode app=$app, enqueueing upload")

        OtpUploadWorker.enqueue(
            context = context,
            from = from,
            body = body,
            otp = otpCode,
            app = app
        )
    }

    private fun extractOtp(text: String): String? {
        for (regex in OTP_REGEX) {
            val match = regex.find(text)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1]
            }
        }
        return null
    }

    private fun detectApp(from: String, body: String): String {
        val combined = "$from $body"
        for ((name, pattern) in APP_PATTERNS) {
            if (pattern.containsMatchIn(combined)) return name
        }
        return "unknown"
    }
}
