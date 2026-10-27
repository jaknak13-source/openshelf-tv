# Contributing

The most useful contribution is a cleanup list for a TV that isn't covered yet. See [lists/README.md](../lists/README.md) for the format. A new list needs:

- the exact model and firmware it was tested on,
- a comment on every line saying what the package is,
- a note in the pull request on what you checked afterwards (`openshelf.py check` output is enough).

For code changes, run the tests before opening a pull request:

```bash
python3 -m unittest discover -s tests
(cd apps/home && ./test.sh)
(cd apps/keeper && ./test.sh)
```

Don't commit backups, screenshots or logs from your own TV. They can contain your IP address, Wi-Fi name or account details. The `.gitignore` covers the default output folders.

By contributing you agree that your changes are released under the project's license (PolyForm Noncommercial 1.0.0).
