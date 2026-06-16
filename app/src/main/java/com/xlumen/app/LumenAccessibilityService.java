package com.xlumen.app;

import static com.xlumen.app.LumenState.flashGuardActive;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.text.TextPaint;
import android.util.Log;
import android.util.TypedValue;
import android.view.WindowManager;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;

import androidx.core.app.NotificationCompat;

/**
 * XLumen LumenAccessibilityService
 *
 * Responsibilities:
 *   1. Draw a full-screen touch-passthrough overlay
 *   2. Update overlay color and opacity from LumenState
 *   3. Paint watermark text along right margin, top-to-bottom
 *
 * This service must be enabled manually by the user in:
 *   Settings > Accessibility > XLumen
 *
 * It cannot be enabled programmatically.  This is intentional by Google.
 */
public class LumenAccessibilityService extends AccessibilityService {

    private static LumenAccessibilityService sInstance = null;

    private WindowManager mWindowManager;
    private OverlayView   mOverlayView;
    private WindowManager.LayoutParams mLayoutParams;

    private static final int UPDATE_INTERVAL_MS = 100;
    private final android.os.Handler mHandler = new android.os.Handler();

    // -------------------------------------------------------------------------
    // Custom overlay view - dim layer + watermark text in one onDraw()
    // -------------------------------------------------------------------------

    /**
     * Full-screen overlay view.
     *
     * onDraw() paints two things:
     *   1. Solid dim rectangle (replaces setBackgroundColor).
     *   2. Watermark text along right margin, rotated 90 degrees clockwise
     *      (top-to-bottom English reading direction).
     *
     * Text: "XLumen LumiGuard V1.0"
     * Color: #aaaaaa with 1px drop shadow at +1,+1 in #606060.
     * Size:  10sp.
     */
    private static class OverlayView extends View {

        private final Paint mDimPaint  = new Paint();
        private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private int   mDimAlpha   = 0;
        private int   mDimR       = 0;
        private int   mDimG       = 0;
        private int   mDimB       = 0;
        private boolean mWatermark = true;

        private static final String WATERMARK_TEXT = "XLumen LumiGuard V1.0";

        public OverlayView(Context context) {
            super(context);

            float sp10 = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 10f,
                    context.getResources().getDisplayMetrics());

            mTextPaint.setTextSize(sp10);
            mTextPaint.setColor(Color.parseColor("#BFBFBF"));
            mTextPaint.setShadowLayer(2f, 1f, 1f, Color.parseColor("#4F4F4F"));
        }

        /** Called from applyOverlayState() each update cycle. */
        public void setDim(int alpha, int r, int g, int b) {
            mDimAlpha = alpha;
            mDimR     = r;
            mDimG     = g;
            mDimB     = b;
            invalidate();
        }

        public void setWatermarkVisible(boolean visible) {
            mWatermark = visible;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!LumenState.enabled) return;

            /* 1. Dim layer */
            mDimPaint.setColor(Color.argb(mDimAlpha, mDimR, mDimG, mDimB));
            canvas.drawRect(0, 0, getWidth(), getHeight(), mDimPaint);

            /* 2. Watermark - right margin, top to bottom */
            if (!mWatermark) return;

            float textWidth = mTextPaint.measureText(WATERMARK_TEXT);
            float descent   = mTextPaint.descent();
            float sp10      = mTextPaint.getTextScaleX();

            /* Rotate canvas 90 deg clockwise around right-margin anchor point.
             * After rotation, x=0 is the top of the screen, y=0 is the right edge.
             * Translate so text baseline sits just inside the right margin. */
            canvas.save();
            float w = getWidth();
            float h = getHeight();
            canvas.translate(getWidth() - 20, h / 6 ); //getHeight() / 2f);
            canvas.rotate(90 ) ; //, w / 2 - 40, 0);

            String invertLine = LumenState.invertEnabled ? "invert enabled" : "";

            String line1 = String.format(
                    java.util.Locale.US,
                    "lumi=%d overlay=%d%% %s",
                    Math.round(LumenState.lumi * 100f / 5) * 5,
                    Math.round(LumenState.overlayOpacity * 100),
                    invertLine
            );

            String line3 = LumenState.sysBrightness >= 0
                    ? String.format(java.util.Locale.US,
                    "sysBrightness=%d%%",
                    Math.round(LumenState.sysBrightness / 255f * 100))
                    : "sysBrightness=unavailable";

            String outStr = WATERMARK_TEXT;
            outStr = outStr + (flashGuardActive ? " [MAX]" : " ");

            outStr = outStr + " " + line1 + " " + line3 ;

            canvas.drawText(outStr, 0, 0, mTextPaint );
            canvas.restore();
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onServiceConnected() {
        sInstance = this;

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes   = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags        = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        setServiceInfo(info);

        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createOverlay();
        scheduleUpdate();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not needed - XLumen drives itself from LumenState, not UI events
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
        mOverlayView = new OverlayView(this);

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
     * Called on a timer - not event driven.
     *
     * Color logic:
     *   invert off = neutral dark  (ARGB: alpha, 0, 0, 0)
     *   invert on  = white overlay (ARGB: alpha, 255, 255, 255)
     *
     // The "68-95-99.7 rule"    68.27, 95.45, 99.74
     //TODO: This service is malfunctioning.  (Settings accessibility page)
     //Does my screen-reading read the value before or after overlay is applied?
     //TODO: V3,0 phone-in-pocket, intercept all taps, no-butt-dial
     */
    private void applyOverlayState() {
        if (mOverlayView == null) return;

        if (!LumenState.enabled) {
            mOverlayView.setDim(0, 0, 0, 0);
            return;
        }

        float opacity = Math.min(0.75f, Math.max(0.05f, LumenState.overlayOpacity));

        /* If system set backlight mostly up, we're at the beach, so don't slam as hard. */
        if (LumenState.sysBrightness > 127) {
            opacity *= 0.5f;
        }

        /* Floor value cannot be trifled with.  Permanent user reminder. */
        if (opacity <= 0.05f) {
            opacity = 0.05f;
        }

        int alpha = (int)(opacity * 255);

        if (LumenState.invertEnabled) {
            mOverlayView.setDim(alpha, 255, 255, 255);
        } else {
            mOverlayView.setDim(alpha, 0, 0, 0);
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