# Safety and undo

## What the tool will and won't do

- It only **disables** packages (`pm disable-user --user 0`). Nothing is uninstalled from the system partition, so a factory reset always brings the TV back to stock.
- Some packages can never be disabled, even if a list contains them: the TV engine and inputs, Settings, Play Store, Google Play services, the system UI, voice search, Chromecast, soundbar control, the HbbTV red button, system updates, the Android core (framework, shell, system providers, TV keyboard and setup wizard), OpenShelf itself, Projectivy Launcher and the AirPlay receiver.
- The stock home screen (the Android TV launcher, the older Leanback launcher or the Google TV launcher) is only disabled when a replacement home app is installed and enabled. On Android TV, if a home app fails, Android falls back to a built-in screen. Google TV models behave differently; see [troubleshooting.md](troubleshooting.md).
- Every package the tool disables is written to `backups/undo.txt`. `apply` asks before changing anything unless you pass `--yes`.

## Undo

```bash
python3 tools/openshelf.py restore                  # everything in backups/undo.txt
python3 tools/openshelf.py restore lists/sony-bravia-2019/03-unused-extras.txt   # just one list
```

If OpenShelf Keeper is installed, uninstall it first. Otherwise it switches the apps off again at the next boot.

Without a computer: Settings > Apps > See all apps > Show system apps, open the app, choose Enable.

Last resort: a factory reset (Settings > Device Preferences > Reset). You will need to sign in to your apps again.

## Restarts

After disabling packages, wait at least 30 seconds before restarting. Android stores the change a few seconds later, and a restart straight away can lose it. This was confirmed on the test TV: an immediate `adb reboot` undid the change, a restart after a pause did not.

## Debugging access

ADB over the network gives full control of the TV to approved computers. A new computer can only connect after someone accepts the prompt on the TV with the remote. Keep the TV on your home network, never forward port 5555 in your router, and decline any prompt you didn't expect.

If you don't use OpenShelf Keeper, switch ADB debugging off when you're done. If you do use it, debugging has to stay on; its key is stored in the keeper's private app storage and is never backed up.

## Privacy

- The OpenShelf home screen has no internet permission.
- OpenShelf Keeper only connects to 127.0.0.1 (the TV itself).
- `backups/` contains your TV's package list, settings and a screenshot. It is git-ignored; don't publish it.
- AdGuard DNS, if you turn it on, sees which hostnames the TV looks up.
