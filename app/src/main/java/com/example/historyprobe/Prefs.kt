package com.example.historyprobe

import android.content.Context

/** 端末内の設定と、アップロード状態。 */
object Prefs {
    val LABELS = listOf("phone1", "tablet", "phone2")

    private fun sp(c: Context) = c.getSharedPreferences("history_recorder", Context.MODE_PRIVATE)

    /** 未設定(null)の間はアップロードしない。別端末のファイルを上書きしないため。 */
    fun label(c: Context): String? = sp(c).getString("device_label", null)
    fun setLabel(c: Context, v: String?) = sp(c).edit().apply { if (v == null) remove("device_label") else putString("device_label", v) }.apply()

    fun retentionDays(c: Context): Int = sp(c).getInt("retention_days", 30)
    fun setRetentionDays(c: Context, d: Int) = sp(c).edit().putInt("retention_days", d).apply()

    fun lastSuccess(c: Context): Long = sp(c).getLong("last_success_ms", 0L)
    fun lastError(c: Context): String? = sp(c).getString("last_error", null)
    fun setError(c: Context, m: String?) = sp(c).edit().apply { if (m == null) remove("last_error") else putString("last_error", m) }.apply()
    fun markSuccess(c: Context) = sp(c).edit().putLong("last_success_ms", System.currentTimeMillis()).remove("last_error").apply()

    fun folderId(c: Context): String? = sp(c).getString("drive_folder_id", null)
    fun setFolderId(c: Context, id: String?) = sp(c).edit().apply { if (id == null) remove("drive_folder_id") else putString("drive_folder_id", id) }.apply()
    fun fileId(c: Context, label: String): String? = sp(c).getString("drive_file_$label", null)
    fun setFileId(c: Context, label: String, id: String?) = sp(c).edit().apply { if (id == null) remove("drive_file_$label") else putString("drive_file_$label", id) }.apply()
}
