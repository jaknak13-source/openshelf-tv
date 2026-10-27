package tv.openshelf.keeper;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import tv.openshelf.keeper.core.Job;
import tv.openshelf.keeper.core.PackageList;
import tv.openshelf.keeper.core.RunLog;

public final class MainActivity extends Activity {
    private static final String TAG = "OpenShelfKeeper";
    private static final long REFRESH_MS = 1_000;

    private Status status;
    private TextView connection;
    private TextView lastTime;
    private TextView result;
    private TextView hint;
    private TextView details;
    private Button apply;

    // Held in a field: SharedPreferences keeps listeners only weakly.
    private final SharedPreferences.OnSharedPreferenceChangeListener onChange = (prefs, key) -> render();
    // Second refresh path besides the listener, so a missed change notification cannot leave stale text.
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, REFRESH_MS);
        }
    };
    private long renderedLogTime = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = new Status(this);
        connection = findViewById(R.id.connection);
        lastTime = findViewById(R.id.last_time);
        result = findViewById(R.id.result);
        hint = findViewById(R.id.hint);
        details = findViewById(R.id.details);
        apply = findViewById(R.id.apply);
        TextView footer = findViewById(R.id.footer);
        footer.setText(getString(R.string.footer, PackageList.all().size(),
                KeeperService.ADB_HOST + ":" + KeeperService.ADB_PORT));
        apply.setOnClickListener(v -> {
            if (!KeeperService.isRunning()) {
                KeeperService.start(this, KeeperService.ACTION_MANUAL);
            }
        });
        apply.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        status.prefs.registerOnSharedPreferenceChangeListener(onChange);
        renderedLogTime = -1;
        tick.run();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        status.prefs.unregisterOnSharedPreferenceChangeListener(onChange);
        super.onPause();
    }

    private void render() {
        boolean running = KeeperService.isRunning();
        String state = status.state();
        String outcome = status.lastOutcome();

        if (running) {
            connection.setText(runningText(state));
            connection.setTextColor(getColor(R.color.text_primary));
        } else if (state != null) {
            // A state is only left behind when the process died during a run.
            connection.setText(R.string.conn_interrupted);
            connection.setTextColor(getColor(R.color.error));
        } else if (outcome == null) {
            connection.setText(R.string.conn_never);
            connection.setTextColor(getColor(R.color.text_secondary));
        } else {
            connection.setText(outcomeText(outcome));
            connection.setTextColor(getColor(outcomeColor(outcome)));
        }

        long time = status.lastTime();
        lastTime.setText(time == 0 ? getString(R.string.none) : dateFormat().format(new Date(time)));
        result.setText(outcome == null ? getString(R.string.none) : resultText(outcome));
        result.setTextColor(getColor(outcome == null ? R.color.text_secondary : outcomeColor(outcome)));

        boolean authProblem = running ? "WAITING_FOR_AUTH".equals(state) : "AUTH_PENDING".equals(outcome);
        hint.setVisibility(authProblem ? TextView.VISIBLE : TextView.GONE);
        apply.setText(running ? R.string.apply_running : R.string.apply);
        long time0 = status.lastTime();
        if (time0 != renderedLogTime) {
            details.setText(lastRunText());
            renderedLogTime = time0;
        }
    }

    private int runningText(String state) {
        if ("WAITING_FOR_AUTH".equals(state)) {
            return R.string.conn_waiting_auth;
        }
        if ("CONNECTED".equals(state)) {
            return R.string.conn_connected;
        }
        if (Job.STARTING.equals(state)) {
            return R.string.conn_starting;
        }
        return R.string.conn_connecting;
    }

    private static int outcomeText(String outcome) {
        switch (outcome) {
            case "DONE":
                return R.string.conn_ok;
            case "UNREACHABLE":
                return R.string.conn_unreachable;
            case "AUTH_PENDING":
                return R.string.conn_auth_missing;
            default:
                return R.string.conn_failed;
        }
    }

    private DateFormat dateFormat() {
        String pattern = getString(R.string.last_run_date_pattern);
        return pattern.isEmpty() ? DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
                : new SimpleDateFormat(pattern, Locale.getDefault());
    }

    /** Counts and the known outcomes are localized; other failures show the core's (English) error text. */
    private String resultText(String outcome) {
        switch (outcome) {
            case "DONE":
                int skipped = status.lastCount(Status.SKIPPED);
                int disabledNow = status.lastCount(Status.DISABLED_NOW);
                int alreadyOff = status.lastCount(Status.ALREADY_OFF);
                int notInstalled = status.lastCount(Status.NOT_INSTALLED);
                int errors = status.lastCount(Status.ERRORS);
                return skipped > 0
                        ? getString(R.string.result_done_skipped, disabledNow, alreadyOff, notInstalled, errors, skipped)
                        : getString(R.string.result_done, disabledNow, alreadyOff, notInstalled, errors);
            case "UNREACHABLE":
                return getString(R.string.result_unreachable, KeeperService.ADB_HOST + ":" + KeeperService.ADB_PORT);
            case "AUTH_PENDING":
                return getString(R.string.conn_auth_missing);
            default:
                return status.lastSummary();
        }
    }

    private static int outcomeColor(String outcome) {
        switch (outcome) {
            case "DONE":
                return R.color.ok;
            case "AUTH_PENDING":
                return R.color.warn;
            default:
                return R.color.error;
        }
    }

    private String lastRunText() {
        String run;
        try {
            run = new RunLog(KeeperService.logFile(this)).lastRun();
        } catch (IOException e) {
            Log.w(TAG, "cannot read run log", e);
            return getString(R.string.log_unreadable);
        }
        if (run.isEmpty()) {
            return getString(R.string.log_empty);
        }
        StringBuilder out = new StringBuilder();
        String[] lines = run.split("\n");
        // First line is the run header (time, trigger, summary), already shown on the left.
        for (int i = 1; i < lines.length; i++) {
            out.append(lines[i].trim()).append('\n');
        }
        return out.length() == 0 ? getString(R.string.log_no_details) : out.toString().trim();
    }
}
