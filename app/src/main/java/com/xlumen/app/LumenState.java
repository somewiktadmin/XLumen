package com.xlumen.app;

/**
 * XLumen LumenState
 *
 * Single shared state object.  All three services read and write here.
 * Volatile fields only - no synchronization overhead, no IPC.
 * Same JVM, same process.  This is intentional.
 */
public class LumenState {

    // --- Master switch ---
    public static volatile boolean enabled = false;

    // --- Current toggle of a11y Color Invert ---
    public static volatile boolean invertEnabled = false;

    // --- Current mode ---
    public static volatile Mode mode = Mode.TINT;

    // --- Overlay parameters ---
    /** opacity: 0.0 = invisible, 1.0 = fully opaque */
    public static volatile float overlayOpacity = 0.05f;
    /** redBias: 0.0 = neutral dark, 1.0 = maximum red tint */
    public static volatile float overlayRedBias = 0.0f;

    // --- Ambient light (Mode 3) ---
    public static volatile float ambientLux = -1f;

    // --- Screen luminance ---
    public static volatile float screenLuminance = -1f;

    // --- Foreground app package (Mode 5 whitelist check) ---
    public static volatile String foregroundPackage = "";

    // --- Flash guard ---
    /** True while flashguard response is active and cooldown has not expired. */
    public static volatile boolean flashGuardActive = false;

    // --- Modes ---
    public enum Mode {
        NIGHTSHOT,   // Mode 1: hard lock, no bright flash ever
        TINT,        // Mode 2: luminance-driven warm tint (the soul of the app)
        RESPONSIVE,  // Mode 3: ambient light tracking via light sensor
        SCHEDULED,   // Mode 4: time-based, sunset/sunrise
        MEDIA        // Mode 5: image/video, neutral dim only
    }
}