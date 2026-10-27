package tv.openshelf.keeper.core;

/** Outcome of one apply run, with per-category counts. */
public final class Result {
    public enum Outcome { DONE, UNREACHABLE, AUTH_PENDING, FAILED }

    public final Outcome outcome;
    public final String message;
    public int disabledNow;
    public int alreadyOff;
    public int notInstalled;
    public int skipped;
    public int errors;

    Result(Outcome outcome, String message) {
        this.outcome = outcome;
        this.message = message;
    }

    public static Result failed(Outcome outcome, String message) {
        return new Result(outcome, message);
    }

    public String summary() {
        if (outcome != Outcome.DONE) {
            return message;
        }
        String s = disabledNow + " disabled now · " + alreadyOff + " already off · "
                + notInstalled + " not installed · " + errors + " errors";
        return skipped > 0 ? s + " · " + skipped + " skipped" : s;
    }
}
