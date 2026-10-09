# Lomo strength beta (0–200%)

This beta adds one control above the shutter. It adjusts the entire existing calibrated Lomo colour look in a single motion.

- **0%:** no Lomo colour transform
- **100%:** exact original v1.0.1 colour transform (the default)
- **200%:** an exaggerated version of the calibrated Lomo result
- **Between:** smooth blend/extrapolation between the unfiltered image and the original full look

The preview updates while the slider moves. Captured photographs use the strength that was selected **when the shutter was pressed**, even if the slider moves while the JPEG is processed. The chosen strength persists between launches using local SharedPreferences. No networking or additional Android permissions are introduced.

The 17-cube calibration and finishing tweaks are fused into the original lookup table for still-image processing. For speed, non-100% stills use a temporary pre-blended 64-cube lookup rather than three extra channel calculations per pixel. The 100% path continues to use the unmodified existing table.

## Install and test

1. Open the successful **Build Android APK** GitHub Actions run for the beta branch.
2. Download the `LomoCamera-debug` artifact ZIP, extract `app-debug.apk`, and install it on your phone.
3. The debug build's package is `com.harristownapps.lomocamera.beta`, and its launcher name is **Lomo Camera Beta**. It installs **beside** the official F-Droid Lomo Camera, not over it. Debug builds from separate CI runs may have different signing keys, so uninstall an older beta if upgrading fails.
4. Compare the view and captured photos at 0%, 100%, and 200%; test both cameras and verify the saved preference survives a relaunch.

This change does not alter the official release build, F-Droid listing, or default strength.
