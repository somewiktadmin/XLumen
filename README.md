# XLumen

Dims screen based on screen luminance.

Original design goal was to toggle screen's color inversion whenever over 50% screen power.

Due to a certain abusive monopoly blockading all useful APIs, moat building by eradicating everything useful from Android 1.0, the approach is now merely to darken the screen when too bright.

Doing so, intelligently, is the challenge.  It is an ongoing adventure, one man against all of googol, and all the giant advertisers that mandate dangerous white backgrounds to make advertisements pop.

I know the giants truly fear anyone taking back even a tiny bit of their freedom.  I do expect a tremendous fight.  Not like knife fights on the streets of Brooklyn anymore, but I'M READY TO FIGHT.

---

## What "luminance" means here

XLumen does not use photometric luminance, scotopic weighting, or any wavelength-adjusted model.

Those models exist to sell things.  HID headlights, blue-light glasses, red-light therapy devices.
The math is real but the application is motivated reasoning dressed in academic clothing.

**Bright white light hurts.**  In every context.  Police lights, protest floodlights, magnesium concert
flares, and autoplay video ads all share one property: they emit a large number of photons directly
at your eyeballs.  No weighting model changes that.  No R:G:B coefficient makes it hurt less.

XLumen measures **lumi**: the fraction of screen pixels that are near-white (R > 220, G > 220,
B > 220).  This is a direct proxy for total photon energy output radiating from screen to eyeball.
More near-white pixels means more total energy, means more pain.  No model needed.

The scotopic weighted average (R=0.06, G=0.67, B=0.27) is retained in the codebase as dead code,
clearly labeled, for reference only.  It drives nothing.  It is there so future readers understand
what was considered and rejected, and why.

---

## Flash Guard AKA Lumi-Guard

Advertising networks and autoplay video routinely detonate white screens without warning.
XLumen detects these events via `lumi` threshold and responds immediately:

- Overlay slammed to 69% opacity.
- System screen brightness slammed to user-configured floor (requires WRITE_SETTINGS permission.)
- Three second cooldown before normal mode resumes.

---

## Permissions required

- **Accessibility service** - draws the overlay via TYPE_ACCESSIBILITY_OVERLAY
- **MediaProjection** - captures screen frames for lumi calculation.  Re-prompted after every reboot by Android design.
- **Modify system settings** (optional) - allows XLumen to hammer screen brightness on flash guard trigger.  Gated behind explicit user trust toggle in settings.

---

## Architecture

```
MainActivity          - MediaProjection permission request, start/stop
LumenService          - frame capture, lumi calculation, flash guard response
LumenAccessibilityService - overlay draw loop, reads LumenState
LumenState            - shared volatile state, no IPC, same JVM
LumenPrefs            - SharedPreferences wrapper, single source of truth
LumenTileService      - Quick Settings tile, mode cycling
```

---

## Modes

- **LUMI_GUARD** - soul of the app.  lumi > threshold triggers [MAX] response.  Primary mode.
- **GRADIENT** - progressive overlay scaling with lumi, 5% to 49%
- **GPS_DAYLIGHT** - sunset/sunrise longitude calculation (TODO v2)
- **POCKET_LOCK** - ambient light sensor driven. butt-dial prevention, blocks taps when pocketed (TODO v3)
- **PER_APP** - per-app blacklist/whitelist (TODO v4)
- **DESKTOP** - port to Chromebook, Windows, Mac (TODO v5)
- **NIGHTSHOOT** - phone has an off button (TODO v7)
- 
