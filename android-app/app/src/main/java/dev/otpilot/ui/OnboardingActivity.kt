package dev.otpilot.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.otpilot.OtpilotApp
import dev.otpilot.R
import dev.otpilot.databinding.ActivityOnboardingBinding
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private var scannerActive = false

    private val permissions: Array<String>
        get() {
            val base = mutableListOf(
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS,
                Manifest.permission.CAMERA
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                base.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            return base.toTypedArray()
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            enableButtons()
        } else {
            Toast.makeText(this, R.string.permissions_needed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Already configured? Go straight to dashboard
        val prefs = getSharedPreferences(OtpilotApp.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains("relay") && prefs.contains("memberToken")) {
            startActivity(Intent(this, DashboardActivity::class.java))
            finish()
            return
        }

        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.scanBtn.setOnClickListener { startQRScanner() }
        binding.pasteBtn.setOnClickListener { showPasteDialog() }

        // Request permissions
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            enableButtons()
        }
    }

    private fun enableButtons() {
        binding.scanBtn.isEnabled = true
        binding.pasteBtn.isEnabled = true
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun startQRScanner() {
        val name = binding.nameInput.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.name_empty, Toast.LENGTH_SHORT).show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            return
        }

        if (scannerActive) return
        scannerActive = true

        // Add camera preview to layout
        val previewView = PreviewView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val overlay = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            addView(previewView)
            setOnClickListener {
                // Tap to close scanner
                (parent as? android.view.ViewGroup)?.removeView(this)
                scannerActive = false
            }
        }

        (binding.root as android.view.ViewGroup).addView(overlay)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }

            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val scanner = BarcodeScanning.getClient()
            val executor = Executors.newSingleThreadExecutor()

            analyzer.setAnalyzer(executor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            for (barcode in barcodes) {
                                if (barcode.valueType == Barcode.TYPE_TEXT) {
                                    val raw = barcode.rawValue ?: continue
                                    runOnUiThread {
                                        cameraProvider.unbindAll()
                                        (overlay.parent as? android.view.ViewGroup)?.removeView(overlay)
                                        scannerActive = false
                                        handleInvitePayload(raw, name)
                                    }
                                    return@addOnSuccessListener
                                }
                            }
                        }
                        .addOnCompleteListener { imageProxy.close() }
                } else {
                    imageProxy.close()
                }
            }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer
            )
        }, ContextCompat.getMainExecutor(this))

        Toast.makeText(this, R.string.point_at_qr, Toast.LENGTH_SHORT).show()
    }

    private fun showPasteDialog() {
        val name = binding.nameInput.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.name_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_paste_code, null)
        val input = view.findViewById<EditText>(R.id.inviteCodeInput)

        AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(view)
            .setPositiveButton(R.string.join_family) { _, _ ->
                val code = input.text.toString().trim()
                if (code.isNotEmpty()) {
                    handleInvitePayload(code, name)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun handleInvitePayload(raw: String, name: String) {
        val invite: JSONObject
        try {
            invite = JSONObject(raw)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.invalid_invite, Toast.LENGTH_SHORT).show()
            return
        }

        val relay = invite.optString("r", "")
        val familyId = invite.optString("f", "")
        val joinToken = invite.optString("j", "")
        val familyKey = invite.optString("k", "")
        val familyName = invite.optString("n", "")

        if (relay.isEmpty() || familyId.isEmpty() || joinToken.isEmpty() || familyKey.isEmpty()) {
            Toast.makeText(this, R.string.invalid_invite, Toast.LENGTH_SHORT).show()
            return
        }

        binding.statusText.visibility = android.view.View.VISIBLE
        binding.statusText.text = getString(R.string.joining)
        binding.scanBtn.isEnabled = false
        binding.pasteBtn.isEnabled = false

        // Join family on background thread
        Thread {
            try {
                val url = URL("$relay/api/family/$familyId/join")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.doOutput = true

                val body = JSONObject().apply {
                    put("joinToken", joinToken)
                    put("name", name)
                }
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

                val code = conn.responseCode
                if (code in 200..299) {
                    val respBody = conn.inputStream.bufferedReader().readText()
                    val resp = JSONObject(respBody)
                    conn.disconnect()

                    val memberId = resp.getString("memberId")
                    val memberToken = resp.getString("memberToken")

                    // Save credentials
                    val prefs = getSharedPreferences(OtpilotApp.PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString("relay", relay)
                        .putString("familyId", familyId)
                        .putString("familyKey", familyKey)
                        .putString("familyName", familyName)
                        .putString("memberId", memberId)
                        .putString("memberToken", memberToken)
                        .putString("memberName", name)
                        .putString("filterKeywords", "otp\ncode\npin\nverification\nverify\ndelivery\nparcel\norder")
                        .putBoolean("notificationsEnabled", true)
                        .apply()

                    runOnUiThread {
                        startActivity(Intent(this, DashboardActivity::class.java))
                        finish()
                    }
                } else {
                    conn.disconnect()
                    runOnUiThread {
                        Toast.makeText(this, R.string.join_failed, Toast.LENGTH_SHORT).show()
                        binding.scanBtn.isEnabled = true
                        binding.pasteBtn.isEnabled = true
                        binding.statusText.visibility = android.view.View.GONE
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "${getString(R.string.network_error)}: ${e.message}", Toast.LENGTH_SHORT).show()
                    binding.scanBtn.isEnabled = true
                    binding.pasteBtn.isEnabled = true
                    binding.statusText.visibility = android.view.View.GONE
                }
            }
        }.start()
    }
}
