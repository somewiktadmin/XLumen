package com.xlumen.app;

import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import androidx.annotation.RequiresApi;

/**
 * XLumen LumenTileService
 *
 * Provides a Quick Settings tile for toggling XLumen on/off without
 * opening the app.  The tile appears in the panel that drops down
 * when the user swipes from the top of the screen.
 *
 * User interaction:
 *   Single tap - toggles LumenState.enabled on/off.
 *                If MediaProjection is not yet running, opens MainActivity
 *                to acquire the permission - cannot be done from a TileService.
 *   Long press  - opens SettingsActivity directly (Android handles this
 *                 automatically via the Settings intent declaration).
 *
 * The user must manually add this tile to their Quick Settings panel
 * the first time.  It cannot be added programmatically.
 *
 * Tile states:
 *   STATE_ACTIVE      - XLumen is running and overlay is enabled
 *   STATE_INACTIVE    - XLumen is running but overlay is disabled
 *   STATE_UNAVAILABLE - accessibility service not enabled yet
 */
public class LumenTileService extends TileService {

    // =========================================================================
    // TileService lifecycle
    // =========================================================================

    /**
     * Called when the tile becomes visible in the Quick Settings panel.
     * Refreshes tile appearance to reflect current LumenState.
     */
    @RequiresApi(api = Build.VERSION_CODES.Q)
    @Override
    public void onStartListening() {
        super.onStartListening();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            updateTile();
        }
    }

    /**
     * Called when the user taps the tile.
     *
     * If accessibility service is not connected, opens MainActivity -
     * the user needs to enable it before anything works.
     *
     * If LumenService is not running (no MediaProjection token),
     * opens MainActivity to acquire the permission.
     *
     * Otherwise toggles LumenState.enabled directly.
     */
    @Override
    public void onClick() {
        super.onClick();

        if (!LumenAccessibilityService.isConnected()) {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAndCollapse(intent);
            return;
        }

        if (!LumenState.enabled && LumenState.lumi < 0f) {
            // lumi < 0 means no valid frame yet - service not running.
            // Must go through MainActivity to get MediaProjection permission.
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAndCollapse(intent);
            return;
        }

        LumenState.enabled = !LumenState.enabled;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            updateTile();
        }
    }


    /**
     * Updates tile label, subtitle, and state to reflect current LumenState.
     *
     * Subtitle shows current mode name and overlay opacity as a percentage,
     * fitting in roughly 20 characters for narrow tile displays.
     */
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;

        if (!LumenAccessibilityService.isConnected()) {
            tile.setState(Tile.STATE_UNAVAILABLE);
            tile.setLabel("XLumen");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.setSubtitle("Enable in Accessibility");
            }
            tile.updateTile();
            return;
        }

        if (!LumenState.enabled) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setLabel("XLumen");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.setSubtitle("Off");
            }
            tile.updateTile();
            return;
        }

        tile.setState(Tile.STATE_ACTIVE);
        tile.setLabel("XLumen");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle("LumiGuard " + Math.round(LumenState.overlayOpacity * 100) + "%");
        }
        tile.updateTile();
    }

}