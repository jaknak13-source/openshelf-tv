# Preparing the TV: turn on network debugging

Everything in this project talks to the TV over ADB (Android Debug Bridge) on your home network. ADB is off by default and hidden behind the developer menu.

## 1. Unlock the developer menu

With the remote:

| Step | English menu | German menu |
|---|---|---|
| 1 | Home > Settings (gear icon, top right) | Home > Einstellungen |
| 2 | Device Preferences > About | Geräteeinstellungen > Info |
| 3 | Select **Build** and press OK seven times | **Build** siebenmal mit OK drücken |
| 4 | "You are now a developer!" | „Sie sind jetzt Entwickler!" |

On some TVs the first screen is a small quick-settings bar; scroll to its end and open Settings from there. Some brands put About under System instead of Device Preferences.

## 2. Turn on debugging

Back once, then open **Developer options** (Entwickleroptionen) at the bottom of Device Preferences and switch on:

- **USB debugging** / **ADB debugging** (ADB-Debugging)
- **Network debugging**, if your TV lists it separately

Android 11 and newer TVs may show **Wireless debugging** with a pairing code instead. Then pair first: `adb pair <ip>:<pairing-port>` with the code shown on the TV, and connect to the port shown under Wireless debugging.

## 3. Find the TV's IP address

Settings > Network & Internet > your Wi-Fi or Ethernet connection, or look it up in your router's device list. Giving the TV a fixed address in the router saves you from looking it up again.

## 4. Connect

```bash
export TV_HOST=<the TV's IP address>
python3 tools/openshelf.py connect
```

The TV now shows **"Allow USB debugging?"**. Tick **"Always allow from this computer"** and press OK. The tool then prints the model, Android version and free memory.

If `connect` says "connection refused", debugging isn't on yet, or the TV needs a full restart (hold the power button on the remote and choose Restart).

## When you are done

If you don't install OpenShelf Keeper, you can switch ADB debugging off again; everything else keeps working. Under Developer options, "Revoke USB debugging authorizations" removes every computer you approved.
