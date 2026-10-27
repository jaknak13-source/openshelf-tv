# AirPlay receiver patch

A small patch for [AirPlay Receiver](https://github.com/jqssun/android-airplay-server) by jqssun (GPL-3.0). When a phone starts mirroring, the receiver brings itself to the front. With the patch, it goes back to whatever was on screen before (an app, live TV or the home screen) when the last sender disconnects. If you opened the receiver by hand, nothing changes.

This folder contains only the patch script, not the receiver itself. `apply_patch.py` edits the smali code of a decoded release APK: it adds a flag to `AirPlayService`, sets it in `launchMainActivity`, and in `onConnectionDestroy` sends `MainActivity` an intent with the boolean extra `openshelf_return`, which makes the activity call `moveTaskToBack(true)`. The script looks for exact code anchors and stops with an error if one is missing, so a different upstream version fails instead of being half-patched. It was written against v0.0.36.

## Build

You need JDK 17, the Android SDK build-tools 34.0.0 (see `../env.sh`), `python3`, `openssl` and [apktool](https://apktool.org) (tested with 3.0.3). Download the upstream `app-release.apk` for v0.0.36 from the project's releases page, then:

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
APKTOOL_JAR=/path/to/apktool.jar ./build.sh /path/to/app-release.apk
# -> build/airplay-patched.apk
```

`apksigner` prints warnings about `META-INF/` entries that are not covered by the v1 signature; those come from the upstream APK and are harmless for v2/v3-signed installs.

The result is signed with your own key, created in `signing/` (git-ignored) on the first build. Android will not install it over the upstream build, which has a different signature: uninstall the upstream app first, then `adb install build/airplay-patched.apk`.

## License

The patched APK is a modified version of a GPL-3.0 program. If you pass it on, the GPL applies: include or offer the upstream source and this patch.
