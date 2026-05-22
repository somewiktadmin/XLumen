package com.xlumen.app;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

/**
 * XLumen SettingsActivity
 *
 * Thin wrapper around LumenPreferenceFragment.
 * All real settings logic lives in LumenPreferenceFragment and LumenPrefs.
 *
 * Users reach this from the main activity Settings button,
 * or via long-press on the Quick Settings tile.
 *
 * Extends AppCompatActivity (not plain Activity) to support
 * PreferenceFragmentCompat.
 */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getSupportFragmentManager()
                .beginTransaction()
                .replace(android.R.id.content, new LumenPreferenceFragment())
                .commit();
    }
}
