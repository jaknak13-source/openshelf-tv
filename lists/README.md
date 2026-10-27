# Cleanup lists

One folder per TV family. Each `.txt` file is a list of Android package names to disable.

## Format

```
# comment lines start with #
com.example.package            # optional comment after the name
```

- One package per line. Names may contain only letters, digits, `_` and `.`; anything else is rejected.
- Packages that aren't installed are skipped, so a list can cover several similar models.
- Number the files in the order they should be applied. Put the stock home screen in its own last file.
- Use `optional-*.txt` for apps some people use.

## Writing a list for another TV

1. `python3 tools/openshelf.py backup` and `audit`. The backup has the full package list in `packages_system.txt`.
2. Look for the usual groups: ACR / viewing trackers (Samba TV, Alphonso and vendor "viewing information" services), vendor app stores and promo apps, shop demo mode, remote-support or device agents, feedback and bug-report senders, hotel or commercial-display services, preloaded apps.
3. Leave alone: anything for tuner, inputs, HDMI-CEC, audio output, picture settings, system update, setup wizard, Google Play services, the keyboard you use, voice search and casting. The tool's denylist protects the most important ones, but it only knows common package names.
4. Comment every line. Apply with `--dry-run`, then one file at a time with `check` in between.
5. If you share your list, include the exact model and firmware (`connect` prints both).

[Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) has tested lists for many models and is a good starting point; check its license before copying files.

## Folders

| Folder | Tested on |
|---|---|
| `sony-bravia-2019/` | Sony KD-55XG8096, Android TV 9, firmware PTT1.190515.001.S54 (EU) |
