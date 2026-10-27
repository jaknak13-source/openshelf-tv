# Troubleshooting

**`connect` says "connection refused"**
Debugging is off, or the TV needs a full restart (hold power on the remote, choose Restart). On Android 11+ check whether your TV uses "Wireless debugging" with pairing instead; see [setup-tv.md](setup-tv.md).

**`connect` says "unauthorized"**
Look at the TV: accept "Allow USB debugging?" with "Always allow". If no prompt appears, open Developer options, revoke USB debugging authorisations, and connect again.

**Apps came back after a restart**
You probably restarted within a few seconds of `apply`. Run `apply` again and wait 30 seconds before restarting. If they come back even after a pause, a firmware update or a vendor service is re-enabling them: install OpenShelf Keeper.

**Pressing Home still opens the old home screen**
`04-stock-home.txt` hasn't been applied, or another launcher is installed and Android is asking which to use. Check with:
`adb -s $TV_HOST:5555 shell cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN`

**Google TV models take Home back**
Google TV's launcher (`com.google.android.apps.tv.launcherx`) and its setup wizard are more tightly bound than the older Android TV launcher. Disabling them can leave a black screen on some models. Test with a launcher you can reach from the app list, keep a backup, and don't disable the setup wizard.

**Play Store says the AirPlay app isn't compatible**
The Play build is 64-bit only. Use the GitHub or F-Droid APK; see [airplay.md](airplay.md).

**The TV doesn't show up in Screen Mirroring**
Phone and TV must be on the same network (guest Wi-Fi often blocks this). Open the AirPlay app once on the TV and check the server is running. `dns-sd -B _airplay._tcp` on a Mac lists receivers it can see.

**A streaming app stops playing after turning on ad-blocking DNS**
`python3 tools/openshelf.py tweak dns off`. Some ad-funded apps refuse to play without their ad servers.

**Stutter on the first scroll after the home screen starts**
Expected on low-end TVs: the TV uploads each app banner to the graphics chip the first time it's shown. Later scrolling is smooth. `openshelf.py frames` measures it.

**Building fails: android.jar or build-tools not found**
Set `ANDROID_SDK_ROOT` and install `platforms;android-28` and `build-tools;34.0.0` with `sdkmanager`; see [install.md](install.md).
