package com.xlumen.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

import androidx.preference.CheckBoxPreference;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

/**
 * XLumen LumenPreferenceFragment
 *
 * Displays user-configurable settings via a standard Preferences screen.
 * Reads layout from res/xml/preferences.xml.
 *
 * Extends PreferenceFragmentCompat (androidx) replacing the deprecated
 * PreferenceFragment.  Requires AppCompatActivity as host - see SettingsActivity.
 *
 * Settings reload automatically - LumenService re-reads LumenPrefs each
 * sample cycle, so no service restart is needed after changes.
 *
 * Keys here must match LumenPrefs constants exactly.
 */
public class LumenPreferenceFragment extends PreferenceFragmentCompat
        implements androidx.preference.Preference.OnPreferenceChangeListener {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);
        updateSummaries();
        wireListeners();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateSummaries();
    }

    /**
     * Wires OnPreferenceChangeListener to all preferences so summaries
     * update immediately when values change.
     */
    private void wireListeners() {
        PreferenceScreen screen = getPreferenceScreen();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            screen.getPreference(i).setOnPreferenceChangeListener(this);
        }
    }

    /**
     * Called when any preference value changes.
     * Refreshes summaries and handles WRITE_SETTINGS trust toggle side effect.
     *
     * @param preference  the preference that changed
     * @param newValue    the new value
     * @return true to persist the new value
     */
    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        XLumenLog.debug("onPreferenceChange: key=" + preference.getKey()
                + " value=" + newValue);
        updateSummaries();
        if (LumenPrefs.KEY_WRITE_SETTINGS_TRUSTED.equals(preference.getKey())) {
            handleWriteSettingsToggle((Boolean) newValue);
        }
        return true;
    }

    /**
     * Refreshes all preference summary lines so the user sees current values
     * without opening each preference individually.
     *
     * Called on onCreatePreferences, onResume, and every preference change.
     */
    private void updateSummaries() {
        LumenPrefs p = new LumenPrefs(requireActivity());

        setSummary(LumenPrefs.KEY_SAMPLE_INTERVAL_MS,
                p.getSampleIntervalMs() + " ms  (~" +
                        Math.round(1000f / p.getSampleIntervalMs()) + " fps)");

        setSummary(LumenPrefs.KEY_COOLDOWN_MS,
                (p.getCooldownMs() / 1000f) + " s cooldown between triggers");

        setSummary(LumenPrefs.KEY_THRESHOLD,
                Math.round(p.getThreshold() * 100) + "% near-white pixels to trigger");

        setSummary(LumenPrefs.KEY_FLASHGUARD_BRIGHTNESS,
                "Brightness floor: " + p.getFlashGuardBrightness() + " / 255");

        boolean canWrite = Settings.System.canWrite(requireActivity());
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
        if (Settings.System.canWrite(requireActivity())) return;

        Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
        intent.setData(Uri.parse("package:" + requireActivity().getPackageName()));
        requireActivity().startActivity(intent);
    }

    /**
     * Sets the summary text for a preference by key.
     * Silently no-ops if the key does not exist in the current preference screen.
     *
     * @param key      preference key
     * @param summary  summary text to display
     */
    private void setSummary(String key, String summary) {
        Preference pref = findPreference(key);
        if (pref != null) {
            pref.setSummary(summary);
        }
    }
}