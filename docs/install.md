# Installing OpenShelf on the TV

Order matters: back up, clean up, then install the home screen before switching off the stock one.

## Tools on your computer

| Tool | macOS | Linux (Debian/Ubuntu) | Windows |
|---|---|---|---|
| adb | `brew install --cask android-platform-tools` | `sudo apt install adb` | [platform-tools zip](https://developer.android.com/tools/releases/platform-tools), add the folder to PATH |
| Python 3.9+ | preinstalled or `brew install python` | `sudo apt install python3` | [python.org](https://www.python.org/downloads/) (`py` launcher) |
| JDK 17 (only to build the apps) | `brew install openjdk@17` | `sudo apt install openjdk-17-jdk` | [Temurin 17](https://adoptium.net/) |
| Android SDK, platform 28 + build-tools 34 (only to build) | `brew install --cask android-commandlinetools` | [command-line tools](https://developer.android.com/studio#command-line-tools-only) | same as Linux |

Install the SDK parts once (about 500 MB):

```bash
sdkmanager --sdk_root="$HOME/android-sdk" "platforms;android-28" "build-tools;34.0.0"
export ANDROID_SDK_ROOT="$HOME/android-sdk"
export JAVA_HOME=<path to JDK 17>      # macOS: $(/usr/libexec/java_home -v 17)
```

The build scripts are bash. On Windows run them in Git Bash or WSL; the Python tool runs natively.

## 1. Backup and audit

```bash
export TV_HOST=<the TV's IP address>
python3 tools/openshelf.py connect
python3 tools/openshelf.py backup
python3 tools/openshelf.py audit
```

The backup lands in `backups/<date-time>/` (package lists, settings, memory, a screenshot). It stays on your computer and is git-ignored.

## 2. Clean up

Use the lists for your model (Sony 2019: `lists/sony-bravia-2019/`). Always dry-run first:

```bash
python3 tools/openshelf.py apply lists/sony-bravia-2019/01-tracking-ads-demo.txt --dry-run
python3 tools/openshelf.py apply lists/sony-bravia-2019/01-tracking-ads-demo.txt
python3 tools/openshelf.py check
```

Repeat for `02-...` and `03-...`. Read `03-unused-extras.txt` before applying it; remove lines for apps you use. Leave `04-stock-home.txt` for step 4.

## 3. Home screen

```bash
apps/home/build.sh
python3 tools/openshelf.py install apps/home/build/openshelf-home.apk
```

Open it once from the app list. Press OK and hold on any tile to move it, add it to Favorites or hide it.

## 4. Make it the home screen

```bash
python3 tools/openshelf.py apply lists/sony-bravia-2019/04-stock-home.txt
```

The tool only switches off the stock home screen when OpenShelf is installed and enabled. Press Home on the remote: OpenShelf opens. If Android asks which app to use, pick OpenShelf and "Always". Then wait 30 seconds, restart the TV and run `check --reboot` to confirm it survives a restart.

## 5. AirPlay

See [airplay.md](airplay.md).

## 6. OpenShelf Keeper (optional)

Only needed if you want the cleanup re-applied automatically, for example after a firmware update. Build it with the same lists you applied:

```bash
KEEPER_LISTS="lists/sony-bravia-2019/01-tracking-ads-demo.txt lists/sony-bravia-2019/02-hotel-business.txt lists/sony-bravia-2019/03-unused-extras.txt lists/sony-bravia-2019/04-stock-home.txt" apps/keeper/build.sh
python3 tools/openshelf.py install apps/keeper/build/openshelf-keeper.apk
```

Open OpenShelf Keeper on the TV and press the apply button. The TV asks "Allow USB debugging?" for the keeper's own key: tick "Always allow" and confirm. From then on it runs by itself after every boot. ADB debugging must stay on.

## 7. YouTube without ads (optional)

[SmartTube](https://github.com/yuliskov/SmartTube/releases) is open source. Pick the APK that matches the TV's CPU (`adb shell getprop ro.product.cpu.abilist`: `armeabi-v7a` means 32-bit, `arm64-v8a` 64-bit) and install it with `openshelf.py install`. It updates itself.

## 8. Faster menus and ad-blocking DNS (optional)

```bash
python3 tools/openshelf.py tweak animations 0.5
python3 tools/openshelf.py tweak dns adguard      # 'tweak dns off' to undo
```

After turning on the DNS, start the streaming apps you use and check that video plays. Ad-funded apps are the ones most likely to break.
