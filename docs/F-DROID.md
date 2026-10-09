# Official F-Droid release process

Lomo Camera is already listed in the official F-Droid repository:
https://f-droid.org/packages/com.harristownapps.lomocamera/

## Application identity

- App name: Lomo Camera
- Official F-Droid application ID: `com.harristownapps.lomocamera`
- Debug/beta application ID: `com.harristownapps.lomocamera.beta` (side-by-side test build only)
- Licence: GPL-3.0-only
- Source: https://github.com/penno2/lomo-camera
- Issue tracker: https://github.com/penno2/lomo-camera/issues
- Minimum Android version: Android 13 / API 33

## Existing F-Droid metadata

The canonical metadata lives in F-Droid's own `fdroiddata` repository:
https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/com.harristownapps.lomocamera.yml

At the time of preparing version 1.1 it specifies:
- `AutoUpdateMode: Version`
- `UpdateCheckMode: Tags`
- `CurrentVersion: 1.0.1`
- `CurrentVersionCode: 2`

F-Droid therefore checks upstream release tags for newer versions. F-Droid's automated detection, build, signing and repository publication can take time and are **not** immediate when a GitHub tag is created.

## Version 1.1 release

- `versionName '1.1'`
- `versionCode 3`
- Release tag: `v1.1`
- Upstream What's New text: `fastlane/metadata/android/en-US/changelogs/3.txt`
- Full app description: `fastlane/metadata/android/en-US/full_description.txt`

### Checklist

1. Finish review/testing of the strength-slider beta and confirm the GitHub Actions debug build passes on the exact release candidate.
2. Merge the beta pull request into `main`. Confirm the merged `main` still has version 1.1 (code 3), and **no** further unverified commits.
3. In GitHub **Releases → Draft a new release**, create the tag `v1.1` **from `main`**, not from the beta branch.
4. Use the release notes in `docs/RELEASE-1.1.md`; publish the release. The tag is the important event for F-Droid's automatic updater.
5. Check https://f-droid.org/packages/com.harristownapps.lomocamera/ and the upstream `fdroiddata` metadata for the new version. If F-Droid does not detect/build it, inspect F-Droid build logs or report an update issue against their metadata.
6. Let existing F-Droid users update through the F-Droid app. **Do not** install GitHub debug APKs on top of official F-Droid builds: debug builds use a different package name and signing key.

A GitHub release APK is not required: F-Droid builds and signs release APKs from source. GitHub's Actions debug APK is **not** the F-Droid-distributed release.

## Privacy

This release adds no permissions. Only the Android CAMERA permission is required. There is no INTERNET or location permission.
