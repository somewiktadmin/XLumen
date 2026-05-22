package com.xlumen.app;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * XLumen XLumenLog
 *
 * Single static debug logger for the entire XLumen process.
 * All classes call XLumenLog.debug() directly.
 * LumenService.debug() is a thin wrapper for backward compatibility.
 *
 * Writes to xlumen_debug.txt in app-private files dir.
 * Must be initialized once via init() before any debug() calls.
 * Safe to call from any thread - synchronized on the file path.
 *
 * Timestamp format: yyyy-MM-dd_HH-mm-ss.SSS
 * One file, append mode, guaranteed write order via synchronization.
 *
 * LOGCAT is unreliable in Bumblebee and on some post-update devices.
 * This file is the primary persistent trace log for XLumen.
 * Read via MainActivity "Read Debug Log" button.
 */
public class XLumenLog {

    private static final String FILENAME = "xlumen_debug.txt";
    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss.SSS", Locale.US);

    private static volatile File sLogFile = null;

    /**
     * Initializes the logger with the app-private files directory.
     * Must be called once before any debug() calls, typically from
     * LumenService.onStartCommand().
     *
     * Safe to call multiple times - subsequent calls are no-ops.
     *
     * @param filesDir  from Context.getFilesDir()
     */
    public static void init(File filesDir) {
        if (sLogFile == null) {
            sLogFile = new File(filesDir, FILENAME);
            android.util.Log.d("XLumen", "XLumenLog.init " + filesDir);
        }
    }

    /**
     * Appends a timestamped line to xlumen_debug.txt.
     * No-op if init() has not been called yet.
     *
     * @param msg  line to append, without trailing newline
     */
    public static void debug(String msg) {
        File logFile = sLogFile;
        String line = DATE_FORMAT.format(new Date()) + " " + msg;
        android.util.Log.d("XLumen", "XLumenLog.debug(): " + line);

        if (logFile == null) {
            android.util.Log.d("XLumenLog", line );
            return;
        }

        synchronized (FILENAME) {
            try {
                FileWriter fw = new FileWriter(logFile, true);
                fw.append( line + "\n" );
                fw.close();
            } catch (Exception e) {
                android.util.Log.e("XLumenLog",line + "\nXLumenLog.debug() write failed: " + e);
            }
        }
    }
}
