package tv.openshelf.keeper.core;

import java.io.IOException;
import java.net.ConnectException;
import java.util.List;
import java.util.Set;

/** Connects to the TV's own adbd and disables every listed package that is installed and still enabled. */
public final class Runner {
    public enum State { CONNECTING, WAITING_FOR_AUTH, CONNECTED }

    public interface Listener {
        void onState(State state);

        void onLine(String line);
    }

    private static final long[] BACKOFF_MS = {2_000, 4_000, 8_000, 15_000, 30_000};
    private static final String DISABLED_MARKER = "new state: disabled-user";

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int authWaitMs;

    public Runner(String host, int port, int connectTimeoutMs, int readTimeoutMs, int authWaitMs) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.authWaitMs = authWaitMs;
    }

    /**
     * Retries connection and protocol failures with backoff until {@code budgetMs} is used up (adbd may still
     * be starting after boot). A pending TV confirmation is not retried: every attempt would raise a new prompt.
     */
    public Result runWithRetry(AdbKey key, String keyName, List<String> packages, String selfPackage,
                               long budgetMs, Listener listener) throws InterruptedException {
        long deadline = System.nanoTime() + budgetMs * 1_000_000L;
        for (int attempt = 0; ; attempt++) {
            try {
                return runOnce(key, keyName, packages, selfPackage, listener);
            } catch (AdbConnection.AuthPendingException e) {
                listener.onLine("key not approved: " + describe(e));
                return Result.failed(Result.Outcome.AUTH_PENDING, "Not approved on the TV yet");
            } catch (IOException e) {
                listener.onLine("attempt " + (attempt + 1) + " failed: " + describe(e));
                long wait = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
                if (System.nanoTime() + wait * 1_000_000L > deadline) {
                    if (e instanceof ConnectException) {
                        return Result.failed(Result.Outcome.UNREACHABLE,
                                "adbd not reachable (" + host + ":" + port + ")");
                    }
                    return Result.failed(Result.Outcome.FAILED, "Error: " + describe(e));
                }
                Thread.sleep(wait);
            }
        }
    }

    Result runOnce(AdbKey key, String keyName, List<String> packages, String selfPackage,
                   Listener listener) throws IOException {
        listener.onState(State.CONNECTING);
        try (AdbConnection c = AdbConnection.open(host, port, key, keyName, connectTimeoutMs, readTimeoutMs,
                authWaitMs, () -> listener.onState(State.WAITING_FOR_AUTH))) {
            listener.onState(State.CONNECTED);
            Set<String> disabled = listPackages(c, Plan.LIST_DISABLED);
            Set<String> installed = listPackages(c, Plan.LIST_INSTALLED);
            if (installed.isEmpty()) {
                throw new IOException("pm list packages returned no packages (package manager not ready?)");
            }
            Set<String> enabled = listPackages(c, Plan.LIST_ENABLED);
            Result r = new Result(Result.Outcome.DONE, null);
            for (Plan.Step step : Plan.build(packages, installed, disabled, enabled, selfPackage)) {
                switch (step.decision) {
                    case DISABLE:
                        String out = c.shell(Plan.disableCommand(step.pkg)).trim();
                        if (out.contains(DISABLED_MARKER)) {
                            r.disabledNow++;
                            listener.onLine("disabled: " + step.pkg);
                        } else {
                            r.errors++;
                            listener.onLine("ERROR " + step.pkg + ": " + out);
                        }
                        break;
                    case ALREADY_OFF:
                        r.alreadyOff++;
                        break;
                    case NOT_INSTALLED:
                        r.notInstalled++;
                        break;
                    case KEPT_FOR_HOME:
                        r.skipped++;
                        listener.onLine("skipped: " + step.pkg + " (no replacement launcher enabled: "
                                + String.join(", ", Plan.REPLACEMENT_LAUNCHERS) + "; Home would be empty)");
                        break;
                    case DENIED:
                        r.skipped++;
                        listener.onLine("skipped: " + step.pkg + " (deny list)");
                        break;
                    case INVALID_NAME:
                        r.skipped++;
                        listener.onLine("skipped: invalid package name " + step.pkg);
                        break;
                }
            }
            return r;
        }
    }

    /** Empty output is an empty list; output without a single valid "package:" line is a pm error. */
    private static Set<String> listPackages(AdbConnection c, String command) throws IOException {
        String out = c.shell(command);
        Set<String> pkgs = Plan.parsePackageList(out);
        if (pkgs.isEmpty() && !out.trim().isEmpty()) {
            String head = out.trim();
            throw new IOException("'" + command + "' returned no package lines: "
                    + (head.length() > 200 ? head.substring(0, 200) + " ..." : head));
        }
        return pkgs;
    }

    private static String describe(Exception e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }
}
