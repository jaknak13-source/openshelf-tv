package tv.openshelf.home;

import android.content.Intent;
import android.content.pm.ActivityInfo;

/** One tile on the home screen: an app, an HDMI input or a fixed system action. Immutable. */
final class Entry {
    static final int APP = 0;
    static final int INPUT = 1;
    static final int SYSTEM = 2;

    static final String KEY_EDIT = "sys:edit";

    /** "app:&lt;package&gt;", "input:&lt;input id&gt;" or "sys:&lt;name&gt;". Stored in preferences. */
    final String key;
    final int kind;
    final String label;
    /** Second line on text cards (HDMI port, connection state). May be null. */
    final String detail;
    /** Null only for the internal edit action. */
    final Intent intent;
    /** Source of banner and icon. Null for text-only cards. */
    final ActivityInfo activity;
    /** Changes whenever the art may have changed (package update time). */
    final long stamp;
    /** Disconnected input: drawn quieter. */
    final boolean dimmed;

    Entry(String key, int kind, String label, String detail, Intent intent,
          ActivityInfo activity, long stamp, boolean dimmed) {
        this.key = key;
        this.kind = kind;
        this.label = label;
        this.detail = detail;
        this.intent = intent;
        this.activity = activity;
        this.stamp = stamp;
        this.dimmed = dimmed;
    }

    static String appKey(String packageName) {
        return "app:" + packageName;
    }

    static String inputKey(String inputId) {
        return "input:" + inputId;
    }
}
