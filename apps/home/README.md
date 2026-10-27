# OpenShelf (home screen)

A replacement home screen for Android TV 9 and newer (package `tv.openshelf.home`). It shows four rows: Favorites, Apps, Inputs and System (Settings, the AirPlay receiver and OpenShelf Keeper if installed, and "Edit home screen"). It has no recommendation rows or ads and no network permission.

Hold OK on a tile (or press the menu button) to add it to the favorites, move it, or hide it. "Edit home screen" in the System row picks a background and brings hidden tiles back.

The default favorites are Live TV (`com.sony.dtv.tvx`), Live Channels (`com.google.android.tv`), Netflix, YouTube, SmartTube, Disney+ and Prime Video. Any that are not installed are skipped. The list is in `src/tv/openshelf/home/Prefs.java`.

The UI is in English, with German used when the TV language is German (`res/values-de/`).

## Build

You need JDK 17 and the Android SDK with `platforms;android-28` and `build-tools;34.0.0`. No Gradle.

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
./build.sh     # writes build/openshelf-home.apk
./test.sh      # plain-JVM tests for the row rules
```

`BUILD_TOOLS_VERSION` selects another build-tools version. The toolchain lookup is in `../env.sh`. Tested on macOS; Linux uses the same commands. On Windows, use WSL (Git Bash may work but is untested). The build stops if less than 2 GB of disk space is free.

The first build creates a signing key in `signing/` (git-ignored). Keep it: Android only installs an update over an existing copy if it is signed with the same key.

## Install

```sh
adb install build/openshelf-home.apk
```

Android shows a chooser the next time Home is pressed while two home apps are installed; pick OpenShelf. To stop the stock home screen from coming back, disable it with `lists/sony-bravia-2019/04-stock-home.txt` (see `../../docs/install.md`, step 4).

## Custom background

Copy JPG or PNG files to `/sdcard/Pictures/Wallpaper/` (for example `adb push picture.jpg /sdcard/Pictures/Wallpaper/`), then pick one under System > Edit home screen. The picture is copied into the app's own storage.

## Permissions

- `READ_EXTERNAL_STORAGE`: only to list `/sdcard/Pictures/Wallpaper/` in the edit screen. Android asks for it only when you select "Allow access to pictures" there; the rest of the app works without it.

## Artwork

`res/drawable-xhdpi/banner.png` and `res/mipmap-xhdpi/ic_launcher.png` are generated from the bundled wallpaper:

```sh
java -Djava.awt.headless=true tools/MakeArt.java res
```
