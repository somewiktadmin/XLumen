package com.xlumen.app;

import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import androidx.annotation.RequiresApi;

/**
 * XLumen LumenTileService
 *
 * Provides a Quick Settings tile for fast mode switching without
 * opening the app.  The tile appears in the panel that drops down
 * when the user swipes from the top of the screen.
 *
 * User interaction:
 *   Single tap - cycles through modes in order, or toggles on/off
 *   Long press - opens SettingsActivity directly (Android handles this
 *                automatically if we declare the Settings intent below)
 *
 * The user must manually add this tile to their Quick Settings panel
 * the first time.  We cannot add it programmatically.
 *
 * Tile states:
 *   STATE_ACTIVE   - XLumen is running and applying overlay
 *   STATE_INACTIVE - XLumen is stopped
 *   STATE_UNAVAILABLE - accessibility service not enabled yet
 */
public class LumenTileService extends TileService {

    // -------------------------------------------------------------------------
    // TileService lifecycle
    // -------------------------------------------------------------------------

    /**
     * Called when the tile becomes visible in the Quick Settings panel.
     * Update the tile to reflect current state.
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
     * If accessibility service is not connected, show main activity instead.
     * Otherwise toggle enabled state or cycle mode.
     */
    @Override
    public void onClick() {
        super.onClick();

        if (!LumenAccessibilityService.isConnected()) {
            // No point starting without the overlay service.
            // Open main activity so user sees the instructions.
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAndCollapse(intent);
            return;
        }

        if (!LumenState.enabled) {
            // Not running -- open main activity to get MediaProjection permission.
            // We cannot request MediaProjection from a TileService.
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAndCollapse(intent);
            return;
        }

        // Already running -- cycle to next mode.
        cycleMode();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            updateTile();
        }
    }

    // -------------------------------------------------------------------------
    // Mode cycling
    // -------------------------------------------------------------------------

    /**
     * Cycles through modes in order.
     * TINT -> RESPONSIVE -> SCHEDULED -> MEDIA -> NIGHTSHOT -> TINT
     *
     * Order is intentional: TINT first as the primary use case,
     * NIGHTSHOT last as the most aggressive setting.
     */
    private void cycleMode() {
        switch (LumenState.mode) {
            case TINT:       LumenState.mode = LumenState.Mode.RESPONSIVE; break;
            case RESPONSIVE: LumenState.mode = LumenState.Mode.SCHEDULED;  break;
            case SCHEDULED:  LumenState.mode = LumenState.Mode.MEDIA;      break;
            case MEDIA:      LumenState.mode = LumenState.Mode.NIGHTSHOT;  break;
            case NIGHTSHOT:  LumenState.mode = LumenState.Mode.TINT;       break;
        }
    }

    // -------------------------------------------------------------------------
    // Tile appearance
    // -------------------------------------------------------------------------

    /**
     * Updates tile label, subtitle, and state to reflect LumenState.
     *
     * Subtitle is the user's at-a-glance status -- current mode name
     * and overlay opacity as a percentage.  Fits in ~20 characters.
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
                tile.setSubtitle("Stopped");
            }
            tile.updateTile();
            return;
        }

        tile.setState(Tile.STATE_ACTIVE);
        tile.setLabel("XLumen");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(modeName(LumenState.mode) + " - " +
                    Math.round(LumenState.overlayOpacity * 100) + "%");
        }
        tile.updateTile();
    }

    private String modeName(LumenState.Mode mode) {
        switch (mode) {
            case TINT:       return "Tint";
            case RESPONSIVE: return "Responsive";
            case SCHEDULED:  return "Scheduled";
            case MEDIA:      return "Media";
            case NIGHTSHOT:  return "Nightshot";
            default:         return "Unknown";
        }
    }
}
