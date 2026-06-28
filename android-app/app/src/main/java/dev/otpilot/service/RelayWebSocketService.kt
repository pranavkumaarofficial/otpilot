package dev.otpilot.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import dev.otpilot.OtpilotApp
import dev.otpilot.R
import dev.otpilot.crypto.CryptoUtil
import dev.otpilot.model.OtpMessage
import dev.otpilot.ui.DashboardActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RelayWebSocketService : Service() {

    companion object {
        private const val TAG = "RelayWSService"
        const val ACTION_CONNECTION = "dev.otpilot.CONNECTION"
        const val ACTION_HISTORY = "dev.otpilot.HISTORY"
        const val ACTION_NEW_OTP = "dev.otpilot.NEW_OTP"
        const val ACTION_MEMBERS = "dev.otpilot.MEMBERS"
        const val ACTION_PRESENCE = "dev.otpilot.PRESENCE"
        const val ACTION_SETTINGS = "dev.otpilot.SETTINGS"
        const val ACTION_MEMBER_JOINED = "dev.otpilot.MEMBER_JOINED"
        const val ACTION_MEMBER_LEFT = "dev.otpilot.MEMBER_LEFT"
        const val ACTION_KICKED = "dev.otpilot.KICKED"
    }

    private val binder = LocalBinder()
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    private var otpNotifId = 1000
    private var wakeLock: PowerManager.WakeLock? = null

    var isConnected = false
        private set

    inner class LocalBinder : Binder() {
        fun getService(): RelayWebSocketService = this@RelayWebSocketService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildServiceNotification())

        // Acquire partial wake lock to prevent Samsung from freezing the service
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "otpilot:ws").apply {
            acquire()
        }
        Log.d(TAG, "Service created, wake lock acquired")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        connect()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        webSocket?.close(1000, "service stopped")
        client.dispatcher.executorService.shutdown()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        super.onDestroy()
    }

    fun connect() {
        val prefs = getSharedPreferences(OtpilotApp.PREFS_NAME, Context.MODE_PRIVATE)
        val relay = prefs.getString("relay", null) ?: return
        val familyId = prefs.getString("familyId", null) ?: return
        val memberId = prefs.getString("memberId", null) ?: return
        val token = prefs.getString("memberToken", null) ?: return

        val wsUrl = relay
            .replace("http://", "ws://")
            .replace("https://", "wss://")

        val url = "$wsUrl/ws?familyId=$familyId&memberId=$memberId&token=$token"
        Log.d(TAG, "Connecting to $url")
        val request = Request.Builder().url(url).build()

        webSocket?.close(1000, "reconnecting")
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                isConnected = true
                broadcast(ACTION_CONNECTION, "connected" to "true")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                broadcast(ACTION_CONNECTION, "connected" to "false")
                if (code == 4010) {
                    Log.d(TAG, "Kicked from family (code 4010)")
                    broadcast(ACTION_KICKED)
                } else {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                isConnected = false
                broadcast(ACTION_CONNECTION, "connected" to "false")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = Runnable { connect() }
        handler.postDelayed(reconnectRunnable!!, 3000)
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.getString("type")

            Log.d(TAG, "WS message type=$type")
            when (type) {
                "history" -> {
                    val intent = Intent(ACTION_HISTORY).apply {
                        putExtra("json", text)
                    }
                    LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                }
                "new_otp" -> {
                    val data = json.getJSONObject("data")
                    val otp = parseOtpFromServer(data)
                    if (otp != null) {
                        val intent = Intent(ACTION_NEW_OTP).apply {
                            putExtra("json", data.toString())
                        }
                        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                        showOtpNotification(otp)
                    }
                }
                "members" -> {
                    broadcast(ACTION_MEMBERS,
                        "json" to json.getJSONArray("data").toString(),
                        "adminId" to json.optString("adminId", "")
                    )
                }
                "presence" -> {
                    broadcast(ACTION_PRESENCE, "json" to json.getJSONObject("data").toString())
                }
                "settings_updated" -> {
                    val data = json.getJSONObject("data")
                    broadcast(ACTION_SETTINGS, "otpTtl" to data.getLong("otpTtl").toString())
                }
                "member_joined" -> {
                    broadcast(ACTION_MEMBER_JOINED, "json" to json.getJSONObject("data").toString())
                }
                "member_left" -> {
                    broadcast(ACTION_MEMBER_LEFT, "json" to json.getJSONObject("data").toString())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleMessage error: ${e.message}")
        }
    }

    private fun parseOtpFromServer(data: JSONObject): OtpMessage? {
        val prefs = getSharedPreferences(OtpilotApp.PREFS_NAME, Context.MODE_PRIVATE)
        val familyKey = prefs.getString("familyKey", null) ?: return null

        val id = data.getString("id")
        val memberId = data.getString("memberId")
        val memberName = data.getString("memberName")
        val timestamp = data.getLong("timestamp")
        val encrypted = data.getJSONObject("encrypted")

        val iv = encrypted.getString("iv")
        val ct = encrypted.getString("ct")

        val plaintext = CryptoUtil.decrypt(iv, ct, familyKey) ?: return null
        val payload = JSONObject(plaintext)

        val from = payload.optString("from", "unknown")
        val body = payload.optString("body", "")
        val otp: String? = if (payload.has("otp") && !payload.isNull("otp")) payload.getString("otp") else null
        val app = payload.optString("app", "unknown")

        return OtpMessage(
            id = id,
            memberId = memberId,
            memberName = memberName,
            from = from,
            body = body,
            otp = if (otp.isNullOrEmpty() || otp == "null") null else otp,
            app = app,
            category = OtpMessage.categoryFor(app),
            timestamp = timestamp
        )
    }

    private fun parseOtpArray(arr: JSONArray): List<OtpMessage> {
        val result = mutableListOf<OtpMessage>()
        for (i in 0 until arr.length()) {
            val otp = parseOtpFromServer(arr.getJSONObject(i))
            if (otp != null) result.add(otp)
        }
        return result
    }

    private fun showOtpNotification(otp: OtpMessage) {
        val prefs = getSharedPreferences(OtpilotApp.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notificationsEnabled", true)) return

        // Don't notify for own OTPs
        val myId = prefs.getString("memberId", "")
        if (otp.memberId == myId) return

        val intent = Intent(this, DashboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("copyOtp", otp.otp ?: "")
        }
        val pending = PendingIntent.getActivity(
            this, otpNotifId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (otp.app != "unknown") "${otp.app} OTP from ${otp.memberName}" else "OTP from ${otp.memberName}"
        val text = otp.otp ?: otp.body.take(100)

        val notif = NotificationCompat.Builder(this, OtpilotApp.CHANNEL_OTPS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(otpNotifId++, notif)
        } catch (_: SecurityException) {}
    }

    private fun broadcast(action: String, vararg extras: Pair<String, String>) {
        val intent = Intent(action)
        for ((k, v) in extras) intent.putExtra(k, v)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun buildServiceNotification(): Notification {
        val intent = Intent(this, DashboardActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, OtpilotApp.CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.notif_service_title))
            .setContentText(getString(R.string.notif_service_text))
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

}
