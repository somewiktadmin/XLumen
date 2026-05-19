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
 * Responsibilities:
 *   1. Request MediaProjection permission (must come from an Activity)
 *   2. Start/stop LumenService
 *   3. Show accessibility service status and instructions
 *   4. Launch SettingsActivity
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

    private TextView mDebugText;

    private androidx.activity.result.ActivityResultLauncher<Intent> mProjectionLauncher;

    private void debug(String msg) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter(
                    getFilesDir() + "/xlumen_debug.txt", true);
            fw.write(System.currentTimeMillis() + "  " + msg + "\n");
            fw.close();
        } catch (Exception e) {
            mDebugText.append( msg + "\n" + e.toString() );
            //sleep(999);
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
    // Accessibility service status
    // -------------------------------------------------------------------------

    /**
     * Shows whether LumenAccessibilityService is connected.
     * The user must enable it manually in Settings > Accessibility > XLumen.
     * We cannot enable it programmatically -- this is intentional by Google.
     */
    private void updateA11yStatus() {
        if (LumenAccessibilityService.isConnected()) {
            mA11yStatusText.append("Accessibility service: active");
        } else {
            mA11yStatusText.append(
                    "Accessibility service not enabled.\n" +
                            "Go to Settings > Accessibility > XLumen and turn it on."
            );
        }
    }
}
