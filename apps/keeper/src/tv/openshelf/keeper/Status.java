package tv.openshelf.keeper;

import android.content.Context;
import android.content.SharedPreferences;

import tv.openshelf.keeper.core.Result;

/** Run state shared between the service (writer) and the activity (reader, via change listener). */
final class Status {
    static final String PREFS = "status";
    private static final String KEY_STATE = "state";
    private static final String KEY_TIME = "last_time";
    private static final String KEY_OUTCOME = "last_outcome";
    private static final String KEY_SUMMARY = "last_summary";
    static final String DISABLED_NOW = "last_disabled_now";
    static final String ALREADY_OFF = "last_already_off";
    static final String NOT_INSTALLED = "last_not_installed";
    static final String ERRORS = "last_errors";
    static final String SKIPPED = "last_skipped";

    final SharedPreferences prefs;

    Status(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void setState(String state) {
        prefs.edit().putString(KEY_STATE, state).apply();
    }

    void saveResult(Result result, long time) {
        prefs.edit()
                .remove(KEY_STATE)
                .putLong(KEY_TIME, time)
                .putString(KEY_OUTCOME, result.outcome.name())
                .putString(KEY_SUMMARY, result.summary())
                .putInt(DISABLED_NOW, result.disabledNow)
                .putInt(ALREADY_OFF, result.alreadyOff)
                .putInt(NOT_INSTALLED, result.notInstalled)
                .putInt(ERRORS, result.errors)
                .putInt(SKIPPED, result.skipped)
                .apply();
    }

    /** Runner.State name while a run is in progress, else null. */
    String state() {
        return prefs.getString(KEY_STATE, null);
    }

    long lastTime() {
        return prefs.getLong(KEY_TIME, 0);
    }

    /** Result.Outcome name of the last finished run, else null. */
    String lastOutcome() {
        return prefs.getString(KEY_OUTCOME, null);
    }

    String lastSummary() {
        return prefs.getString(KEY_SUMMARY, "");
    }

    /** One of the count keys above, from the last finished run. */
    int lastCount(String key) {
        return prefs.getInt(key, 0);
    }
}
