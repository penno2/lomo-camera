# Lomo Camera

A tiny Android camera that does one thing: it shoots vivid, cross-processed Lomo-style photos.

Lomo Camera opens straight into the filtered view and deliberately avoids becoming a general-purpose camera suite. There is no filter picker, gallery browser, account, advertising, analytics or network code.

## Features

- live Lomo-style preview using Android `RuntimeShader`
- full-resolution JPEG capture through Camera2
- the same calibrated colour treatment applied to saved photos
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

The remaining binary store artwork (icon and screenshots) should be added before tagging `v1.0`.

## Current release status

The codebase is being prepared for the 1.0 release. The release version is `versionName 1.0`, `versionCode 1`.

Known limitation: processed JPEGs do not yet preserve the complete original camera EXIF set such as ISO, exposure time and aperture. Richer photographic EXIF is planned as a later improvement.
