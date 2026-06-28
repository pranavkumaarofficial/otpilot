package dev.otpilot

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class OtpilotApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val otpChannel = NotificationChannel(
                CHANNEL_OTPS,
                getString(R.string.notif_channel_otps),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming OTPs"
            }

            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                getString(R.string.notif_channel_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent connection status"
            }

            manager.createNotificationChannels(listOf(otpChannel, serviceChannel))
        }
    }

    companion object {
        const val CHANNEL_OTPS = "otps"
        const val CHANNEL_SERVICE = "service"
        const val PREFS_NAME = "otpilot"
    }
}
