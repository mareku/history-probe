package com.example.historyprobe

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** 1日1回(充電中・通信可)の自動アップロード。失敗してもレコードは何も消えない。 */
class UploadWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result {
        val c = applicationContext
        RecordStore.purge(Prefs.retentionDays(c))
        return when (val o = DriveUploader.upload(c)) {
            is DriveUploader.Outcome.Ok -> Result.success()
            is DriveUploader.Outcome.NeedAuth -> {
                // バックグラウンドでは同意画面を出せない。アプリを開いて「今すぐアップロード」で再認可する
                Prefs.setError(c, "Drive権限の再認可が必要です。アプリで「今すぐアップロード」を押してください")
                Result.success()
            }
            is DriveUploader.Outcome.Fail -> if (o.retry) Result.retry() else Result.success()
        }
    }
}
