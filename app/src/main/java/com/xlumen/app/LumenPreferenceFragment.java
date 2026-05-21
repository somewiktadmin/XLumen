package com.xlumen.app;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceFragment;

/**
 * XLumen LumenPreferenceFragment
 *
 * Displays user-configurable settings via a standard Preferences screen.
 * Reads layout from res/xml/preferences.xml.
 *
 * Settings reload automatically -- LumenService re-reads LumenPrefs each
 * sample cycle, so no service restart is needed after changes.
 *
 * Keys here must match LumenPrefs constants exactly.
 */
public class LumenPreferenceFragment extends PreferenceFragment
        implements SharedPreferences.OnSharedPreferenceChangeListener {


    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        updateSummaries();
        if (LumenPrefs.KEY_WRITE_SETTINGS_TRUSTED.equals(key)) {
            handleWriteSettingsToggle(prefs.getBoolean(key, false));
        }
    }

    private void updateSummaries() {
        LumenPrefs p = new LumenPrefs(getActivity());

        setSummary(LumenPrefs.KEY_SAMPLE_INTERVAL_MS,
                p.getSampleIntervalMs() + " ms  (~" +
                        Math.round(1000f / p.getSampleIntervalMs()) + " fps)");

        setSummary(LumenPrefs.KEY_COOLDOWN_MS,
                (p.getCooldownMs() / 1000f) + " s cooldown");

        setSummary(LumenPrefs.KEY_THRESHOLD,
                Math.round(p.getThreshold() * 100) + "% near-white pixel trigger");

        setSummary(LumenPrefs.KEY_WHITEBOMB_BRIGHTNESS,
                "Brightness floor: " + p.getWhitebombBrightness() + " / 255");

        boolean canWrite = android.provider.Settings.System.canWrite(getActivity());
        setSummary(LumenPrefs.KEY_WRITE_SETTINGS_TRUSTED,
                canWrite
                        ? "Permission granted.  Brightness control active."
                        : "Permission NOT granted.  Tap to request.");
    }

    private void handleWriteSettingsToggle(boolean requested) {
        if (!requested) return;
        if (android.provider.Settings.System.canWrite(getActivity())) return;

        // User flipped the toggle on but permission not yet granted.
        // Send them to the system grant screen.
        android.content.Intent intent = new android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS);
        intent.setData(android.net.Uri.parse(
                "package:" + getActivity().getPackageName()));
        startActivity(intent);
    }

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
    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceScreen().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    /*
    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        updateSummaries();
    }*/

    /**
     * Keep summaries current so the user sees actual values
     * without opening each preference individually.
     */
    /*
    private void updateSummaries() {
        LumenPrefs p = new LumenPrefs(getActivity());

        setSummary(LumenPrefs.KEY_SAMPLE_INTERVAL_MS,
                p.getSampleIntervalMs() + " ms  (~" +
                        Math.round(1000f / p.getSampleIntervalMs()) + " fps)");

        setSummary(LumenPrefs.KEY_COOLDOWN_MS,
                (p.getCooldownMs() / 1000f) + " s minimum between flips");

        setSummary(LumenPrefs.KEY_THRESHOLD,
                Math.round(p.getThreshold() * 100) + "% of full-white luminance");
    }*/

    private void setSummary(String key, String summary) {
        if (findPreference(key) != null) {
            findPreference(key).setSummary(summary);
        }
    }
}
