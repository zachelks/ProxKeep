package io.github.zachelks.proxkeep.status

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.zachelks.proxkeep.MainActivity
import io.github.zachelks.proxkeep.ProxKeepApplication
import io.github.zachelks.proxkeep.alerts.StatusWorker

/** A Quick Settings tile showing the signed-in server's status. A tap opens the app. */
class StatusTileService : TileService() {

    private val container
        get() = (application as ProxKeepApplication).container

    override fun onTileAdded() {
        container.statusSurfaces.setTilePlaced(true)
        StatusWorker.runOnce(this)
    }

    override fun onTileRemoved() {
        container.statusSurfaces.setTilePlaced(false)
    }

    override fun onStartListening() {
        val tile = qsTile ?: return
        val lines = describe(this, container.statusSurfaces.store.read(), container.settings.lockEnabled.value)
        tile.label = lines.title
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = lines.body.firstOrNull()
        tile.contentDescription = (listOf(lines.title) + lines.body).joinToString(". ")
        // The tile switches nothing on or off; it only shows status.
        tile.state = Tile.STATE_ACTIVE
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
