package com.xlumen.app;

import static com.xlumen.app.LumenState.flashGuardActive;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
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
 *   2. Compute lumi (near-white flash fraction) from sampled pixels
 *   3. Detect flash events and apply flash guard response
 *   4. Write results to LumenState for LumenAccessibilityService to act on
 *
 * This service runs in the foreground and requires a persistent notification.
 * MediaProjection permission must be granted by the user each session -
 * Android re-prompts after every reboot.  This is by design and cannot
 * be suppressed.
 *
 * lumi is the primary sensor output: fraction of sampled pixels with
 * R > 220, G > 220, B > 220.  Direct proxy for total photon energy
 * output from screen to eyeball.  See README.md for full rationale.
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

    /**
     *  Number of unique lumi:overlay pairs retained in
     *  notification shader history line two.
     */
    private static final int HISTORY_SIZE = 4;

    // =========================================================================
    // Fields
    // =========================================================================

    private MediaProjection mProjection;
    private VirtualDisplay  mVirtualDisplay;
    private ImageReader     mImageReader;
    private Handler         mHandler;
    private boolean         mRunning = false;
    private XLumenBrightnessLog mBrightnessLog;
    private AmbientLightMonitor mAmbientLightMonitor;
    private InversionMonitor    mInversionMonitor;
    private int mSavedBrightness     = -1;

    /*
     * This innocent looking min value is a (hopefully) subtle reminder
     * to users, that this utility is running.  Fantastic during testing too.
     */
    private static final float OVERLAY_FLOOR = 0.05f;

    // GRADIENT mode range.  Ceiling is one sigma (68%) by convention
    // but may need per-device tuning.
    private static final float GRADIENT_LUMI_MAX     = 0.5f;
    private static final float GRADIENT_OVERLAY_CEILING = 0.68f;

    /**
     * Overlay opacity applied during LUMI_GUARD flash response.
     *
     * Derived as one percentage point above GRADIENT_OVERLAY_CEILING,
     * not hardcoded.  This guarantees a visible step change at the mode
     * boundary - the user sees an unambiguous slam, not a gentle nudge.
     *
     * If GRADIENT_OVERLAY_CEILING is tuned for a specific device,
     * this value follows automatically.  No magic numbers scattered
     * across switch cases.
     *
     * At GRADIENT_OVERLAY_CEILING=0.68f, this evaluates to 0.69f.
     * One sigma plus one percent.  The universe approves.
     */
    private static final float LUMI_GUARD_OVERLAY =
            ((int)(GRADIENT_OVERLAY_CEILING * 100) + 1) / 100f;

    private static final float LUMI_QUANTIZE_STEP = 0.10f;

    /** System.currentTimeMillis() value before which flash guard response is suppressed. */
    private long mFlashGuardCooldownUntil = 0;

    /** Last lumi:overlay pair written to history.  Duplicate suppression. */
    private String lastMappingPair = "";

    /** Ring buffer of recent unique lumi:overlay pairs for notification line 2. */
    private final ArrayDeque<String> mappingHistory = new ArrayDeque<>();

    // =========================================================================
    // debug() - LOGCAT unreliable in Bumblebee; this is the primary trace log
    // NB: Android Settings, { } Developer Options, LOGGER BUFFER SIZES = 1M
    // =========================================================================

    /**
     * Appends msg to xlumen_debug.txt in app-private files dir.
     * LOGCAT is unreliable in this build environment - this file is the
     * primary persistent log.  Read via MainActivity "Read Debug Log" button.
     *
     * Thin wrapper around new XLumenLog.debug().
     *
     * @param msg  line to append, without trailing newline
     */
    public void debug(String msg) {
        XLumenLog.debug(msg);
    }

    // =========================================================================
    // Inner classes
    // =========================================================================

    /**
     * Carries both measurements from a single pixel pass through processFrame().
     * Pure sensor output - no policy, no threshold comparison.
     * Policy lives in doSample().
     */
    private static class FrameResult {
        /**
         * Fraction of sampled pixels with R > 220, G > 220, B > 220.
         * Range 0.0-1.0.  Primary driver of all overlay decisions.
         * -1.0 on acquisition failure.
         */
        float lumi;

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

    /**
     * Monitors the ambient light sensor and writes lux values to
     * LumenState.ambientLux for use by XLumenBrightnessLog and
     * eventually GPS_DAYLIGHT mode (TO-DO v2).
     *
     * No permission required - TYPE_LIGHT is ungated.
     * SENSOR_DELAY_NORMAL (~200ms) is sufficient for ambient light tracking.
     *
     * Started once in onStartCommand, unregistered in onDestroy.
     * Lifetime matches service.
     */
    public class AmbientLightMonitor {

        private final SensorManager mSensorManager;
        private final SensorEventListener mListener;

        /**
         * @param context  service context, used to get SensorManager
         */
        public AmbientLightMonitor(Context context) {
            mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);

            mListener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    LumenState.ambientLux = event.values[0];
                }
                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {}
            };
        }

        /**
         * Registers the light sensor listener.
         * Call from onStartCommand() after XLumenLog.init().
         */
        public void start() {
            Sensor lightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
            if (lightSensor == null) {
                XLumenLog.debug("AmbientLightMonitor: no light sensor on this device");
                return;
            }
            mSensorManager.registerListener(mListener, lightSensor,
                    SensorManager.SENSOR_DELAY_NORMAL);
            XLumenLog.debug("AmbientLightMonitor.start(): light sensor registered");
        }

        /**
         * Unregisters the light sensor listener.
         * Call from onDestroy().
         */
        public void stop() {
            mSensorManager.unregisterListener(mListener);
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
            } catch (Exception ignored) {  }
            stopSelf();
        }

        mInversionMonitor = new InversionMonitor(this);
        mInversionMonitor.start();
        mAmbientLightMonitor = new AmbientLightMonitor(this);
        mAmbientLightMonitor.start();
        mBrightnessLog = new XLumenBrightnessLog(this);

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
        mInversionMonitor.stop();
        mAmbientLightMonitor.stop();
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
     * full resolution is not needed for lumi calculation, and the reduced size
     * cuts pixel processing cost by 64x.
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
     * without restarting the service. TODO: make them restart service (just warn on settings screen)
     */
    private void scheduleNextSample() {
        if (!mRunning) return;
        LumenPrefs prefs = new LumenPrefs(this);

        int interval = prefs.getSampleIntervalMs();
        if (false) debug("scheduleNextSample: interval=" + interval);
        mHandler.postDelayed(this::doSample, interval);
    }

    /**
     * Core sample cycle.  Called on mHandler at the configured interval.
     *
     * Calls processFrame() for a single-pass pixel analysis, then branches:
     *   - lumi > threshold and flash guard enabled and cooldown expired:
     *     "applyFlashGuard"
     *   - otherwise: normal mode logic via updateOverlayFromMode()
     *
     * recordMappingHistory() and updateNotification() run every cycle
     * regardless of which branch was taken.
     */
    private void doSample() {
        if (!mRunning) return;

        long now = System.currentTimeMillis();

        if ((mFlashGuardCooldownUntil > 0) && (now < mFlashGuardCooldownUntil)) {
            scheduleNextSample();
            return;
        }

        FrameResult result = processFrame();

        LumenPrefs prefs = new LumenPrefs(this);

        if (result.lumi >= 0f) {
            LumenState.lumi = result.lumi;

            float threshold = prefs.getThreshold();
            float releaseThreshold = flashGuardActive
                    ? threshold - LUMI_QUANTIZE_STEP
                    : threshold;
            boolean isFlashing = result.lumi > releaseThreshold;

            //releaseFlashGuard()
            if (flashGuardActive && (!isFlashing)) {
                flashGuardActive = false;

                debug("doSample: MAX release - lumi=" + String.format(java.util.Locale.US, "%.4f", result.lumi)
                        + " releaseThreshold=" + String.format(java.util.Locale.US, "%.4f", releaseThreshold));

                if ( prefs.isWriteSettingsTrusted() && Settings.System.canWrite(this) ) {
                    if (mSavedBrightness >= 0) { Settings.System.putInt(getContentResolver(),
                                Settings.System.SCREEN_BRIGHTNESS, mSavedBrightness); }
                    Settings.System.putInt(getContentResolver(),
                            Settings.System.SCREEN_BRIGHTNESS_MODE,
                            1); //SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                }
            } //end releaseFlashGuard()

            //applyFlashGuard()
            if (prefs.isFlashGuardEnabled() && isFlashing) {
                if (now > mFlashGuardCooldownUntil) {
                    mFlashGuardCooldownUntil = now + prefs.getCooldownMs();
                    LumenState.overlayOpacity   = 0.69f;
                    flashGuardActive = true;

                    try {
                        mSavedBrightness = Settings.System.getInt(getContentResolver(),
                                Settings.System.SCREEN_BRIGHTNESS );
                    } catch (Settings.SettingNotFoundException e) {
                        XLumenLog.debug("applyFlashGuard: SCREEN_BRIGHTNESS not found, " + e.getMessage() );
                    }
                    if (prefs.isWriteSettingsTrusted()
                            && android.provider.Settings.System.canWrite(this)) {
                        Settings.System.putInt(getContentResolver(),
                                Settings.System.SCREEN_BRIGHTNESS_MODE,
                                0); //SCREEN_BRIGHTNESS_MODE_MANUAL
                        android.provider.Settings.System.putInt(
                                getContentResolver(),
                                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                                prefs.getFlashGuardBrightness()
                        );
                    }

                    if (false) debug("applyFlashGuard: overlay=69% brightness="
                            + prefs.getFlashGuardBrightness());
                    updateNotification();
                } //end applyFlashGuard()

            // else: in cooldown, response already latched, do nothing
            } else {
                flashGuardActive = false;
                updateOverlayFromMode();
            }

            recordMappingHistory();

            if (false)
                debug("doSample: lumi=" + String.format(java.util.Locale.US, "%.4f", result.lumi)
                        + " coolDown=" + mFlashGuardCooldownUntil
                        + " flashGuardEnabled=" + prefs.isFlashGuardEnabled());

        }

        updateNotification();
        mBrightnessLog.maybelog(prefs);
        scheduleNextSample();
    }

    /**
     * Acquires one frame from the VirtualDisplay and performs a single pixel
     * pass computing both lumi and scotopic luminance.
     *
     * Sampling mode is read from LumenPrefs each frame:
     *   FULL      - every pixel, exhaustive, use for diagnostics only
     *   STRIDE_65 - one pixel per 65 bytes, linear drift across rows,
     *               roughly 1/64 of pixels, adequate for full-screen events
     *
     * Near-white threshold: R > 220 AND G > 220 AND B > 220.
     * lumi is the only output that matters.
     * scotopicLuminance is computed but deprecated - see FrameResult.
     *
     * @return FrameResult with both fields -1 on any acquisition failure
     */
    private FrameResult processFrame() {
        FrameResult result            = new FrameResult();
        result.lumi = -1f;

        // Threshold for near-white pixel detection.
        // When flash guard is active, overlay darkens each pixel by factor (1 - LUMI_GUARD_OVERLAY).
        // Compensate by scaling the threshold down proportionally so we detect
        // pixels that would be near-white without the overlay.
        // 220 * (1 - 0.69) = 68.2, floored to 68.
        // Derived from LUMI_GUARD_OVERLAY so it tracks automatically if that constant changes.

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

            int wt = (int)(220 * (1f - LumenState.overlayOpacity));
            int bt = 255 - wt;
            boolean inverted = LumenState.invertEnabled;

            if (fullScan) {
                if (inverted) {
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            int idx = y * rowStride + x * pixelStride;
                            int r   = buf.get(idx)     & 0xFF;
                            int g   = buf.get(idx + 1) & 0xFF;
                            int b   = buf.get(idx + 2) & 0xFF;
                            if (r < bt && g < bt && b < bt) white++;
                            total++;
                        }
                    }
                } else {
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            int idx = y * rowStride + x * pixelStride;
                            int r   = buf.get(idx)     & 0xFF;
                            int g   = buf.get(idx + 1) & 0xFF;
                            int b   = buf.get(idx + 2) & 0xFF;
                            if (r > wt && g > wt && b > wt) white++;
                            total++;
                        }
                    }
                }
            } else {
                if (inverted) {
                    int bufLimit = buf.limit();
                    for (int pos = 0; pos + 3 < bufLimit; pos += 65 * pixelStride) {
                        int r = buf.get(pos)     & 0xFF;
                        int g = buf.get(pos + 1) & 0xFF;
                        int b = buf.get(pos + 2) & 0xFF;
                        if (r < bt && g < bt && b < bt) white++;
                        total++;
                    }
                } else {
                    int bufLimit = buf.limit();
                    for (int pos = 0; pos + 3 < bufLimit; pos += 65 * pixelStride) {
                        int r = buf.get(pos)     & 0xFF;
                        int g = buf.get(pos + 1) & 0xFF;
                        int b = buf.get(pos + 2) & 0xFF;
                        if (r > wt && g > wt && b > wt) white++;
                        total++;
                    }
                }
            }

            if (total == 0) return result;

            // Primary output.  Drives flash guard and overlay in doSample().
            result.lumi = white / (float) total;

        } catch (Exception e) {
            Log.e(TAG, "processFrame failed: " + e);
        }

        return result;
    }

    /**
     * Responds to a detected flash event.
     *
     * Slams overlay to 69% via LumenState - LumenAccessibilityService
     * picks this up on its next 100ms tick.
     *
     * If the user has enabled brightness control and granted WRITE_SETTINGS,
     * also hammers system screen brightness to the configured floor.
     *
     * LumenState.flashGuardActive drives the [MAX] title in buildNotification().
     * Cleared by doSample() when lumi drops below threshold after cooldown.
     */
    private void applyFlashGuard() {

    }

    // =========================================================================
    // Mode logic - writes to LumenState for overlay to consume
    // =========================================================================

    /**
     * Translates current mode into overlay opacity.
     * Called every sample cycle when flash guard is not active.
     *
     * Gate: if LumenState.enabled is false, zeroes opacity and returns.
     * LumenAccessibilityService has an independent gate as well.
     * Both must agree before any overlay is applied.
     *
     * GRADIENT is the only fully implemented mode.
     * All others are stubs pending future work - see Mode enum for TODOs.
     *
     */
    private void updateOverlayFromMode() {
        if (!LumenState.enabled) {
            LumenState.overlayOpacity = 0f;
            return;
        }

        switch (LumenState.mode) {

            case LUMI_GUARD:
                // Flash guard response is handled in doSample() was "applyFlashGuard"
                // Falls through to GRADIENT behavior when lumi is below threshold.
                LumenState.overlayOpacity = LUMI_GUARD_OVERLAY ;
                break;

            case GRADIENT:
                LumenState.overlayOpacity = OVERLAY_FLOOR
                        + (LumenState.lumi / GRADIENT_LUMI_MAX)
                        * (GRADIENT_OVERLAY_CEILING - OVERLAY_FLOOR);
                break;

            case GPS_DAYLIGHT:
                // TODO v2: drive from sunset/sunrise longitude calculation.
                LumenState.overlayOpacity = 0.15f;
                break;

            case POCKET_LOCK:
                // TODO v3: intercept all taps, prevent butt-dial.
                LumenState.overlayOpacity = 1f; //0.05f;
                break;

            case PER_APP:
                // TODO v4: per-app blacklist/whitelist, neutral dim only.
                LumenState.overlayOpacity = 0.05f + LumenState.lumi * 0.4f;
                break;

            case NIGHTSHOOT:
                // The phone has an off button.  (TODO v7)
                LumenState.overlayOpacity = 0.75f;
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
        //TODO: Settings choice for STFU
        //NotificationManager nm = getSystemService(NotificationManager.class);
        //nm.notify(NOTIF_ID, buildNotification());
        buildNotification();
    }

    /**
     * Records a lumi:overlay pair to the mapping history ring buffer.
     * Duplicate pairs (same as last recorded) are silently dropped.
     * Buffer is trimmed to HISTORY_SIZE before logging so the debug
     * file reflects exactly what the notification shows.
     *
     * lumi is quantized to nearest 5 before recording to reduce noise
     * and suppress near-duplicate pairs from minor frame variation.
     *
     * History is displayed on notification line 2 as space-separated pairs,
     * e.g.: "45:69 14:15 30:47 12:14"
     */
    private void recordMappingHistory() {
        // quantize to nearest 5 - see LUMI_QUANTIZE_STEP
        int lumi    = Math.round(LumenState.lumi * 100f / 5) * 5;
        int overlay = Math.round(LumenState.overlayOpacity * 100f);

        String pair = lumi + ":" + overlay;
        //if (mappingHistory.contains(pair)) return;  // reject any duplicate in history
        if (pair.equals(lastMappingPair)) return;
        lastMappingPair = pair;

        mappingHistory.addLast(pair);
        if (mappingHistory.size() > HISTORY_SIZE) {
            mappingHistory.removeFirst();
        }
        buildHistoryString();
    }

    /**
     * Joins the current mapping history into a single space-separated string
     * for display on notification line 2.
     * Appends -M to the most recent pair when flash guard is active.
     *
     * @return e.g. "45:69-M 14:15 30:47" or empty string if no history yet
     */
    private String buildHistoryString() {
        StringBuilder sb    = new StringBuilder();
        boolean       first = true;
        String[]      pairs = mappingHistory.toArray(new String[0]);
        for (int i = 0; i < pairs.length; i++) {
            if (!first) sb.append(" ");
            sb.append(pairs[i]);
            if (i == pairs.length - 1 && flashGuardActive) sb.append("-M");
            first = false;
        }
        return sb.toString();
    }

    /**
     * Builds the foreground notification shown in the shader drop-down.
     *
     * Title: "XLumen [MAX]" during flash guard response, "XLumen" otherwise.
     * Line 1: lumi=0.13 overlay=15% invert=off
     * Line 2: recent unique lumi:overlay pairs, space-separated
     * Line 3: sysBrightness=187/73%
     *
     * @return fully constructed Notification ready for NotificationManager
     */
    private Notification buildNotification() {
        String invertLine = LumenState.invertEnabled ? "invert=on" : "invert=off";

        String line1 = String.format(
                java.util.Locale.US,
                "lumi=%d overlay=%d%% %s",
                (Math.round(LumenState.lumi * 100f / 5) * 5),
                Math.round(LumenState.overlayOpacity * 100),
                invertLine
        );

        String line2 = buildHistoryString();

        //LumenState.sysBrightness = 0; //no, use last known value instead
        try {
            LumenState.sysBrightness = android.provider.Settings.System.getInt(
                    getContentResolver(),
                    android.provider.Settings.System.SCREEN_BRIGHTNESS);
        } catch (android.provider.Settings.SettingNotFoundException e) {
            //LumenState.sysBrightness = 0; //fukno, leave at last known value
        }

        LumenState.sysAdaptBright = ""; //Settings.System.SCREEN_BRIGHTNESS_MODE
        try {
            LumenState.sysAdaptBright = android.provider.Settings.System.getString(
                    getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE);
        } catch (Exception e) {
            Log.e("XLumen", String.valueOf(e));
        }

        String line3 = LumenState.sysBrightness >= 0
                ? String.format(java.util.Locale.US,
                "sysBrightness=%d (%d%%)",
                LumenState.sysBrightness,
                Math.round(LumenState.sysBrightness / 255f * 100))
                : "sysBrightness=unavailable";
        line3 += " " + LumenState.sysAdaptBright;

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(flashGuardActive ? "XLumen [MAX]" : "XLumen")
                .setContentText(line1)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(line1 + "\n" + line3))
                        //.bigText(line1 + "\n" + line2 + "\n" + line3))
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();

    }

    // =========================================================================
    // Dead code - retained for reference
    // =========================================================================

    /**
     * Original single-pass luminance computation.
     * Superseded by processFrame(), which combines lumi and scotopic
     * luminance in one pixel pass and supports STRIDE_65 sampling.
     *
     * // Scotopic weighted average - retained for historical reference.
     * // This approach was considered and set aside in favor of lumi.
     * // Scotopic weighting models perceptual sensitivity to wavelengths,
     * // which is academically interesting but not what XLumen needs.
     * // XLumen measures total photon energy output, not perception.
     * // The simpler measurement is the honest one.
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