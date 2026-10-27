package tv.openshelf.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.provider.Settings;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What is installed and plugged in right now. Built on the worker thread (binder calls). */
final class Catalog {
    static final String TAG = "OpenShelf";

    static final String PKG_AIRPLAY = "io.github.jqssun.airplay";
    static final String PKG_KEEPER = "tv.openshelf.keeper";

    final List<Entry> apps;
    final List<Entry> inputs;
    final List<Entry> system;

    private Catalog(List<Entry> apps, List<Entry> inputs, List<Entry> system) {
        this.apps = apps;
        this.inputs = inputs;
        this.system = system;
    }

    static Catalog load(Context c) {
        long t0 = SystemClock.uptimeMillis();
        PackageManager pm = c.getPackageManager();
        // AirPlay and the keeper live in the System row, so they are not repeated under Apps.
        Set<String> systemPackages = new HashSet<>();
        systemPackages.add(PKG_AIRPLAY);
        systemPackages.add(PKG_KEEPER);

        Map<String, Entry> byPackage = new LinkedHashMap<>();
        addLaunchables(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, systemPackages, byPackage);
        addLaunchables(pm, Intent.CATEGORY_LAUNCHER, systemPackages, byPackage);
        List<Entry> apps = new ArrayList<>(byPackage.values());
        final Collator collator = Collator.getInstance();
        Collections.sort(apps, (a, b) -> collator.compare(a.label, b.label));

        List<Entry> system = new ArrayList<>();
        system.add(new Entry("sys:settings", Entry.SYSTEM, c.getString(R.string.tile_settings), null,
                new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                null, 0L, false));
        addPackageTile(pm, PKG_AIRPLAY, system);
        addPackageTile(pm, PKG_KEEPER, system);
        system.add(new Entry(Entry.KEY_EDIT, Entry.SYSTEM, c.getString(R.string.tile_edit), null,
                null, null, 0L, false));

        long t1 = SystemClock.uptimeMillis();
        List<Entry> inputs = loadInputs(c, collator);
        Log.i(TAG, "apps " + (t1 - t0) + " ms, inputs " + (SystemClock.uptimeMillis() - t1) + " ms");
        return new Catalog(apps, inputs, system);
    }

    private static void addLaunchables(PackageManager pm, String category, Set<String> skip,
                                       Map<String, Entry> out) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (out.containsKey(ai.packageName) || skip.contains(ai.packageName)) continue;
            out.put(ai.packageName, appEntry(pm, ai, category, Entry.APP, Entry.appKey(ai.packageName),
                    String.valueOf(ri.loadLabel(pm)).trim()));
        }
    }

    private static void addPackageTile(PackageManager pm, String pkg, List<Entry> out) {
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(pkg);
            List<ResolveInfo> found = pm.queryIntentActivities(query, 0);
            if (!found.isEmpty()) {
                ResolveInfo ri = found.get(0);
                out.add(appEntry(pm, ri.activityInfo, category, Entry.SYSTEM, "sys:" + pkg,
                        String.valueOf(ri.loadLabel(pm)).trim()));
                return;
            }
        }
    }

    private static Entry appEntry(PackageManager pm, ActivityInfo ai, String category, int kind,
                                  String key, String label) {
        Intent launch = new Intent(Intent.ACTION_MAIN)
                .addCategory(category)
                .setComponent(new ComponentName(ai.packageName, ai.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        long stamp;
        try {
            stamp = pm.getPackageInfo(ai.packageName, 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            stamp = 0L; // removed between query and lookup; the package receiver triggers a reload
        }
        return new Entry(key, kind, label, null, launch, ai, stamp, false);
    }

    private static List<Entry> loadInputs(Context c, final Collator collator) {
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        List<Entry> out = new ArrayList<>();
        if (tim == null) return out;

        List<TvInputInfo> hdmi = new ArrayList<>();
        Set<String> parentsWithDevice = new HashSet<>();
        for (TvInputInfo info : tim.getTvInputList()) {
            if (info.getType() != TvInputInfo.TYPE_HDMI || info.isHidden(c)) continue;
            hdmi.add(info);
            if (info.getParentId() != null) parentsWithDevice.add(info.getParentId());
        }
        Map<String, TvInputInfo> byId = new HashMap<>();
        for (TvInputInfo info : hdmi) byId.put(info.getId(), info);

        final Map<String, String> portOf = new HashMap<>();
        for (TvInputInfo info : hdmi) {
            // A CEC device (child) stands in for its port (parent): one tile per socket.
            if (parentsWithDevice.contains(info.getId())) continue;
            String id = info.getId();
            CharSequence custom = info.loadCustomLabel(c);
            CharSequence base = info.loadLabel(c);
            TvInputInfo parent = info.getParentId() != null ? byId.get(info.getParentId()) : null;
            String port = parent != null ? str(parent.loadLabel(c)) : str(base);
            String label = !TextUtils.isEmpty(custom) ? custom.toString().trim() : str(base);
            int state = tim.getInputState(id);
            boolean connected = state != TvInputManager.INPUT_STATE_DISCONNECTED;
            Log.i(TAG, "input id=" + id + " label=" + base + " custom=" + custom
                    + " parent=" + info.getParentId() + " state=" + state);

            String detail = label.equals(port) ? null : port;
            if (!connected) {
                String off = c.getString(R.string.input_disconnected);
                detail = detail == null ? off : detail + " · " + off;
            }
            Intent view = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Entry e = new Entry(Entry.inputKey(id), Entry.INPUT, label, detail, view, null,
                    connected ? 1L : 0L, !connected);
            out.add(e);
            portOf.put(e.key, port);
        }
        Collections.sort(out, (a, b) -> {
            if (a.dimmed != b.dimmed) return a.dimmed ? 1 : -1;
            return collator.compare(portOf.get(a.key), portOf.get(b.key));
        });
        return out;
    }

    private static String str(CharSequence s) {
        return s == null ? "" : s.toString().trim();
    }
}
