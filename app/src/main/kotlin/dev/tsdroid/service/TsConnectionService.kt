package dev.tsdroid.service

import android.graphics.PixelFormat
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.app.Notification
import kotlin.math.hypot
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.animation.ValueAnimator
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
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

class TsConnectionService : Service(), LifecycleOwner, ViewModelStoreOwner {

    companion object {
        private const val TAG = "TsConnService"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT = "com.flammedemon.ts6droid.DISCONNECT"
        private const val ACTION_TOGGLE_MUTE = "com.flammedemon.ts6droid.TOGGLE_MUTE"
        private const val ACTION_SHOW_OVERLAY = "com.flammedemon.ts6droid.SHOW_OVERLAY"
        private const val ACTION_HIDE_OVERLAY = "com.flammedemon.ts6droid.HIDE_OVERLAY"

        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(Intent(context, TsConnectionService::class.java))
            } else {
                context.startService(Intent(context, TsConnectionService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TsConnectionService::class.java))
        }

        fun showOverlay(context: Context) {
            val intent = Intent(context, TsConnectionService::class.java).apply { action = ACTION_SHOW_OVERLAY }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun hideOverlay(context: Context) {
            val intent = Intent(context, TsConnectionService::class.java).apply { action = ACTION_HIDE_OVERLAY }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
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

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private var composeView: ComposeView? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val serviceViewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = serviceViewModelStore

    private var overlayConnected by mutableStateOf(false)
    private var overlayChannelName by mutableStateOf<String?>(null)
    private var overlayActiveSpeakerId by mutableStateOf<Int?>(null)
    private var overlayActiveSpeakerName by mutableStateOf<String?>(null)
    private var overlayRecording by mutableStateOf(false)
    private var overlayTouchSlop = 0
    private var dismissZoneActive by mutableStateOf(false)
    private var trashTargetView: ComposeView? = null
    private var trashTargetLayoutParams: WindowManager.LayoutParams? = null
    private var isInDismissZone = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayTouchSlop = ViewConfiguration.get(this).scaledTouchSlop
        audioBridge = AudioBridge(applicationContext, tsClient)
        audioBridge.initialize()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        // Listen for audio events, talk status, and play per-user mixing
        tsClient.events.onEach { event ->
            when (event.type) {
                "audio_received" -> {
                    val userId = (event.data["user_id"] as? Number)?.toInt() ?: return@onEach
                    val data = event.data["data"]
                    if (data is ByteArray) {
                        audioBridge.playAudio(userId, data)
                    } else if (data is Array<*>) {
                        val bytes = ByteArray(data.size) { (data[it] as? Number)?.toByte() ?: 0 }
                        audioBridge.playAudio(userId, bytes)
                    }
                }
                "talk_status_start" -> {
                    val speakerId = (event.data["user_id"] as? Number)?.toInt()
                    if (speakerId != null && speakerId != tsClient.clientId) {
                        overlayActiveSpeakerId = speakerId
                        overlayActiveSpeakerName = findUserNickname(speakerId)
                    }
                }
                "talk_status_stop" -> {
                    val speakerId = (event.data["user_id"] as? Number)?.toInt()
                    if (speakerId != null && speakerId == overlayActiveSpeakerId) {
                        overlayActiveSpeakerId = null
                        overlayActiveSpeakerName = null
                    }
                }
            }
        }.launchIn(serviceScope)

        tsClient.state.onEach { state ->
            overlayConnected = state == dev.tslib.ConnectionState.CONNECTED
            updateOverlayChannelName()
            updateNotification()
        }.launchIn(serviceScope)

        tsClient.users.onEach {
            updateOverlayChannelName()
            refreshActiveSpeakerName()
        }.launchIn(serviceScope)

        tsClient.channels.onEach {
            updateOverlayChannelName()
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
            ACTION_SHOW_OVERLAY -> {
                showFloatingWindow()
                return START_STICKY
            }
            ACTION_HIDE_OVERLAY -> {
                hideFloatingWindow()
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
        cancelPushToTalk()
        hideFloatingWindow()
        audioBridge.stopCapture()
        serviceScope.launch(Dispatchers.IO) {
            tsClient.disconnect()
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    fun showFloatingWindow() {
        Log.d(TAG, "showFloatingWindow called")
        if (!hasOverlayPermission()) {
            Log.w(TAG, "Cannot show floating window because overlay permission is missing")
            return
        }
        if (overlayView != null) {
            Log.d(TAG, "showFloatingWindow skipped: already visible")
            return
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 200
        }

        composeView = ComposeView(this).apply {
            setContent {
                FloatingOverlayContent(
                    connected = overlayConnected,
                    channelName = overlayChannelName,
                    activeSpeakerName = overlayActiveSpeakerName,
                    recording = overlayRecording,
                )
            }
            setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f
                private var longPressTriggered = false
                private var dragging = false
                private var longPressRunnable: Runnable? = null

                override fun onTouch(view: View, event: MotionEvent): Boolean {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            overlayLayoutParams?.let { layout ->
                                initialX = layout.x
                                initialY = layout.y
                            }
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            longPressTriggered = false
                            dragging = false
                            isInDismissZone = false
                            dismissZoneActive = false
                            longPressRunnable = Runnable {
                                longPressTriggered = true
                                startPushToTalk()
                            }
                            view.postDelayed(longPressRunnable, 300)
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - initialTouchX
                            val dy = event.rawY - initialTouchY
                            if (!longPressTriggered && !dragging && hypot(dx.toDouble(), dy.toDouble()) > overlayTouchSlop) {
                                dragging = true
                                longPressRunnable?.let { view.removeCallbacks(it) }
                                longPressRunnable = null
                                showDismissZone()
                            }
                            if (dragging && !longPressTriggered) {
                                val activeComposeView = composeView ?: return true
                                overlayLayoutParams?.let { layout ->
                                    layout.x = initialX + dx.toInt()
                                    layout.y = initialY + dy.toInt()
                                    try {
                                        windowManager.updateViewLayout(activeComposeView, layout)
                                        updateDismissZoneHoverState(layout, activeComposeView)
                                    } catch (_: Exception) {}
                                }
                            }
                            return true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            longPressRunnable?.let { view.removeCallbacks(it) }
                            longPressRunnable = null
                            if (longPressTriggered) {
                                stopPushToTalk()
                            } else if (!dragging) {
                                openMainActivity()
                            } else {
                                val activeComposeView = composeView
                                overlayLayoutParams?.let { layout ->
                                    if (isInDismissZone) {
                                        hideFloatingWindow()
                                    } else if (activeComposeView != null) {
                                        animateOverlayToEdge(layout, activeComposeView)
                                    }
                                }
                            }
                            longPressTriggered = false
                            dragging = false
                            hideDismissZone()
                            return true
                        }
                    }
                    return false
                }
            })
        }

        overlayView = composeView
        overlayLayoutParams = params
        composeView?.let { windowManager.addView(it, params) }
    }

    private fun startPushToTalk() {
        if (overlayRecording) return
        overlayRecording = true
        audioBridge.setMuted(false)
        updateNotification()
        performHapticFeedback(true)
    }

    private fun stopPushToTalk() {
        if (!overlayRecording) return
        overlayRecording = false
        audioBridge.setMuted(true)
        updateNotification()
        performHapticFeedback(false)
    }

    private fun performHapticFeedback(started: Boolean) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        } ?: return

        if (!vibrator.hasVibrator()) return
        val duration = 20L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }

    private fun animateOverlayToEdge(layout: WindowManager.LayoutParams, view: View) {
        val displayWidth = getScreenWidth()
        val viewWidth = view.width.takeIf { it > 0 } ?: view.measuredWidth.takeIf { it > 0 } ?: 200
        val margin = (resources.displayMetrics.density * 16).toInt()
        val targetX = if (layout.x + viewWidth / 2 <= displayWidth / 2) {
            margin
        } else {
            displayWidth - viewWidth - margin
        }

        ValueAnimator.ofInt(layout.x, targetX).apply {
            duration = 250
            addUpdateListener { animator ->
                layout.x = animator.animatedValue as Int
                try {
                    windowManager.updateViewLayout(view, layout)
                } catch (_: Exception) {
                }
            }
            start()
        }
    }

    private fun getScreenWidth(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.width()
        } else {
            @Suppress("DEPRECATION")
            val metrics = resources.displayMetrics
            metrics.widthPixels
        }
    }

    private fun getScreenHeight(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val metrics = resources.displayMetrics
            metrics.heightPixels
        }
    }

    private fun showDismissZone() {
        if (trashTargetView != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        val targetView = ComposeView(this).apply {
            setContent {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 32.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .background(
                                color = if (dismissZoneActive) Color(0xFFB71C1C) else Color(0x88D32F2F),
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss zone",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }
        }

        trashTargetView = targetView
        trashTargetLayoutParams = params
        windowManager.addView(targetView, params)
    }

    private fun hideDismissZone() {
        trashTargetView?.let { view ->
            try {
                windowManager.removeViewImmediate(view)
            } catch (_: Exception) {
            }
        }
        trashTargetView = null
        trashTargetLayoutParams = null
        dismissZoneActive = false
        isInDismissZone = false
    }

    private fun updateDismissZoneHoverState(layout: WindowManager.LayoutParams, view: View) {
        val screenHeight = getScreenHeight()
        val zoneSize = (resources.displayMetrics.density * 96).toInt()
        val screenWidth = getScreenWidth()
        val zoneLeft = (screenWidth - zoneSize) / 2
        val zoneTop = screenHeight - zoneSize - (resources.displayMetrics.density * 32).toInt()
        val viewCenterX = layout.x + (view.width.takeIf { it > 0 } ?: view.measuredWidth) / 2
        val viewCenterY = layout.y + (view.height.takeIf { it > 0 } ?: view.measuredHeight) / 2
        val inside = viewCenterX in zoneLeft..(zoneLeft + zoneSize) && viewCenterY >= zoneTop
        isInDismissZone = inside
        dismissZoneActive = inside
    }

    private fun cancelPushToTalk() {
        if (overlayRecording) {
            stopPushToTalk()
        }
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
    }

    private fun updateOverlayChannelName() {
        val myId = tsClient.clientId ?: return
        val currentChannelId = tsClient.users.value.find { it.id == myId }?.channelId
        overlayChannelName = currentChannelId?.let { channelId ->
            tsClient.channels.value.find { it.id == channelId }?.name
        }
    }

    private fun refreshActiveSpeakerName() {
        overlayActiveSpeakerName = overlayActiveSpeakerId?.let { findUserNickname(it) }
    }

    private fun findUserNickname(userId: Int): String? {
        return tsClient.users.value.firstOrNull { it.id == userId }?.nickname
    }

    fun hideFloatingWindow() {
        Log.d(TAG, "hideFloatingWindow called")
        cancelPushToTalk()
        hideDismissZone()
        overlayView?.let { view ->
            try {
                windowManager.removeViewImmediate(view)
            } catch (_: Exception) {
            }
        }
        overlayView = null
        composeView = null
        overlayLayoutParams = null
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        serviceViewModelStore.clear()
        cancelPushToTalk()
        hideFloatingWindow()
        audioBridge.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun updateNotification() {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    @Composable
    private fun FloatingOverlayContent(
        connected: Boolean,
        channelName: String?,
        activeSpeakerName: String?,
        recording: Boolean,
    ) {
        val backgroundColor = when {
            recording -> Color(0xFFD32F2F)
            connected -> Color(0xFF2E7D32)
            else -> Color(0xFF616161)
        }
        val statusText = when {
            recording -> "Recording"
            connected -> "Connected"
            else -> "Disconnected"
        }
        val statusColor = when {
            recording -> Color(0xFFFFCDD2)
            connected -> Color(0xFFC8E6C9)
            else -> Color(0xFFBDBDBD)
        }

        Box(
            modifier = Modifier
                .size(156.dp)
                .background(backgroundColor, RoundedCornerShape(32.dp))
                .padding(12.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(statusColor, CircleShape),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusText,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = channelName ?: if (connected) "Channel unknown" else "Offline",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.size(8.dp))
                if (!activeSpeakerName.isNullOrEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.VolumeUp,
                            contentDescription = "Active speaker",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = activeSpeakerName,
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Text(
                        text = if (recording) "Release to stop" else "Tap to return",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
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
