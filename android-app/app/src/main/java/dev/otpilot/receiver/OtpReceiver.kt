package dev.otpilot.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import dev.otpilot.crypto.CryptoUtil
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
        // Keywords that indicate an OTP message (case-insensitive)
        private val OTP_KEYWORDS = listOf(
            "otp", "code", "pin", "verification", "verify",
            "delivery", "parcel", "order"
        )

        // Keywords to EXCLUDE (banking OTPs — configurable)
        private val EXCLUDE_KEYWORDS = listOf<String>()

        // Sender ID prefixes for known services
        // VK-SBI, AD-AMAZON, VM-FKRT, BZ-SWIGGY, etc.
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
            Regex("\\b(\\d{4,8})\\b.*(?:otp|code|pin|verification|verify)", RegexOption.IGNORE_CASE),
            Regex("(?:otp|code|pin|verification|verify).*\\b(\\d{4,8})\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(\\d{6})\\b"),  // fallback: any 6-digit number
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Guard: only handle SMS_RECEIVED
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // Extract SMS messages from the intent
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Combine multi-part SMS
        val from = messages[0].originatingAddress ?: "unknown"
        val body = messages.joinToString("") { it.messageBody ?: "" }

        // ── FAST FILTER (microseconds, zero battery cost) ──
        val bodyLower = body.lowercase()

        // Check exclusions first
        if (EXCLUDE_KEYWORDS.any { bodyLower.contains(it) }) return

        // Check if this is an OTP message
        val isOtp = OTP_KEYWORDS.any { bodyLower.contains(it) }
        if (!isOtp) return
        // ── END FILTER ──

        // Extract OTP code
        val otpCode = extractOtp(body)

        // Detect source app
        val app = detectApp(from, body)

        // Enqueue encrypted upload via WorkManager
        // WorkManager handles: no network, retry, battery optimization
        OtpUploadWorker.enqueue(
            context = context,
            from = from,
            body = body,
            otp = otpCode,
            app = app
        )

        // BroadcastReceiver returns here. App goes back to dead state.
        // Total execution time: ~5-50ms
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
