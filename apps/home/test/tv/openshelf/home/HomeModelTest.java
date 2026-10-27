package tv.openshelf.home;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Plain-JVM checks of the row rules. Run with test.sh (android.jar only supplies types). */
public final class HomeModelTest {
    private static int failures;

    private static Entry app(String pkg, String label) {
        return new Entry(Entry.appKey(pkg), Entry.APP, label, null, null, null, 0L, false);
    }

    private static Entry input(String id) {
        return new Entry(Entry.inputKey(id), Entry.INPUT, id, null, null, null, 0L, false);
    }

    private static void check(String what, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            failures++;
            System.out.println("FAIL " + what + "\n  expected " + expected + "\n  actual   " + actual);
        } else {
            System.out.println("ok   " + what);
        }
    }

    public static void main(String[] args) {
        List<Entry> apps = Arrays.asList(app("a.arte", "Arte"), app("b.netflix", "Netflix"),
                app("c.timers", "Timer"), app("d.youtube", "YouTube"), app("e.zdf", "ZDF"));
        List<Entry> inputs = Arrays.asList(input("HW2"), input("HW4"));
        List<Entry> system = Collections.singletonList(
                new Entry(Entry.KEY_EDIT, Entry.SYSTEM, "Edit", null, null, null, 0L, false));
        List<String> none = Collections.emptyList();
        Set<String> hidden = new HashSet<>(Collections.singletonList("app:c.timers"));

        // Favourites keep the stored order, skip missing apps, and are not repeated under Apps.
        HomeModel m = HomeModel.build(apps, inputs, system,
                Arrays.asList("app:d.youtube", "app:not.installed", "app:b.netflix"), hidden, none, none);
        check("favourites in stored order, missing skipped",
                Arrays.asList("app:d.youtube", "app:b.netflix"), HomeModel.keys(m.favorites));
        check("apps exclude favourites and hidden, alphabetical",
                Arrays.asList("app:a.arte", "app:e.zdf"), HomeModel.keys(m.apps));

        // A hidden favourite is not shown anywhere.
        m = HomeModel.build(apps, inputs, system, Arrays.asList("app:c.timers"), hidden, none, none);
        check("hidden beats favourite", 0, m.favorites.size());

        // An input can be a favourite and then leaves the inputs row.
        m = HomeModel.build(apps, inputs, system, Arrays.asList("input:HW4"), hidden, none, none);
        check("input favourite", Arrays.asList("input:HW4"), HomeModel.keys(m.favorites));
        check("inputs row without the favourite", Arrays.asList("input:HW2"), HomeModel.keys(m.inputs));

        // Custom order first, unknown/new apps after it in natural order.
        m = HomeModel.build(apps, inputs, system, none, hidden, Arrays.asList("app:e.zdf", "app:gone", "app:b.netflix"), none);
        check("stored app order then the rest",
                Arrays.asList("app:e.zdf", "app:b.netflix", "app:a.arte", "app:d.youtube"), HomeModel.keys(m.apps));

        // Moves stay inside the row.
        check("move right", Arrays.asList("app:b.netflix", "app:e.zdf", "app:a.arte", "app:d.youtube"),
                HomeModel.moved(m.apps, 0, 1));
        check("move left at start refused", true, HomeModel.moved(m.apps, 0, -1) == null);
        check("move right at end refused", true, HomeModel.moved(m.apps, 3, 1) == null);

        // Reordering visible favourites keeps uninstalled ones for a later reinstall.
        check("merge favourites",
                Arrays.asList("app:b.netflix", "app:d.youtube", "app:not.installed"),
                HomeModel.mergeFavorites(Arrays.asList("app:b.netflix", "app:d.youtube"),
                        Arrays.asList("app:d.youtube", "app:not.installed", "app:b.netflix")));

        // Default lists from the brief.
        check("first default favourite", "app:com.sony.dtv.tvx", Prefs.DEFAULT_FAVORITES.get(0));
        check("default favourites count", 7, Prefs.DEFAULT_FAVORITES.size());

        // Default favourites on a TV that has only some of them: the rest are skipped without a trace.
        List<Entry> fewApps = Arrays.asList(app("com.netflix.ninja", "Netflix"), app("e.other", "Other"),
                app("com.google.android.tv", "Live Channels"));
        m = HomeModel.build(fewApps, inputs, system, Prefs.DEFAULT_FAVORITES, Collections.<String>emptySet(), none, none);
        check("defaults: only installed ones, in default order",
                Arrays.asList("app:com.google.android.tv", "app:com.netflix.ninja"), HomeModel.keys(m.favorites));
        check("defaults: other apps stay under Apps", Arrays.asList("app:e.other"), HomeModel.keys(m.apps));
        m = HomeModel.build(Collections.<Entry>emptyList(), inputs, system, Prefs.DEFAULT_FAVORITES,
                Collections.<String>emptySet(), none, none);
        check("defaults: none installed gives an empty favourites row", 0, m.favorites.size());
        check("launcher hides itself", true, Prefs.DEFAULT_HIDDEN.contains("app:tv.openshelf.home"));

        System.out.println(failures == 0 ? "all passed" : failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }
}
