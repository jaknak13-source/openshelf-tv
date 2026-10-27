"""Tests for tools/openshelf.py with a fake adb runner; no device needed.

Run: python3 -m unittest discover -s tests -v
"""
from __future__ import annotations

import contextlib
import io
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "tools"))
import openshelf as osh  # noqa: E402

SERIAL = "tv.example:5555"


class FakeTV:
    """Answers adb argv lists the way a TV would; records every call."""

    def __init__(self, state="device", installed=(), disabled=(), resumed=""):
        self.state = state
        self.installed = set(installed)
        self.disabled = set(disabled)
        self.resumed = resumed
        self.calls: list[list[str]] = []

    @property
    def enabled(self):
        return self.installed - self.disabled

    def shell_calls(self):
        return [c[4] for c in self.calls if c[1:4] == ["-s", SERIAL, "shell"]]

    def __call__(self, argv, timeout):
        argv = list(argv)
        self.calls.append(argv)
        args = argv[1:]
        if args == ["devices"]:
            return osh.Result(0, f"List of devices attached\n{SERIAL}\t{self.state}\r\n\r\n".encode())
        if args[0] == "connect":
            return osh.Result(0, f"already connected to {SERIAL}\n".encode())
        assert args[:2] == ["-s", SERIAL], args
        if args[2] != "shell":
            return osh.Result(0, b"")
        cmd = args[3]
        lines = {
            "pm list packages": self.installed,
            "pm list packages -d": self.disabled,
            "pm list packages -e": self.enabled,
        }
        if cmd in lines:
            return osh.Result(0, "".join(f"package:{p}\r\n" for p in sorted(lines[cmd])).encode())
        if cmd.startswith("pm disable-user --user 0 "):
            pkg = cmd.split()[-1]
            self.disabled.add(pkg)
            return osh.Result(0, f"Package {pkg} new state: disabled-user\r\n".encode())
        if cmd.startswith("pm enable "):
            pkg = cmd.split()[-1]
            self.disabled.discard(pkg)
            return osh.Result(0, f"Package {pkg} new state: enabled\r\n".encode())
        if cmd == "dumpsys activity activities":
            return osh.Result(0, f"  mResumedActivity: {self.resumed}\n".encode())
        return osh.Result(0, b"")


def run_cmd(func, tv, **kwargs):
    import argparse
    out = io.StringIO()
    with contextlib.redirect_stdout(out), mock.patch.object(osh, "sleep", lambda s: None):
        code = func(osh.Adb("adb", SERIAL, tv), argparse.Namespace(**kwargs))
    return code, out.getvalue()


def write(directory, name, text, newline="\n"):
    path = Path(directory) / name
    with open(path, "w", encoding="utf-8", newline=newline) as handle:
        handle.write(text)
    return path


class ParseListTest(unittest.TestCase):
    def test_comments_blank_lines_and_inline_comments(self):
        text = "# header\n\ncom.a.b\n   \ncom.c  # note with spaces\n\t# indented comment\n"
        self.assertEqual(osh.parse_package_list(text), (["com.a.b", "com.c"], []))

    def test_crlf(self):
        self.assertEqual(osh.parse_package_list("com.a\r\ncom.b  # x\r\n"), (["com.a", "com.b"], []))

    def test_internal_whitespace_rejected(self):
        packages, errors = osh.parse_package_list("com.ok\ncom.bad name\ncom.tab\tname # c\n")
        self.assertEqual(packages, ["com.ok"])
        self.assertEqual(len(errors), 2)
        self.assertIn("line 2", errors[0])
        self.assertIn("line 3", errors[1])

    def test_shell_characters_rejected(self):
        _, errors = osh.parse_package_list("com.a;reboot\ncom.b$(x)\n")
        self.assertEqual(len(errors), 2)

    def test_duplicates_removed_in_order(self):
        self.assertEqual(osh.parse_package_list("b\na\nb\n")[0], ["b", "a"])

    def test_read_lists_handles_bom_and_crlf_files(self):
        with tempfile.TemporaryDirectory() as d:
            path = write(d, "l.txt", "﻿com.a\ncom.b\n", newline="\r\n")
            self.assertEqual(osh.read_lists([path]), ["com.a", "com.b"])

    def test_read_lists_raises_on_any_invalid_line(self):
        with tempfile.TemporaryDirectory() as d:
            path = write(d, "l.txt", "com.a\ncom.b c\n")
            with self.assertRaises(osh.ToolError) as ctx:
                osh.read_lists([path])
            self.assertIn("line 2", str(ctx.exception))


class DenylistTest(unittest.TestCase):
    def test_exact_and_prefix(self):
        for pkg in ("com.sony.dtv.tvx", "com.google.android.gms", "tv.openshelf.home", "tv.openshelf.keeper",
                    "io.github.jqssun.airplay", "com.sony.dtv.tvinput.tuner", "com.sony.dtv.tvinput.external"):
            self.assertTrue(osh.is_protected(pkg), pkg)
        for pkg in ("com.sony.dtv.tvxx", "com.sony.dtv.promos", "com.google.android.tvlauncher"):
            self.assertFalse(osh.is_protected(pkg), pkg)

    def test_plan_refuses_protected_even_if_installed(self):
        plan = osh.plan_disable(["com.android.vending", "com.sony.dtv.tvinput.tuner"],
                                installed={"com.android.vending", "com.sony.dtv.tvinput.tuner"},
                                disabled=set(), enabled={"com.android.vending", "com.sony.dtv.tvinput.tuner"})
        self.assertEqual([i.action for i in plan], ["REFUSE", "REFUSE"])

    def test_plan_skips_missing_and_already_disabled(self):
        plan = osh.plan_disable(["a", "b", "c"], installed={"b", "c"}, disabled={"b"}, enabled={"c"})
        self.assertEqual([(i.package, i.action, i.reason) for i in plan],
                         [("a", "SKIP", "not installed"), ("b", "SKIP", "already disabled"), ("c", "DISABLE", "")])


class LauncherRuleTest(unittest.TestCase):
    TL = "com.google.android.tvlauncher"

    def plan(self, enabled, packages=(TL,), disabled=()):
        installed = set(enabled) | set(disabled) | set(packages)
        return {i.package: i.action for i in osh.plan_disable(list(packages), installed, disabled, enabled)}

    def test_allowed_with_openshelf_home_enabled(self):
        self.assertEqual(self.plan({self.TL, "tv.openshelf.home"})[self.TL], "DISABLE")

    def test_allowed_with_projectivy_enabled(self):
        self.assertEqual(self.plan({self.TL, "com.spocky.projengmenu"})[self.TL], "DISABLE")

    def test_refused_without_replacement(self):
        self.assertEqual(self.plan({self.TL})[self.TL], "REFUSE")

    def test_refused_when_replacement_installed_but_disabled(self):
        self.assertEqual(self.plan({self.TL}, disabled={"com.spocky.projengmenu"})[self.TL], "REFUSE")

    def test_replacement_launcher_in_list_is_protected(self):
        # A list that names the replacement launcher must not take it away: the launcher is
        # refused, stays enabled, and therefore still counts as the replacement home.
        result = self.plan({self.TL, "com.spocky.projengmenu"}, packages=(self.TL, "com.spocky.projengmenu"))
        self.assertEqual(result, {self.TL: "DISABLE", "com.spocky.projengmenu": "REFUSE"})


class ApplyTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.list = write(self.tmp.name, "list.txt",
                          "com.ads\ncom.gone  # not on this TV\ncom.old\ncom.android.vending\n")
        self.undo = Path(self.tmp.name) / "sub" / "undo.txt"
        self.tv = FakeTV(installed={"com.ads", "com.old", "com.android.vending"}, disabled={"com.old"})

    def apply(self, **kw):
        args = dict(lists=[str(self.list)], dry_run=False, yes=False, reboot=False, undo=str(self.undo))
        args.update(kw)
        return run_cmd(osh.cmd_apply, self.tv, **args)

    def test_dry_run_prints_plan_and_changes_nothing(self):
        code, out = self.apply(dry_run=True)
        self.assertEqual(code, 0)
        self.assertIn("DISABLE com.ads", out)
        self.assertIn("SKIP    com.gone (not installed)", out)
        self.assertIn("SKIP    com.old (already disabled)", out)
        self.assertIn("REFUSE  com.android.vending (protected", out)
        self.assertFalse([c for c in self.tv.shell_calls() if "disable-user" in c])
        self.assertFalse(self.undo.exists())

    def test_yes_disables_records_undo_and_warns(self):
        code, out = self.apply(yes=True)
        self.assertEqual(code, 0)
        self.assertEqual([c for c in self.tv.shell_calls() if "disable-user" in c],
                         ["pm disable-user --user 0 com.ads"])
        self.assertEqual(self.undo.read_text(), "com.ads\n")
        self.assertIn("wait at least 30 seconds", out)
        self.assertNotIn(["adb", "-s", SERIAL, "reboot"], self.tv.calls)

    def test_reboot_waits_30_seconds_first(self):
        waits = []
        args = dict(lists=[str(self.list)], dry_run=False, yes=True, reboot=True, undo=str(self.undo))
        import argparse
        with contextlib.redirect_stdout(io.StringIO()), mock.patch.object(osh, "sleep", waits.append):
            osh.cmd_apply(osh.Adb("adb", SERIAL, self.tv), argparse.Namespace(**args))
        self.assertEqual(waits, [30])
        self.assertEqual(self.tv.calls[-1], ["adb", "-s", SERIAL, "reboot"])

    def test_declined_confirmation_changes_nothing(self):
        with mock.patch("builtins.input", return_value="n"):
            code, out = self.apply()
        self.assertEqual(code, 1)
        self.assertIn("Aborted", out)
        self.assertFalse([c for c in self.tv.shell_calls() if "disable-user" in c])

    def test_eof_at_prompt_counts_as_no(self):
        with mock.patch("builtins.input", side_effect=EOFError):
            code, _ = self.apply()
        self.assertEqual(code, 1)

    def test_invalid_line_aborts_before_any_adb_call(self):
        bad = write(self.tmp.name, "bad.txt", "com.ads\ncom.x y\n")
        with self.assertRaises(osh.ToolError):
            self.apply(lists=[str(bad)], yes=True)
        self.assertEqual(self.tv.calls, [])

    def test_failed_disable_not_recorded(self):
        tv = self.tv

        def runner(argv, timeout):
            if "disable-user" in " ".join(argv):
                tv.calls.append(list(argv))
                return osh.Result(255, b"", b"Exception occurred while executing 'disable-user'\n")
            return tv(argv, timeout)

        import argparse
        with contextlib.redirect_stdout(io.StringIO()) as out:
            code = osh.cmd_apply(osh.Adb("adb", SERIAL, runner), argparse.Namespace(
                lists=[str(self.list)], dry_run=False, yes=True, reboot=False, undo=str(self.undo)))
        self.assertEqual(code, 1)
        self.assertFalse(self.undo.exists())
        self.assertIn("FAIL    com.ads", out.getvalue())
        self.assertNotIn("30 seconds", out.getvalue())


class RestoreTest(unittest.TestCase):
    def test_undo_deduplicated_and_sorted(self):
        with tempfile.TemporaryDirectory() as d:
            undo = write(d, "undo.txt", "com.b\ncom.a\ncom.b\ncom.a\n")
            tv = FakeTV(installed={"com.a", "com.b"}, disabled={"com.a", "com.b"})
            code, out = run_cmd(osh.cmd_restore, tv, lists=[str(undo)])
        self.assertEqual(code, 0)
        self.assertEqual([c for c in tv.shell_calls() if c.startswith("pm enable")],
                         ["pm enable com.a", "pm enable com.b"])
        self.assertEqual(tv.disabled, set())
        self.assertIn("wait at least 30 seconds", out)

    def test_missing_undo_file(self):
        with self.assertRaises(osh.ToolError):
            run_cmd(osh.cmd_restore, FakeTV(), lists=["/nonexistent/undo.txt"])


class ConnectionStateTest(unittest.TestCase):
    def test_unauthorized_explains_dialog(self):
        tv = FakeTV(state="unauthorized")
        with mock.patch.object(osh, "sleep", lambda s: None):
            with self.assertRaises(osh.ToolError) as ctx:
                osh.require_device(osh.Adb("adb", SERIAL, tv))
        self.assertIn("Allow USB debugging?", str(ctx.exception))
        self.assertIn("Always allow", str(ctx.exception))
        self.assertIn(["adb", "connect", SERIAL], tv.calls)

    def test_offline(self):
        with mock.patch.object(osh, "sleep", lambda s: None):
            with self.assertRaises(osh.ToolError) as ctx:
                osh.require_device(osh.Adb("adb", SERIAL, FakeTV(state="offline")))
        self.assertIn("offline", str(ctx.exception))

    def test_not_listed(self):
        tv = FakeTV()
        tv.state = "device"
        adb = osh.Adb("adb", "other:5555", tv)
        with mock.patch.object(osh, "sleep", lambda s: None):
            with self.assertRaises(osh.ToolError) as ctx:
                osh.require_device(adb)
        self.assertIn("missing", str(ctx.exception))

    def test_connect_command_hints_then_succeeds_after_accept(self):
        tv = FakeTV(state="unauthorized", installed={"x"})
        polls = []

        def accept_after_two(seconds):
            polls.append(seconds)
            if len(polls) == 2:
                tv.state = "device"

        import argparse
        with contextlib.redirect_stdout(io.StringIO()) as out, mock.patch.object(osh, "sleep", accept_after_two):
            code = osh.cmd_connect(osh.Adb("adb", SERIAL, tv), argparse.Namespace())
        self.assertEqual(code, 0)
        self.assertEqual(out.getvalue().count("Allow USB debugging?"), 1)
        self.assertIn(f"Connected to {SERIAL}", out.getvalue())

    def test_device_state_parsing(self):
        text = "List of devices attached\nemulator-5554\tdevice\nh:5555\tunauthorized\n"
        self.assertEqual(osh.parse_devices(text, "h:5555"), "unauthorized")
        self.assertEqual(osh.parse_devices(text, "x:5555"), "missing")


class MainTest(unittest.TestCase):
    def run_main(self, argv, env):
        err = io.StringIO()
        with mock.patch.dict(os.environ, env, clear=True), contextlib.redirect_stderr(err), \
                contextlib.redirect_stdout(io.StringIO()):
            code = osh.main(argv, runner=FakeTV())
        return code, err.getvalue()

    def test_missing_host_is_a_clear_error(self):
        code, err = self.run_main(["audit"], {})
        self.assertEqual(code, 1)
        self.assertIn("no TV address", err)
        self.assertIn("TV_HOST", err)

    def test_missing_adb_is_a_clear_error(self):
        code, err = self.run_main(["--host", "tv.example", "--adb", "/nonexistent/adb", "connect"], {})
        self.assertEqual(code, 1)
        self.assertIn("adb not found", err)

    def test_host_and_port_after_subcommand(self):
        args = osh.build_parser().parse_args(["frames", "--host", "tv.example", "--port", "5556"])
        adb = osh.make_adb(argparse_ns(args, adb=sys.executable), FakeTV())
        self.assertEqual(adb.serial, "tv.example:5556")

    def test_env_host_and_default_port(self):
        args = osh.build_parser().parse_args(["connect"])
        with mock.patch.dict(os.environ, {"TV_HOST": "tv.example", "ADB": sys.executable}, clear=True):
            adb = osh.make_adb(args, FakeTV())
        self.assertEqual(adb.serial, "tv.example:5555")

    def test_no_ip_default_in_help(self):
        parser = osh.build_parser()
        help_text = parser.format_help()
        self.assertNotRegex(help_text, r"\d+\.\d+\.\d+\.\d+")


def argparse_ns(args, **extra):
    for key, value in extra.items():
        setattr(args, key, value)
    return args


class SubprocessRunnerTest(unittest.TestCase):
    def test_every_adb_call_gets_devnull_stdin(self):
        done = subprocess.CompletedProcess([], 0, b"ok", b"")
        with mock.patch.object(osh.subprocess, "run", return_value=done) as run:
            osh.subprocess_runner(["adb", "devices"], 5)
        self.assertIs(run.call_args.kwargs["stdin"], subprocess.DEVNULL)

    def test_timeout_becomes_tool_error(self):
        with mock.patch.object(osh.subprocess, "run", side_effect=subprocess.TimeoutExpired("adb", 5)):
            with self.assertRaises(osh.ToolError):
                osh.subprocess_runner(["adb", "devices"], 5)

    def test_shell_quotes_values_for_remote_shell(self):
        tv = FakeTV(installed={"io.github.jqssun.airplay"})
        run_cmd(osh.cmd_airplay_set, tv, key="name", value="Living room TV")
        self.assertIn("--es key name --es value 'Living room TV'", tv.shell_calls()[-1])


class ParserHelpersTest(unittest.TestCase):
    def test_pss_top_sorted_and_names_cleaned(self):
        text = ("Total PSS by process:\n"
                "    180,110K: com.google.android.tvlauncher (pid 3419 / activities)\n"
                "     92,447K: system (pid 2159)\n"
                "    124,799K: dtv_svc (pid 1612)\n"
                "\nTotal PSS by OOM adjustment:\n    1K: x (pid 1)\n")
        self.assertEqual(osh.parse_pss(text, 2), [(180110, "com.google.android.tvlauncher"), (124799, "dtv_svc")])

    def test_gfxinfo(self):
        text = ("Total frames rendered: 120\nJanky frames: 12 (10.00%)\nJanky frames (legacy): 30 (25.00%)\n"
                "50th percentile: 8ms\n90th percentile: 17ms\n99th percentile: 40ms\n50th gpu percentile: 3ms\n")
        self.assertEqual(osh.parse_gfxinfo(text),
                         {"frames": "120", "janky": "12 (10.00%)", "p50": "8ms", "p90": "17ms", "p99": "40ms"})

    def test_in_front_matches_whole_package(self):
        line = "mResumedActivity: ActivityRecord{1 u0 com.sony.dtv.tvx/.MainActivity t1}"
        self.assertTrue(osh.is_in_front(line, "com.sony.dtv.tvx"))
        self.assertFalse(osh.is_in_front(line, "dtv.tvx"))
        self.assertFalse(osh.is_in_front(line.replace("tvx/", "tvxx/"), "com.sony.dtv.tvx"))


class CheckTest(unittest.TestCase):
    def test_missing_app_is_skip_and_protected_disabled_is_fail(self):
        with tempfile.TemporaryDirectory() as d:
            apps = write(d, "apps.txt", "com.netflix.ninja\ncom.not.here\n")
            tv = FakeTV(installed={"com.netflix.ninja", "com.sony.dtv.tvinput.tuner"},
                        disabled={"com.sony.dtv.tvinput.tuner"},
                        resumed="ActivityRecord{1 u0 com.netflix.ninja/.Main t1}")
            code, out = run_cmd(osh.cmd_check, tv, reboot=False, apps=str(apps))
        self.assertIn("OK   starts: com.netflix.ninja", out)
        self.assertIn("SKIP not installed: com.not.here", out)
        self.assertIn("FAIL com.sony.dtv.tvinput.tuner is disabled", out)
        self.assertIn("FAIL Settings did not open", out)
        self.assertEqual(code, 1)

    def test_all_pass_returns_zero(self):
        with tempfile.TemporaryDirectory() as d:
            apps = write(d, "apps.txt", "# none installed\ncom.not.here\n")
            tv = FakeTV(resumed="ActivityRecord{1 u0 com.android.tv.settings/.MainSettings t1}")
            code, out = run_cmd(osh.cmd_check, tv, reboot=False, apps=str(apps))
        self.assertEqual(code, 0, out)
        self.assertIn("RESULT pass", out)


if __name__ == "__main__":
    unittest.main()
