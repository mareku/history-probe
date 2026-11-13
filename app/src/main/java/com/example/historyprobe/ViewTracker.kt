package com.example.historyprobe

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 生イベント → 視聴レコード(§5)。
 *  - 開始: PLAYING になった時刻。タイトルが空/「[不明]」の区間は記録しない。
 *  - 終了: タイトル変更 / STOPPED・NONE・ERROR・セッション終了 / 非再生(PAUSED等)が5分継続。
 *  - ended_at は「最後に再生していた時刻」(ポーズ放置で閉じた場合はポーズ開始時刻)。
 *  - watched_sec は PLAYING の累積のみ。1秒未満は記録しない。
 *  - 進行中レコードは open_state.json に保存し、プロセス死亡後の起動時に最終生存時刻で確定する。
 */
object ViewTracker {
    private const val PAUSE_LIMIT_MS = 5 * 60 * 1000L
    private const val UNKNOWN_TITLE = "[不明]"

    private class Open(val pkg: String, val app: String, val title: String, val startedAt: Long) {
        var artist: String? = null
        var album: String? = null
        var durationMs: Long? = null
        var watchedMs = 0L
        var playingSince: Long? = null
        var idleSince: Long? = null

        fun toJson(alive: Long) = JSONObject()
            .put("pkg", pkg).put("app", app).put("title", title).put("startedAt", startedAt)
            .put("artist", nz(artist)).put("album", nz(album)).put("duration", nz(durationMs))
            .put("watchedMs", watchedMs).put("playingSince", nz(playingSince))
            .put("idleSince", nz(idleSince)).put("alive", alive)
    }

    private val open = HashMap<Int, Open>()
    private var dir: File? = null
    private var dirty = false

    @Synchronized fun init(c: Context) {
        dir = c.filesDir
        val f = File(dir!!, "open_state.json")
        if (!f.exists()) return
        runCatching {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                val o = Open(j.getString("pkg"), j.getString("app"), j.getString("title"), j.getLong("startedAt"))
                o.artist = j.strOrNull("artist"); o.album = j.strOrNull("album"); o.durationMs = j.longOrNull("duration")
                o.watchedMs = j.getLong("watchedMs"); o.playingSince = j.longOrNull("playingSince"); o.idleSince = j.longOrNull("idleSince")
                close(o, j.getLong("alive"))
            }
        }
        f.delete()
    }

    @Synchronized fun onEvent(e: MediaWatcher.RawEvent) {
        val now = e.nowMs
        expire(now)

        if (e.kind == MediaWatcher.Kind.REMOVED) {
            open.remove(e.sid)?.let { close(it, now); dirty = true }
            flush(now); return
        }

        val title = e.title?.trim()?.takeIf { it.isNotEmpty() && it != UNKNOWN_TITLE }
        open[e.sid]?.let { if (it.title != title) { close(it, now); open.remove(e.sid); dirty = true } }
        val state = e.state
        if (title == null || state == null) { flush(now); return }

        var o = open[e.sid]
        if (o == null) {
            if (state != "PLAYING") { flush(now); return }
            o = Open(e.pkg, e.app, title, now).also { it.playingSince = now }
            open[e.sid] = o; dirty = true
        }
        if (o.artist == null) { o.artist = e.artist; if (e.artist != null) dirty = true }
        if (o.album == null) { o.album = e.album; if (e.album != null) dirty = true }
        e.durationMs?.let { d -> if (o.durationMs == null || d > o.durationMs!!) { o.durationMs = d; dirty = true } } // 最大値

        when (state) {
            "PLAYING" -> if (o.playingSince == null) { o.playingSince = now; o.idleSince = null; dirty = true }
            "STOPPED", "NONE", "ERROR" -> { close(o, now); open.remove(e.sid); dirty = true }
            else -> if (o.playingSince != null) { // PAUSED / BUFFERING 等は非再生として扱う
                o.watchedMs += now - o.playingSince!!; o.playingSince = null; o.idleSince = now; dirty = true
            }
        }
        flush(now)
    }

    /** 定期呼び出し: ポーズ放置の確定と、生存時刻つきの保存。 */
    @Synchronized fun tick(now: Long) { expire(now); dirty = true; flush(now) }

    /** 通知アクセス切断時: 監視できなくなるので全て確定。 */
    @Synchronized fun closeAll(now: Long) {
        open.values.forEach { close(it, now) }
        open.clear(); dirty = true; flush(now)
    }

    private fun expire(now: Long) {
        val it = open.entries.iterator()
        while (it.hasNext()) {
            val o = it.next().value
            val idle = o.idleSince
            if (o.playingSince == null && idle != null && now - idle >= PAUSE_LIMIT_MS) {
                close(o, now); it.remove(); dirty = true
            }
        }
    }

    private fun close(o: Open, now: Long) {
        val endAt: Long
        val ps = o.playingSince
        if (ps != null) { o.watchedMs += now - ps; endAt = now } else endAt = o.idleSince ?: now
        o.playingSince = null
        if (o.watchedMs < 1000) return
        val end = maxOf(endAt, o.startedAt)
        RecordStore.add(JSONObject()
            .put("package", o.pkg).put("app", o.app).put("title", o.title)
            .put("artist", nz(o.artist)).put("album", nz(o.album)).put("duration_ms", nz(o.durationMs))
            .put("started_at", iso(o.startedAt)).put("ended_at", iso(end))
            .put("watched_sec", (o.watchedMs + 500) / 1000).put("_end", end))
    }

    private fun flush(now: Long) {
        if (!dirty) return
        dirty = false
        val d = dir ?: return
        runCatching {
            val arr = JSONArray(); open.values.forEach { arr.put(it.toJson(now)) }
            val tmp = File(d, "open_state.json.tmp")
            tmp.writeText(arr.toString(), Charsets.UTF_8)
            tmp.renameTo(File(d, "open_state.json"))
        }
    }
}
