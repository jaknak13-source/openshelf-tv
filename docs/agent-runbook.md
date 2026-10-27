# Runbook for coding agents

This file is written for a coding agent (Claude Code, Codex, Cursor and similar) that sets up OpenShelf TV for a user. Follow it in order. Humans can use it as a checklist too.

## Rules

1. **Ask before changing the TV.** Read-only commands (`connect`, `backup`, `audit`, `apply --dry-run`, `check` without `--reboot`, `airplay list`) are fine. Before every `apply`, `restore`, `install`, `uninstall`, `tweak`, `airplay set` or restart, show the user what will change and wait for a yes.
2. **Backup first.** Run `backup` before the first change and keep the folder.
3. **One list at a time**, dry-run first, `check` after each.
4. **Wait 30 seconds after `apply` before any restart.** An immediate restart can lose the change.
5. **Never disable** anything outside the provided lists without the user's agreement, and never edit the tool's built-in denylist.
6. **Prompts on the TV are the user's.** When the TV shows "Allow USB debugging?", tell the user to accept it with the remote. Don't approve it by sending remote keys unless the user explicitly tells you to.
7. **Don't download APKs from mirrors.** Only from the GitHub releases or F-Droid pages linked in the docs. Compare SHA-256 or signing certificates when two sources exist.
8. **Report results, not intentions.** After each step, show the command and its output. If a check fails, stop and say so.

## Steps

### 0. Gather facts
Ask the user for: the TV's brand and model, how they watch live TV (antenna, cable, satellite, none), what is connected over HDMI (soundbar, console, receiver), which apps they use, and whether they use voice search and Chromecast. Their answers decide which list lines to keep.

### 1. Tools
Check `adb version` and `python3 --version` (Windows: `py --version`). Install missing ones per [install.md](install.md). JDK 17 and the Android SDK are only needed when building the apps.

### 2. Connect
Have the user enable debugging ([setup-tv.md](setup-tv.md)), then:
```bash
export TV_HOST=<ip>
python3 tools/openshelf.py connect
```
If unauthorized, ask the user to accept the prompt on the TV, then run `connect` again.

### 3. Backup and audit
```bash
python3 tools/openshelf.py backup
python3 tools/openshelf.py audit
```
Summarise for the user: model, Android version, free memory, which list packages are installed, the biggest memory users.

### 4. Pick the lists
- Sony BRAVIA 2019 on Android TV 9: `lists/sony-bravia-2019/`.
- Other models: write a new list folder from the `audit` output following [../lists/README.md](../lists/README.md). Show it to the user line by line before applying.
- Remove lines for anything the user said they use (for example live TV over a tuner needs the tuner and HbbTV packages; those are on the denylist anyway).

### 5. Apply
For each list: `apply <list> --dry-run`, show the plan, get a yes, `apply <list>`, then `check`. Stop on any FAIL and `restore <list>`.

### 6. Home screen
Build `apps/home/build.sh`, install, ask the user to open it once and look at it. Only then apply the stock-home list. Confirm with the HOME resolve command in [troubleshooting.md](troubleshooting.md).

### 7. Optional parts
Offer, don't assume: AirPlay ([airplay.md](airplay.md)), SmartTube, faster animations, ad-blocking DNS, OpenShelf Keeper. Explain the trade-off of each (keeper needs ADB to stay on; DNS can break ad-funded apps; the patched AirPlay app doesn't auto-update).

### 8. Restart test
Wait 30 seconds after the last change, then `python3 tools/openshelf.py check --reboot`. It must pass: home screen, apps start, required packages enabled.

### 9. Hand over
Tell the user what changed, what to test by hand (AirPlay from their phone, soundbar volume with the TV remote, an HDMI device), and how to undo (`restore`, uninstall keeper first). Remind them that `backups/` holds data from their TV and should stay private.
