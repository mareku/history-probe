package com.example.historyprobe

import android.content.ComponentName
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 通知アクセスは「セッションを読む鍵」としてだけ使う。
 * 通知の中身は読まない・保存しない・送信しない(onNotificationPosted は空実装)。
 */
class ProbeListenerService : NotificationListenerService() {

    companion object {
        @Volatile var connected = false
        @Volatile var watchError: String? = null
    }

    private var watcher: MediaWatcher? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            ViewTracker.tick(System.currentTimeMillis())
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onListenerConnected() {
        connected = true
        val w = MediaWatcher(this, ComponentName(this, ProbeListenerService::class.java), ViewTracker::onEvent)
        watcher = w
        w.start()
        watchError = w.error
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 60_000)
    }

    override fun onListenerDisconnected() {
        teardown()
        if (Build.VERSION.SDK_INT >= 24) requestRebind(ComponentName(this, ProbeListenerService::class.java))
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        connected = false
        handler.removeCallbacks(ticker)
        watcher?.stop()
        watcher = null
        ViewTracker.closeAll(System.currentTimeMillis())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
