#!/usr/bin/env python3
"""openshelf: inspect and tune an Android TV over network ADB.

Stdlib only, Python 3.9+, works on macOS, Linux and Windows.
Run `python3 tools/openshelf.py --help` for the command list.
"""
from __future__ import annotations

import argparse
import math
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Callable, Iterable, Sequence

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_PORT = 5555
DEFAULT_UNDO = ROOT / "backups" / "undo.txt"
DEFAULT_APPS = ROOT / "config" / "check-apps.txt"

PACKAGE_RE = re.compile(r"^[a-zA-Z0-9_.]+$")

# Packages that keep the TV usable (live TV, inputs, settings, store, cast, voice,
# updates, the replacement home screen and its helpers). Never disabled, even if listed.
PROTECTED = frozenset({
    "com.sony.dtv.tvx",
    "com.android.tv.settings",
    "com.android.vending",
    "com.google.android.gms",
    "com.android.systemui",
    "com.google.android.katniss",
    "com.google.android.apps.mediashell",
    "com.sony.dtv.braviasyncservice",
    "com.sony.dtv.audiooutput",
    "com.sony.dtv.hbbtvlauncher",
    "com.sony.dtv.systemupdate",
    "tv.openshelf.home",
    "tv.openshelf.keeper",
    "io.github.jqssun.airplay",
    "com.spocky.projengmenu",  # replacement launcher; disabling it could leave no home app
})
PROTECTED_PREFIXES = ("com.sony.dtv.tvinput",)

STOCK_LAUNCHER = "com.google.android.tvlauncher"
HOME_REPLACEMENTS = ("tv.openshelf.home", "com.spocky.projengmenu")

AIRPLAY_PKG = "io.github.jqssun.airplay"
AIRPLAY_RECEIVER = "io.github.jqssun.airplay/.SettingsReceiver"

ANIMATION_KEYS = ("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
DNS_KEYS = ("private_dns_mode", "private_dns_specifier")
ADGUARD_DNS = "dns.adguard-dns.com"

REBOOT_WARNING = (
    "WARNING: wait at least 30 seconds before restarting the TV.\n"
    "Android writes package state to disk with a delay; restarting sooner can lose these changes."
)

sleep = time.sleep


class ToolError(Exception):
    pass


# --- pure helpers -----------------------------------------------------------

def parse_package_list(text: str) -> tuple[list[str], list[str]]:
    """Return (packages in first-seen order without duplicates, error messages)."""
    packages: list[str] = []
    errors: list[str] = []
    seen: set[str] = set()
    for number, raw in enumerate(text.splitlines(), 1):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        if not PACKAGE_RE.match(line):
            errors.append(f"line {number}: not a package name: {line!r}")
            continue
        if line not in seen:
            seen.add(line)
            packages.append(line)
    return packages, errors


def is_protected(package: str) -> bool:
    return package in PROTECTED or package.startswith(PROTECTED_PREFIXES)


def replacement_home(enabled: Iterable[str], planned_disable: Iterable[str]) -> str | None:
    """A replacement HOME app that is enabled now and not about to be disabled, or None."""
    enabled_set = set(enabled)
    planned = set(planned_disable)
    for package in HOME_REPLACEMENTS:
        if package in enabled_set and package not in planned:
            return package
    return None


@dataclass(frozen=True)
class PlanItem:
    package: str
    action: str  # DISABLE, SKIP or REFUSE
    reason: str = ""


def plan_disable(packages: Sequence[str], installed: Iterable[str], disabled: Iterable[str],
                 enabled: Iterable[str]) -> list[PlanItem]:
    installed_set, disabled_set = set(installed), set(disabled)
    plan: list[PlanItem] = []
    for package in packages:
        if is_protected(package):
            plan.append(PlanItem(package, "REFUSE", "protected, never disabled"))
        elif package not in installed_set:
            plan.append(PlanItem(package, "SKIP", "not installed"))
        elif package in disabled_set:
            plan.append(PlanItem(package, "SKIP", "already disabled"))
        else:
            plan.append(PlanItem(package, "DISABLE"))
    to_disable = {item.package for item in plan if item.action == "DISABLE"}
    if STOCK_LAUNCHER in to_disable and replacement_home(enabled, to_disable) is None:
        reason = "no replacement home app enabled (" + " or ".join(HOME_REPLACEMENTS) + ")"
        plan = [PlanItem(i.package, "REFUSE", reason) if i.package == STOCK_LAUNCHER else i for i in plan]
    return plan


def parse_pm_list(text: str) -> set[str]:
    return {line.strip()[len("package:"):] for line in text.splitlines() if line.strip().startswith("package:")}


def parse_devices(text: str, serial: str) -> str:
    """State of `serial` in `adb devices` output: device, unauthorized, offline, ... or missing."""
    for line in text.splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[0] == serial:
            return parts[1]
    return "missing"


def parse_meminfo(text: str) -> dict[str, int]:
    """/proc/meminfo as {key: kB}."""
    values: dict[str, int] = {}
    for line in text.splitlines():
        match = re.match(r"^(\w+):\s+(\d+)", line)
        if match:
            values[match.group(1)] = int(match.group(2))
    return values


def parse_pss(text: str, limit: int = 15) -> list[tuple[int, str]]:
    """Top processes from the "Total PSS by process" section of `dumpsys meminfo`, as (kB, name)."""
    rows: list[tuple[int, str]] = []
    in_section = False
    for line in text.splitlines():
        if line.startswith("Total PSS by process"):
            in_section = True
            continue
        if in_section:
            match = re.match(r"^\s*([\d,]+)K: (.*?)(?: \(pid \d+.*\))?$", line)
            if not match:
                break
            rows.append((int(match.group(1).replace(",", "")), match.group(2)))
    rows.sort(key=lambda row: row[0], reverse=True)
    return rows[:limit]


def parse_gfxinfo(text: str) -> dict[str, str]:
    """Frame stats from `dumpsys gfxinfo <pkg>`. Later matches win, like the awk original."""
    patterns = {
        "frames": r"Total frames rendered:\s*(\d+)",
        "janky": r"Janky frames:\s*(\d+ \([\d.]+%\))",
        "p50": r"50th percentile:\s*(\S+)",
        "p90": r"90th percentile:\s*(\S+)",
        "p99": r"99th percentile:\s*(\S+)",
    }
    stats = {key: "?" for key in patterns}
    for line in text.splitlines():
        for key, pattern in patterns.items():
            match = re.search(pattern, line)
            if match:
                stats[key] = match.group(1)
    return stats


def is_in_front(resumed_line: str, package: str) -> bool:
    return re.search(r"(?<![\w.])" + re.escape(package) + "/", resumed_line) is not None


# --- adb access -------------------------------------------------------------

@dataclass
class Result:
    returncode: int
    stdout: bytes
    stderr: bytes = b""

    @property
    def text(self) -> str:
        return self.stdout.decode("utf-8", "replace").replace("\r", "")

    @property
    def output(self) -> str:
        return (self.text + self.stderr.decode("utf-8", "replace").replace("\r", "")).strip()


Runner = Callable[[Sequence[str], float], Result]


def subprocess_runner(argv: Sequence[str], timeout: float) -> Result:
    # stdin=DEVNULL: adb reads stdin otherwise and can swallow input meant for the caller.
    try:
        proc = subprocess.run(list(argv), stdin=subprocess.DEVNULL, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise ToolError(f"adb did not answer within {timeout:.0f} s: {' '.join(argv[1:])}") from None
    return Result(proc.returncode, proc.stdout, proc.stderr)


class Adb:
    def __init__(self, exe: str, serial: str, runner: Runner = subprocess_runner):
        self.exe = exe
        self.serial = serial
        self.runner = runner

    def plain(self, *args: str, timeout: float = 30) -> Result:
        """adb command not bound to the TV (connect, devices)."""
        return self.runner([self.exe, *args], timeout)

    def run(self, *args: str, timeout: float = 60) -> Result:
        return self.runner([self.exe, "-s", self.serial, *args], timeout)

    def shell(self, *args: str, timeout: float = 60) -> Result:
        # adb passes one string to the TV's shell; quote each word so spaces survive.
        return self.run("shell", " ".join(shlex.quote(a) for a in args), timeout=timeout)

    def sh(self, *args: str, timeout: float = 60) -> str:
        return self.shell(*args, timeout=timeout).text

    def state(self) -> str:
        return parse_devices(self.plain("devices").text, self.serial)


def state_error(adb: Adb, state: str) -> ToolError:
    if state == "unauthorized":
        return ToolError(
            f"{adb.serial} is unauthorized. On the TV, accept \"Allow USB debugging?\" "
            "and tick \"Always allow from this computer\", then run the command again.")
    if state == "offline":
        return ToolError(
            f"{adb.serial} is offline. Check that the TV is on and network debugging is enabled, "
            f"then run: adb disconnect {adb.serial}  and try again.")
    return ToolError(
        f"could not reach {adb.serial} (state: {state}). Check the address, that the TV is on, "
        "and that network debugging is enabled in Developer options.")


def require_device(adb: Adb, wait: int = 10) -> None:
    """Connect if needed; raise ToolError unless the TV reaches `device` state within ~wait seconds."""
    state = adb.state()
    if state == "device":
        return
    adb.plain("connect", adb.serial)
    for _ in range(wait):
        state = adb.state()
        if state == "device":
            return
        sleep(1)
    raise state_error(adb, state)


def installed_packages(adb: Adb, flag: str = "") -> set[str]:
    args = ["pm", "list", "packages"] + ([flag] if flag else [])
    result = adb.shell(*args)
    if result.returncode != 0:
        raise ToolError(f"{' '.join(args)} failed: {result.output}")
    return parse_pm_list(result.text)


def read_lists(paths: Sequence[Path]) -> list[str]:
    packages: list[str] = []
    errors: list[str] = []
    for path in paths:
        if not path.is_file():
            raise ToolError(f"list file not found: {path}")
        found, bad = parse_package_list(path.read_text(encoding="utf-8-sig"))
        packages += [p for p in found if p not in packages]
        errors += [f"{path}: {e}" for e in bad]
    if errors:
        raise ToolError("invalid list entries, nothing was changed:\n  " + "\n  ".join(errors))
    return packages


def home_activity(adb: Adb) -> str:
    out = adb.sh("cmd", "package", "resolve-activity", "--brief",
                 "-c", "android.intent.category.HOME", "-a", "android.intent.action.MAIN")
    lines = [line for line in out.splitlines() if line.strip()]
    return lines[-1].strip() if lines else "(unknown)"


def resumed_activity(adb: Adb) -> str:
    for line in adb.sh("dumpsys", "activity", "activities").splitlines():
        if "ResumedActivity" in line:
            return line.strip()
    return ""


# --- commands ---------------------------------------------------------------

def cmd_connect(adb: Adb, args: argparse.Namespace) -> int:
    print(adb.plain("connect", adb.serial).output)
    hinted = False
    for _ in range(30):  # ~60 s, time to accept the dialog on the TV
        state = adb.state()
        if state == "device":
            break
        if state == "unauthorized" and not hinted:
            print("Waiting for authorization: on the TV, accept \"Allow USB debugging?\" "
                  "and tick \"Always allow from this computer\".")
            hinted = True
        sleep(2)
    else:
        raise state_error(adb, state)
    mem = parse_meminfo(adb.sh("cat", "/proc/meminfo"))
    print(f"Connected to {adb.serial}")
    print(f"Model        {adb.sh('getprop', 'ro.product.manufacturer').strip()} "
          f"{adb.sh('getprop', 'ro.product.model').strip()}")
    print(f"Android      {adb.sh('getprop', 'ro.build.version.release').strip()} "
          f"(SDK {adb.sh('getprop', 'ro.build.version.sdk').strip()})")
    print(f"Firmware     {adb.sh('getprop', 'ro.build.fingerprint').strip()}")
    print(f"RAM total    {mem.get('MemTotal', 0) // 1024} MB")
    print(f"MemAvailable {mem.get('MemAvailable', 0) // 1024} MB")
    return 0


def cmd_backup(adb: Adb, args: argparse.Namespace) -> int:
    require_device(adb)
    out = Path(args.out) if args.out else ROOT / "backups" / datetime.now().strftime("%Y-%m-%d_%H%M%S")
    out.mkdir(parents=True, exist_ok=True)
    snapshots = {
        "getprop.txt": ["getprop"],
        "packages_system.txt": ["pm", "list", "packages", "-s"],
        "packages_user.txt": ["pm", "list", "packages", "-3"],
        "packages_enabled.txt": ["pm", "list", "packages", "-e"],
        "packages_disabled.txt": ["pm", "list", "packages", "-d"],
        "home_launcher.txt": ["cmd", "package", "resolve-activity", "--brief",
                              "-c", "android.intent.category.HOME", "-a", "android.intent.action.MAIN"],
        "home_candidates.txt": ["cmd", "package", "query-activities", "--brief",
                                "-c", "android.intent.category.HOME", "-a", "android.intent.action.MAIN"],
        "meminfo.txt": ["cat", "/proc/meminfo"],
        "dumpsys_meminfo.txt": ["dumpsys", "meminfo"],
        "processes.txt": ["dumpsys", "activity", "processes"],
        "top.txt": ["top", "-b", "-n", "1"],
        "df.txt": ["df", "-h"],
    }
    warnings = 0
    for name, command in snapshots.items():
        result = adb.shell(*command, timeout=120)
        (out / name).write_text(result.text, encoding="utf-8")
        if result.returncode != 0:
            print(f"WARN {name}: '{' '.join(command)}' exited {result.returncode}: {result.output[:200]}")
            warnings += 1
    settings = [f"{key}={adb.sh('settings', 'get', 'global', key).strip()}"
                for key in ANIMATION_KEYS + DNS_KEYS]
    (out / "settings_global.txt").write_text("\n".join(settings) + "\n", encoding="utf-8")
    shot = adb.run("exec-out", "screencap", "-p", timeout=60)
    (out / "home_screen.png").write_bytes(shot.stdout)
    if not shot.stdout.startswith(b"\x89PNG"):
        print(f"WARN home_screen.png is not a PNG ({len(shot.stdout)} bytes)")
        warnings += 1
    print(f"Backup written to {out}")
    for path in sorted(out.iterdir()):
        print(f"  {path.stat().st_size:>9}  {path.name}")
    return 1 if warnings else 0


def default_lists() -> list[Path]:
    return sorted((ROOT / "lists").rglob("*.txt"))


def cmd_audit(adb: Adb, args: argparse.Namespace) -> int:
    paths = [Path(p) for p in args.lists] or default_lists()
    if not paths:
        raise ToolError(f"no list files given and none found under {ROOT / 'lists'}")
    for path in paths:
        read_lists([path])  # validate before touching the TV
    require_device(adb)
    installed = installed_packages(adb)
    disabled = installed_packages(adb, "-d")
    for path in paths:
        print(f"== {path}")
        for package in read_lists([path]):
            if package not in installed:
                status = "missing"
            elif package in disabled:
                status = "disabled"
            else:
                status = "enabled"
            note = "  (protected, never disabled)" if is_protected(package) else ""
            print(f"  {status:<9} {package}{note}")
    print("== Top 15 processes by PSS")
    for kb, name in parse_pss(adb.sh("dumpsys", "meminfo", timeout=120)):
        print(f"  {kb // 1024:>5} MB  {name[:70]}")
    return 0


def confirm(prompt: str) -> bool:
    try:
        answer = input(prompt)
    except EOFError:
        return False
    return answer.strip().lower() in ("y", "yes")


def cmd_apply(adb: Adb, args: argparse.Namespace) -> int:
    packages = read_lists([Path(p) for p in args.lists])
    require_device(adb)
    plan = plan_disable(packages, installed_packages(adb), installed_packages(adb, "-d"),
                        installed_packages(adb, "-e"))
    for item in plan:
        print(f"{item.action:<8}{item.package}" + (f" ({item.reason})" if item.reason else ""))
    todo = [item.package for item in plan if item.action == "DISABLE"]
    if not todo:
        print("Nothing to disable.")
        return 0
    if args.dry_run:
        print(f"Dry run: {len(todo)} package(s) would be disabled, nothing changed.")
        return 0
    if not args.yes and not confirm(f"Disable {len(todo)} package(s)? [y/N] "):
        print("Aborted, nothing changed.")
        return 1
    undo = Path(args.undo)
    undo.parent.mkdir(parents=True, exist_ok=True)
    done = failed = 0
    for package in todo:
        result = adb.shell("pm", "disable-user", "--user", "0", package)
        if "disabled-user" in result.output:
            with undo.open("a", encoding="utf-8") as handle:
                handle.write(package + "\n")
            print(f"DONE    {package} -> {result.output}")
            done += 1
        else:
            print(f"FAIL    {package} -> {result.output}")
            failed += 1
    print(f"Disabled {done}, failed {failed}. Undo list: {undo}")
    if done:
        print(REBOOT_WARNING)
        if args.reboot:
            print("Waiting 30 s, then rebooting.")
            sleep(30)
            adb.run("reboot")
    return 1 if failed else 0


def cmd_restore(adb: Adb, args: argparse.Namespace) -> int:
    paths = [Path(p) for p in args.lists] or [DEFAULT_UNDO]
    packages = sorted(read_lists(paths))
    require_device(adb)
    failed = 0
    for package in packages:
        result = adb.shell("pm", "enable", package)
        print(f"ENABLE  {package} -> {result.output}")
        if "new state: enabled" not in result.output:
            failed += 1
    if len(packages) > failed:
        print(REBOOT_WARNING)
    return 1 if failed else 0


def cmd_check(adb: Adb, args: argparse.Namespace) -> int:
    apps = read_lists([Path(args.apps)])
    require_device(adb)
    fail = 0
    if args.reboot:
        adb.run("reboot")
        sleep(20)
        for _ in range(60):
            adb.plain("connect", adb.serial)
            if adb.sh("getprop", "sys.boot_completed").strip() == "1":
                break
            sleep(5)
        if adb.sh("getprop", "sys.boot_completed").strip() != "1":
            print("FAIL TV did not finish booting")
            return 1
        print("OK   booted, waiting 60 s for startup services to settle")
        sleep(60)

    mem = parse_meminfo(adb.sh("cat", "/proc/meminfo"))
    print(f"MEM  available {mem.get('MemAvailable', 0) // 1024} MB")

    bad = sorted(p for p in installed_packages(adb, "-d") if is_protected(p))
    for package in bad:
        print(f"FAIL {package} is disabled")
        fail = 1
    if not bad:
        print("OK   no protected package is disabled")

    print(f"HOME {home_activity(adb)}")

    installed = installed_packages(adb)
    for package in apps:
        if package not in installed:
            print(f"SKIP not installed: {package}")
            continue
        adb.shell("input", "keyevent", "KEYCODE_HOME")
        sleep(2)
        adb.shell("monkey", "-p", package, "-c", "android.intent.category.LEANBACK_LAUNCHER", "1")
        top = ""
        for _ in range(5):  # up to ~15 s: some apps show a consent or splash screen first
            sleep(3)
            top = resumed_activity(adb)
            if is_in_front(top, package):
                break
        if is_in_front(top, package):
            print(f"OK   starts: {package}")
        else:
            print(f"FAIL did not come to front: {package} ({top})")
            fail = 1

    adb.shell("input", "keyevent", "KEYCODE_HOME")
    sleep(2)
    adb.shell("am", "start", "-a", "android.settings.SETTINGS")
    top = ""
    for _ in range(5):
        sleep(2)
        top = resumed_activity(adb)
        if is_in_front(top, "com.android.tv.settings"):
            break
    if is_in_front(top, "com.android.tv.settings"):
        print("OK   starts: Settings")
    else:
        print(f"FAIL Settings did not open ({top})")
        fail = 1
    adb.shell("input", "keyevent", "KEYCODE_HOME")

    print("RESULT pass" if fail == 0 else "RESULT FAIL")
    return fail


def put_settings(adb: Adb, values: dict[str, str]) -> int:
    failed = 0
    for key, value in values.items():
        adb.shell("settings", "put", "global", key, value)
        now = adb.sh("settings", "get", "global", key).strip()
        print(f"{key}={now}")
        if now != value:
            print(f"FAIL {key} is {now!r}, expected {value!r}")
            failed = 1
    return failed


def scale_value(text: str) -> str:
    try:
        value = float(text)
    except ValueError:
        raise argparse.ArgumentTypeError(f"not a number: {text}") from None
    if not math.isfinite(value) or not 0 <= value <= 10:
        raise argparse.ArgumentTypeError("scale must be between 0 and 10")
    return f"{value:g}"


def cmd_tweak_animations(adb: Adb, args: argparse.Namespace) -> int:
    require_device(adb)
    return put_settings(adb, {key: args.scale for key in ANIMATION_KEYS})


def cmd_tweak_dns(adb: Adb, args: argparse.Namespace) -> int:
    require_device(adb)
    if args.mode == "adguard":
        return put_settings(adb, {"private_dns_mode": "hostname", "private_dns_specifier": ADGUARD_DNS})
    return put_settings(adb, {"private_dns_mode": "opportunistic"})


def require_airplay(adb: Adb) -> None:
    require_device(adb)
    if AIRPLAY_PKG not in installed_packages(adb):
        raise ToolError(f"{AIRPLAY_PKG} is not installed on the TV")


def cmd_airplay_set(adb: Adb, args: argparse.Namespace) -> int:
    require_airplay(adb)
    result = adb.shell("am", "broadcast", "-a", "io.github.jqssun.airplay.SET_SETTING",
                       "-n", AIRPLAY_RECEIVER, "--es", "key", args.key, "--es", "value", args.value)
    print(result.output)
    return 0 if result.returncode == 0 else 1


def cmd_airplay_list(adb: Adb, args: argparse.Namespace) -> int:
    require_airplay(adb)
    result = adb.shell("am", "broadcast", "-a", "io.github.jqssun.airplay.LIST_SETTINGS",
                       "-n", AIRPLAY_RECEIVER)
    print(result.output)
    return 0 if result.returncode == 0 else 1


FRAME_ROUTE = (["KEYCODE_DPAD_RIGHT"] * 9 + ["KEYCODE_DPAD_LEFT"] * 9
               + ["KEYCODE_DPAD_DOWN"] * 3 + ["KEYCODE_DPAD_UP"] * 3)


def cmd_frames(adb: Adb, args: argparse.Namespace) -> int:
    require_device(adb)
    print(f"Route: 9 right, 9 left, 3 down, 3 up on {args.package}, {args.runs} run(s)")
    adb.shell("input", "keyevent", "KEYCODE_HOME")
    sleep(3)
    for run in range(1, args.runs + 1):
        adb.shell("dumpsys", "gfxinfo", args.package, "reset")
        for key in FRAME_ROUTE:
            adb.shell("input", "keyevent", key)
            sleep(0.25)
        sleep(1)
        stats = parse_gfxinfo(adb.sh("dumpsys", "gfxinfo", args.package))
        print(f"run {run}: frames={stats['frames']} janky={stats['janky']} "
              f"p50={stats['p50']} p90={stats['p90']} p99={stats['p99']}")
    return 0


def cmd_install(adb: Adb, args: argparse.Namespace) -> int:
    for apk in args.apks:
        if not Path(apk).is_file():
            raise ToolError(f"APK not found: {apk}")
    require_device(adb)
    failed = 0
    for apk in args.apks:
        result = adb.run("install", "-r", apk, timeout=600)
        print(f"{apk}: {result.output}")
        if result.returncode != 0 or "Success" not in result.output:
            failed = 1
    return failed


# --- command line -----------------------------------------------------------

def positive_int(text: str) -> int:
    value = int(text)
    if value < 1:
        raise argparse.ArgumentTypeError("must be 1 or more")
    return value


def port_number(text: str) -> int:
    value = int(text)
    if not 1 <= value <= 65535:
        raise argparse.ArgumentTypeError("port must be 1-65535")
    return value


def build_parser() -> argparse.ArgumentParser:
    # Connection options work before or after the subcommand.
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--host", default=argparse.SUPPRESS, help="TV IP address or hostname (or env TV_HOST)")
    common.add_argument("--port", type=port_number, default=argparse.SUPPRESS,
                        help=f"ADB port (default {DEFAULT_PORT})")
    common.add_argument("--adb", default=argparse.SUPPRESS, help="path to adb (or env ADB; default: adb on PATH)")

    parser = argparse.ArgumentParser(
        prog="openshelf", parents=[common],
        description="Inspect and tune an Android TV over network ADB. "
                    "Disables preinstalled packages (reversible), checks health, adjusts settings.")
    sub = parser.add_subparsers(dest="command", metavar="COMMAND")
    sub.required = True

    def add(name: str, func: Callable[[Adb, argparse.Namespace], int] | None, help_text: str,
            target: argparse._SubParsersAction = sub) -> argparse.ArgumentParser:
        p = target.add_parser(name, parents=[common], help=help_text, description=help_text)
        if func:
            p.set_defaults(func=func)
        return p

    add("connect", cmd_connect, "connect to the TV and show model, Android version and memory")

    p = add("backup", cmd_backup, "save a read-only snapshot of the TV state (changes nothing)")
    p.add_argument("--out", metavar="DIR", help="output directory (default: backups/<timestamp>)")

    p = add("audit", cmd_audit, "show the state of every package in the lists and the top memory users")
    p.add_argument("lists", nargs="*", metavar="LIST", help="package list files (default: lists/**/*.txt)")

    p = add("apply", cmd_apply, "disable the packages in the lists (pm disable-user, reversible with restore)")
    p.add_argument("lists", nargs="+", metavar="LIST", help="package list files")
    p.add_argument("--dry-run", action="store_true", help="show the plan, change nothing")
    p.add_argument("--yes", action="store_true", help="do not ask for confirmation")
    p.add_argument("--reboot", action="store_true", help="after changes, wait 30 s and reboot the TV")
    p.add_argument("--undo", default=str(DEFAULT_UNDO), metavar="FILE",
                   help="file that disabled packages are appended to (default: backups/undo.txt)")

    p = add("restore", cmd_restore, "re-enable packages (pm enable)")
    p.add_argument("lists", nargs="*", metavar="LIST", help="package list files (default: backups/undo.txt)")

    p = add("check", cmd_check, "health check: memory, protected packages, home app, app starts")
    p.add_argument("--reboot", action="store_true", help="reboot first and wait for the TV to come back")
    p.add_argument("--apps", default=str(DEFAULT_APPS), metavar="FILE",
                   help="apps to start (default: config/check-apps.txt)")

    tweak = add("tweak", None, "change animation speed or private DNS")
    tweak_sub = tweak.add_subparsers(dest="what", metavar="SETTING")
    tweak_sub.required = True
    p = add("animations", cmd_tweak_animations, "set window, transition and animator scale", tweak_sub)
    p.add_argument("scale", type=scale_value, help="0 = off, 0.5 = fast, 1 = default")
    p = add("dns", cmd_tweak_dns, "set private DNS", tweak_sub)
    p.add_argument("mode", choices=["adguard", "off"],
                   help=f"adguard = {ADGUARD_DNS}, off = automatic (opportunistic)")

    airplay = add("airplay", None, f"read or change settings of the AirPlay receiver ({AIRPLAY_PKG})")
    airplay_sub = airplay.add_subparsers(dest="action", metavar="ACTION")
    airplay_sub.required = True
    p = add("set", cmd_airplay_set, "set one AirPlay receiver setting", airplay_sub)
    p.add_argument("key")
    p.add_argument("value")
    add("list", cmd_airplay_list, "list AirPlay receiver settings", airplay_sub)

    p = add("frames", cmd_frames, "measure home screen frame times over a fixed D-pad route")
    p.add_argument("--runs", type=positive_int, default=3, help="number of runs (default 3)")
    p.add_argument("--package", default="tv.openshelf.home", help="app to measure (default tv.openshelf.home)")

    p = add("install", cmd_install, "install or update APK files (adb install -r)")
    p.add_argument("apks", nargs="+", metavar="APK")
    return parser


def make_adb(args: argparse.Namespace, runner: Runner) -> Adb:
    host = (getattr(args, "host", None) or os.environ.get("TV_HOST", "")).strip()
    if not host:
        raise ToolError("no TV address. Pass --host <TV IP> or set the TV_HOST environment variable.")
    port = getattr(args, "port", DEFAULT_PORT)
    wanted = getattr(args, "adb", None) or os.environ.get("ADB") or "adb"
    exe = shutil.which(wanted)
    if not exe:
        raise ToolError(f"adb not found ({wanted}). Install Android platform-tools, "
                        "or pass --adb / set ADB to its path.")
    return Adb(exe, f"{host}:{port}", runner)


def main(argv: Sequence[str] | None = None, runner: Runner = subprocess_runner) -> int:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(errors="replace")
    args = build_parser().parse_args(argv)
    try:
        return args.func(make_adb(args, runner), args)
    except ToolError as error:
        print(f"openshelf: error: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\nInterrupted.", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
