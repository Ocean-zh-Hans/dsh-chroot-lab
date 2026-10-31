package dev.dsh.agent

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** 快捷设置磁贴只做一件事：暂停/继续 DSH 的 APK 动作。 */
class PresenceTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        PresenceState.toggle(this)
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val phase = PresenceState.phase()
        val active = phase == PresencePhase.WORKING
        tile.label = phase.label
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(
            this,
            if (active) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
        )
        tile.updateTile()
    }
}
