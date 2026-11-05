package pl.bartek.callrecorder.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/** Kafelek w panelu szybkich ustawień – start/stop nagrywania (np. w trakcie rozmowy). */
class RecordTileService : TileService() {

    override fun onStartListening() = refresh()

    override fun onClick() {
        val svc = CallRecorderService.instance
        if (svc == null) {
            Toast.makeText(this, "Włącz usługę dostępności „Rejestrator Rozmów” w aplikacji", Toast.LENGTH_LONG).show()
            return
        }
        svc.toggleRecording()
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val svc = CallRecorderService.instance
        tile.state = when {
            svc == null -> Tile.STATE_UNAVAILABLE
            svc.isRecording -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = if (svc?.isRecording == true) "Nagrywam…" else "Nagraj rozmowę"
        tile.updateTile()
    }
}
