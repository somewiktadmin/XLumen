package com.xlumen.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * XLumen LumenPrefs
 *
 * Single source of truth for all user-configurable settings.
 * LumenService re-instantiates this each sample cycle, so settings
 * changes take effect within one interval without restarting the service.
 *
 * All keys must match res/xml/preferences.xml exactly.
 */
public class LumenPrefs {

    private static final String PREFS_NAME = "xlumen_prefs";

    // === Keys ===
    public static final String KEY_SAMPLE_INTERVAL_MS = "sample_interval_ms";
    public static final String KEY_COOLDOWN_MS        = "cooldown_ms";
    public static final String KEY_THRESHOLD          = "threshold";
    public static final String KEY_ENABLED            = "enabled";
    public static final String KEY_MODE               = "mode";

    // === Defaults ===
    public static final int     DEFAULT_SAMPLE_INTERVAL_MS = 100;
    public static final int     DEFAULT_COOLDOWN_MS        = 4000;
    public static final int     DEFAULT_THRESHOLD          = 50;
    public static final boolean DEFAULT_ENABLED            = false;
    public static final String  DEFAULT_MODE               = "TINT";

    // === Bounds ===
    public static final int MIN_SAMPLE_INTERVAL_MS = 3;
    public static final int MAX_SAMPLE_INTERVAL_MS = 333;
    public static final int MIN_COOLDOWN_MS        = 500;
    public static final int MAX_COOLDOWN_MS        = 30000;

    private final SharedPreferences mPrefs;

    // === Keys ===
    public static final String KEY_WHITEBOMB_ENABLED      = "whitebomb_enabled";
    public static final String KEY_WHITEBOMB_BRIGHTNESS   = "whitebomb_brightness";
    public static final String KEY_WRITE_SETTINGS_TRUSTED = "write_settings_trusted";
    public static final String KEY_PIXEL_SAMPLE_MODE      = "pixel_sample_mode";

    // === Defaults ===
    public static final boolean DEFAULT_WHITEBOMB_ENABLED      = true;
    public static final int     DEFAULT_WHITEBOMB_BRIGHTNESS   = 10;
    public static final boolean DEFAULT_WRITE_SETTINGS_TRUSTED = false;
    public static final String  DEFAULT_PIXEL_SAMPLE_MODE      = "STRIDE_65";

    // === Getters ===
    public boolean isWhitebombEnabled() {
        return mPrefs.getBoolean(KEY_WHITEBOMB_ENABLED, DEFAULT_WHITEBOMB_ENABLED);
    }

    public int getWhitebombBrightness() {
        return clamp(
                mPrefs.getInt(KEY_WHITEBOMB_BRIGHTNESS, DEFAULT_WHITEBOMB_BRIGHTNESS),
                0, 255
        );
    }

    public boolean isWriteSettingsTrusted() {
        return mPrefs.getBoolean(KEY_WRITE_SETTINGS_TRUSTED, DEFAULT_WRITE_SETTINGS_TRUSTED);
    }

    public String getPixelSampleMode() {
        return mPrefs.getString(KEY_PIXEL_SAMPLE_MODE, DEFAULT_PIXEL_SAMPLE_MODE);
    }

    public LumenPrefs(Context ctx) {
        mPrefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public int getSampleIntervalMs() {
        return clamp(
                mPrefs.getInt(KEY_SAMPLE_INTERVAL_MS, DEFAULT_SAMPLE_INTERVAL_MS),
                MIN_SAMPLE_INTERVAL_MS,
                MAX_SAMPLE_INTERVAL_MS
        );
    }

    public int getCooldownMs() {
        return clamp(
                mPrefs.getInt(KEY_COOLDOWN_MS, DEFAULT_COOLDOWN_MS),
                MIN_COOLDOWN_MS,
                MAX_COOLDOWN_MS
        );
    }

    // Stored as int 1-99, returned as float 0.01-0.99
    public float getThreshold() {
        int v = mPrefs.getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD);
        return clamp(v, 1, 99) / 100f;
    }

    public boolean isEnabled() {
        return mPrefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED);
    }

    public void setEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public LumenState.Mode getMode() {
        String name = mPrefs.getString(KEY_MODE, DEFAULT_MODE);
        try {
            return LumenState.Mode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return LumenState.Mode.TINT;
        }
    }

    public void setMode(LumenState.Mode mode) {
        mPrefs.edit().putString(KEY_MODE, mode.name()).apply();
    }

    // ===================
    // Helpers
    // ===================

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
