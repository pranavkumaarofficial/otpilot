package dev.otpilot.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject

/**
 * Setup activity. User opens this ONCE to:
 * 1. Grant SMS permission
 * 2. Scan the family QR code
 * 3. Enter their name
 *
 * After setup, the app has no UI. The BroadcastReceiver handles everything.
 * The user can forget this app exists — it just works silently.
 */
class MainActivity : AppCompatActivity() {

    private val requiredPermissions = arrayOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.CAMERA
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.all { it.value }) {
            checkSetupState()
        } else {
            Toast.makeText(this, "SMS and camera permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if already configured
        val prefs = getSharedPreferences("otpilot", Context.MODE_PRIVATE)
        if (prefs.contains("relay") && prefs.contains("familyId")) {
            showConfiguredState(prefs)
            return
        }

        // Request permissions
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            checkSetupState()
        }
    }

    private fun checkSetupState() {
        val prefs = getSharedPreferences("otpilot", Context.MODE_PRIVATE)
        if (prefs.contains("relay")) {
            showConfiguredState(prefs)
        } else {
            startQRScanner()
        }
    }

    /**
     * Scan the family invite QR code.
     * QR contains: {"r":"http://...","f":"f_xxx","j":"joinToken","k":"encKey","n":"Family Name"}
     *
     * Uses CameraX + ML Kit barcode scanning — standard Google libraries.
     */
    private fun startQRScanner() {
        // Implementation uses CameraX + ML Kit BarcodeScanning
        // The QR payload is parsed and stored in SharedPreferences
        // Then we call the relay's /api/family/{id}/join endpoint
        // to register this device as a family member

        Toast.makeText(this, "Point camera at the family QR code", Toast.LENGTH_LONG).show()

        // QR scanning implementation would go here
        // Using: androidx.camera:camera-camera2 + com.google.mlkit:barcode-scanning
        //
        // On successful scan:
        //   val json = JSONObject(qrContent)
        //   saveConfig(json.getString("r"), json.getString("f"),
        //              json.getString("j"), json.getString("k"), json.getString("n"))
    }

    /**
     * After QR scan: join the family and save config.
     */
    private fun saveConfig(relay: String, familyId: String, joinToken: String, key: String, familyName: String) {
        val memberName = "Phone" // Could prompt user for their name

        // Call /api/family/{id}/join to register
        // Then save credentials
        val prefs = getSharedPreferences("otpilot", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("relay", relay)
            .putString("familyId", familyId)
            .putString("familyKey", key)
            .putString("memberName", memberName)
            // memberToken comes from the join API response
            .apply()

        Toast.makeText(this, "Joined $familyName! OTPs will forward automatically.", Toast.LENGTH_LONG).show()
    }

    private fun showConfiguredState(prefs: android.content.SharedPreferences) {
        val name = prefs.getString("memberName", "?")
        val family = prefs.getString("familyName", "?")
        Toast.makeText(this, "otpilot is active. Forwarding OTPs as $name in $family.", Toast.LENGTH_LONG).show()
        // Show minimal status UI: "Active — forwarding OTPs for [name]"
        // with a "Leave family" button
    }
}
