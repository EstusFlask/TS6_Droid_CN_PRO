package dev.tsdroid.service

import android.util.Log
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.tsdroid.MainActivity
import dev.tsdroid.R
import dev.tsdroid.TsDroidApp
import dev.tsdroid.bridge.AudioBridge
import dev.tsdroid.bridge.TsClient
import dev.tslib.Identity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TsConnectionService : Service() {

    companion object {
        private const val TAG = "TsConnService"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT = "com.flammedemon.ts6droid.DISCONNECT"
        private const val ACTION_TOGGLE_MUTE = "com.flammedemon.ts6droid.TOGGLE_MUTE"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TsConnectionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TsConnectionService::class.java))
        }
    }

    inner class LocalBinder : Binder() {
        val tsClient: TsClient get() = this@TsConnectionService.tsClient
        val audioBridge: AudioBridge get() = this@TsConnectionService.audioBridge
        val service: TsConnectionService get() = this@TsConnectionService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val tsClient = TsClient()
    lateinit var audioBridge: AudioBridge
        private set

    override fun onCreate() {
        super.onCreate()
        audioBridge = AudioBridge(applicationContext, tsClient)
        audioBridge.initialize()

        // Listen for audio events and play them (per-user mixing)
        tsClient.events.onEach { event ->
            if (event.type == "audio_received") {
                val userId = (event.data["user_id"] as? Number)?.toInt() ?: return@onEach
                val data = event.data["data"]
                if (data is ByteArray) {
                    audioBridge.playAudio(userId, data)
                } else if (data is Array<*>) {
                    // JNI might wrap it differently
                    val bytes = ByteArray(data.size) { (data[it] as? Number)?.toByte() ?: 0 }
                    audioBridge.playAudio(userId, bytes)
                }
            }
        }.launchIn(serviceScope)

        // Update notification when state changes
        tsClient.state.onEach { state ->
            if (state != dev.tslib.ConnectionState.DISCONNECTED) {
                updateNotification()
            }
        }.launchIn(serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnect()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MUTE -> {
                audioBridge.toggleMute()
                updateNotification()
                return START_STICKY
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun connect(address: String, identity: Identity, nickname: String, password: String?) {
        serviceScope.launch {
            tsClient.connect(address, identity, nickname, password)
            audioBridge.startCapture(serviceScope)
            // Sync initial mute state with server (PTT starts muted)
            if (audioBridge.isMuted.value) {
                tsClient.setInputMuted(true)
            }
            updateNotification()
            // Start event loop
            launch { tsClient.eventLoop() }
        }
    }

    fun disconnect() {
        audioBridge.stopCapture()
        serviceScope.launch(Dispatchers.IO) {
            tsClient.disconnect()
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        audioBridge.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun updateNotification() {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val disconnectIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TsConnectionService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val muteIntent = PendingIntent.getService(
            this, 2,
            Intent(this, TsConnectionService::class.java).apply { action = ACTION_TOGGLE_MUTE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val serverName = tsClient.serverInfo.value?.name ?: getString(R.string.connecting)
        val muteLabel = getString(if (audioBridge.isMuted.value) R.string.notif_unmute else R.string.notif_mute)

        return NotificationCompat.Builder(this, TsDroidApp.CHANNEL_ID_CONNECTION)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(serverName)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(0, muteLabel, muteIntent)
            .addAction(0, getString(R.string.disconnect), disconnectIntent)
            .build()
    }
}
