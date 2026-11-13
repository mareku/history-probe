package com.example.historyprobe

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 専用アカウントのDriveへ、端末ごとに1ファイルを丸ごと上書きする(drive.file スコープ)。
 * フォルダ「視聴履歴」は初回にアプリ自身が作成する(drive.file は自作ファイルしか見えないため)。
 * 必ずバックグラウンドスレッドから呼ぶこと(Tasks.await を使う)。
 */
object DriveUploader {
    const val ACCOUNT = "mareku.hisho@gmail.com"
    private const val FOLDER_NAME = "視聴履歴"
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    private const val TEXT = "text/plain; charset=UTF-8"

    sealed class Outcome {
        object Ok : Outcome()
        class NeedAuth(val pi: PendingIntent) : Outcome()
        class Fail(val msg: String, val retry: Boolean = true) : Outcome()
    }

    @Synchronized
    fun upload(ctx: Context): Outcome {
        val label = Prefs.label(ctx) ?: return fail(ctx, "端末ラベルが未設定です(他端末のファイルを上書きしないため送信しません)", false)
        val snap = RecordStore.all()
        val ids = snap.map { it.getString("_id") }.toSet()
        val content = RecordStore.toJsonl(snap, label).toByteArray(Charsets.UTF_8)

        val token: String
        try {
            val req = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(SCOPE)))
                .setAccount(Account(ACCOUNT, "com.google"))
                .build()
            val res = Tasks.await(Identity.getAuthorizationClient(ctx).authorize(req), 60, TimeUnit.SECONDS)
            if (res.hasResolution()) return Outcome.NeedAuth(res.pendingIntent!!)
            token = res.accessToken ?: return fail(ctx, "アクセストークンを取得できませんでした", true)
        } catch (e: Exception) {
            return fail(ctx, "Google認可に失敗: ${e.cause?.message ?: e.message}", true)
        }

        try {
            val folderId = ensureFolder(ctx, token)
            putFile(ctx, token, folderId, label, content)
        } catch (e: Exception) {
            return fail(ctx, "Driveへの送信に失敗: ${e.message}", true)
        }
        RecordStore.markUploaded(ids)
        Prefs.markSuccess(ctx)
        return Outcome.Ok
    }

    private fun fail(ctx: Context, msg: String, retry: Boolean): Outcome {
        Prefs.setError(ctx, msg)
        return Outcome.Fail(msg, retry)
    }

    private fun ensureFolder(ctx: Context, token: String): String {
        Prefs.folderId(ctx)?.let { id ->
            val (code, body) = http("GET", "$API/files/$id?fields=id,trashed", token)
            if (code == 200 && !JSONObject(body).optBoolean("trashed")) return id
            if (code != 200 && code != 404) throw IOException("HTTP $code ${body.take(200)}")
            Prefs.setFolderId(ctx, null)
        }
        findFirst(token, "name='$FOLDER_NAME' and mimeType='application/vnd.google-apps.folder' and trashed=false")?.let {
            Prefs.setFolderId(ctx, it); return it
        }
        val meta = JSONObject().put("name", FOLDER_NAME).put("mimeType", "application/vnd.google-apps.folder").toString()
        val (code, body) = http("POST", "$API/files?fields=id", token, meta.toByteArray(Charsets.UTF_8), "application/json; charset=UTF-8")
        if (code !in 200..299) throw IOException("フォルダ作成 HTTP $code ${body.take(200)}")
        val id = JSONObject(body).getString("id")
        Prefs.setFolderId(ctx, id)
        return id
    }

    private fun putFile(ctx: Context, token: String, folderId: String, label: String, content: ByteArray) {
        val name = "history_$label.jsonl"

        Prefs.fileId(ctx, label)?.let { id ->
            val code = patch(token, id, content)
            if (code in 200..299) return
            if (code != 404) throw IOException("更新 HTTP $code")
            Prefs.setFileId(ctx, label, null)
        }
        findFirst(token, "name='$name' and '$folderId' in parents and trashed=false")?.let { id ->
            val code = patch(token, id, content)
            if (code !in 200..299) throw IOException("更新 HTTP $code")
            Prefs.setFileId(ctx, label, id); return
        }
        val b = "histprobe${System.nanoTime()}"
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(folderId)).put("mimeType", "text/plain").toString()
        val out = ByteArrayOutputStream()
        out.write("--$b\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$b\r\nContent-Type: $TEXT\r\n\r\n".toByteArray(Charsets.UTF_8))
        out.write(content)
        out.write("\r\n--$b--".toByteArray(Charsets.UTF_8))
        val (code, body) = http("POST", "$UPLOAD/files?uploadType=multipart&fields=id", token, out.toByteArray(), "multipart/related; boundary=$b")
        if (code !in 200..299) throw IOException("作成 HTTP $code ${body.take(200)}")
        Prefs.setFileId(ctx, label, JSONObject(body).getString("id"))
    }

    /** 内容の置き換え。HttpURLConnection で PATCH を直接使わず、メソッド上書きヘッダで送る。 */
    private fun patch(token: String, id: String, content: ByteArray): Int =
        http("POST", "$UPLOAD/files/$id?uploadType=media", token, content, TEXT, "PATCH").first

    private fun findFirst(token: String, q: String): String? {
        val (code, body) = http("GET", "$API/files?q=${URLEncoder.encode(q, "UTF-8")}&fields=files(id)&pageSize=1", token)
        if (code != 200) throw IOException("検索 HTTP $code ${body.take(200)}")
        val arr = JSONObject(body).optJSONArray("files") ?: return null
        return if (arr.length() > 0) arr.getJSONObject(0).getString("id") else null
    }

    private fun http(
        method: String, url: String, token: String,
        body: ByteArray? = null, ctype: String? = null, override: String? = null
    ): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("Authorization", "Bearer $token")
            if (override != null) c.setRequestProperty("X-HTTP-Method-Override", override)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", ctype)
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return code to text
        } finally {
            c.disconnect()
        }
    }
}
