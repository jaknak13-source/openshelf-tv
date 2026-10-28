"""Adds "return to the previous app when mirroring ends" to a decoded AirPlay Receiver APK.

Upstream: github.com/jqssun/android-airplay-server (GPL-3.0), patched with apktool.
When a sender connects, the service brings MainActivity to the front. This patch
remembers that, and when the last sender disconnects it sends MainActivity an intent
with EXTRA; the activity then calls moveTaskToBack(true), so Android shows whatever
was in front before (an app, live TV or the home screen). If the user opened the
AirPlay app by hand, the flag is never set and nothing changes.

Usage: python3 -I apply_patch.py <decoded-dir>
Fails loudly if any anchor is missing, so a new upstream version can't be half-patched.
"""
import pathlib
import sys

EXTRA = "openshelf_return"
SVC = "Lio/github/jqssun/airplay/service/AirPlayService;"
FIELD = f"{SVC}->os_autoLaunched:Z"

root = pathlib.Path(sys.argv[1]) / "smali"
svc_path = root / "io/github/jqssun/airplay/service/AirPlayService.smali"
main_path = root / "io/github/jqssun/airplay/MainActivity.smali"


def replace_once(text: str, old: str, new: str, what: str) -> str:
    count = text.count(old)
    if count != 1:
        sys.exit(f"anchor for {what} found {count} times, expected 1")
    return text.replace(old, new)


def method_body(text: str, signature: str) -> tuple[int, int]:
    start = text.find(signature)
    if start < 0 or text.count(signature) != 1:
        sys.exit(f"method not found exactly once: {signature}")
    end = text.find(".end method", start)
    return start, end


# --- AirPlayService ---------------------------------------------------------
svc = svc_path.read_text()
if "os_autoLaunched" in svc:
    sys.exit("already patched")

# The helper below reads the service's connection counter through obfuscated names from v0.0.36.
# smali does not resolve them at build time, so check they exist instead of crashing at runtime.
if ".field private final _connectionCount:Lbq1;" not in svc:
    sys.exit("anchor for _connectionCount:Lbq1; not found; this patch is for receiver v0.0.36")
if not any(root.parent.glob("smali*/eq2.smali")):
    sys.exit("class eq2 not found; this patch is for receiver v0.0.36")

# 1. new field
svc = replace_once(svc, "\n# direct methods", f"\n.field private os_autoLaunched:Z\n\n# direct methods", "field section")

# 2. launchMainActivity sets the flag
sig = ".method private final launchMainActivity()V\n    .locals 3\n"
svc = replace_once(svc, sig, sig + f"\n    const/4 v0, 0x1\n\n    iput-boolean v0, p0, {FIELD}\n", "launchMainActivity")

# 3. onConnectionDestroy calls the helper right before its final return
start, end = method_body(svc, ".method public onConnectionDestroy()V")
body = svc[start:end]
tail = "invoke-direct {p0, v0}, " + SVC + "->log(Ljava/lang/String;)V"
idx = body.rfind(tail)
if idx < 0:
    sys.exit("onConnectionDestroy: final log call not found")
idx += len(tail)
body = body[:idx] + f"\n\n    invoke-direct {{p0}}, {SVC}->os_maybeReturn()V\n" + body[idx:]
svc = svc[:start] + body + svc[end:]

# 4. helper method
helper = f"""
.method private final os_maybeReturn()V
    .locals 3

    iget-object v0, p0, {SVC}->_connectionCount:Lbq1;

    check-cast v0, Leq2;

    invoke-virtual {{v0}}, Leq2;->getValue()Ljava/lang/Object;

    move-result-object v0

    check-cast v0, Ljava/lang/Number;

    invoke-virtual {{v0}}, Ljava/lang/Number;->intValue()I

    move-result v0

    if-nez v0, :bh_done

    iget-boolean v0, p0, {FIELD}

    if-eqz v0, :bh_done

    const/4 v0, 0x0

    iput-boolean v0, p0, {FIELD}

    new-instance v0, Landroid/content/Intent;

    const-class v1, Lio/github/jqssun/airplay/MainActivity;

    invoke-direct {{v0, p0, v1}}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V

    const/high16 v1, 0x10020000

    invoke-virtual {{v0, v1}}, Landroid/content/Intent;->addFlags(I)Landroid/content/Intent;

    const-string v1, "{EXTRA}"

    const/4 v2, 0x1

    invoke-virtual {{v0, v1, v2}}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Z)Landroid/content/Intent;

    :try_bh_start
    invoke-virtual {{p0, v0}}, Landroid/content/Context;->startActivity(Landroid/content/Intent;)V
    :try_bh_end
    .catch Ljava/lang/Exception; {{:try_bh_start .. :try_bh_end}} :catch_bh

    :bh_done
    return-void

    :catch_bh
    move-exception v0

    const-string v1, "AirPlayService"

    const-string v2, "os_maybeReturn failed"

    invoke-static {{v1, v2, v0}}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I

    return-void
.end method
"""
svc = replace_once(svc, "\n# virtual methods", helper + "\n# virtual methods", "virtual methods section")
svc_path.write_text(svc)

# --- MainActivity.onCreate: a fresh instance started by the return intent goes back ---
main = main_path.read_text()
oc = ".method public final onCreate(Landroid/os/Bundle;)V\n    .locals 8\n"
start, end = method_body(main, oc)
body = main[start:end]
sup = "invoke-super {p0, p1}, Lqu0;->onCreate(Landroid/os/Bundle;)V\n"
check = f"""
    invoke-virtual {{p0}}, Landroid/app/Activity;->getIntent()Landroid/content/Intent;

    move-result-object v0

    if-eqz v0, :bh_go

    const-string v1, "{EXTRA}"

    const/4 v2, 0x0

    invoke-virtual {{v0, v1, v2}}, Landroid/content/Intent;->getBooleanExtra(Ljava/lang/String;Z)Z

    move-result v0

    if-eqz v0, :bh_go

    const/4 v0, 0x1

    invoke-virtual {{p0, v0}}, Landroid/app/Activity;->moveTaskToBack(Z)Z

    :bh_go
"""
body = replace_once(body, sup, sup + check, "MainActivity.onCreate super call")
main_path.write_text(main[:start] + body + main[end:])

# --- wv.onNewIntent (final in the base class): existing instance goes back --------
wv_path = root / "wv.smali"
wv = wv_path.read_text()
sig = ".method public final onNewIntent(Landroid/content/Intent;)V\n    .locals 1\n"
start, end = method_body(wv, sig)
body = wv[start:end].replace("    .locals 1\n", "    .locals 2\n", 1)
sup = "invoke-super {p0, p1}, Landroid/app/Activity;->onNewIntent(Landroid/content/Intent;)V\n"
check = f"""
    const-string v0, "{EXTRA}"

    const/4 v1, 0x0

    invoke-virtual {{p1, v0, v1}}, Landroid/content/Intent;->getBooleanExtra(Ljava/lang/String;Z)Z

    move-result v0

    if-eqz v0, :bh_skip

    const/4 v0, 0x1

    invoke-virtual {{p0, v0}}, Landroid/app/Activity;->moveTaskToBack(Z)Z

    return-void

    :bh_skip
"""
body = replace_once(body, sup, sup + check, "wv.onNewIntent super call")
wv_path.write_text(wv[:start] + body + wv[end:])

print("patched: AirPlayService (field, launchMainActivity, onConnectionDestroy, os_maybeReturn), MainActivity.onCreate, wv.onNewIntent")
