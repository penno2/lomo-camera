# Lomo strength beta (0–200%)

This beta adds one control above the shutter. It adjusts the entire existing calibrated Lomo colour look in a single motion.

- **0%:** no Lomo colour transform
- **100%:** exact original v1.0.1 colour transform (the default)
- **200%:** a deliberately stronger treatment than the first beta (3x colour-map shift plus additional contrast, saturation, and a subtle dark-corner vignette)
- **Between:** smooth blend/extrapolation between the unfiltered image and the original full look

The preview now reinstalls its RenderEffect whenever the slider moves; merely changing the RuntimeShader uniform did not refresh the TextureView on the Pixel 8. The vignette's coordinate space updates on layout changes and rotation. The preview should update immediately while the slider moves (device verification still required). Captured photographs use the strength that was selected **when the shutter was pressed**, even if the slider moves while the JPEG is processed. The chosen strength persists between launches using local SharedPreferences. No networking or additional Android permissions are introduced.

The 17-cube calibration and finishing tweaks are fused into the original lookup table for still-image processing. For speed, non-100% stills use a temporary pre-blended 64-cube lookup instead of per-pixel colour grading calculations. Above 100%, a simple position-dependent vignette is applied as the photo is saved. 0–100% is intentionally unchanged from the original beta, so 100% matches the official app. The 100% path continues to use the unmodified existing table.

## Install and test

1. Open the successful **Build Android APK** GitHub Actions run for the beta branch.
2. Download the `LomoCamera-debug` artifact ZIP, extract `app-debug.apk`, and install it on your phone.
3. The debug build's package is `com.harristownapps.lomocamera.beta`, and its launcher name is **Lomo Camera Beta**. It installs **beside** the official F-Droid Lomo Camera, not over it. Debug builds from separate CI runs may have different signing keys, so uninstall an older beta if upgrading fails.
4. **Before taking photos**, move the slider from 0% to 200% and verify the *live preview* clearly responds, including after rotating the phone or switching cameras. Compare saved photos at 0%, 100%, and 200%, and verify the saved preference survives relaunch. If the preview still refuses to change, please report device/Android version and whether the preview remains stuck at 100%.

This change does not alter the official release build, F-Droid listing, or default strength.
