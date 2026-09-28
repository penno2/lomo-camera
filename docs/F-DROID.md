# F-Droid release notes

This document keeps the upstream pieces needed for a first submission to the official F-Droid repository in one place.

## Application identity

- App name: Lomo Camera
- Application ID: `com.harristownapps.lomocamera`
- Licence: `GPL-3.0-only`
- Source: `https://github.com/penno2/lomo-camera`
- Issue tracker: `https://github.com/penno2/lomo-camera/issues`
- Minimum Android version: Android 13 / API 33

## Upstream metadata

Store metadata lives in:

`fastlane/metadata/android/en-US/`

Before tagging v1.0, add the binary artwork files:

- `fastlane/metadata/android/en-US/images/icon.png`
- `fastlane/metadata/android/en-US/images/phoneScreenshots/1.png`
- `fastlane/metadata/android/en-US/images/phoneScreenshots/2.png`

Additional screenshots are welcome but not required for the first submission.

## Suggested fdroiddata metadata

The following is a starting point for `metadata/com.harristownapps.lomocamera.yml` in a fork of F-Droid's `fdroiddata` repository. It should be checked with the current `fdroidserver` linter before submission.

```yaml
Categories:
  - Multimedia
License: GPL-3.0-only
AuthorName: Harristown Apps
SourceCode: https://github.com/penno2/lomo-camera
IssueTracker: https://github.com/penno2/lomo-camera/issues

RepoType: git
Repo: https://github.com/penno2/lomo-camera.git

Builds:
  - versionName: '1.0'
    versionCode: 1
    commit: v1.0
    subdir: app
    gradle:
      - yes

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: '1.0'
CurrentVersionCode: 1
```

## Release sequence

1. Add the icon and screenshots under the Fastlane metadata path.
2. Confirm `versionName '1.0'` and `versionCode 1` in `app/build.gradle`.
3. Confirm the GitHub Actions debug build is green and perform one final device smoke test.
4. Tag that exact commit `v1.0` and push the tag.
5. Fork `fdroid/fdroiddata`, add `metadata/com.harristownapps.lomocamera.yml`, run/lint the metadata if practical, and open a merge request labelled as a new app.

F-Droid will build and sign the APK from source using its own infrastructure. That is intentional for this project; a Play Store build may use a different signing key, so switching between the F-Droid and Play editions can require uninstall/reinstall.
