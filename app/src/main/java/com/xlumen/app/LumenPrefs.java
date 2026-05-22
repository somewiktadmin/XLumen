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
 *
 * EditTextPreference stores all values as strings internally, regardless
 * of inputType.  All integer getters therefore read via getString() and
 * parse manually.  getBoolean() and getString() are unaffected.
 */
public class LumenPrefs {

    //private static final String PREFS_NAME = "xlumen_prefs";
    private static final String PREFS_NAME = "com.xlumen.app_preferences";

    // =========================================================================
    // Keys - must match preferences.xml exactly
    // =========================================================================

    public static final String KEY_SAMPLE_INTERVAL_MS      = "sample_interval_ms";
    public static final String KEY_COOLDOWN_MS             = "cooldown_ms";
    public static final String KEY_THRESHOLD               = "threshold";
    public static final String KEY_ENABLED                 = "enabled";
    public static final String KEY_MODE                    = "mode";
    public static final String KEY_FLASHGUARD_ENABLED      = "flashguard_enabled";
    public static final String KEY_FLASHGUARD_BRIGHTNESS   = "flashguard_brightness";
    public static final String KEY_WRITE_SETTINGS_TRUSTED  = "write_settings_trusted";
    public static final String KEY_PIXEL_SAMPLE_MODE       = "pixel_sample_mode";

    // =========================================================================
    // Defaults
    // =========================================================================

    public static final int     DEFAULT_SAMPLE_INTERVAL_MS   = 100;
    public static final int     DEFAULT_COOLDOWN_MS          = 3000;
    public static final int     DEFAULT_THRESHOLD            = 10;
    public static final boolean DEFAULT_ENABLED              = false;
    public static final String  DEFAULT_MODE                 = "TINT";
    public static final boolean DEFAULT_FLASHGUARD_ENABLED   = true;
    public static final int     DEFAULT_FLASHGUARD_BRIGHTNESS = 10;
    public static final boolean DEFAULT_WRITE_SETTINGS_TRUSTED = false;
    public static final String  DEFAULT_PIXEL_SAMPLE_MODE    = "STRIDE_65";

    // =========================================================================
    // Bounds
    // =========================================================================

    public static final int MIN_SAMPLE_INTERVAL_MS = 3; //pretty pointless, where thrashing begins
    public static final int MAX_SAMPLE_INTERVAL_MS = 9999; //testing only = 999, norm is 333
    public static final int MIN_COOLDOWN_MS        = 500;
    public static final int MAX_COOLDOWN_MS        = 30000;

    // =========================================================================

    private final SharedPreferences mPrefs;

    /**
     * @param ctx  any context; uses app-private shared prefs
     */
    public LumenPrefs(Context ctx) {
        mPrefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // =========================================================================
    // Getters
    // =========================================================================

    /**
     * How often LumenService grabs and processes a screen frame.
     * Stored as string by EditTextPreference; parsed here.
     *
     * @return milliseconds, clamped to [MIN_SAMPLE_INTERVAL_MS, MAX_SAMPLE_INTERVAL_MS]
     */
    public int getSampleIntervalMs() {
        return clamp(
                parseInt(KEY_SAMPLE_INTERVAL_MS, DEFAULT_SAMPLE_INTERVAL_MS),
                MIN_SAMPLE_INTERVAL_MS,
                MAX_SAMPLE_INTERVAL_MS
        );
    }

    /**
     * Minimum time between flash guard response triggers.
     * Stored as string by EditTextPreference; parsed here.
     *
     * @return milliseconds, clamped to [MIN_COOLDOWN_MS, MAX_COOLDOWN_MS]
     */
    public int getCooldownMs() {
        return clamp(
                parseInt(KEY_COOLDOWN_MS, DEFAULT_COOLDOWN_MS),
                MIN_COOLDOWN_MS,
                MAX_COOLDOWN_MS
        );
    }

    /**
     * Fraction of sampled pixels that must be near-white to trigger flash guard.
     * Stored as string 1-99 by EditTextPreference; returned as float 0.01-0.99.
     *
     * @return float in range [0.01, 0.99]
     */
    public float getThreshold() {
        int v = parseInt(KEY_THRESHOLD, DEFAULT_THRESHOLD);
        return clamp(v, 1, 99) / 100f;
    }

    /**
     * Master XLumen enabled state.  Written by MainActivity and TileService.
     * Uses putBoolean/getBoolean directly - not an EditTextPreference.
     *
     * @return true if XLumen overlay is active
     */
    public boolean isEnabled() {
        return mPrefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED);
    }

    /**
     * @param enabled  true to activate XLumen overlay
     */
    public void setEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /**
     * Current overlay mode.  Stored as enum name string.
     * Falls back to TINT on any parse error.
     *
     * @return current Mode, never null
     */
    public LumenState.Mode getMode() {
        String name = mPrefs.getString(KEY_MODE, DEFAULT_MODE);
        try {
            return LumenState.Mode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return LumenState.Mode.GRADIENT;
        }
    }

    /**
     * @param mode  mode to persist
     */
    public void setMode(LumenState.Mode mode) {
        mPrefs.edit().putString(KEY_MODE, mode.name()).apply();
    }

    /**
     * Whether flash guard detection and response is active.
     * CheckBoxPreference - stored as boolean directly.
     *
     * @return true if flash guard protection is enabled
     */
    public boolean isFlashGuardEnabled() {
        return mPrefs.getBoolean(KEY_FLASHGUARD_ENABLED, DEFAULT_FLASHGUARD_ENABLED);
    }

    /**
     * System SCREEN_BRIGHTNESS value slammed on flash guard trigger.
     * Stored as string by EditTextPreference; parsed here.
     *
     * @return brightness floor, clamped to [0, 255]
     */
    public int getFlashGuardBrightness() {
        return clamp(
                parseInt(KEY_FLASHGUARD_BRIGHTNESS, DEFAULT_FLASHGUARD_BRIGHTNESS),
                0, 255
        );
    }

    /**
     * Whether the user has granted WRITE_SETTINGS and trusts XLumen to
     * hammer system brightness on flash guard trigger.
     * CheckBoxPreference - stored as boolean directly.
     *
     * @return true if brightness hammering is permitted
     */
    public boolean isWriteSettingsTrusted() {
        return mPrefs.getBoolean(KEY_WRITE_SETTINGS_TRUSTED, DEFAULT_WRITE_SETTINGS_TRUSTED);
    }

    /**
     * Pixel sampling strategy used by processFrame().
     * ListPreference - stored as string directly.
     *
     * @return "STRIDE_65" or "FULL"
     */
    public String getPixelSampleMode() {
        return mPrefs.getString(KEY_PIXEL_SAMPLE_MODE, DEFAULT_PIXEL_SAMPLE_MODE);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Reads a SharedPreferences key as string and parses to int.
     * EditTextPreference always stores as string regardless of inputType.
     * Returns defaultValue on missing key or parse failure.
     *
     * @param key           SharedPreferences key
     * @param defaultValue  returned on missing or unparseable value
     * @return parsed int, or defaultValue
     */
    private int parseInt(String key, int defaultValue) {
        String s = mPrefs.getString(key, null);
        if (s == null) return defaultValue;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Clamps v to [lo, hi] inclusive.
     *
     * @param v   value to clamp
     * @param lo  lower bound
     * @param hi  upper bound
     * @return clamped value
     */
    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}