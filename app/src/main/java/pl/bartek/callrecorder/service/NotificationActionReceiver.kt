package pl.bartek.callrecorder.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP) CallRecorderService.instance?.let { if (it.isRecording) it.toggleRecording() }
    }

    companion object {
        const val ACTION_STOP = "pl.bartek.callrecorder.STOP"
    }
}
