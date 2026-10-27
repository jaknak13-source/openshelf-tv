package tv.openshelf.home;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the installed apps and inputs plus the user's choices into the four rows.
 * Pure Java on purpose, so the rules can be tested off the device (see test/).
 */
final class HomeModel {
    static final int ROW_FAVORITES = 0;
    static final int ROW_APPS = 1;
    static final int ROW_INPUTS = 2;
    static final int ROW_SYSTEM = 3;

    final List<Entry> favorites;
    final List<Entry> apps;
    final List<Entry> inputs;
    final List<Entry> system;

    private HomeModel(List<Entry> favorites, List<Entry> apps, List<Entry> inputs, List<Entry> system) {
        this.favorites = favorites;
        this.apps = apps;
        this.inputs = inputs;
        this.system = system;
    }

    /** Rows taken as they are (startup snapshot). */
    static HomeModel of(List<Entry> favorites, List<Entry> apps, List<Entry> inputs, List<Entry> system) {
        return new HomeModel(favorites, apps, inputs, system);
    }

    List<Entry> row(int row) {
        switch (row) {
            case ROW_FAVORITES: return favorites;
            case ROW_APPS: return apps;
            case ROW_INPUTS: return inputs;
            default: return system;
        }
    }

    /**
     * @param apps    launchable apps in natural (alphabetical) order
     * @param inputs  HDMI inputs in natural order (connected first)
     * @param favKeys favourite keys in the user's order; keys of missing apps are skipped
     */
    static HomeModel build(List<Entry> apps, List<Entry> inputs, List<Entry> system,
                           List<String> favKeys, Set<String> hidden,
                           List<String> appOrder, List<String> inputOrder) {
        Map<String, Entry> all = new LinkedHashMap<>();
        for (Entry e : apps) all.put(e.key, e);
        for (Entry e : inputs) all.put(e.key, e);

        List<Entry> favs = new ArrayList<>();
        Set<String> favSet = new HashSet<>();
        for (String k : favKeys) {
            Entry e = all.get(k);
            if (e != null && !hidden.contains(k) && favSet.add(k)) favs.add(e);
        }
        return new HomeModel(favs,
                ordered(visible(apps, favSet, hidden), appOrder),
                ordered(visible(inputs, favSet, hidden), inputOrder),
                new ArrayList<>(system));
    }

    private static List<Entry> visible(List<Entry> natural, Set<String> favs, Set<String> hidden) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : natural) {
            if (!favs.contains(e.key) && !hidden.contains(e.key)) out.add(e);
        }
        return out;
    }

    /** Keys named in {@code order} come first in that order; everything else keeps natural order after them. */
    static List<Entry> ordered(List<Entry> natural, List<String> order) {
        Map<String, Entry> rest = new LinkedHashMap<>();
        for (Entry e : natural) rest.put(e.key, e);
        List<Entry> out = new ArrayList<>();
        for (String k : order) {
            Entry e = rest.remove(k);
            if (e != null) out.add(e);
        }
        out.addAll(rest.values());
        return out;
    }

    /** Row keys after moving the entry at {@code index} by {@code delta}; null if the move is impossible. */
    static List<String> moved(List<Entry> row, int index, int delta) {
        int target = index + delta;
        if (index < 0 || index >= row.size() || target < 0 || target >= row.size()) return null;
        List<String> keys = keys(row);
        String k = keys.remove(index);
        keys.add(target, k);
        return keys;
    }

    /**
     * New favourite list after reordering the visible favourites. Stored keys that are not
     * visible right now (uninstalled apps) are kept at the end so a reinstall brings them back.
     */
    static List<String> mergeFavorites(List<String> visibleOrder, List<String> stored) {
        List<String> out = new ArrayList<>(visibleOrder);
        for (String k : stored) {
            if (!out.contains(k)) out.add(k);
        }
        return out;
    }

    static List<String> keys(List<Entry> row) {
        List<String> out = new ArrayList<>(row.size());
        for (Entry e : row) out.add(e.key);
        return out;
    }

    static int indexOf(List<Entry> row, String key) {
        for (int i = 0; i < row.size(); i++) {
            if (row.get(i).key.equals(key)) return i;
        }
        return -1;
    }
}
