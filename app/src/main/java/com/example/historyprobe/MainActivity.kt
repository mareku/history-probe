package com.example.historyprobe

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {

    private lateinit var statusTv: TextView
    private lateinit var uploadTv: TextView
    private lateinit var listTv: TextView
    private lateinit var uploadBtn: Button
    private lateinit var retentionEt: EditText
    private val handler = Handler(Looper.getMainLooper())
    private val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val fmtFull = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val refresher = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 10_000) }
    }

    private val authLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        // 一部の端末(GMS後付けのFire等)では、同意画面で進めても RESULT_OK が返らないことがある。
        // 結果コードでは判断せず、戻ってきたら必ず認可を取り直して判定する。
        // afterAuth=true の経路は同意画面を再表示しないので、ループにはならない。
        startUpload(afterAuth = true)
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            try {
                val text = RecordStore.toJsonl(RecordStore.all(), Prefs.label(this) ?: "unset")
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                toast("書き出しました")
            } catch (e: Exception) { toast("書き出し失敗: ${e.message}") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        root.addView(text("履歴プローブ", 22f).apply { setTypeface(typeface, Typeface.BOLD) })
        statusTv = text("", 14f); root.addView(statusTv)
        root.addView(button("通知アクセス設定を開く") {
            startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })

        root.addView(text("端末ラベル", 14f).apply { setPadding(0, dp(12), 0, 0) })
        val spinner = Spinner(this)
        val items = listOf("(未設定)") + Prefs.LABELS
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        spinner.setSelection(Prefs.label(this)?.let { items.indexOf(it) }?.takeIf { it >= 0 } ?: 0, false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                Prefs.setLabel(this@MainActivity, if (pos == 0) null else Prefs.LABELS[pos - 1])
                refresh()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        root.addView(spinner)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(text("送信済みの保持日数", 14f))
        retentionEt = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Prefs.retentionDays(this@MainActivity).toString())
            minEms = 3
        }
        row.addView(retentionEt)
        row.addView(button("保存") {
            val d = retentionEt.text.toString().toIntOrNull()
            if (d == null || d < 1) toast("1以上の数字を入れてください")
            else { Prefs.setRetentionDays(this, d); toast("保存しました") }
        })
        root.addView(row)

        uploadTv = text("", 14f).apply { setPadding(0, dp(12), 0, 0) }; root.addView(uploadTv)
        uploadBtn = button("今すぐアップロード") { startUpload(afterAuth = false) }; root.addView(uploadBtn)
        root.addView(button("手動エクスポート(端末内の全レコード)") {
            exportLauncher.launch("history_${Prefs.label(this) ?: "unset"}.jsonl")
        })

        root.addView(text("履歴(新しい順・最大200件。● は未送信)", 14f).apply { setPadding(0, dp(12), 0, dp(4)) })
        listTv = text("", 12f).apply { typeface = Typeface.MONOSPACE }; root.addView(listTv)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); handler.post(refresher) }
    override fun onPause() { handler.removeCallbacks(refresher); super.onPause() }

    private fun startUpload(afterAuth: Boolean) {
        uploadBtn.isEnabled = false
        Thread {
            val o = DriveUploader.upload(applicationContext)
            runOnUiThread {
                uploadBtn.isEnabled = true
                when (o) {
                    is DriveUploader.Outcome.NeedAuth ->
                        if (afterAuth) { Prefs.setError(this, "認可後も権限を取得できませんでした(同意が完了していない可能性)") }
                        else authLauncher.launch(IntentSenderRequest.Builder(o.pi.intentSender).build())
                    is DriveUploader.Outcome.Fail -> toast(o.msg)
                    DriveUploader.Outcome.Ok -> toast("アップロードしました")
                }
                refresh()
            }
        }.start()
    }

    private fun refresh() {
        statusTv.text = "通知アクセス: " + (if (ProbeListenerService.connected) "接続中" else "未接続") +
            (ProbeListenerService.watchError?.let { "\n$it" } ?: "")

        val all = RecordStore.all()
        val last = Prefs.lastSuccess(this)
        uploadTv.text = "最終成功: " + (if (last > 0) at(last, fmtFull) else "まだありません") +
            "\n未送信: ${all.count { it.optInt("_up") == 0 }}件" +
            (Prefs.lastError(this)?.let { "\n直近のエラー: $it" } ?: "")

        listTv.text = all.sortedByDescending { it.optLong("_end") }.take(200).joinToString("\n") { r ->
            val mark = if (r.optInt("_up") == 0) "● " else "  "
            val artist = r.strOrNull("artist")?.let { " / $it" } ?: ""
            val w = r.optLong("watched_sec")
            "$mark${at(r.optLong("_end"), fmt)} [${r.optString("app")}] ${r.optString("title")}$artist (${w / 60}:${"%02d".format(w % 60)})"
        }.ifEmpty { "(まだ記録がありません)" }
    }

    private fun at(ms: Long, f: DateTimeFormatter) = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), JST).format(f)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    private fun text(s: String, size: Float) = TextView(this).apply { text = s; textSize = size }
    private fun button(label: String, onClick: () -> Unit) = Button(this).apply { text = label; setOnClickListener { onClick() } }
}
