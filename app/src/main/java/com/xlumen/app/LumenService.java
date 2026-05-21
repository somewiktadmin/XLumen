package com.xlumen.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;


/**
 * XLumen LumenService
 *
 * Responsibilities:
 *   1. Capture screen frames via MediaProjection at a user-configured rate
 *   2. Compute rod-sensitivity-weighted luminance from subsampled pixels
 *   3. Write results to LumenState for LumenAccessibilityService to act on
 *   4. Read light sensor for Mode 3 (RESPONSIVE)
 *
 * This service runs in the foreground and requires a persistent notification.
 * MediaProjection permission must be granted by the user each session -
 * Android re-prompts after every reboot.  This is by design and cannot
 * be suppressed.
 *
 * Scotopic (rod-sensitivity) luminance weights used here, not photopic:
 *   R: 0.06   G: 0.67   B: 0.27
 * Standard luma (photopic) weights for reference:
 *   R: 0.2126  G: 0.7152  B: 0.0722
 * Rod cells peak at ~498nm (blue-green).  The standard blue coefficient
 * dramatically underestimates rod stimulation.  We correct for this.
 */
public class LumenService extends Service {

    private static final String TAG = "XLumen";

    // - Notification -
    public static final String CHANNEL_ID  = "xlumen_service";
    public static final int    NOTIF_ID    = 1;

    // - State -
    private MediaProjection  mProjection;
    private VirtualDisplay   mVirtualDisplay;
    private ImageReader      mImageReader;
    private Handler          mHandler;
    private boolean          mRunning = false;


    // - Whitebomb -
    private long mWhiteBombCooldownUntil = 0;


    /**
     * This debug work-around is because LOGCAT does not work in bumblebee
     * @param msg
     */
    public void debug(String msg) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter(
                    getFilesDir() + "/xlumen_debug.txt", true);
            fw.append(msg + "\n");
            fw.close();
        } catch (Exception e) {
            Log.e(TAG, "debug: ", e);
        }
    }


    // FrameResult carries both outputs of a single pixel pass.
    private static class FrameResult {
        float luminance;
        boolean isWhiteBomb;
    }

    private FrameResult processFrame() {
        FrameResult result = new FrameResult();
        result.luminance   = -1f;
        result.isWhiteBomb = false;

        try (Image image = mImageReader.acquireLatestImage()) {
            if (image == null) return result;

            Image.Plane plane  = image.getPlanes()[0];
            ByteBuffer  buf    = plane.getBuffer();
            int         stride = plane.getRowStride();
            int         w      = image.getWidth();
            int         h      = image.getHeight();

            LumenPrefs prefs      = new LumenPrefs(this);
            boolean    fullScan   = "FULL".equals(prefs.getPixelSampleMode());
            int        pixelStride = plane.getPixelStride();

            long rSum = 0, gSum = 0, bSum = 0;
            long white = 0, total = 0;

            if (fullScan) {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int idx = y * stride + x * pixelStride;
                        int r   = buf.get(idx)     & 0xFF;
                        int g   = buf.get(idx + 1) & 0xFF;
                        int b   = buf.get(idx + 2) & 0xFF;
                        rSum += r;  gSum += g;  bSum += b;
                        if (r > 220 && g > 220 && b > 220) white++;
                        total++;
                    }
                }
            } else {
                // STRIDE_65: one pixel every 65, linear through the buffer.
                // Drifts across rows naturally - decent 2D scatter for free.
                int bufLimit = buf.limit();
                for (int pos = 0; pos + 3 < bufLimit; pos += 65 * pixelStride) {
                    int r = buf.get(pos)     & 0xFF;
                    int g = buf.get(pos + 1) & 0xFF;
                    int b = buf.get(pos + 2) & 0xFF;
                    rSum += r;  gSum += g;  bSum += b;
                    if (r > 220 && g > 220 && b > 220) white++;
                    total++;
                }
            }

            if (total == 0) return result;

            float rAvg = rSum / (float)(total * 255);
            float gAvg = gSum / (float)(total * 255);
            float bAvg = bSum / (float)(total * 255);

            result.luminance   = 0.06f * rAvg + 0.67f * gAvg + 0.27f * bAvg;
            result.isWhiteBomb = (white * 100 / total) > 10;
        } catch (Exception e) {
            Log.e(TAG, "processFrame failed: " + e);
        }

        return result;
    }

    private void applyWhiteBomb() {
        LumenState.overlayOpacity  = 0.69f;
        LumenState.overlayRedBias  = 0f;
        LumenState.whiteBombActive = true;

        LumenPrefs prefs = new LumenPrefs(this);
        if (prefs.isWriteSettingsTrusted()
                && android.provider.Settings.System.canWrite(this)) {
            android.provider.Settings.System.putInt(
                    getContentResolver(),
                    android.provider.Settings.System.SCREEN_BRIGHTNESS,
                    prefs.getWhitebombBrightness()
            );
        }

        updateNotification();
    }


    /**
     * Intent extras
     */
    public static class Extras {
        public static final String RESULT_CODE     = "result_code";
        public static final String PROJECTION_DATA = "projection_data";
    }


    /**
     * Figure out what the invert toggle is set to currently
     */
    public class InversionMonitor {

        private final Context context;
        private final ContentObserver observer;

        public InversionMonitor(Context context) {
            this.context = context;

            // Create the observer
            observer = new ContentObserver(new Handler(context.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    super.onChange(selfChange, uri);
                    boolean enabled = isInvertColorsEnabled();
                    LumenState.invertEnabled = enabled;
                    Log.d("InversionMonitor", "Invert colors changed: " + enabled);
                    debug("InversionMonitor: Invert changed: " + enabled);
                    updateNotification();
                }
            };
        }

        // Register the observer
        public void start() {
            Uri uri = Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED);
            context.getContentResolver().registerContentObserver(uri, false, observer);

            // Optionally trigger callback immediately to get current state
            boolean enabled = isInvertColorsEnabled();
            Log.d("InversionMonitor", "Invert colors initial: " + enabled);
            debug("InversionMonitor.start(): Invert colors: " + enabled);
        }

        // Unregister when done
        public void stop() {
            context.getContentResolver().unregisterContentObserver(observer);
        }

        // Helper to read current value
        private boolean isInvertColorsEnabled() {
            try {
                int value = Settings.Secure.getInt(context.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED);
                return value != 0;
            } catch (Settings.SettingNotFoundException e) {
                return false;
            }
        }
    }



    // -------------------
    // Lifecycle
    // -------------------


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            int    resultCode     = intent.getIntExtra(Extras.RESULT_CODE, -1);
            Intent projectionData = intent.getParcelableExtra(Extras.PROJECTION_DATA);

            mHandler = new Handler(Looper.getMainLooper());

            startProjection(resultCode, projectionData);
            scheduleNextSample();

        } catch (Exception e) {
            // Write crash reason to file so MainActivity can read it
            try {
                java.io.FileWriter fw = new java.io.FileWriter(
                        getFilesDir() + "/xlumen_debug.txt", true);
                fw.write("LumenService crash: " + e.getClass().getName()
                        + " - " + e.getMessage() + "\n");
                fw.close();
            } catch (Exception ignored) { }
            stopSelf();
        }

        InversionMonitor monitor = new InversionMonitor(this);
        monitor.start();

        return START_REDELIVER_INTENT;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        mRunning = false;
        tearDownProjection();
        super.onDestroy();
    }

    // -------------------
    // MediaProjection setup / teardown
    // -------------------

    private void startProjection(int resultCode, Intent data) {

        // startForeground MUST come before getMediaProjection on Android 14.
        if (Build.VERSION.SDK_INT >= 34) {
            createNotificationChannel();
            startForeground(NOTIF_ID, buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            createNotificationChannel();
            startForeground(NOTIF_ID, buildNotification());
        }

        MediaProjectionManager mgr =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mProjection = mgr.getMediaProjection(resultCode, data);

        if (Build.VERSION.SDK_INT >= 34) {
            mProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    mRunning = false;
                    tearDownProjection();
                }
            }, mHandler);
        }

        DisplayMetrics metrics = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE))
                .getDefaultDisplay().getRealMetrics(metrics);

        int w = metrics.widthPixels  / 8;
        int h = metrics.heightPixels / 8;

        mImageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        mVirtualDisplay = mProjection.createVirtualDisplay(
                "XLumenCapture",
                w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mImageReader.getSurface(), null, null
        );

        mRunning = true;
    }

    private void tearDownProjection() {
        if (mVirtualDisplay != null) { mVirtualDisplay.release(); mVirtualDisplay = null; }
        if (mImageReader   != null) { mImageReader.close();       mImageReader   = null; }
        if (mProjection    != null) { mProjection.stop();         mProjection    = null; }
    }

    // -------------------
    // Sampling loop
    // -------------------

    private void scheduleNextSample() {
        if (!mRunning) return;
        LumenPrefs prefs = new LumenPrefs(this);
        mHandler.postDelayed(this::doSample, prefs.getSampleIntervalMs());
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ID, buildNotification());
    }

    private void doSample() {
        if (!mRunning) return;

        FrameResult result = processFrame();

        if (result.luminance >= 0f) {
            LumenState.screenLuminance = result.luminance;

            LumenPrefs prefs = new LumenPrefs(this);
            if (prefs.isWhitebombEnabled() && result.isWhiteBomb) {
                long now = System.currentTimeMillis();
                if (now > mWhiteBombCooldownUntil) {
                    mWhiteBombCooldownUntil = now + prefs.getCooldownMs();
                    applyWhiteBomb();
                }
            } else {
                LumenState.whiteBombActive = false;
                updateOverlayFromMode();
            }

            recordMappingHistory();
        }

        updateNotification();
        scheduleNextSample();
    }



    /**
     * Acquires the latest frame, computes rod-weighted luminance.
     * Returns 0.0..1.0, or -1.0 on failure.
     *
     * Scotopic weights: R=0.06  G=0.67  B=0.27
     */
    private float computeScreenLuminance() {
        try (Image image = mImageReader.acquireLatestImage()) {
            if (image == null) return -1f;

            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int stride = plane.getRowStride();
            int w = image.getWidth();
            int h = image.getHeight();

            long rSum = 0, gSum = 0, bSum = 0, count = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int idx = y * stride + x * 4;
                    rSum += (buf.get(idx) & 0xFF);
                    gSum += (buf.get(idx + 1) & 0xFF);
                    bSum += (buf.get(idx + 2) & 0xFF);
                    count++;
                }
            }

            if (count == 0) return -1f;

            float rAvg = rSum / (float) (count * 255);
            float gAvg = gSum / (float) (count * 255);
            float bAvg = bSum / (float) (count * 255);

            // Scotopic-weighted luminance
            return 0.06f * rAvg + 0.67f * gAvg + 0.27f * bAvg;

        } catch (Exception e) {
            Log.e(TAG, "Luminance sample failed: " + e);
            return -1f;
        }
    }

    // =============================
    // Mode logic - writes to LumenState for overlay to consume
    // =============================

    private void updateOverlayFromMode() {

        // Gate 1: service running but user has not activated XLumen.
        // Write zeros and return - overlay stays invisible.
        // LumenAccessibilityService enforces this independently as well.
        // Both gates must agree before any overlay is applied.
        if (!LumenState.enabled) {
            LumenState.overlayOpacity = 0f;
            LumenState.overlayRedBias = 0f;
            return;
        }

        // Beyond this point, XLumen is active and mode logic applies.
        switch (LumenState.mode) {

            case TINT:
                // Minimum 15% overlay always applied - never fully transparent.
                // Scales up to 75% at maximum luminance.
                // Red bias fixed at 0.8 - warm tint, not pure red.
                LumenState.overlayOpacity = 0.05f + (LumenState.screenLuminance * 0.70f);
                LumenState.overlayRedBias = 0.0f;
                break;

            case NIGHTSHOT:
                // Hard lock.  Maximum overlay, maximum red bias.
                // No gradual ramp - a single white flash ruins dark adaptation.
                LumenState.overlayOpacity = 0.75f;
                LumenState.overlayRedBias = 1.0f;
                break;

            case RESPONSIVE:
                // TODO: drive from light sensor, not just screen luminance.
                // LumenState.ambientLux will be written by a SensorEventListener.
                // Placeholder until sensor code is added.
                LumenState.overlayOpacity = 0.05f;
                break;

            case SCHEDULED:
                // TODO: drive from time of day and sunset/sunrise calculation.
                LumenState.overlayOpacity = 0.15f;
                break;

            case MEDIA:
                // Neutral dim only - no red bias.
                // Opacity driven by luminance, red bias forced to zero.
                LumenState.overlayOpacity = 0.05f + LumenState.screenLuminance * 0.4f;
                LumenState.overlayRedBias = 0f;
                break;
        }
    }

    // =============================
    // Shader drop-down Notification
    // =============================

    private void createNotificationChannel() {
        NotificationChannel chan = new NotificationChannel(
                CHANNEL_ID,
                "XLumen Service",
                NotificationManager.IMPORTANCE_LOW
        );
        getSystemService(NotificationManager.class).createNotificationChannel(chan);
    }

    private static final int HISTORY_SIZE = 6;
    private String lastMappingPair = "";
    private final ArrayDeque<String> mappingHistory = new ArrayDeque<>();

    private void recordMappingHistory() {
        int lum     = Math.round(LumenState.screenLuminance * 100f);
        int overlay = Math.round(LumenState.overlayOpacity  * 100f);

        String pair = lum + ":" + overlay;
        if (pair.equals(lastMappingPair)) return;
        lastMappingPair = pair;

        mappingHistory.addLast(pair);
        if (mappingHistory.size() > HISTORY_SIZE) {
            mappingHistory.removeFirst();   // trim before logging
        }
        debug(buildHistoryString());
    }

    private String buildHistoryString() {
        StringBuilder sb = new StringBuilder();

        boolean first = true;

        for (String pair : mappingHistory) {
            if (!first) {
                sb.append(" ");
            }
            sb.append(pair);
            first = false;
        }
        return sb.toString();
    }

    private Notification buildNotification() {

        String invertLine = LumenState.invertEnabled
                ? "invert=on"
                : "invert=off";

        String line1 = String.format(
                java.util.Locale.US,
                "lum=%.2f overlay=%d%% %s",
                LumenState.screenLuminance,
                Math.round(LumenState.overlayOpacity * 100),
                invertLine
        );

        String line2 = buildHistoryString(); // your 47:65 pairs

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(LumenState.whiteBombActive ? "XLumen [MAX]" : "XLumen")
                .setContentText(line1)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(line1 + "\n" + line2 ))
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
    }

}