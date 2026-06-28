package dev.otpilot.service

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dev.otpilot.OtpilotApp
import dev.otpilot.worker.OtpUploadWorker

/**
 * Reads SMS notifications posted by Google Messages / Samsung Messages.
 *
 * Samsung blocks the SMS_RECEIVED broadcast for non-default SMS apps,
 * but NotificationListenerService still gets every notification.
 * The user grants access once via Settings > Notification access.
 *
 * When notification content is hidden (Samsung "sensitive content" setting),
 * we try multiple fallbacks: tickerText, MessagingStyle messages, textLines.
 */
class SmsNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "SmsNotifListener"

        // Packages that post SMS notifications
        private val SMS_PACKAGES = setOf(
            "com.google.android.apps.messaging",   // Google Messages
            "com.samsung.android.messaging",        // Samsung Messages
            "com.android.mms",                      // AOSP SMS
            "com.messaging.sms",                    // Some OEM SMS apps
        )

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

        // Track recently processed notifications to avoid duplicates
        private val recentKeys = LinkedHashSet<String>()
        private const val MAX_RECENT = 50
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "NotificationListener CONNECTED - service is active")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "NotificationListener DISCONNECTED")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val notif = sbn.notification
        val extras = notif.extras

        // Extract text using multiple fallback strategies
        val extracted = extractNotificationText(notif, extras)
        val title = extracted.first
        val body = extracted.second

        Log.d(TAG, "ANY notification: pkg=$pkg title=$title text=${body.take(100)}")

        // Skip our own notifications
        if (pkg == applicationContext.packageName) return

        // Check if it's from an SMS app
        if (pkg !in SMS_PACKAGES) {
            val category = notif.category
            if (category != "msg") return
            Log.d(TAG, "Non-SMS-package but msg category, processing: $pkg")
        }

        if (body.isBlank()) {
            Log.d(TAG, "Empty body after all extraction attempts, skipping")
            return
        }

        // Deduplicate
        val key = "$pkg:$title:$body"
        if (key in recentKeys) return
        recentKeys.add(key)
        if (recentKeys.size > MAX_RECENT) {
            recentKeys.remove(recentKeys.first())
        }

        Log.d(TAG, "SMS notification from $pkg: title=$title body=$body")

        // Check if app is configured
        val prefs = applicationContext.getSharedPreferences(OtpilotApp.PREFS_NAME, MODE_PRIVATE)
        if (!prefs.contains("relay") || !prefs.contains("memberToken")) {
            Log.d(TAG, "App not configured, skipping")
            return
        }

        val bodyLower = body.lowercase()

        // Load filter keywords
        val keywordsStr = prefs.getString("filterKeywords", null)
        val keywords = if (keywordsStr != null) {
            keywordsStr.split("\n").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        } else {
            DEFAULT_KEYWORDS
        }

        // Load blocked senders
        val blockedStr = prefs.getString("blockedSenders", "")
        val blocked = blockedStr?.split("\n")?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() } ?: emptyList()

        // Check blocked
        if (blocked.any { title.lowercase().contains(it) }) {
            Log.d(TAG, "Sender blocked: $title")
            return
        }

        // Check keywords
        val isOtp = keywords.any { bodyLower.contains(it) }
        if (!isOtp) {
            Log.d(TAG, "No keyword match, skipping")
            return
        }

        val otpCode = extractOtp(body)
        val app = detectApp(title, body)
        Log.d(TAG, "OTP detected via notification: code=$otpCode app=$app, enqueueing upload")

        OtpUploadWorker.enqueue(
            context = applicationContext,
            from = title,
            body = body,
            otp = otpCode,
            app = app
        )
    }

    /**
     * Try multiple strategies to get the real notification content.
     * Samsung hides "sensitive" content, so the standard extras may show
     * "Sensitive notification content hidden". We try fallbacks:
     *
     * 1. Standard extras (android.title / android.text / android.bigText)
     * 2. tickerText (often not redacted)
     * 3. MessagingStyle messages (Google Messages stores full text here)
     * 4. InboxStyle textLines
     * 5. android.subText
     */
    private fun extractNotificationText(notif: Notification, extras: Bundle?): Pair<String, String> {
        val stdTitle = extras?.getCharSequence("android.title")?.toString() ?: ""
        val stdText = extras?.getCharSequence("android.text")?.toString() ?: ""
        val bigText = extras?.getCharSequence("android.bigText")?.toString() ?: ""

        // Check if content is hidden/redacted
        val isRedacted = stdText.contains("Sensitive notification content hidden", ignoreCase = true)
                || stdText.contains("Contents hidden", ignoreCase = true)
                || (stdText.isBlank() && stdTitle.isBlank())

        if (!isRedacted) {
            val body = if (bigText.length > stdText.length) bigText else stdText
            return Pair(stdTitle, body)
        }

        Log.d(TAG, "Content redacted, trying fallbacks...")

        // Fallback 1: tickerText
        val ticker = notif.tickerText?.toString() ?: ""
        if (ticker.isNotBlank()) {
            Log.d(TAG, "Fallback tickerText: $ticker")
            // tickerText is often "Sender: message body"
            val colonIdx = ticker.indexOf(": ")
            if (colonIdx > 0) {
                return Pair(ticker.substring(0, colonIdx), ticker.substring(colonIdx + 2))
            }
            return Pair(stdTitle, ticker)
        }

        // Fallback 2: MessagingStyle messages (API 24+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val messages = extras?.getParcelableArray("android.messages")
            if (messages != null && messages.isNotEmpty()) {
                val lastMsg = messages.last()
                if (lastMsg is Bundle) {
                    val msgText = lastMsg.getCharSequence("text")?.toString() ?: ""
                    val msgSender = lastMsg.getCharSequence("sender")?.toString()
                        ?: lastMsg.getCharSequence("sender_person")?.toString()
                        ?: stdTitle
                    if (msgText.isNotBlank()) {
                        Log.d(TAG, "Fallback MessagingStyle: sender=$msgSender text=$msgText")
                        return Pair(msgSender, msgText)
                    }
                }
            }
        }

        // Fallback 3: textLines (InboxStyle)
        val lines = extras?.getCharSequenceArray("android.textLines")
        if (lines != null && lines.isNotEmpty()) {
            val lastLine = lines.last()?.toString() ?: ""
            if (lastLine.isNotBlank()) {
                Log.d(TAG, "Fallback textLines: $lastLine")
                return Pair(stdTitle, lastLine)
            }
        }

        // Fallback 4: subText
        val subText = extras?.getCharSequence("android.subText")?.toString() ?: ""
        if (subText.isNotBlank()) {
            Log.d(TAG, "Fallback subText: $subText")
            return Pair(stdTitle, subText)
        }

        // Fallback 5: infoText
        val infoText = extras?.getCharSequence("android.infoText")?.toString() ?: ""
        if (infoText.isNotBlank()) {
            Log.d(TAG, "Fallback infoText: $infoText")
            return Pair(stdTitle, infoText)
        }

        // Dump all extras keys for debugging
        if (extras != null) {
            val keys = extras.keySet().joinToString(", ")
            Log.d(TAG, "All extras keys: $keys")
            for (key in extras.keySet()) {
                val value = extras.get(key)
                if (value is CharSequence && value.isNotBlank()) {
                    Log.d(TAG, "  extra[$key] = ${value.toString().take(100)}")
                }
            }
        }

        return Pair(stdTitle, stdText)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // no-op
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
