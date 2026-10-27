# AirPlay on Android TV

The receiver is [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server) (GPL-3.0, built on UxPlay). iPhones, iPads and Macs see the TV under their built-in Screen Mirroring button. Nothing needs installing on the Apple devices.

## Pick the right download

The Google Play build of the receiver is 64-bit only. Many TVs run 32-bit Android (`adb shell getprop ro.product.cpu.abilist` shows `armeabi-v7a`), and Play then says the device isn't compatible. Use the APK from the project's [GitHub releases](https://github.com/jqssun/android-airplay-server/releases) or [F-Droid](https://f-droid.org/packages/io.github.jqssun.airplay); both contain 32-bit and 64-bit code. You can check that the two downloads are identical by comparing their SHA-256.

## Option A: unmodified app

```bash
python3 tools/openshelf.py install app-release.apk
python3 tools/openshelf.py airplay set server_name "Living room TV"
```

Open the app once on the TV. "Start server at boot" and "Run in background" are on by default, so the TV stays visible to Apple devices, including while it is in standby.

## Option B: with "return to previous screen"

Without the patch, the AirPlay screen stays open after you stop mirroring. The patch makes the TV go back to whatever was in front before: an app, live TV or the home screen. If you open the AirPlay app by hand, nothing changes.

```bash
export APKTOOL_JAR=/path/to/apktool.jar    # https://github.com/iBotPeaches/Apktool/releases
apps/airplay-patch/build.sh app-release.apk
```

The patched APK is signed with your own key, so Android won't install it over the original. Save the settings, uninstall, install, restore:

```bash
python3 tools/openshelf.py airplay list
adb -s $TV_HOST:5555 uninstall io.github.jqssun.airplay
python3 tools/openshelf.py install apps/airplay-patch/build/airplay-patched.apk
python3 tools/openshelf.py airplay set server_name "Living room TV"
```

The patched app no longer updates by itself. For a new upstream release, run `build.sh` with the new APK; it stops with an error if the patch no longer fits.

## Picture quality

By default the receiver advertises the TV's display mode, which is 1920x1080 on most Android TVs even with a 4K panel. You can advertise 4K instead:

```bash
python3 tools/openshelf.py airplay set resolution 3840x2160
```

On the test TV this raised an iPhone mirror from 500x1080 (H.264) to 998x2160 (H.265) with no visible lag. Restart the AirPlay app afterwards (`adb shell am force-stop io.github.jqssun.airplay`, then open it). Set it back with `airplay set resolution auto`.

To see what a sender actually delivers, turn on the statistics overlay: `airplay set debug_enabled true` (and `false` afterwards).

## Limits

- Copy-protected video (Apple TV+, Netflix on the phone) shows a black screen because of Apple's DRM.
- iOS usually limits iPhone screen mirroring to about as many pixels as 1080p; Macs and video casting can go higher.
- The audio-only AirPlay 1 protocol used by some older tools (RTSP ANNOUNCE) is not supported by this receiver.
