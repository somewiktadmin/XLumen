package com.xlumen.app;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * XLumen XLumenBrightnessLog
 *
 * Logs ambient brightness calibration data to a per-session NDJSON file.
 * One file per session, named with session start timestamp.
 * One device per file - files are meant to be collected and compared
 * across devices separately, not merged.
 *
 * File format: NDJSON (newline-delimited JSON).
 * Line 1: session header with device identity and XLumen version.
 * Subsequent lines: one record per interval with brightness, ambientLux,
 * and overlay opacity.
 *
 * ambientLux is -1 until SensorEventListener is wired up (TODO v2).
 * The field is present in the format now so the schema does not change
 * when ambient detection is added.
 *
 * Interval is user-configurable via LumenPrefs.KEY_BRIGHTNESS_LOG_INTERVAL_MS.
 * 0 = logging disabled.
 *
 * Files are written to app-private files dir alongside xlumen_debug.txt.
 * Filename: yyyy-MM-dd_HH-mm-ss_xlumen_bright.ndjson
 *
 ***
 *** Device Explorer panel in Android Studio - usually on the right side toolbar,
 *** looks like a little phone with a folder.  If it's not visible: View > Tool
 *** Windows > Device Explorer or Device File Explorer.
 ***
 *** Then navigate: /data/data/com.xlumen.app/files/
 ***
 *** Bookmark that path mentally.  It's the same place xlumen_debug.txt lives.
 ***
 *
 */
public class XLumenBrightnessLog {

    private static final String TAG             = "XLumenBrightnessLog";
    private static final String XLUMEN_VERSION  = "1.0";

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);

    private final Context mContext;
    private final File    mLogFile;
    private long          mLastLogTime = 0;

    /**
     * Creates a new brightness log for this session.
     * File is named with the session start timestamp and created immediately
     * with a header line containing device identity.
     *
     * @param context   app context, used for ContentResolver and filesDir
     */
    public XLumenBrightnessLog(Context context) {
        mContext = context;

        String filename = DATE_FORMAT.format(new Date()) + "_xlumen_bright.ndjson";
        mLogFile = new File(context.getFilesDir(), filename);

        writeHeader();
    }

    /**
     * Writes the session header line with device identity and XLumen version.
     * Called once on construction.
     */
    private void writeHeader() {
        String header = "{"
                + "\"xlumen\":\"" + XLUMEN_VERSION + "\""
                + ",\"model\":\""        + Build.MODEL        + "\""
                + ",\"manufacturer\":\"" + Build.MANUFACTURER + "\""
                + ",\"android\":"        + Build.VERSION.SDK_INT
                + ",\"created\":\""      + DATE_FORMAT.format(new Date()) + "\""
                + "}";
        writeLine(header);
    }

    /**
     * Writes one brightness record if the configured interval has elapsed.
     * Call from LumenService.doSample() every cycle - interval gating is
     * handled internally.
     *
     * No-op if interval is 0 (logging disabled) or interval has not elapsed.
     *
     * @param prefs  current LumenPrefs instance for interval and brightness floor
     */
    public void maybelog(LumenPrefs prefs) {
        int intervalMs = prefs.getBrightnessLogIntervalMs();
        if (intervalMs == 0) return;

        long now = System.currentTimeMillis();
        if (now - mLastLogTime < intervalMs) return;
        mLastLogTime = now;

        int sysBrightness = -1;
        try {
            sysBrightness = Settings.System.getInt(
                    mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS);
        } catch (Settings.SettingNotFoundException e) {
            // unavailable on this device
        }

        String record = "{"
                + "\"ts\":\""         + DATE_FORMAT.format(new Date())      + "\""
                + ",\"brightness\":"  + sysBrightness
                + ",\"ambientLux\":"  + LumenState.ambientLux
                + ",\"overlay\":"     + String.format(Locale.US, "%.4f", LumenState.overlayOpacity)
                + ",\"lumi\":"        + String.format(Locale.US, "%.4f", LumenState.lumi)
                + "}";
        writeLine(record);
    }

    /**
     * Appends a line to the log file.
     * Silently swallows exceptions - brightness logging is best-effort,
     * must never interfere with the main sampling loop.
     *
     * @param line  text to append, without trailing newline
     */
    private void writeLine(String line) {
        try {
            FileWriter fw = new FileWriter(mLogFile, true);
            fw.append(line).append("\n");
            fw.close();
        } catch (Exception e) {
            android.util.Log.e(TAG, "writeLine failed: " + e);
        }
    }
}