package com.xlumen.app;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.view.View;
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
public class MainActivity extends Activity {

    private static final int REQUEST_MEDIA_PROJECTION = 100;

    private MediaProjectionManager mProjMgr;
    private Button   mStartStopBtn;
    private TextView mStatusText;
    private TextView mA11yStatusText;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mProjMgr       = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mStartStopBtn  = findViewById(R.id.btn_start_stop);
        mStatusText    = findViewById(R.id.txt_status);
        mA11yStatusText = findViewById(R.id.txt_a11y_status);

        mStartStopBtn.setOnClickListener(v -> onStartStopClicked());

        findViewById(R.id.btn_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class))
        );
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
        if (LumenState.enabled) {
            stopLumenService();
        } else {
            // MediaProjection permission must be requested from an Activity.
            // The result comes back in onActivityResult.
            startActivityForResult(
                    mProjMgr.createScreenCaptureIntent(),
                    REQUEST_MEDIA_PROJECTION
            );
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_MEDIA_PROJECTION) return;

        if (resultCode != RESULT_OK || data == null) {
            mStatusText.setText("Screen capture permission denied.");
            return;
        }

        startLumenService(resultCode, data);
    }

    private void startLumenService(int resultCode, Intent data) {
        Intent intent = new Intent(this, LumenService.class);
        intent.putExtra(LumenService.Extras.RESULT_CODE, resultCode);
        intent.putExtra(LumenService.Extras.PROJECTION_DATA, data);
        startForegroundService(intent);

        LumenState.enabled = true;
        mStatusText.setText("XLumen running.");
        mStartStopBtn.setText("Stop");
    }

    private void stopLumenService() {
        stopService(new Intent(this, LumenService.class));
        LumenState.enabled = false;
        mStatusText.setText("XLumen stopped.");
        mStartStopBtn.setText("Start");
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
            mA11yStatusText.setText("Accessibility service: active");
        } else {
            mA11yStatusText.setText(
                    "Accessibility service not enabled.\n" +
                            "Go to Settings > Accessibility > XLumen and turn it on."
            );
        }
    }
}
