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
 * MediaProjection permission must be granted by the user each session --
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

    // --- Notification ---
    public static final String CHANNEL_ID  = "xlumen_service";
    public static final int    NOTIF_ID    = 1;

    // --- State ---
    private MediaProjection  mProjection;
    private VirtualDisplay   mVirtualDisplay;
    private ImageReader      mImageReader;
    private Handler          mHandler;
    private boolean          mRunning = false;

    // --- Intent extras ---
    public static class Extras {
        public static final String RESULT_CODE     = "result_code";
        public static final String PROJECTION_DATA = "projection_data";
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }


        try {
            int    resultCode     = intent.getIntExtra(Extras.RESULT_CODE, -1);
            Intent projectionData = intent.getParcelableExtra(Extras.PROJECTION_DATA);

            //createNotificationChannel();

            /*
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, buildNotification("XLumen starting..."),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIF_ID, buildNotification("XLumen starting..."));
            }*/

            //startForeground(NOTIF_ID, buildNotification("osc XLumen starting..."));

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

        return START_REDELIVER_INTENT;
    }

    /*
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int    resultCode      = intent.getIntExtra(Extras.RESULT_CODE, -1);
        Intent projectionData  = intent.getParcelableExtra(Extras.PROJECTION_DATA);

        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("XLumen running"));

        mHandler = new Handler(Looper.getMainLooper());
        startProjection(resultCode, projectionData);
        scheduleNextSample();

        return START_REDELIVER_INTENT;
    }*/

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

    // -------------------------------------------------------------------------
    // MediaProjection setup / teardown
    // -------------------------------------------------------------------------

    private void startProjection(int resultCode, Intent data) {

        // startForeground MUST come before getMediaProjection on Android 14.
        if (Build.VERSION.SDK_INT >= 34) {
            createNotificationChannel();
            startForeground(NOTIF_ID, buildNotification("XLumen running"),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            createNotificationChannel();
            startForeground(NOTIF_ID, buildNotification("XLumen running"));
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

    /*
    private void startProjection(int resultCode, Intent data) {
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

            startForeground(NOTIF_ID, buildNotification("XLumen running"),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, buildNotification("XLumen running"));
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
        Log.i(TAG, "Projection started at " + w + "x" + h);
    }*/

    /*
    private void startProjection(int resultCode, Intent data) {
        MediaProjectionManager mgr =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mProjection = mgr.getMediaProjection(resultCode, data);

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, buildNotification("sp XLumen starting..."),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, buildNotification("sp XLumen starting..."));
        }

        DisplayMetrics metrics = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE))
                .getDefaultDisplay().getRealMetrics(metrics);

        // rest of startProjection unchanged...

        // Capture at 1/8 linear resolution -- 1/64 of total pixels.
        // We only need luminance, not a viewable image.
        // On a Moto G Play this keeps CPU impact minimal.
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
        Log.i(TAG, "Projection started at " + w + "x" + h);
    }*/

    private void tearDownProjection() {
        if (mVirtualDisplay != null) { mVirtualDisplay.release(); mVirtualDisplay = null; }
        if (mImageReader   != null) { mImageReader.close();       mImageReader   = null; }
        if (mProjection    != null) { mProjection.stop();         mProjection    = null; }
    }

    // -------------------------------------------------------------------------
    // Sampling loop
    // -------------------------------------------------------------------------

    private void scheduleNextSample() {
        if (!mRunning) return;
        LumenPrefs prefs = new LumenPrefs(this);
        mHandler.postDelayed(this::doSample, prefs.getSampleIntervalMs());
    }

    private void doSample() {
        if (!mRunning) return;

        float luminance = computeScreenLuminance();
        if (luminance >= 0f) {
            LumenState.screenLuminance = luminance;
            updateOverlayFromMode();
        }

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
            Log.w(TAG, "Luminance sample failed: " + e.getMessage());
            return -1f;
        }
    }

    // -------------------------------------------------------------------------
    // Mode logic -- writes to LumenState for overlay to consume
    // -------------------------------------------------------------------------

    private void updateOverlayFromMode() {

        // Gate 1: service running but user has not activated XLumen.
        // Write zeros and return -- overlay stays invisible.
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
                // Minimum 15% overlay always applied -- never fully transparent.
                // Scales up to 75% at maximum luminance.
                // Red bias fixed at 0.8 -- warm tint, not pure red.
                LumenState.overlayOpacity = 0.05f + (LumenState.screenLuminance * 0.70f);
                LumenState.overlayRedBias = 0.8f;
                break;

            case NIGHTSHOT:
                // Hard lock.  Maximum overlay, maximum red bias.
                // No gradual ramp -- a single white flash ruins dark adaptation.
                LumenState.overlayOpacity = 0.75f;
                LumenState.overlayRedBias = 1.0f;
                break;

            case RESPONSIVE:
                // TODO: drive from light sensor, not screen luminance.
                // LumenState.ambientLux will be written by a SensorEventListener.
                // Placeholder until sensor code is added.
                LumenState.overlayOpacity = 0.05f;
                break;

            case SCHEDULED:
                // TODO: drive from time of day and sunset/sunrise calculation.
                LumenState.overlayOpacity = 0.15f;
                break;

            case MEDIA:
                // Neutral dim only -- no red bias.
                // Opacity driven by luminance, red bias forced to zero.
                LumenState.overlayOpacity = 0.05f + LumenState.screenLuminance * 0.4f;
                LumenState.overlayRedBias = 0f;
                break;
        }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private void createNotificationChannel() {
        NotificationChannel chan = new NotificationChannel(
                CHANNEL_ID,
                "XLumen Service",
                NotificationManager.IMPORTANCE_LOW
        );
        getSystemService(NotificationManager.class).createNotificationChannel(chan);
    }

    private Notification buildNotification(String status) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("XLumen")
                .setContentText(status)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
    }
}