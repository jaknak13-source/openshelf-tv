# OpenShelf TV

An ad-free home screen, a bloatware cleanup tool and AirPlay mirroring for Android TV sets. Built and tested on a 2019 Sony BRAVIA.

OpenShelf runs on top of the TV's own Android TV system and leaves it in place. It installs a home screen app, switches off preinstalled apps you don't need and sets up an open-source AirPlay receiver. Every change can be undone.

| Before: Sony's stock home screen | After: OpenShelf |
|---|---|
| ![Stock Sony Android TV home screen with Shop and Discover tabs and preinstalled promo apps](docs/images/stock-home.jpg) | ![OpenShelf home screen with a Favorites row and an Apps row on a dark background](docs/images/openshelf-home.jpg) |

Both screenshots are from the test TV, which is set to German. One app tile and the profile icon are blurred.

## Features

### Home screen

Four rows: Favorites, Apps, Inputs and System. The dark background takes on a tint of the focused app's color, and the date and time sit in the top right corner. There are no recommendation rows or ads, and the app has no internet permission. Hold OK on a tile to move it, hide it or add it to Favorites.

On the test TV the home screen uses about 23 MB of memory, compared with 176 MB for the stock launcher, and starts in under half a second.

### Cleanup lists

Plain text lists of system packages to switch off, for example Samba TV's viewing tracker, promo tiles, shop demo mode and hotel display services. Each line has a comment that says what the package does. The tool disables packages with `pm disable-user` and never uninstalls them.

### OpenShelf Keeper (optional)

A small app that checks after every boot that the listed apps are still disabled. If a firmware update turned one back on, it switches it off again.

### AirPlay

Screen mirroring from iPhone, iPad and Mac through the built-in Screen Mirroring button, with nothing to install on the Apple device. The receiver is [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server). An optional patch sends the TV back to the app, live TV channel or home screen you had open when mirroring stops.

### Command-line tool

`tools/openshelf.py` covers backup, audit, apply, undo, health checks and settings. It needs only Python 3.9 or newer and adb, and runs on macOS, Linux and Windows.

On the test TV (Sony KD-55XG8096, 2 GB RAM), free memory 60 seconds after a restart went from 537 MB to 778 MB.

## Which TVs

| Device | Status |
|---|---|
| Sony BRAVIA 2019 Android TV 9 (XG80, XG85, XG95 and similar) | Tested. Ready-made cleanup lists in `lists/sony-bravia-2019/`. |
| Other Android TV sets (Philips, TCL, Hisense, Sharp, other Sony years) and boxes (Nvidia Shield, Chromecast with Google TV) | Not tested yet. Nothing in the home screen, keeper, AirPlay setup or tool is specific to Sony, so they are expected to work. You need a cleanup list for your model: `openshelf.py audit` shows what is installed, and [lists/README.md](lists/README.md) explains how to write one. |
| Google TV models (2021+) | Possible, but replacing the home screen is harder; Google TV can take Home back. Read [docs/troubleshooting.md](docs/troubleshooting.md) first. |
| Amazon Fire TV with Fire OS | Android-based, untested. The tool and AirPlay should work; home screen replacement is restricted by Amazon. |
| Samsung (Tizen), LG (webOS), Roku | Not possible. These are not Android TV. |

## Quick start

You need a computer on the same network as the TV, [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) (`adb`) and Python 3.9 or newer.

1. Turn on network debugging on the TV: [docs/setup-tv.md](docs/setup-tv.md).
2. Connect and take a backup:
   ```bash
   export TV_HOST=<your TV's IP address>
   python3 tools/openshelf.py connect
   python3 tools/openshelf.py backup
   ```
3. See what would change, then apply:
   ```bash
   python3 tools/openshelf.py audit
   python3 tools/openshelf.py apply lists/sony-bravia-2019/01-tracking-ads-demo.txt --dry-run
   python3 tools/openshelf.py apply lists/sony-bravia-2019/01-tracking-ads-demo.txt
   ```
4. Build and install the home screen, then switch off the stock one: [docs/install.md](docs/install.md).
5. Check that everything still works: `python3 tools/openshelf.py check`.

On Windows use `py` instead of `python3` and `set TV_HOST=...` instead of `export`.

## Using a coding agent

A coding agent such as Claude Code can do the whole setup for you. Open this folder in the agent and tell it:

> Follow docs/agent-runbook.md to set up my TV.

The runbook has the order of steps, the checks after each one, and the points where the agent has to stop and ask you (for example before changing anything on the TV, and for the prompts you must accept with the remote).

## Undo

Every package the tool disables is logged in `backups/undo.txt`.

```bash
python3 tools/openshelf.py restore
```

If the keeper is installed, uninstall it first. Otherwise it switches the apps off again after the next restart. Without a computer, any app can be switched back on under Settings > Apps on the TV. Details: [docs/safety-and-undo.md](docs/safety-and-undo.md).

## Things to know

- ADB debugging has to stay on if you use the keeper. Only computers you approved on the TV can connect.
- After switching apps off, wait at least 30 seconds before restarting the TV. Android saves the change with a short delay; a restart straight away can lose it.
- Most Android TVs draw their menus at 1920x1080, even on 4K panels. Video apps still play in 4K, and AirPlay can be set to advertise 4K (see [docs/airplay.md](docs/airplay.md)).
- Copy-protected apps (Apple TV+, Netflix on the phone) cannot be mirrored over AirPlay. That is an Apple limit.
- Ad-blocking DNS (`openshelf.py tweak dns adguard`) blocks ad servers for the whole TV but can break ad-funded streaming apps. It is off unless you turn it on.

## Questions

### Does this need root?

No. Everything goes through ADB, which every Android TV offers in its developer options. Apps are switched off with `pm disable-user`, the same thing the Disable button under Settings > Apps does.

### Can it break the TV?

Nothing is uninstalled, so a factory reset always brings the TV back to stock. The tool refuses to disable packages the TV needs to work, such as live TV, inputs, Settings, the Play Store, Chromecast and system updates, even if a list contains them. The stock home screen is only switched off when a replacement is installed.

### What happens after a firmware update?

System updates stay on. An update can turn some of the disabled apps back on. Run `apply` again, or install OpenShelf Keeper, which does it after every boot.

### Does it remove ads inside YouTube or Netflix?

No. The home screen has no ads, but ads inside apps are not touched. For YouTube, [SmartTube](https://github.com/yuliskov/SmartTube) is a common choice. The optional ad-blocking DNS setting blocks known ad servers for the whole TV.

### Can I mirror an iPhone to an Android TV?

Yes. After the AirPlay setup the TV shows up under Screen Mirroring on the iPhone, iPad or Mac, like an Apple TV would. See [docs/airplay.md](docs/airplay.md).

### Can I use another launcher, such as Projectivy?

Yes. The tool accepts Projectivy Launcher as the replacement home screen and never disables it. The cleanup lists, the keeper and the AirPlay setup work the same with either launcher.

## Folder layout

```
apps/home/           OpenShelf home screen (Java, builds without Gradle)
apps/keeper/         OpenShelf Keeper boot-time safety net
apps/airplay-patch/  patch script for the open-source AirPlay receiver
lists/               cleanup lists per TV model
tools/openshelf.py   command-line tool
tests/               tests for the tool
config/              default app list for health checks
wallpaper/           generator for the home screen background
docs/                setup, install, agent runbook, troubleshooting
```

## Contributing

Lists for other TV models are the most welcome addition. See [.github/CONTRIBUTING.md](.github/CONTRIBUTING.md).

## License and credits

PolyForm Noncommercial 1.0.0. You may use, change and share this for free for any noncommercial purpose. Selling it, or putting it or a modified version behind a paywall, is not allowed. See [LICENSE](LICENSE).

Third-party projects this builds on (not included here, each under its own license):

- [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server), GPL-3.0. The patch script modifies your own downloaded copy; if you share the patched APK, the GPL applies to it.
- [Apktool](https://github.com/iBotPeaches/Apktool), Apache-2.0, used by the patch script.
- [SmartTube](https://github.com/yuliskov/SmartTube), MIT, recommended for YouTube without ads.
- [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv), whose model lists were a reference for the Sony lists.
- AdGuard DNS (dns.adguard-dns.com), a free public DNS service.

Sony, BRAVIA, AirPlay and other names are trademarks of their owners. This project is not affiliated with any of them.
