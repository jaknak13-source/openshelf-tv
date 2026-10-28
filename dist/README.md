# Prebuilt APKs

Ready-to-install builds of version 1.0.0, made from the source in this repository.

| File | What it is |
|---|---|
| `openshelf-home-1.0.0.apk` | The OpenShelf home screen. Needs Android TV 9 or newer. |
| `openshelf-keeper-1.0.0-sony-bravia-2019.apk` | OpenShelf Keeper, built with the four core cleanup lists in `lists/sony-bravia-2019/` (`01` to `04`). It only switches off packages from those lists that are installed. For another TV, or if you changed the lists, build the keeper yourself. |
| `SHA256SUMS.txt` | Checksums for both APKs. |

The AirPlay receiver is not included. Get it from [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server/releases) and follow [docs/airplay.md](../docs/airplay.md).

## Install

Prepare the TV first ([docs/setup-tv.md](../docs/setup-tv.md)), then from the repository folder:

```bash
python3 tools/openshelf.py install dist/openshelf-home-1.0.0.apk
```

Then continue with step 4 of [docs/install.md](../docs/install.md). For the keeper, install `dist/openshelf-keeper-1.0.0-sony-bravia-2019.apk` the same way and continue with step 6.

## Check the files

```bash
cd dist
shasum -a 256 -c SHA256SUMS.txt          # macOS and Linux
apksigner verify --print-certs openshelf-home-1.0.0.apk
```

On Windows, `certutil -hashfile <file> SHA256` prints the checksum. `apksigner` is not part of platform-tools; it is in the Android SDK build-tools (for example `build-tools/34.0.0/apksigner`).

| APK | Certificate | SHA-256 of the certificate |
|---|---|---|
| Home screen | `CN=OpenShelf` | `43:79:C4:7B:84:DD:BB:5C:2A:53:9C:AA:E4:85:4E:21:EA:B1:4F:7F:9A:F4:5C:E0:B0:18:23:E4:AB:A9:F1:D2` |
| Keeper | `CN=OpenShelf Keeper` | `87:51:5C:09:4B:6E:E0:1A:05:64:9B:80:11:7B:C2:D1:B5:EA:30:2F:5B:59:99:CD:B4:18:A8:61:5B:59:3E:B1` |

## Building your own

The signing keys for these files are not in the repository. When you build the apps yourself (`apps/home/build.sh`, `apps/keeper/build.sh`), the first build creates your own key. Android only installs an update over an existing app if both are signed with the same key, so uninstall the prebuilt app before installing your own build, or the other way round.
