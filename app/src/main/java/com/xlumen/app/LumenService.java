package com.xlumen.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;


/**
 * XLumen LumenService
 *
 * Responsibilities:
 *   1. Capture screen frames via MediaProjection at a user-configured rate
 *   2. Compute rod-sensitivity-weighted luminance from subsampled pixels
 *   3. Detect whitebomb frames and apply protective response
 *   4. Write results to LumenState for LumenAccessibilityService to act on
 *   5. Read light sensor for Mode 3 (RESPONSIVE)
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

    // =========================================================================
    // Constants
    // =========================================================================

    /** Android notification channel ID.  Must be stable across versions. */
    public static final String CHANNEL_ID = "xlumen_service";

    /** Foreground notification ID.  Arbitrary, must be non-zero. */
    public static final int NOTIF_ID = 1;

    /** Number of unique lum:overlay pairs retained in the notification history line. */
    private static final int HISTORY_SIZE = 6;

    // =========================================================================
    // Fields
    // =========================================================================

    private MediaProjection mProjection;
    private VirtualDisplay  mVirtualDisplay;
    private ImageReader     mImageReader;
    private Handler         mHandler;
    private boolean         mRunning = false;

    /** System.currentTimeMillis() value before which whitebomb response is suppressed. */
    private long mWhiteBombCooldownUntil = 0;

    /** Last lum:overlay pair written to history.  Duplicate suppression. */
    private String lastMappingPair = "";

    /** Ring buffer of recent unique lum:overlay pairs for notification line 2. */
    private final ArrayDeque<String> mappingHistory = new ArrayDeque<>();

    // =========================================================================
    // debug() - LOGCAT unavailable in Bumblebee; this is the only survivor
    // =========================================================================

    /**
     * Appends msg to xlumen_debug.txt in app-private files dir.
     * Called throughout the service for tracing startup, mode changes,
     * and whitebomb events.  LOGCAT is non-functional in this build
     * environment - this file is the only persistent log.
     *
     * Read via MainActivity "Read Debug Log" button.
     *
     * @param msg line to append, without trailing newline
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

    // =========================================================================
    // Inner classes
    // =========================================================================

    /**
     * Carries both outputs of a single pixel pass through processFrame().
     * Avoids acquiring the image twice or splitting luminance and whitebomb
     * detection into separate methods with separate frame acquisitions.
     */
    private static class FrameResult {
        /** Scotopic-weighted luminance, 0.0..1.0.  -1.0 on failure. */
        float   luminance;
        /** True if more than 10% of sampled pixels are near-white (R,G,B > 220). */
        boolean isWhiteBomb;
    }

    /**
     * Intent extras passed from MainActivity when starting this service.
     * Keys are stable - do not rename without updating MainActivity.
     */
    public static class Extras {
        /** MediaProjection result code from onActivityResult. */
        public static final String RESULT_CODE     = "result_code";
        /** MediaProjection intent data from onActivityResult. */
        public static final String PROJECTION_DATA = "projection_data";
    }

    /**
     * Monitors the system Color Inversion accessibility toggle via
     * Settings.Secure content observer.  Writes changes to LumenState.invertEnabled
     * so the notification can reflect current invert state without polling.
     *
     * Started once in onStartCommand, never stopped - lifetime matches service.
     */
    public class InversionMonitor {

        private final Context         context;
        private final ContentObserver observer;

        /**
         * @param context  service context, used to register the observer
         */
        public InversionMonitor(Context context) {
            this.context = context;

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

        /**
         * Registers the observer and immediately reads the current invert state
         * so LumenState is accurate before the first onChange fires.
         */
        public void start() {
            Uri uri = Settings.Secure.getUriFor(
                    Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED);
            context.getContentResolver().registerContentObserver(uri, false, observer);

            boolean enabled = isInvertColorsEnabled();
            Log.d("InversionMonitor", "Invert colors initial: " + enabled);
            debug("InversionMonitor.start(): Invert colors: " + enabled);
        }

        /** Unregisters the observer.  Call on service destroy. */
        public void stop() {
            context.getContentResolver().unregisterContentObserver(observer);
        }

        /**
         * Reads the current Color Inversion setting directly from Settings.Secure.
         *
         * @return true if inversion is currently enabled
         */
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

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Entry point when MainActivity fires startForegroundService().
     * Enforces startup sequence: projection first, then sampling loop,
     * then inversion monitor.  Any exception during projection setup
     * is written to the debug file and the service stops itself cleanly.
     *
     * Returns START_REDELIVER_INTENT so Android restarts with the original
     * intent if the service is killed, preserving the MediaProjection token.
     */
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
            // debug() is not safe here if getFilesDir() is the source of the crash.
            // Write directly so the reason survives for the next session.
            try {
                java.io.FileWriter fw = new java.io.FileWriter(
                        getFilesDir() + "/xlumen_debug.txt", true);
                fw.write("LumenService crash: " + e.getClass().getName()
                        + " - " + e.getMessage() + "\n");
                fw.close();
            } catch (Exception ignored) { }
            stopSelf();
        }

        new InversionMonitor(this).start();

        return START_REDELIVER_INTENT;
    }

    /**
     * Not a bound service.  Always returns null.
     */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Cleans up projection resources on service stop.
     * mRunning=false halts the sampling loop before teardown begins.
     */
    @Override
    public void onDestroy() {
        mRunning = false;
        tearDownProjection();
        super.onDestroy();
    }

    // =========================================================================
    // MediaProjection setup / teardown
    // =========================================================================

    /**
     * Initializes the foreground notification, MediaProjection, ImageReader,
     * and VirtualDisplay.  Must be called before scheduleNextSample().
     *
     * On Android 14+, startForeground with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
     * must precede getMediaProjection - this ordering is enforced here.
     *
     * VirtualDisplay is created at 1/8 screen resolution.  This is intentional:
     * full resolution is not needed for luminance or whitebomb detection, and
     * the reduced size cuts pixel processing cost by 64x.
     *
     * @param resultCode     from MediaProjection permission dialog
     * @param data           intent data from MediaProjection permission dialog
     */
    private void startProjection(int resultCode, Intent data) {
        createNotificationChannel();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
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

    /**
     * Releases VirtualDisplay, ImageReader, and MediaProjection in safe order.
     * Null-checks each before release - may be called from onStop callback
     * before all resources are fully initialized.
     */
    private void tearDownProjection() {
        if (mVirtualDisplay != null) { mVirtualDisplay.release(); mVirtualDisplay = null; }
        if (mImageReader    != null) { mImageReader.close();      mImageReader    = null; }
        if (mProjection     != null) { mProjection.stop();        mProjection     = null; }
    }

    // =========================================================================
    // Sampling loop
    // =========================================================================

    /**
     * Posts the next doSample() call to mHandler after the user-configured interval.
     * Re-reads prefs each cycle so interval changes take effect immediately
     * without restarting the service.
     */
    private void scheduleNextSample() {
        if (!mRunning) return;
        LumenPrefs prefs = new LumenPrefs(this);
        mHandler.postDelayed(this::doSample, prefs.getSampleIntervalMs());
    }

    /**
     * Core sample cycle.  Called on mHandler at the configured interval.
     *
     * Calls processFrame() for a single-pass pixel analysis, then branches:
     *   - whitebomb detected and enabled and cooldown expired: applyWhiteBombResponse()
     *   - otherwise: normal mode logic via updateOverlayFromMode()
     *
     * recordMappingHistory() and updateNotification() run every cycle
     * regardless of which branch was taken.
     */
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
                    applyWhiteBombResponse();
                }
                // else: in cooldown, response already latched, do nothing
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
     * Acquires one frame from the VirtualDisplay and performs a single pixel
     * pass computing both scotopic luminance and near-white pixel fraction.
     *
     * Sampling mode is read from LumenPrefs each frame:
     *   FULL      - every pixel, exhaustive, use for diagnostics
     *   STRIDE_65 - one pixel per 65 bytes, linear drift across rows,
     *               roughly 1/64 of pixels, adequate for full-screen events
     *
     * Near-white threshold: R > 220 AND G > 220 AND B > 220.
     * Whitebomb flag set if more than 10% of sampled pixels are near-white.
     *
     * Scotopic weights: R=0.06  G=0.67  B=0.27
     *
     * @return FrameResult with luminance=-1 on any failure
     */
    private FrameResult processFrame() {
        FrameResult result    = new FrameResult();
        result.luminance      = -1f;
        result.isWhiteBomb    = false;

        try (Image image = mImageReader.acquireLatestImage()) {
            if (image == null) return result;

            Image.Plane plane       = image.getPlanes()[0];
            ByteBuffer  buf         = plane.getBuffer();
            int         rowStride   = plane.getRowStride();
            int         pixelStride = plane.getPixelStride();
            int         w           = image.getWidth();
            int         h           = image.getHeight();

            LumenPrefs prefs    = new LumenPrefs(this);
            boolean    fullScan = "FULL".equals(prefs.getPixelSampleMode());

            long rSum = 0, gSum = 0, bSum = 0;
            long white = 0, total = 0;

            if (fullScan) {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int idx = y * rowStride + x * pixelStride;
                        int r   = buf.get(idx)     & 0xFF;
                        int g   = buf.get(idx + 1) & 0xFF;
                        int b   = buf.get(idx + 2) & 0xFF;
                        rSum += r;  gSum += g;  bSum += b;
                        if (r > 220 && g > 220 && b > 220) white++;
                        total++;
                    }
                }
            } else {
                // STRIDE_65: one pixel every 65 positions, linear through buffer.
                // Natural row drift gives decent 2D scatter without block math.
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

    /**
     * Responds to a detected whitebomb frame.
     *
     * Two simultaneous actions:
     *   1. Slams overlay to 69% via LumenState - LumenAccessibilityService
     *      picks this up on its next 100ms tick.
     *   2. Hammers system screen brightness to the user-configured floor via
     *      Settings.System.SCREEN_BRIGHTNESS - only if the user has both
     *      enabled the trust toggle and granted WRITE_SETTINGS permission.
     *
     * LumenState.whiteBombActive drives the [MAX] title in buildNotification().
     * Cleared by doSample() when no whitebomb is detected and cooldown has expired.
     */
    private void applyWhiteBombResponse() {
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

        debug("applyWhiteBombResponse: overlay=69% brightness=" +
                prefs.getWhitebombBrightness());
        updateNotification();
    }

    // =========================================================================
    // Mode logic - writes to LumenState for overlay to consume
    // =========================================================================

    /**
     * Translates current mode and screenLuminance into overlay parameters.
     * Called every sample cycle when no whitebomb response is active.
     *
     * Gate: if LumenState.enabled is false, zeroes both opacity and redBias
     * and returns immediately.  LumenAccessibilityService has an independent
     * gate as well - both must agree before any overlay is applied.
     *
     * TINT is the primary mode.  Others are stubs pending sensor and
     * scheduling work.  See TODO comments inside each case.
     */
    private void updateOverlayFromMode() {
        if (!LumenState.enabled) {
            LumenState.overlayOpacity = 0f;
            LumenState.overlayRedBias = 0f;
            return;
        }

        switch (LumenState.mode) {

            case TINT:
                // Scales 5% to 75% opacity with luminance.
                // No red bias - warm tint is handled by overlay color in LumenAccessibilityService.
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
                // TODO: drive from light sensor, not screen luminance.
                // LumenState.ambientLux written by a SensorEventListener.
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

    // =========================================================================
    // Notification (shader drop-down)
    // =========================================================================

    /**
     * Creates the notification channel on first call.  Safe to call repeatedly -
     * Android ignores duplicate channel creation.  Called from startProjection()
     * before startForeground().
     */
    private void createNotificationChannel() {
        NotificationChannel chan = new NotificationChannel(
                CHANNEL_ID,
                "XLumen Service",
                NotificationManager.IMPORTANCE_LOW
        );
        getSystemService(NotificationManager.class).createNotificationChannel(chan);
    }

    /**
     * Pushes a fresh notification to the system notification manager.
     * Called after every sample cycle and after any state change that
     * affects the notification display.
     */
    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ID, buildNotification());
    }

    /**
     * Records a lum:overlay pair to the mapping history ring buffer.
     * Duplicate pairs (same as last recorded) are silently dropped.
     * Buffer is trimmed to HISTORY_SIZE before logging so the debug
     * file reflects exactly what the notification shows.
     *
     * History is displayed on notification line 2 as space-separated pairs,
     * e.g.: "45:69 14:15 30:47 12:14"
     */
    private void recordMappingHistory() {
        int lum     = Math.round(LumenState.screenLuminance * 100f);
        int overlay = Math.round(LumenState.overlayOpacity  * 100f);

        String pair = lum + ":" + overlay;
        if (pair.equals(lastMappingPair)) return;
        lastMappingPair = pair;

        mappingHistory.addLast(pair);
        if (mappingHistory.size() > HISTORY_SIZE) {
            mappingHistory.removeFirst();
        }
        debug(buildHistoryString());
    }

    /**
     * Joins the current mapping history into a single space-separated string
     * for display on notification line 2.
     *
     * @return e.g. "45:69 14:15 30:47" or empty string if no history yet
     */
    private String buildHistoryString() {
        StringBuilder sb    = new StringBuilder();
        boolean       first = true;
        for (String pair : mappingHistory) {
            if (!first) sb.append(" ");
            sb.append(pair);
            first = false;
        }
        return sb.toString();
    }

    /**
     * Builds the foreground notification shown in the shader drop-down.
     *
     * Title: "XLumen [MAX]" during whitebomb response, "XLumen" otherwise.
     * Line 1: lum=0.13 overlay=15% invert=off
     * Line 2: recent unique lum:overlay pairs, space-separated
     *
     * @return fully constructed Notification ready for NotificationManager
     */
    private Notification buildNotification() {
        String invertLine = LumenState.invertEnabled ? "invert=on" : "invert=off";

        String line1 = String.format(
                java.util.Locale.US,
                "lum=%.2f overlay=%d%% %s",
                LumenState.screenLuminance,
                Math.round(LumenState.overlayOpacity * 100),
                invertLine
        );

        String line2 = buildHistoryString();

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(LumenState.whiteBombActive ? "XLumen [MAX]" : "XLumen")
                .setContentText(line1)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(line1 + "\n" + line2))
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
    }

    // =========================================================================
    // Dead code - retained for reference
    // =========================================================================

    /**
     * Original single-pass luminance computation.
     * Superseded by processFrame(), which combines luminance and whitebomb
     * detection in one pixel pass and supports STRIDE_65 sampling.
     *
     * Retained for diffing and fallback reference.  Do not call.
     *
     * @deprecated superseded by processFrame()
     */
    @Deprecated
    @SuppressWarnings("unused")
    private float computeScreenLuminance() {
        try (Image image = mImageReader.acquireLatestImage()) {
            if (image == null) return -1f;

            Image.Plane plane  = image.getPlanes()[0];
            ByteBuffer  buf    = plane.getBuffer();
            int         stride = plane.getRowStride();
            int         w      = image.getWidth();
            int         h      = image.getHeight();

            long rSum = 0, gSum = 0, bSum = 0, count = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int idx = y * stride + x * 4;
                    rSum += (buf.get(idx)     & 0xFF);
                    gSum += (buf.get(idx + 1) & 0xFF);
                    bSum += (buf.get(idx + 2) & 0xFF);
                    count++;
                }
            }

            if (count == 0) return -1f;

            float rAvg = rSum / (float)(count * 255);
            float gAvg = gSum / (float)(count * 255);
            float bAvg = bSum / (float)(count * 255);

            return 0.06f * rAvg + 0.67f * gAvg + 0.27f * bAvg;

        } catch (Exception e) {
            Log.e(TAG, "computeScreenLuminance failed: " + e);
            return -1f;
        }
    }
}