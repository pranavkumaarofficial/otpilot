package dev.otpilot.worker

import android.content.Context
import androidx.work.*
import dev.otpilot.crypto.CryptoUtil
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * One-shot worker that encrypts and POSTs a single OTP to the relay.
 *
 * Uses WorkManager so Android handles:
 * - Network unavailable → queues and retries when connected
 * - App killed → work survives across process death
 * - Doze mode → expedited work runs even in deep sleep
 *
 * The worker exists for ~200ms, fires the POST, and is garbage collected.
 */
class OtpUploadWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("otpilot", Context.MODE_PRIVATE)
        val relay = prefs.getString("relay", null) ?: return Result.failure()
        val familyId = prefs.getString("familyId", null) ?: return Result.failure()
        val memberToken = prefs.getString("memberToken", null) ?: return Result.failure()
        val familyKey = prefs.getString("familyKey", null) ?: return Result.failure()
        val memberName = prefs.getString("memberName", "phone") ?: "phone"

        val from = inputData.getString("from") ?: return Result.failure()
        val body = inputData.getString("body") ?: return Result.failure()
        val otp = inputData.getString("otp")
        val app = inputData.getString("app") ?: "unknown"

        // Build the plaintext payload
        val payload = """{"from":"${escape(from)}","body":"${escape(body)}","otp":${if (otp != null) "\"$otp\"" else "null"},"app":"$app","memberName":"${escape(memberName)}","timestamp":${System.currentTimeMillis()}}"""

        // Encrypt with family key (AES-256-GCM, hardware-accelerated)
        val encrypted = CryptoUtil.encrypt(payload, familyKey)
            ?: return Result.retry()

        // POST to relay
        return try {
            val url = URL("$relay/api/family/$familyId/otp")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $memberToken")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true

            val jsonBody = """{"encrypted":{"iv":"${encrypted.iv}","ct":"${encrypted.ciphertext}"}}"""
            OutputStreamWriter(conn.outputStream).use { it.write(jsonBody) }

            val code = conn.responseCode
            conn.disconnect()

            if (code in 200..299) Result.success() else Result.retry()
        } catch (e: Exception) {
            Result.retry() // WorkManager will retry with exponential backoff
        }
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    companion object {
        /**
         * Enqueue a one-shot OTP upload. Called from the BroadcastReceiver.
         * Constraints: requires network. Expedited for urgency.
         */
        fun enqueue(context: Context, from: String, body: String, otp: String?, app: String) {
            val data = Data.Builder()
                .putString("from", from)
                .putString("body", body)
                .putString("otp", otp)
                .putString("app", app)
                .build()

            val request = OneTimeWorkRequestBuilder<OtpUploadWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
