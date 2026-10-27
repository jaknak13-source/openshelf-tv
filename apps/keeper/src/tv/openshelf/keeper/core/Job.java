package tv.openshelf.keeper.core;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** One complete apply run. Whatever fails, it ends with a run in the log and a result handed to the sink. */
public final class Job {
    /** State name written before anything can fail, so the UI shows that a run began. */
    public static final String STARTING = "STARTING";

    public interface KeySource {
        AdbKey load(Consumer<String> log) throws Exception;
    }

    public interface Sink {
        void state(String state);

        void finished(Result result, long time);
    }

    private Job() {
    }

    public static Result run(KeySource keys, Runner runner, String keyName, List<String> packages, String selfPackage,
                             long budgetMs, String trigger, RunLog log, Sink sink) {
        sink.state(STARTING);
        List<String> lines = new ArrayList<>();
        Runner.Listener listener = new Runner.Listener() {
            @Override
            public void onState(Runner.State state) {
                sink.state(state.name());
            }

            @Override
            public void onLine(String line) {
                lines.add(line);
            }
        };
        Result result;
        try {
            AdbKey key = keys.load(lines::add);
            result = runner.runWithRetry(key, keyName, packages, selfPackage, budgetMs, listener);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = Result.failed(Result.Outcome.FAILED, "Cancelled");
        } catch (Throwable e) {
            // Top-level boundary of a background run: any failure, including Errors from platform differences,
            // must reach the log and the UI instead of killing the process.
            lines.add("Error: " + e);
            for (StackTraceElement frame : firstFrames(e)) {
                lines.add("    at " + frame);
            }
            result = Result.failed(Result.Outcome.FAILED, "Error: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }

        long now = System.currentTimeMillis();
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(now));
        try {
            log.append(when + " · " + trigger + " · " + result.summary(), lines);
        } catch (Throwable e) {
            result = Result.failed(result.outcome == Result.Outcome.DONE ? Result.Outcome.FAILED : result.outcome,
                    result.summary() + " (log not writable: " + e + ")");
        }
        sink.finished(result, now);
        return result;
    }

    private static List<StackTraceElement> firstFrames(Throwable e) {
        List<StackTraceElement> out = new ArrayList<>();
        StackTraceElement[] trace = e.getStackTrace();
        for (int i = 0; i < trace.length && i < 4; i++) {
            out.add(trace[i]);
        }
        return out;
    }
}
