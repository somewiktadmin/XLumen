package com.xlumen.app;

/**
 * XLumen LumenState
 *
 * Single shared state object.  All three services read and write here.
 * Volatile fields only - no synchronization overhead, no IPC.
 * Same JVM, same process.  This is intentional.
 *
 * Primary sensor output is lumi - see field comment below.
 */
public class LumenState {

    // --- Master switch ---
    public static volatile boolean enabled = false;

    // --- Current toggle of a11y Color Invert ---
    public static volatile boolean invertEnabled = false;

    // --- Current mode ---
    public static volatile Mode mode = Mode.GRADIENT;

    // --- Overlay parameters ---
    /** opacity: 0.0 = invisible, 1.0 = fully opaque */
    public static volatile float overlayOpacity = 0.05f;

    // --- lumi: the real measurement ---
    /**
     * Fraction of sampled screen pixels with R > 220, G > 220, B > 220.
     * Range 0.0-1.0.  Direct proxy for total photon energy output from
     * screen to eyeball.  Drives all overlay decisions.
     * -1.0 indicates no valid frame yet.
     */
    public static volatile float lumi = -1f;

    // --- Flash guard ---
    /** True while flash guard response is active and cooldown has not expired. */
    public static volatile boolean flashGuardActive = false;

    // --- Ambient light (GPS_DAYLIGHT, TODO v2) ---
    public static volatile float ambientLux = -1f;

    // --- Foreground app package (PER_APP, TODO v4) ---
    public static volatile String foregroundPackage = "";

    /** Raw system screen brightness (0-255).  0 if unavailable.
     *  Read each sample cycle in LumenService.doSample(). */
    public static volatile int sysBrightness = 0;

    /* True is system ambient light adaptation is turned on */
    public static volatile String sysAdaptBright = "";

    // =========================================================================
    // Dead code - retained for reference
    // =========================================================================

    /**
     * Scotopic weighted luminance (R=0.06, G=0.67, B=0.27).
     *
     * @deprecated Retained for reference only.  Drives nothing.
     *             See lumi for the measurement that actually matters.
     *             Scotopic weighting is photometric modeling used to sell
     *             products.  XLumen measures total photon energy, not
     *             perceptual models.  Do not read or write this field
     *             from any active code path.
     */
    @Deprecated
    public static volatile float scotopicLuminance = -1f;

    /**
     * Red bias applied to overlay color.
     *
     * @deprecated Retained for reference only.  Drives nothing.
     *             Red bias was a perceptual experiment that did not survive
     *             contact with reality.  Overlay is neutral dark only.
     *             Do not read or write this field from any active code path.
     */
    @Deprecated
    public static volatile float overlayRedBias = 0f;

    // --- Modes ---
    public enum Mode {
        LUMI_GUARD,   // Mode 1: soul of the app.  lumi > threshold triggers [MAX] response.
        GRADIENT,     // Mode 2: progressive overlay scaling with lumi, 5% to 49%
        GPS_DAYLIGHT, // Mode 3: sunset/sunrise longitude calculation (TODO v2)
        POCKET_LOCK,  // Mode 4: butt-dial prevention, blocks taps when pocketed (TODO v3)
        PER_APP,      // Mode 5: per-app blacklist/whitelist (TODO v4)
        NIGHTSHOOT    // Mode 6: phone has an off button (TODO v7)
    }
}