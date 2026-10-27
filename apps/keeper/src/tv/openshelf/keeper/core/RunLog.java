package tv.openshelf.keeper.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Rolling text log: one block per run, keeps at most MAX_RUNS blocks and MAX_BYTES bytes. */
public final class RunLog {
    static final int MAX_RUNS = 50;
    static final int MAX_BYTES = 50 * 1024;
    private static final String RUN_MARK = "=== ";

    private final File file;

    public RunLog(File file) {
        this.file = file;
    }

    public synchronized void append(String header, List<String> lines) throws IOException {
        StringBuilder run = new StringBuilder(RUN_MARK).append(oneLine(header)).append('\n');
        for (String line : lines) {
            // Body lines are indented so no line inside a run can look like a run header.
            run.append("  ").append(oneLine(line)).append('\n');
        }
        List<String> runs = readRuns();
        runs.add(run.toString());
        int bytes = 0;
        for (String r : runs) {
            bytes += r.getBytes(StandardCharsets.UTF_8).length;
        }
        while (runs.size() > 1 && (runs.size() > MAX_RUNS || bytes > MAX_BYTES)) {
            bytes -= runs.remove(0).getBytes(StandardCharsets.UTF_8).length;
        }
        File tmp = new File(file.getPath() + ".tmp");
        Files.write(tmp.toPath(), String.join("", runs).getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(file)) {
            throw new IOException("cannot write " + file);
        }
    }

    /** Text of the newest run, or "" if nothing was logged yet. */
    public synchronized String lastRun() throws IOException {
        List<String> runs = readRuns();
        return runs.isEmpty() ? "" : runs.get(runs.size() - 1);
    }

    synchronized List<String> readRuns() throws IOException {
        List<String> runs = new ArrayList<>();
        if (!file.exists()) {
            return runs;
        }
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        int start = 0;
        while (start < text.length()) {
            int next = text.indexOf("\n" + RUN_MARK, start);
            int end = next < 0 ? text.length() : next + 1;
            runs.add(text.substring(start, end));
            start = end;
        }
        return runs;
    }

    private static String oneLine(String s) {
        return s.replace("\r", "").replace("\n", " | ");
    }
}
