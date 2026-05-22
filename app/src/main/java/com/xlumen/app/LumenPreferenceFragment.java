package com.xlumen.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceFragment;
import android.provider.Settings;

/**
 * XLumen LumenPreferenceFragment
 *
 * Displays user-configurable settings via a standard Preferences screen.
 * Reads layout from res/xml/preferences.xml.
 *
 * Settings reload automatically - LumenService re-reads LumenPrefs each
 * sample cycle, so no service restart is needed after changes.
 *
 * Keys here must match LumenPrefs constants exactly.
 */
public class LumenPreferenceFragment extends PreferenceFragment
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);
        updateSummaries();
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceScreen().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(this);
        updateSummaries();
    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceScreen().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    /**
     * Fired whenever any preference value changes.
     * Refreshes all summaries and handles the WRITE_SETTINGS trust toggle
     * side effect if that specific key changed.
     *
     * @param prefs  the SharedPreferences that changed
     * @param key    the key that changed
     */
    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        android.util.Log.d("XLumen", "pref changed key=" + key
                + " file=" + prefs.toString()
                + " value=" + prefs.getString(key, "NOT_FOUND"));
        updateSummaries();
        if (LumenPrefs.KEY_WRITE_SETTINGS_TRUSTED.equals(key)) {
            handleWriteSettingsToggle(prefs.getBoolean(key, false));
        }
    }

    /**
     * Refreshes all preference summary lines so the user sees current values
     * without opening each preference individually.
     *
     * Called on onCreate, onResume, and every preference change.
     */
    private void updateSummaries() {
        LumenPrefs p = new LumenPrefs(getActivity());

        setSummary(LumenPrefs.KEY_SAMPLE_INTERVAL_MS,
                p.getSampleIntervalMs() + " ms  (~" +
                        Math.round(1000f / p.getSampleIntervalMs()) + " fps)");

        setSummary(LumenPrefs.KEY_COOLDOWN_MS,
                (p.getCooldownMs() / 1000f) + " s cooldown between triggers");

        setSummary(LumenPrefs.KEY_THRESHOLD,
                Math.round(p.getThreshold() * 100) + "% near-white pixels to trigger");

        setSummary(LumenPrefs.KEY_FLASHGUARD_BRIGHTNESS,
                "Brightness floor: " + p.getFlashGuardBrightness() + " / 255");

        boolean canWrite = Settings.System.canWrite(getActivity());
        setSummary(LumenPrefs.KEY_WRITE_SETTINGS_TRUSTED,
                canWrite
                        ? "Permission granted.  Brightness control active."
                        : "Permission NOT granted.  Tap to request.");
    }

    /**
     * Called when the WRITE_SETTINGS trust toggle is flipped on.
     * If the system permission is not yet granted, fires the system
     * settings intent so the user can grant it immediately.
     * No-op if permission is already granted or toggle was turned off.
     *
     * @param requested  true if the user just enabled the toggle
     */
    private void handleWriteSettingsToggle(boolean requested) {
        XLumenLog.debug("handleWriteSettingsToggle: requested=" + requested);
        if (!requested) return;
        if (Settings.System.canWrite(getActivity())) return;

        Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
        intent.setData(Uri.parse("package:" + getActivity().getPackageName()));
        startActivity(intent);

        XLumenLog.debug("handleWriteSettingsToggle: intent=" + intent);
    }

    /**
     * Sets the summary text for a preference by key.
     * Silently no-ops if the key does not exist in the current preference screen.
     *
     * @param key      preference key
     * @param summary  summary text to display
     */
    private void setSummary(String key, String summary) {
        if (findPreference(key) != null) {
            findPreference(key).setSummary(summary);
        }
    }
}