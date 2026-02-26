package dev.tsdroid

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class TsDroidApp : Application() {

    companion object {
        const val CHANNEL_ID_CONNECTION = "ts_connection"

        init {
            System.loadLibrary("tslib_jni")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val channel = NotificationChannel(
            CHANNEL_ID_CONNECTION,
            getString(R.string.channel_connection),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_connection_desc)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
