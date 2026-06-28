package dev.otpilot.ui

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import dev.otpilot.OtpilotApp
import dev.otpilot.R
import dev.otpilot.crypto.CryptoUtil
import dev.otpilot.databinding.ActivityDashboardBinding
import dev.otpilot.model.FamilyMember
import dev.otpilot.model.OtpMessage
import dev.otpilot.service.RelayWebSocketService
import dev.otpilot.worker.OtpUploadWorker
import org.json.JSONArray
import org.json.JSONObject

class DashboardActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DashboardActivity"
    }

    private lateinit var binding: ActivityDashboardBinding
    private lateinit var otpAdapter: OtpAdapter
    private lateinit var tabAdapter: MemberTabAdapter
    private lateinit var lbm: LocalBroadcastManager

    private val allOtps = mutableListOf<OtpMessage>()
    private val members = mutableListOf<FamilyMember>()
    private var selectedTab = "all"
    private var otpTtl = 300_000L
    private var service: RelayWebSocketService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as RelayWebSocketService.LocalBinder).getService()
            bound = true
            updateConnDot(service?.isConnected == true)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
        }
    }

    private val wsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Broadcast received: ${intent.action}")
            when (intent.action) {
                RelayWebSocketService.ACTION_CONNECTION -> {
                    val connected = intent.getStringExtra("connected") == "true"
                    Log.d(TAG, "Connection: $connected")
                    updateConnDot(connected)
                }
                RelayWebSocketService.ACTION_HISTORY -> {
                    val json = intent.getStringExtra("json") ?: return
                    Log.d(TAG, "History received, length=${json.length}")
                    handleHistory(json)
                }
                RelayWebSocketService.ACTION_NEW_OTP -> {
                    val json = intent.getStringExtra("json") ?: return
                    Log.d(TAG, "New OTP received: $json")
                    handleNewOtp(json)
                }
                RelayWebSocketService.ACTION_MEMBERS -> {
                    val json = intent.getStringExtra("json") ?: return
                    handleMembers(json)
                }
                RelayWebSocketService.ACTION_PRESENCE -> {
                    val json = intent.getStringExtra("json") ?: return
                    handlePresence(json)
                }
                RelayWebSocketService.ACTION_SETTINGS -> {
                    val ttl = intent.getStringExtra("otpTtl")?.toLongOrNull() ?: return
                    otpTtl = ttl
                    otpAdapter.setTtl(ttl)
                }
                RelayWebSocketService.ACTION_MEMBER_JOINED,
                RelayWebSocketService.ACTION_MEMBER_LEFT -> {
                    // Members list will be refreshed via the members broadcast
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        lbm = LocalBroadcastManager.getInstance(this)

        // Family label
        val prefs = getSharedPreferences(OtpilotApp.PREFS_NAME, Context.MODE_PRIVATE)
        binding.familyLabel.text = prefs.getString("familyName", "")

        // Settings button
        binding.settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // OTP adapter
        otpAdapter = OtpAdapter(
            onCopyClick = { code -> copyToClipboard(code) },
            otpTtl = otpTtl
        )
        binding.otpFeed.layoutManager = LinearLayoutManager(this)
        binding.otpFeed.adapter = otpAdapter

        // Tab adapter
        tabAdapter = MemberTabAdapter { tabId ->
            selectedTab = tabId
            tabAdapter.setSelected(tabId)
            updateFeed()
        }
        binding.tabsBar.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.tabsBar.adapter = tabAdapter

        // Test OTP button (bypasses SMS receiver to test upload + WebSocket path)
        binding.testOtpBtn.setOnClickListener {
            sendTestOtp()
        }

        // Handle copy from notification
        intent?.getStringExtra("copyOtp")?.let { code ->
            if (code.isNotEmpty()) copyToClipboard(code)
        }

        // Ask user to disable battery optimization (Samsung kills background services)
        requestBatteryOptimizationExemption()

        // Ask user to enable notification access (Samsung blocks SMS broadcast)
        requestNotificationAccess()

        // Start WebSocket service
        val serviceIntent = Intent(this, RelayWebSocketService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        // Register receivers
        val filter = IntentFilter().apply {
            addAction(RelayWebSocketService.ACTION_CONNECTION)
            addAction(RelayWebSocketService.ACTION_HISTORY)
            addAction(RelayWebSocketService.ACTION_NEW_OTP)
            addAction(RelayWebSocketService.ACTION_MEMBERS)
            addAction(RelayWebSocketService.ACTION_PRESENCE)
            addAction(RelayWebSocketService.ACTION_SETTINGS)
            addAction(RelayWebSocketService.ACTION_MEMBER_JOINED)
            addAction(RelayWebSocketService.ACTION_MEMBER_LEFT)
        }
        lbm.registerReceiver(wsReceiver, filter)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.getStringExtra("copyOtp")?.let { code ->
            if (code.isNotEmpty()) copyToClipboard(code)
        }
    }

    override fun onDestroy() {
        lbm.unregisterReceiver(wsReceiver)
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    private fun updateConnDot(connected: Boolean) {
        runOnUiThread {
            binding.connDot.setBackgroundResource(
                if (connected) R.drawable.dot_connected else R.drawable.dot_disconnected
            )
        }
    }

    private fun handleHistory(json: String) {
        runOnUiThread {
            try {
                val root = JSONObject(json)
                val data = root.getJSONArray("data")
                allOtps.clear()
                for (i in 0 until data.length()) {
                    parseOtp(data.getJSONObject(i))?.let { allOtps.add(it) }
                }
                updateFeed()
            } catch (_: Exception) {}
        }
    }

    private fun handleNewOtp(json: String) {
        runOnUiThread {
            try {
                val otp = parseOtp(JSONObject(json))
                if (otp != null) {
                    allOtps.add(0, otp)
                    updateFeed()
                    updateTabs()
                }
            } catch (_: Exception) {}
        }
    }

    private fun handleMembers(json: String) {
        runOnUiThread {
            try {
                val arr = JSONArray(json)
                members.clear()
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    members.add(FamilyMember(
                        id = m.getString("id"),
                        name = m.getString("name"),
                        online = m.getBoolean("online")
                    ))
                }
                updateTabs()
            } catch (_: Exception) {}
        }
    }

    private fun handlePresence(json: String) {
        runOnUiThread {
            try {
                val data = JSONObject(json)
                val id = data.getString("id")
                val online = data.getBoolean("online")
                val idx = members.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    members[idx] = members[idx].copy(online = online)
                    updateTabs()
                }
            } catch (_: Exception) {}
        }
    }

    private fun parseOtp(data: JSONObject): OtpMessage? {
        val prefs = getSharedPreferences(OtpilotApp.PREFS_NAME, Context.MODE_PRIVATE)
        val familyKey = prefs.getString("familyKey", null) ?: return null

        val id = data.getString("id")
        val memberId = data.getString("memberId")
        val memberName = data.getString("memberName")
        val timestamp = data.getLong("timestamp")
        val encrypted = data.getJSONObject("encrypted")

        val plaintext = CryptoUtil.decrypt(
            encrypted.getString("iv"),
            encrypted.getString("ct"),
            familyKey
        ) ?: return null

        val payload = JSONObject(plaintext)
        val from = payload.optString("from", "unknown")
        val body = payload.optString("body", "")
        val rawOtp = payload.optString("otp", "")
        val otp = if (rawOtp.isEmpty() || rawOtp == "null") null else rawOtp
        val app = payload.optString("app", "unknown")

        return OtpMessage(
            id = id,
            memberId = memberId,
            memberName = memberName,
            from = from,
            body = body,
            otp = otp,
            app = app,
            category = OtpMessage.categoryFor(app),
            timestamp = timestamp
        )
    }

    private fun updateFeed() {
        val now = System.currentTimeMillis()
        val filtered = allOtps
            .filter { now - it.timestamp < otpTtl }
            .filter { selectedTab == "all" || it.memberId == selectedTab }

        otpAdapter.submitList(filtered.toList())

        binding.emptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.otpFeed.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateTabs() {
        val now = System.currentTimeMillis()
        val activeOtps = allOtps.filter { now - it.timestamp < otpTtl }
        val counts = activeOtps.groupBy { it.memberId }.mapValues { it.value.size }
        tabAdapter.setData(members, counts, activeOtps.size)
        tabAdapter.setSelected(selectedTab)
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        AlertDialog.Builder(this)
            .setTitle("Battery optimization")
            .setMessage(getString(R.string.battery_optimization_needed))
            .setPositiveButton("Open settings") { _, _ ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestNotificationAccess() {
        if (isNotificationListenerEnabled()) return

        AlertDialog.Builder(this)
            .setTitle("Notification access")
            .setMessage(getString(R.string.notif_access_needed))
            .setPositiveButton("Open settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (TextUtils.isEmpty(flat)) return false
        val names = flat.split(":")
        for (name in names) {
            val cn = ComponentName.unflattenFromString(name)
            if (cn != null && cn.packageName == packageName) return true
        }
        return false
    }

    private fun sendTestOtp() {
        val prefs = getSharedPreferences(OtpilotApp.PREFS_NAME, Context.MODE_PRIVATE)
        val relay = prefs.getString("relay", null)
        val familyId = prefs.getString("familyId", null)
        val memberToken = prefs.getString("memberToken", null)

        if (relay == null || familyId == null || memberToken == null) {
            Toast.makeText(this, R.string.test_otp_failed, Toast.LENGTH_SHORT).show()
            return
        }

        val testCode = (100000..999999).random().toString()
        Log.d(TAG, "Sending test OTP: $testCode")

        OtpUploadWorker.enqueue(
            context = this,
            from = "otpilot-test",
            body = "Your test OTP is $testCode. This is a test message from otpilot.",
            otp = testCode,
            app = "otpilot"
        )

        Toast.makeText(this, "${getString(R.string.test_otp_sent)}: $testCode", Toast.LENGTH_LONG).show()
    }

    private fun copyToClipboard(code: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OTP", code))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}
