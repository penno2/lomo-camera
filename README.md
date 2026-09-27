# Lomo Camera v0.1

A tiny Android camera whose only look is the Camera ZOOM FX-inspired Lomo treatment we calibrated from the supplied Pixel 8 photo pairs.

## What it does

- opens straight into the filtered camera
- live Lomo preview (Android `RuntimeShader` + a compact 17×17×17 runtime LUT)
- full-resolution JPEG capture through Camera2
- applies the same LUT to the saved photo
- tap to focus
- pinch to zoom (including the logical camera's sub-1× range when Android exposes it)
- flash: OFF → AUTO → ON
- front/rear camera switch
- saves to `DCIM/Lomo`

That's it. There is no filter picker, gallery, account, analytics or network code.

## Privacy

The manifest requests **only**:

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

There is deliberately **no `INTERNET` permission** and no location permission. Photos are written through Android MediaStore, so no broad storage permission is needed on modern Android.

## Device / Android target

v0.1 intentionally targets Android 13+ (`minSdk 33`) because the live preview uses `RuntimeShader`. It is aimed first at the Pixel 8 / GrapheneOS setup it was designed for.

## Build in Android Studio

This is a standard Java Android Studio project with no third-party runtime dependencies.

Current build settings:

- Android Gradle Plugin: 9.4.1
- Gradle: 9.6.0
- compileSdk: 36
- targetSdk: 36
- Java: 17

Open the `LomoCamera` folder in Android Studio, let Gradle sync, then run the `app` configuration on the phone.

If Android Studio asks for SDK components, install **Android SDK Platform 36** and **Build Tools 36.0.0**.

## One-click GitHub build

`.github/workflows/android.yml` is included. Push this project to a GitHub repo and run **Build Android APK** from Actions (or push to `main`/`master`). The workflow uploads `LomoCamera-debug` containing `app-debug.apk`.

## Calibration

The LUT is not copied from Camera ZOOM FX. A 33×33×33 calibration LUT was fitted from corresponding pixels in three user-supplied original → Camera ZOOM FX Lomo image pairs. The app uses a compact 17×17×17 resampling of that fitted mapping; trilinear interpolation restores smooth colour transitions while keeping the runtime asset small. It captures the characteristic cool/blue shadows, warm/yellow highlights, saturated greens/reds and contrast shift seen in those examples.

The runtime calibration asset is:

`app/src/main/res/drawable-nodpi/lomo_lut.png`

Atlas layout is 17 blue slices laid left-to-right; within each slice X=red and Y=green. Both the live AGSL shader and the saved-image CPU path use trilinear interpolation through that same LUT.

## First-test checklist

On the Pixel 8:

1. Launch and grant Camera permission.
2. Confirm the live preview has the expected Lomo look.
3. Take one outdoor and one indoor photo.
4. Check `DCIM/Lomo` for full-resolution images.
5. Compare against Camera ZOOM FX. The LUT is deliberately easy to regenerate/tune if the live display colour-management makes the preview differ from the saved JPEG.

## Known v0.1 limitations

- Full-resolution LUT processing is CPU-side after capture, so saving can take a moment. The shutter disables until processing finishes.
- Re-encoding the processed JPEG does not currently preserve the camera's full original EXIF metadata.
- The live filter is calibrated in sRGB. Android display colour management can make the on-screen preview differ slightly from the final JPEG; that is exactly what the first on-device test is for.
