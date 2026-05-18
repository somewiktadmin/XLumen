package com.xlumen.app;

import android.app.Activity;
import android.os.Bundle;

/**
 * XLumen SettingsActivity
 *
 * Thin wrapper around LumenPreferenceFragment.
 * All real settings logic lives in LumenPreferenceFragment and LumenPrefs.
 *
 * Users reach this from the main activity Settings button,
 * or via long-press on the Quick Settings tile.
 */
public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getFragmentManager()
                .beginTransaction()
                .replace(android.R.id.content, new LumenPreferenceFragment())
                .commit();
    }
}
