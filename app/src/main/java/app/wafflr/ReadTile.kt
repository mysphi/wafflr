package app.wafflr

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * A Quick Settings tile, "Wafflr: read screen". Tap it in the pull-down shade to read the screen
 * underneath; tap it again while reading to stop.
 */
class ReadTile : TileService(), ReaderListener {
    private val reader get() = WafflrApp.instance.reader

    override fun onStartListening() {
        reader.addListener(this)
    }

    override fun onStopListening() {
        reader.removeListener(this)
    }

    override fun onStatus(status: ReaderStatus) {
        val tile = qsTile ?: return
        val reading = status.state != ReaderState.IDLE
        tile.state = if (reading) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Wafflr"
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (reading) "Stop reading" else "Read screen"
        tile.updateTile()
    }

    override fun onClick() {
        if (reader.isActive) {
            reader.stop()
            return
        }
        // Close the shade first so the screen underneath is what gets read.
        val intent = Intent(this, TileReadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }
}

/** Invisible: closes at once, then asks the service to read the screen once the shade is gone. */
class TileReadActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = WafflrService.instance
        if (service == null) {
            Toast.makeText(this, "Turn on Wafflr in its Settings first.", Toast.LENGTH_LONG).show()
        } else {
            Handler(Looper.getMainLooper()).postDelayed({ service.readScreen() }, 600)
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
