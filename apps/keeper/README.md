# OpenShelf Keeper

OpenShelf Keeper (package `tv.openshelf.keeper`) is a safety net for a TV whose bloat apps you have disabled: after every boot, and whenever you press "Apply now", it disables the apps on its list again if anything switched them back on.

It does this the same way you would from a computer: it connects to the TV's own ADB daemon on `127.0.0.1:5555` with its own RSA key and runs `pm disable-user --user 0 <package>` for each listed package that is installed and still enabled. The only other commands it sends are `pm list packages`, `pm list packages -d` and `pm list packages -e`. It never uninstalls or enables anything.

ADB debugging has to stay switched on in the TV's developer options. The first run makes the TV ask "Allow USB debugging?" for the keeper's key; tick "Always allow" and confirm. Until then the keeper reports that approval is missing and does not retry, so you won't get a stream of prompts. The key lives in the app's private storage and is excluded from backups.

## What it will not touch

- A fixed deny list in `src/tv/openshelf/keeper/core/Plan.java`: every package starting with `com.sony.dtv.tvinput`, the keeper itself, and `com.sony.dtv.tvx`, `com.android.tv.settings`, `com.android.vending`, `com.google.android.gms`, `com.android.systemui`, `com.google.android.katniss`, `com.google.android.apps.mediashell`, `com.sony.dtv.braviasyncservice`, `com.sony.dtv.audiooutput`, `com.sony.dtv.hbbtvlauncher`, `com.sony.dtv.systemupdate`, `com.spocky.projengmenu`, `tv.openshelf.home`, `io.github.jqssun.airplay`.
- The stock home screen (`com.google.android.tvlauncher`), unless OpenShelf (`tv.openshelf.home`) or Projectivy (`com.spocky.projengmenu`) is installed and enabled. Without a replacement, Home would end up on a Sony settings screen.
- Package names outside `[a-zA-Z0-9_.]`. These are rejected at build time and checked again on the TV.

## Package lists

The list is compiled into the APK. By default the build reads the four core lists `../../lists/sony-bravia-2019/0*.txt`; `optional-extras.txt` is not included. Set `KEEPER_LISTS` to use other files (space-separated, globs allowed, paths without spaces):

```sh
KEEPER_LISTS="../../lists/sony-bravia-2019/0*.txt ../../lists/sony-bravia-2019/optional-extras.txt" ./build.sh
```

The build prints which files it used and how many packages it embedded. A line with a space inside a package name, or any other invalid character, stops the build.

To undo a single package on the TV: `adb shell pm enable <package>`. Remove it from the list and rebuild, or the keeper disables it again on the next boot.

## Build

You need JDK 17, `openssl`, `zip` and the Android SDK with `platforms;android-28` and `build-tools;34.0.0`. No Gradle.

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
./build.sh     # -> build/openshelf-keeper.apk
./test.sh      # plan rules, key storage, run log, ADB client against a fake adbd
adb install build/openshelf-keeper.apk
```

`test.sh` also checks the key encoding against your computer's adb key (`~/.android/adbkey`, or the path in `ADBKEY`). Without one, that check is skipped and says so. The toolchain lookup is in `../env.sh`; on Windows use WSL. The first build creates a signing key in `signing/` (git-ignored). Keep it for future updates.

## Permissions

- `INTERNET`: only for the socket to the TV's own adbd on `127.0.0.1:5555`.
- `RECEIVE_BOOT_COMPLETED`: to start a run after each boot.
- `FOREGROUND_SERVICE`: a run is a short foreground service with a notification, so Android does not stop it halfway.

## Screen

Shows the connection state, the time and result of the last run, and the log of that run. The log keeps the last 50 runs in the app's storage. Its lines are in English; the rest of the UI follows the TV language (English or German).

## Artwork

```sh
java -Djava.awt.headless=true tools/MakeArt.java res
```
