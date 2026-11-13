package com.example.historyprobe

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 視聴レコードの端末内保管(JSONL)。内部項目 _id/_up/_end は送信・書き出し時に除く。
 * device は保存せず、出力時に現在の端末ラベルを付ける(ラベル変更が全レコードに効く)。
 */
object RecordStore {
    private var dir: File? = null
    fun init(c: Context) { dir = c.filesDir }
    private fun file() = File(dir!!, "records.jsonl")

    @Synchronized fun add(rec: JSONObject) {
        rec.put("_id", UUID.randomUUID().toString())
        rec.put("_up", 0)
        file().appendText(rec.toString() + "\n", Charsets.UTF_8)
    }

    @Synchronized fun all(): List<JSONObject> {
        val f = file()
        if (!f.exists()) return emptyList()
        return f.readLines(Charsets.UTF_8).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
    }

    fun unsentCount(): Int = all().count { it.optInt("_up") == 0 }

    @Synchronized fun markUploaded(ids: Set<String>) {
        val list = all()
        list.forEach { if (it.getString("_id") in ids) it.put("_up", 1) }
        rewrite(list)
    }

    /** 送信済みで保持期間を過ぎたものだけ削除。未送信は消さない。 */
    @Synchronized fun purge(days: Int) {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        val list = all()
        val kept = list.filterNot { it.optInt("_up") == 1 && it.optLong("_end") < cutoff }
        if (kept.size != list.size) rewrite(kept)
    }

    /** §5 の形式(キー順固定)。送信・手動エクスポート共通。 */
    fun toJsonl(list: List<JSONObject>, label: String): String = buildString {
        for (r in list) {
            val o = JSONObject()
            o.put("device", label)
            o.put("package", r.getString("package"))
            o.put("app", r.getString("app"))
            o.put("title", r.getString("title"))
            o.put("artist", nz(r.strOrNull("artist")))
            o.put("album", nz(r.strOrNull("album")))
            o.put("duration_ms", nz(r.longOrNull("duration_ms")))
            o.put("started_at", r.getString("started_at"))
            o.put("ended_at", r.getString("ended_at"))
            o.put("watched_sec", r.getLong("watched_sec"))
            append(o.toString()).append('\n')
        }
    }

    private fun rewrite(list: List<JSONObject>) {
        val f = file()
        val tmp = File(f.parentFile, "records.jsonl.tmp")
        tmp.writeText(list.joinToString("") { it.toString() + "\n" }, Charsets.UTF_8)
        tmp.renameTo(f)
    }
}
