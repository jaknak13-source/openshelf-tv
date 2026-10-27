package tv.openshelf.home;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** User choices. Lists are stored newline-separated so their order survives. */
final class Prefs {
    static final List<String> DEFAULT_FAVORITES = Collections.unmodifiableList(Arrays.asList(
            Entry.appKey("com.sony.dtv.tvx"),
            Entry.appKey("com.google.android.tv"),
            Entry.appKey("com.netflix.ninja"),
            Entry.appKey("com.google.android.youtube.tv"),
            Entry.appKey("org.smarttube.stable"),
            Entry.appKey("com.disney.disneyplus"),
            Entry.appKey("com.amazon.amazonvideo.livingroom")));

    static final List<String> DEFAULT_HIDDEN = Collections.unmodifiableList(Arrays.asList(
            Entry.appKey("com.sony.dtv.timers"),
            Entry.appKey("com.sony.dtv.tvxlauncher.programguide"),
            Entry.appKey("com.sony.dtv.tvxlauncher.titlelist"),
            Entry.appKey("tv.openshelf.home")));

    static final String ORDER_APPS = "order_apps";
    static final String ORDER_INPUTS = "order_inputs";

    private static final String FAVORITES = "favorites";
    private static final String HIDDEN = "hidden";
    private static final String VERSION = "version";
    private static final String WALLPAPER_NAME = "wallpaper_name";
    private static final String WALLPAPER_STAMP = "wallpaper_stamp";

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    /** Bumped on every write, so the home screen can tell that the edit screen changed something. */
    int version() {
        return sp.getInt(VERSION, 0);
    }

    List<String> favorites() {
        return list(FAVORITES, DEFAULT_FAVORITES);
    }

    Set<String> hidden() {
        return new HashSet<>(list(HIDDEN, DEFAULT_HIDDEN));
    }

    List<String> hiddenOrdered() {
        return list(HIDDEN, DEFAULT_HIDDEN);
    }

    List<String> order(String name) {
        return list(name, Collections.<String>emptyList());
    }

    void setFavorites(List<String> keys) {
        put(FAVORITES, keys);
    }

    void setHidden(List<String> keys) {
        put(HIDDEN, keys);
    }

    void setOrder(String name, List<String> keys) {
        put(name, keys);
    }

    /** Display name of the custom wallpaper, or null when the bundled one is active. */
    String wallpaperName() {
        return sp.getString(WALLPAPER_NAME, null);
    }

    long wallpaperStamp() {
        return sp.getLong(WALLPAPER_STAMP, 0L);
    }

    void setWallpaper(String name) {
        sp.edit()
                .putString(WALLPAPER_NAME, name)
                .putLong(WALLPAPER_STAMP, System.currentTimeMillis())
                .putInt(VERSION, version() + 1)
                .apply();
    }

    private List<String> list(String key, List<String> def) {
        String s = sp.getString(key, null);
        if (s == null) return new ArrayList<>(def);
        if (s.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(s.split("\n")));
    }

    private void put(String key, List<String> keys) {
        sp.edit().putString(key, String.join("\n", keys)).putInt(VERSION, version() + 1).apply();
    }
}
