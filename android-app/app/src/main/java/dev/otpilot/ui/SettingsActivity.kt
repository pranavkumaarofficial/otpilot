package dev.otpilot.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import dev.otpilot.OtpilotApp
import dev.otpilot.R
import dev.otpilot.databinding.ActivitySettingsBinding
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(OtpilotApp.PREFS_NAME, Context.MODE_PRIVATE)

        // Back button
        binding.backBtn.setOnClickListener { finish() }

        // Profile
        binding.nameInput.setText(prefs.getString("memberName", ""))
        binding.saveNameBtn.setOnClickListener { saveName() }

        // TTL options
        val isAdmin = prefs.getString("adminToken", null) != null
        val currentTtl = prefs.getLong("otpTtl", 300_000L)
        setupTtlOptions(currentTtl, isAdmin)

        if (!isAdmin) {
            binding.ttlAdminHint.visibility = View.VISIBLE
        }

        // Filters
        binding.filterInput.setText(prefs.getString("filterKeywords", "otp\ncode\npin\nverification\nverify\ndelivery\nparcel\norder"))
        binding.saveFiltersBtn.setOnClickListener { saveFilters() }

        // Notifications toggle
        binding.notifToggle.isChecked = prefs.getBoolean("notificationsEnabled", true)
        binding.notifToggle.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("notificationsEnabled", checked).apply()
        }

        // Load members
        loadMembers()

        // Leave family
        binding.leaveBtn.setOnClickListener { showLeaveDialog() }
    }

    private fun setupTtlOptions(currentTtl: Long, isAdmin: Boolean) {
        val options = listOf(
            binding.ttl1min to 60_000L,
            binding.ttl3min to 180_000L,
            binding.ttl5min to 300_000L,
            binding.ttl10min to 600_000L
        )

        for ((view, value) in options) {
            view.isSelected = value == currentTtl

            if (isAdmin) {
                view.setOnClickListener {
                    for ((v, _) in options) v.isSelected = false
                    view.isSelected = true
                    saveTtl(value)
                }
            } else {
                view.alpha = 0.5f
            }
        }
    }

    private fun saveName() {
        val newName = binding.nameInput.text.toString().trim()
        if (newName.isEmpty()) {
            Toast.makeText(this, R.string.name_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getSharedPreferences(OtpilotApp.PREFS_NAME, Context.MODE_PRIVATE)
        val currentName = prefs.getString("memberName", "")
        if (newName == currentName) return

        val relay = prefs.getString("relay", "") ?: ""
        val familyId = prefs.getString("familyId", "") ?: ""
        val token = prefs.getString("memberToken", "") ?: ""

        Thread {
            try {
                val url = URL("$relay/api/family/$familyId/name")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "PATCH"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.doOutput = true

                val body = JSONObject().apply { put("name", newName) }
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

                val code = conn.responseCode
                conn.disconnect()

                runOnUiThread {
                    if (code in 200..299) {
                        prefs.edit().putString("memberName", newName).apply()
                        Toast.makeText(this, R.string.name_updated, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, R.string.network_error, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, R.string.network_error, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun saveTtl(ttl: Long) {
        val prefs = getSharedPreferences(OtpilotApp.PREFS_NAME, Context.MODE_PRIVATE)
        val relay = prefs.getString("relay", "") ?: ""
        val familyId = prefs.getString("familyId", "") ?: ""
        val adminToken = prefs.getString("adminToken", "") ?: ""

        Thread {
            try {
                val url = URL("$relay/api/family/$familyId/settings")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "PATCH"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $adminToken")
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.doOutput = true

                val body = JSONObject().apply { put("otpTtl", ttl) }
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

                val code = conn.responseCode
                conn.disconnect()

                runOnUiThread {
                    if (code in 200..299) {
                        prefs.edit().putLong("otpTtl", ttl).apply()
                        Toast.makeText(this, R.string.ttl_updated, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, R.string.admin_only, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, R.string.network_error, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun saveFilters() {
        val keywords = binding.filterInput.text.toString()
        val prefs = getSharedPreferences(OtpilotApp.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("filterKeywords", keywords).apply()
        Toast.makeText(this, R.string.filters_saved, Toast.LENGTH_SHORT).show()
    }

    private fun loadMembers() {
        val prefs = getSharedPreferences(OtpilotApp.PREFS_NAME, Context.MODE_PRIVATE)
        val relay = prefs.getString("relay", "") ?: ""
        val familyId = prefs.getString("familyId", "") ?: ""
        val token = prefs.getString("memberToken", "") ?: ""

        Thread {
            try {
                val url = URL("$relay/api/family/$familyId/members")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000

                val code = conn.responseCode
                if (code in 200..299) {
                    val respBody = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    val resp = JSONObject(respBody)
                    val membersArr = resp.getJSONArray("members")

                    runOnUiThread { renderMembers(membersArr) }
                } else {
                    conn.disconnect()
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun renderMembers(arr: JSONArray) {
        binding.membersContainer.removeAllViews()

        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val name = m.getString("name")
            val online = m.getBoolean("online")

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }

            // Online dot
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                    marginEnd = dp(8)
                }
                setBackgroundResource(if (online) R.drawable.dot_connected else R.drawable.dot_disconnected)
            }

            // Name
            val label = TextView(this).apply {
                text = name
                setTextColor(ContextCompat.getColor(context, R.color.body))
                textSize = 14f
            }

            row.addView(dot)
            row.addView(label)
            binding.membersContainer.addView(row)

            // Divider (not after last)
            if (i < arr.length() - 1) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                    ).apply { topMargin = dp(4); bottomMargin = dp(4) }
                    setBackgroundColor(ContextCompat.getColor(context, R.color.hairline))
                }
                binding.membersContainer.addView(divider)
            }
        }
    }

    private fun showLeaveDialog() {
        AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(R.string.leave_family)
            .setMessage(R.string.leave_confirm)
            .setPositiveButton(R.string.leave) { _, _ -> leaveFamily() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun leaveFamily() {
        val prefs = getSharedPreferences(OtpilotApp.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        // Stop the WebSocket service
        stopService(Intent(this, dev.otpilot.service.RelayWebSocketService::class.java))

        // Go to onboarding
        val intent = Intent(this, OnboardingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
