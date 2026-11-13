package com.example.historyprobe

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        RecordStore.init(this)
        ViewTracker.init(this) // 前回プロセス死亡時の進行中レコードを確定

        val req = PeriodicWorkRequest.Builder(UploadWorker::class.java, 24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresCharging(true)
                    .build()
            ).build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("daily_upload", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
