package com.xlumen.app;

import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

/**
 * XLumen MainActivity
 *
 * Minimal UI.  Most users will never open this after initial setup.
 * Real interaction happens via the Quick Settings tile.
 *
 * Terminology used throughout this file:
 *   a11y / A11y  :=  Accessibility  (numeronym: a + 11 letters + y)
 *
 * Responsibilities:
 *   1. Request MediaProjection permission (must come from an Activity)
 *   2. Start/stop LumenService
 *   3. Show a11y service status and instructions
 *   4. Launch SettingsActivity
 *
 * Startup sequence enforced by this activity:
 *   1. A11y service must be enabled by user in Settings > Accessibility > XLumen
 *   2. Start button becomes active only after a11y service confirms connected
 *   3. MediaProjection permission requested only after user taps Start
 *   4. LumenService starts only after MediaProjection is granted
 *
 * This sequence is enforced by Google on every Android version differently.
 * Do not reorder steps.  Do not skip steps.  Do not try to be clever.
 *
 * Note: MediaProjection consent dialog re-appears after every reboot
 * on Android 10+.  This is intentional by Google and cannot be suppressed.
 */

//public class MainActivity extends Activity {
public class MainActivity extends androidx.appcompat.app.AppCompatActivity {

    private static final int REQUEST_MEDIA_PROJECTION = 100;

    private MediaProjectionManager mProjMgr;
    private Button   mStartStopBtn;
    private TextView mStatusText;
    private TextView mA11yStatusText;

    private Button mOpenA11ySettingsBtn;
    private Button mOpenA11yXLumenBtn;
    private TextView mA11yWarningText;

    private TextView mDebugText;

    private androidx.activity.result.ActivityResultLauncher<Intent> mProjectionLauncher;


    private void debug(String msg) {
        try {
            java.text.SimpleDateFormat sdf =
                    new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",
                            java.util.Locale.US);
            String timestamp = sdf.format(new java.util.Date());

            java.io.FileWriter fw = new java.io.FileWriter(
                    getFilesDir() + "/xlumen_debug.txt", true);
            fw.write(timestamp + "  " + msg + "\n");
            fw.close();
        } catch (Exception e) {
            // Cannot write to debug file -- show error inline instead.
            // This message survives only until the next setText/append call.
            // If the file write is failing consistently, this is the only place
            // the error will ever be visible -- check it on the NEXT session
            // before tapping "Read Debug Log" which clears the file.
            if (mDebugText != null) {
                mDebugText.append(msg + "\n" + e.toString() + "\n");
                //Thread.sleep(999);
            }
        }
    }


    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mProjMgr        = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mStartStopBtn   = findViewById(R.id.btn_start_stop);
        mStatusText     = findViewById(R.id.txt_status);
        mA11yStatusText = findViewById(R.id.txt_a11y_status);
        mDebugText      = findViewById(R.id.txt_debug);

        mOpenA11ySettingsBtn = findViewById(R.id.btn_open_a11y_settings);
        mOpenA11yXLumenBtn   = findViewById(R.id.btn_open_a11y_xlumen);
        mA11yWarningText     = findViewById(R.id.txt_a11y_warning);

        // Open top-level accessibility settings.
        // Safest cross-version approach -- user finds XLumen in the list manually.
        mOpenA11ySettingsBtn.setOnClickListener(v ->
                startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        );

        // Attempt to open XLumen-specific a11y page directly.
        // Undocumented deep link -- Google has broken this in some Android versions.
        // Falls back to top-level settings if deep link fails.
        mOpenA11yXLumenBtn.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                intent.putExtra(":settings:fragment_args_key",
                        "com.xlumen.app/.LumenAccessibilityService");
                startActivity(intent);
            } catch (Exception e) {
                // Deep link failed -- fall back to top-level settings silently.
                // User will need to find XLumen in the list manually.
                debug("A11y deep link failed, falling back to top-level settings: "
                        + e.getMessage());
                startActivity(
                        new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });

        //mStartStopBtn.setEnabled(false);

        debug("MainAct onCreate OK");

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(
                    new String[]{"android.permission.POST_NOTIFICATIONS"},
                    0
            );
            debug("Notification permission requested");
        }

        mProjectionLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (mDebugText != null) debug("Launcher result received");
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                        if (mDebugText != null) debug("MediaProjection denied");
                        return;
                    }
                    if (mDebugText != null) debug("Granted, starting service...");
                    try {
                        startLumenService(result.getResultCode(), result.getData());
                        if (mDebugText != null) debug("Service started OK");
                    } catch (Exception e) {
                        if (mDebugText != null) debug("CRASH: " + e.getMessage());
                    }
                }
        );

        mStartStopBtn.setOnClickListener(v -> onStartStopClicked());

        findViewById(R.id.btn_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class))
        );

        findViewById(R.id.btn_read_debug).setOnClickListener(v -> {
            try {
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.FileReader(getFilesDir() + "/xlumen_debug.txt"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append("\n");
                br.close();
                mDebugText.setText(sb.toString());
            } catch (Exception e) {
                mDebugText.setText("No debug log found");
            }

            // Reset debug log on each fresh display dump, persist through next crash
            try {
                new java.io.FileWriter(getFilesDir() + "/xlumen_debug.txt", false).close();
            } catch (Exception ignored) { }
            mDebugText.setText("MainAct read debug button OK");

        });

    }

    @Override
    protected void onResume() {
        super.onResume();
        updateA11yStatus();
    }

    // -------------------------------------------------------------------------
    // Start / stop
    // -------------------------------------------------------------------------

    private void onStartStopClicked() {
        debug("Start clicked");
        if (LumenState.enabled) {
            debug("Stopping...");
            stopLumenService();
        } else {
            debug("Requesting MediaProjection...");
            try {
                mProjectionLauncher.launch(mProjMgr.createScreenCaptureIntent());

                /*bstartActivityForResult(
                        mProjMgr.createScreenCaptureIntent(),
                        REQUEST_MEDIA_PROJECTION
                ); */

                debug("MediaProjection dialog launched");
            } catch (Exception e) {
                debug("CRASH: " + e.getMessage());
            }
        }
    }

    /*
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (mDebugText != null) debug("onActivityResult fired");
        if (requestCode != REQUEST_MEDIA_PROJECTION) return;
        if (resultCode != RESULT_OK || data == null) {
            if (mDebugText != null) debug("MediaProjection denied");
            return;
        }
        if (mDebugText != null) debug("Granted, starting service...");
        try {
            startLumenService(resultCode, data);
            if (mDebugText != null) debug("Service started OK");
        } catch (Exception e) {
            if (mDebugText != null) debug("CRASH: " + e.getMessage());
        }
    }
    */

    private void startLumenService(int resultCode, Intent data) {
        Intent intent = new Intent(this, LumenService.class);
        intent.putExtra(LumenService.Extras.RESULT_CODE, resultCode);
        intent.putExtra(LumenService.Extras.PROJECTION_DATA, data);
        startForegroundService(intent);

        LumenState.enabled = true;
        mStatusText.append("XLumen running.");
        mStartStopBtn.append("Stop");
    }

    private void stopLumenService() {
        stopService(new Intent(this, LumenService.class));
        LumenState.enabled = false;
        mStatusText.append("XLumen stopped.");
        mStartStopBtn.append("Start");
    }

    // -------------------------------------------------------------------------
    // A11y service status and UI gating
    // -------------------------------------------------------------------------

    /**
     * Checks whether LumenAccessibilityService is connected and updates UI accordingly.
     * The user must enable it manually in Settings > Accessibility > XLumen.
     * We cannot enable it programmatically -- this is intentional by Google.
     *
     * Side effect: enables or disables Start button based on a11y service state.
     * Start button must never be active before a11y service is confirmed connected.
     * See startup sequence in class javadoc above.
     */

    private void updateA11yStatus() {
        if (LumenAccessibilityService.isConnected()) {
            // A11y connected -- hide setup buttons, show MediaProjection pre-warning.
            mA11yStatusText.setText("Accessibility service: active");
            mA11yWarningText.setVisibility(android.view.View.VISIBLE);
            mA11yWarningText.setText(
                    "ABOUT THE NEXT WARNING\n\n" +
                            "Android is about to tell you XLumen can see everything\n" +
                            "on your screen.  That warning is completely accurate.\n\n" +
                            "What XLumen actually does with that access:\n" +
                            "  - Measures average pixel brightness 10 times per second\n" +
                            "  - Uses that number to set overlay opacity\n" +
                            "  - Discards the data immediately after\n\n" +
                            "What XLumen does NOT do:\n" +
                            "  - Does not record or store screen contents\n" +
                            "  - Does not take screenshots\n" +
                            "  - Does not transmit anything to anyone\n" +
                            "  - Does not keep any history of what was on your screen\n\n" +
                            "You have no way to verify this except to read the source\n" +
                            "code yourself.  It is public at github.com/somewiktadmin/XLumen\n\n" +
                            "Tap Start when ready.  Tap \"Start now\" in the next dialog.\n" +
                            "This dialog returns after every reboot.  That is Google policy."
            );
            mOpenA11ySettingsBtn.setVisibility(android.view.View.GONE);
            mOpenA11yXLumenBtn.setVisibility(android.view.View.GONE);
            mStartStopBtn.setEnabled(true);
            mStartStopBtn.setText("Start");
        } else {
            // A11y not connected -- show setup instructions, disable Start button.
            mA11yStatusText.setText(
                    "Step 1: Tap below to open Accessibility Settings.\n" +
                            "Find XLumen in the list and turn it on.\n" +
                            "Then return here - Start will become available."
            );
            mA11yWarningText.setVisibility(android.view.View.VISIBLE);
            mA11yWarningText.setText(
                    "IMPORTANT: When enabling XLumen in Accessibility Settings,\n" +
                            "Android will show warnings stating this app can observe your screen.\n" +
                            "These warnings are correct and expected.  XLumen reads screen brightness\n" +
                            "to protect your eyes.  It does not record, store, or transmit anything.\n" +
                            "Google warnings will likely become more alarming in future Android versions.\n" +
                            "This is intentional on Google's part.  You have been warned."
            );
            mOpenA11ySettingsBtn.setVisibility(android.view.View.VISIBLE);
            mOpenA11yXLumenBtn.setVisibility(android.view.View.VISIBLE);
            mStartStopBtn.setEnabled(false);
            mStartStopBtn.setText("Enable Accessibility first (see above)");
        }
    }
    
    /*
    private void updateA11yStatus() {
        if (LumenAccessibilityService.isConnected()) {
            mA11yStatusText.setText("Accessibility service: active");
            // A11y connected -- hide setup instructions, enable Start button.
            mA11yWarningText.setVisibility(android.view.View.GONE);
            mOpenA11ySettingsBtn.setVisibility(android.view.View.GONE);
            mOpenA11yXLumenBtn.setVisibility(android.view.View.GONE);
            mStartStopBtn.setEnabled(true);
            mStartStopBtn.setText("Start");
        } else {
            mA11yStatusText.setText(
                    "Step 1: Tap below to open Accessibility Settings.\n" +
                            "Find XLumen in the list and turn it on.\n" +
                            "Then return here - Start will become available."
            );
            // A11y not connected -- show setup instructions, disable Start button.
            mA11yWarningText.setVisibility(android.view.View.VISIBLE);
            mOpenA11ySettingsBtn.setVisibility(android.view.View.VISIBLE);
            mOpenA11yXLumenBtn.setVisibility(android.view.View.VISIBLE);
            mStartStopBtn.setEnabled(false);
            mStartStopBtn.setText("Enable Accessibility first (see above)");
        }
    }


    private void updateA11yStatus() {
        if (LumenAccessibilityService.isConnected()) {
            mA11yStatusText.setText("Accessibility service: active");
            // Prerequisite met -- Start button now available
            mStartStopBtn.setEnabled(true);
            mStartStopBtn.setText("Start");
        } else {
            mA11yStatusText.setText(
                    "Step 1: Go to Settings > Accessibility > XLumen and turn it on.\n" +
                            "Then return here and tap Start.");
            // Prerequisite not met -- Start button locked until a11y service connects.
            // Button text explains why it is disabled, not just silently greyed out.
            mStartStopBtn.setEnabled(false);
            mStartStopBtn.setText("Enable Accessibility first (see above)");
        }
    }*/
}
