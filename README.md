# Lomo Camera

A tiny Android camera that does one thing: it shoots vivid, cross-processed Lomo-style photos.

Lomo Camera opens straight into the filtered view and deliberately avoids becoming a general-purpose camera suite. There is no filter picker, gallery browser, account, advertising, analytics or network code.

## Features

- live Lomo-style preview using Android `RuntimeShader`, with adjustable 0–200% Lomo strength
- full-resolution JPEG capture through Camera2
- the same calibrated colour treatment applied to saved photos at the selected strength
- 100% preserves the original colour look; 0% is unfiltered; 200% adds extra-vivid colour, contrast and vignetting
- chosen Lomo strength is remembered between launches
- tap to focus
- pinch to zoom
- flash off / auto / on
- front/rear camera switch
- portrait and landscape controls
- brief screen-flash and haptic shutter feedback
- photos saved to `DCIM/Lomo`

## Privacy

The manifest requests only:

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

There is deliberately no `INTERNET` permission and no location permission. Photos are written through Android MediaStore, so no broad storage permission is needed on modern Android.

Application ID: `com.harristownapps.lomocamera`

## Android target

Lomo Camera targets Android 13+ (`minSdk 33`) because the live preview uses `RuntimeShader`. It was developed primarily on a Pixel 8 running GrapheneOS and has also been tested on a Samsung Galaxy A35.

## Build

This is a Java/Gradle Android project with no third-party runtime dependencies.

Current build settings:

- Android Gradle Plugin: 9.2.1
- Gradle: 9.4.1
- compileSdk: 36
- targetSdk: 36
- Java: 17

Open the project in Android Studio, let Gradle sync, then run the `app` configuration on a connected Android device.

The GitHub Actions workflow at `.github/workflows/android.yml` also builds a debug APK and uploads it as an artifact.

## Colour calibration

The LUT is not copied from any third-party application. A calibration mapping was fitted from corresponding pixels in user-supplied before/after photographs, then implemented independently in Lomo Camera.

The app ships a compact 17×17×17 RGB LUT at:

`app/src/main/res/drawable-nodpi/lomo_lut.png`

The live AGSL shader samples it with trilinear interpolation. For saved photographs the compact LUT is expanded to a 64×64×64 in-memory lookup table for fast full-resolution processing.

See [`docs/LUT.md`](docs/LUT.md) for the clean-room provenance and technical details.

## Licence

Lomo Camera is released under the GNU General Public License v3.0 only (`GPL-3.0-only`). See [`LICENSE`](LICENSE).

## F-Droid

Upstream store metadata is kept under `fastlane/metadata/android/en-US/`. Release/submission notes are in [`docs/F-DROID.md`](docs/F-DROID.md).

Official F-Droid packaging is published; newer upstream releases are detected from version tags in GitHub.

## Current release status

Version **1.1** adds a live adjustable Lomo strength slider (`versionName 1.1`, `versionCode 3`). The official F-Droid package remains `com.harristownapps.lomocamera`; debug beta builds are separately installable as `com.harristownapps.lomocamera.beta`.

Known limitation: processed JPEGs do not yet preserve the complete original camera EXIF set such as ISO, exposure time and aperture. Richer photographic EXIF is planned as a later improvement.
