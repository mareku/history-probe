package com.example.historyprobe

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * メディアセッション監視部品(第1段から流用。UIに依存しない)。
 * 読むだけ。再生の制御はしない。通知の内容には一切触れない。
 * 第2段の変更点: ログ出力(LogStore)をやめ、生イベントを sink に渡す。セッションごとに sid を振る。
 */
class MediaWatcher(
    private val ctx: Context,
    private val listenerComponent: ComponentName,
    private val sink: (RawEvent) -> Unit
) {

    enum class Kind { METADATA, PLAYBACK, REMOVED }

    data class RawEvent(
        val sid: Int, val kind: Kind, val nowMs: Long, val pkg: String, val app: String,
        val title: String?, val artist: String?, val album: String?,
        val durationMs: Long?, val state: String?
    )

    data class SessionInfo(
        val app: String, val pkg: String,
        val title: String?, val displayTitle: String?, val artist: String?, val album: String?,
        val durationMs: Long?, val state: String?, val positionMs: Long?
    )

    private class Entry(val id: Int, val controller: MediaController, val callback: MediaController.Callback) {
        var lastMeta: String? = null
    }

    private val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val handler = Handler(Looper.getMainLooper())
    private val entries = LinkedHashMap<MediaSession.Token, Entry>()
    private val labelCache = HashMap<String, String>()
    private var nextId = 1

    @Volatile var error: String? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { sync(it ?: emptyList()) }

    fun start() {
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
            sync(msm.getActiveSessions(listenerComponent))
            error = null
        } catch (e: SecurityException) {
            error = "セッション取得が拒否されました(通知アクセス未許可?): ${e.message}"
        }
    }

    fun stop() {
        try { msm.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        entries.values.forEach { try { it.controller.unregisterCallback(it.callback) } catch (_: Exception) {} }
        entries.clear()
    }

    private fun sync(list: List<MediaController>) {
        val current = list.associateBy { it.sessionToken }
        entries.entries.toList().filter { it.key !in current }.forEach { removeEntry(it.key, it.value) }
        list.filter { it.sessionToken !in entries }.forEach { addEntry(it) }
    }

    private fun addEntry(c: MediaController) {
        val cb = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) { logMetadata(c) }
            override fun onPlaybackStateChanged(state: PlaybackState?) { logPlayback(c) }
            override fun onSessionDestroyed() { entries[c.sessionToken]?.let { removeEntry(c.sessionToken, it) } }
        }
        c.registerCallback(cb, handler)
        entries[c.sessionToken] = Entry(nextId++, c, cb)
        // 既に再生中のものも拾うため、発見時に現状を1回ずつ流す
        logMetadata(c)
        logPlayback(c)
    }

    private fun removeEntry(t: MediaSession.Token, e: Entry) {
        try { e.controller.unregisterCallback(e.callback) } catch (_: Exception) {}
        entries.remove(t)
        emit(e.id, e.controller, Kind.REMOVED, null, null)
    }

    private fun logMetadata(c: MediaController) {
        val e = entries[c.sessionToken] ?: return
        val md = c.metadata
        val sig = listOf(
            md?.getString(MediaMetadata.METADATA_KEY_TITLE), md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            md?.getString(MediaMetadata.METADATA_KEY_ARTIST), md?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationOf(md)
        ).toString()
        if (e.lastMeta == sig) return // 同一内容の連続は流さない
        e.lastMeta = sig
        emit(e.id, c, Kind.METADATA, md, c.playbackState)
    }

    private fun logPlayback(c: MediaController) {
        val e = entries[c.sessionToken] ?: return
        emit(e.id, c, Kind.PLAYBACK, c.metadata, c.playbackState)
    }

    private fun durationOf(md: MediaMetadata?): Long? =
        if (md != null && md.containsKey(MediaMetadata.METADATA_KEY_DURATION))
            md.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 } else null

    /** 現在位置の推定: position + (経過時間 × playbackSpeed)。PLAYING のときだけ補正する。 */
    private fun estimatePosition(ps: PlaybackState?): Long? {
        if (ps == null || ps.position == PlaybackState.PLAYBACK_POSITION_UNKNOWN) return null
        if (ps.state != PlaybackState.STATE_PLAYING) return ps.position
        val elapsed = SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime
        return ps.position + (elapsed * ps.playbackSpeed).toLong()
    }

    private fun stateName(ps: PlaybackState?): String? = when (ps?.state) {
        null -> null
        PlaybackState.STATE_PLAYING -> "PLAYING"
        PlaybackState.STATE_PAUSED -> "PAUSED"
        PlaybackState.STATE_STOPPED -> "STOPPED"
        PlaybackState.STATE_BUFFERING -> "BUFFERING"
        PlaybackState.STATE_NONE -> "NONE"
        PlaybackState.STATE_ERROR -> "ERROR"
        PlaybackState.STATE_CONNECTING -> "CONNECTING"
        PlaybackState.STATE_FAST_FORWARDING -> "FAST_FORWARDING"
        PlaybackState.STATE_REWINDING -> "REWINDING"
        PlaybackState.STATE_SKIPPING_TO_NEXT -> "SKIPPING_TO_NEXT"
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "SKIPPING_TO_PREVIOUS"
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "SKIPPING_TO_QUEUE_ITEM"
        else -> "UNKNOWN(${ps.state})"
    }

    private fun appLabel(pkg: String): String = labelCache.getOrPut(pkg) {
        try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
    }

    private fun emit(id: Int, c: MediaController, kind: Kind, md: MediaMetadata?, ps: PlaybackState?) {
        val pkg = c.packageName
        sink(
            RawEvent(
                id, kind, System.currentTimeMillis(), pkg, appLabel(pkg),
                md?.getString(MediaMetadata.METADATA_KEY_TITLE),
                md?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                md?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationOf(md), stateName(ps)
            )
        )
    }

    /** ライブ表示用(現在は未使用。第1段互換のため残置)。 */
    fun snapshot(): List<SessionInfo> = entries.values.map { e ->
        val c = e.controller; val md = c.metadata; val ps = c.playbackState
        SessionInfo(
            appLabel(c.packageName), c.packageName,
            md?.getString(MediaMetadata.METADATA_KEY_TITLE),
            md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            md?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            md?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationOf(md), stateName(ps), estimatePosition(ps)
        )
    }
}
