package pl.bartek.callrecorder

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import pl.bartek.callrecorder.data.AppDatabase

class App : Application() {

    val db: AppDatabase by lazy { AppDatabase.build(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_RECORDING, "Nagrywanie rozmowy", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ANALYSIS, "Analiza nagrań", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val CH_RECORDING = "recording"
        const val CH_ANALYSIS = "analysis"
        lateinit var instance: App
            private set
    }
}
