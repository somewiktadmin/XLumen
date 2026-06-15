package com.xlumen.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.View;

/**
 * XLumen LumenAccessibilityService
 *
 * Responsibilities:
 *   1. Draw a full-screen touch-passthrough overlay
 *   2. Update overlay color and opacity from LumenState
 *
 * This service must be enabled manually by the user in:
 *   Settings > Accessibility > XLumen
 *
 * It cannot be enabled programmatically. This is intentional by Google.
 */
public class LumenAccessibilityService extends AccessibilityService {

    private static LumenAccessibilityService sInstance = null;

    private WindowManager mWindowManager;
    private View mOverlayView;
    private WindowManager.LayoutParams mLayoutParams;

    private static final int UPDATE_INTERVAL_MS = 100;
    private final android.os.Handler mHandler = new android.os.Handler();

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onServiceConnected() {
        sInstance = this;

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes  = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        setServiceInfo(info);

        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createOverlay();
        scheduleUpdate();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not needed — XLumen drives itself from LumenState, not UI events
    }

    @Override
    public void onInterrupt() {
        // Required override
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        sInstance = null;
        removeOverlay();
        return super.onUnbind(intent);
    }

    // -------------------------------------------------------------------------
    // Overlay
    // -------------------------------------------------------------------------

    private void createOverlay() {
        mOverlayView = new View(this);

        mLayoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );

        mWindowManager.addView(mOverlayView, mLayoutParams);
        applyOverlayState();
    }

    private void removeOverlay() {
        if (mOverlayView != null && mWindowManager != null) {
            mWindowManager.removeView(mOverlayView);
            mOverlayView = null;
        }
    }

    /**
     * Reads LumenState and applies current opacity and color to the overlay.
     * Called on a timer — not event driven.
     *
     * Color logic:
     *   redBias 0.0 = pure neutral dark (ARGB: alpha, 0, 0, 0)
     *   redBias 1.0 = full red tint     (ARGB: alpha, 255, 0, 0)
     *   Values between = interpolated
     *
     // The “68–95–99.7 rule”    68.27, 95.45, 99.74
     //TODO: This service is malfunctioning.  (Settings accessibility page)
     //Does my screen-reading read the value before or after overlay is applied?
     //TODO: V3,0 phone-in-pocket, intercept all taps, no-butt-dial
     */
    private void applyOverlayState() {
        if (mOverlayView == null) return;

        if (!LumenState.enabled) {
            mOverlayView.setBackgroundColor(Color.argb(0, 0, 0, 0));
            return;
        }

        float opacity = Math.min(0.75f, Math.max(0.05f, LumenState.overlayOpacity));

        /* If system set backlight mostly up, we're at the beach, so don't slam as hard. */
        if (LumenState.sysBrightness > 127) {
            opacity *= 0.5f;
        }

        /* Floor value cannot be triffled with.  Permanent user reminder. */
        if (opacity <= 0.05f) {
            opacity = 0.05f;
        }

        int alpha = (int)(opacity * 255);

        if (LumenState.invertEnabled) {
            mOverlayView.setBackgroundColor(Color.argb(alpha, 255, 255, 255));
        } else {
            mOverlayView.setBackgroundColor(Color.argb(alpha, 0, 0, 0));
        }
    }


    // -------------------------------------------------------------------------
    // Update loop
    // -------------------------------------------------------------------------

    private void scheduleUpdate() {
        mHandler.postDelayed(() -> {
            applyOverlayState();
            scheduleUpdate();
        }, UPDATE_INTERVAL_MS);
    }

    // -------------------------------------------------------------------------
    // Static accessor
    // -------------------------------------------------------------------------

    public static boolean isConnected() {
        return sInstance != null;
    }
}
