package tv.openshelf.keeper.core;

import java.io.File;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.InvalidKeySpecException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Plain-JVM tests for the Android-free core. Usage: CoreTests <scratch-dir> */
public final class CoreTests {
    private static final String SELF = "tv.openshelf.keeper";
    private static final String KEY_NAME = "test@keeper";
    private static final String PROJECTIVY = "com.spocky.projengmenu";
    private static final String OWN_HOME = "tv.openshelf.home";
    private static final String STOCK = "com.google.android.tvlauncher";
    private static int passed;

    public static void main(String[] args) throws Exception {
        File scratch = new File(args[0]);
        scratch.mkdirs();
        planRules();
        embeddedList();
        generatedKey(scratch);
        jobBoundary(scratch);
        runLog(scratch);
        runnerAgainstFakeAdbd(scratch);
        System.out.println("CoreTests: " + passed + " checks passed");
    }

    static void check(boolean ok, String what) {
        if (!ok) {
            System.out.println("FAIL " + what);
            System.exit(1);
        }
        passed++;
        System.out.println("PASS " + what);
    }

    static Set<String> set(String... s) {
        return new HashSet<>(Arrays.asList(s));
    }

    static Plan.Decision decide(String pkg, Set<String> installed, Set<String> disabled) {
        Set<String> enabled = new HashSet<>(installed);
        enabled.removeAll(disabled);
        return Plan.build(Collections.singletonList(pkg), installed, disabled, enabled, SELF).get(0).decision;
    }

    static void planRules() {
        Set<String> withHome = set(STOCK, PROJECTIVY, "com.x");
        check(decide(STOCK, withHome, set()) == Plan.Decision.DISABLE,
                "stock launcher disabled when projengmenu installed and enabled");
        check(decide(STOCK, withHome, set(PROJECTIVY))
                == Plan.Decision.KEPT_FOR_HOME, "stock launcher kept when projengmenu disabled");
        check(decide(STOCK, set(STOCK), set())
                == Plan.Decision.KEPT_FOR_HOME, "stock launcher kept when no replacement installed");
        check(decide(STOCK, set(STOCK, OWN_HOME), set()) == Plan.Decision.DISABLE,
                "stock launcher disabled when only openshelf home installed and enabled");
        check(decide(STOCK, set(STOCK, OWN_HOME), set(OWN_HOME)) == Plan.Decision.KEPT_FOR_HOME,
                "stock launcher kept when openshelf home is the only replacement and disabled");
        check(decide(STOCK, set(STOCK, OWN_HOME, PROJECTIVY), set(PROJECTIVY)) == Plan.Decision.DISABLE,
                "stock launcher disabled when projengmenu disabled but openshelf home enabled");
        check(decide(STOCK, set(STOCK, OWN_HOME, PROJECTIVY), set(OWN_HOME)) == Plan.Decision.DISABLE,
                "stock launcher disabled when openshelf home disabled but projengmenu enabled");
        check(decide(STOCK, set(STOCK, OWN_HOME, PROJECTIVY), set(OWN_HOME, PROJECTIVY))
                == Plan.Decision.KEPT_FOR_HOME, "stock launcher kept when both replacements disabled");
        check(Plan.build(Collections.singletonList(STOCK), set(STOCK, PROJECTIVY), set(), set(STOCK), SELF)
                .get(0).decision == Plan.Decision.KEPT_FOR_HOME,
                "stock launcher kept when replacement is not positively listed as enabled");
        check(decide("com.google.android.tvlauncher", withHome, set("com.google.android.tvlauncher"))
                == Plan.Decision.ALREADY_OFF, "stock launcher already off is reported as such");

        Set<String> all = set("com.sony.dtv.tvx", "com.sony.dtv.tvinput.hdmi", "com.sony.dtv.tvinput",
                "com.android.tv.settings", "com.android.vending", "com.google.android.gms", "com.android.systemui",
                "com.google.android.katniss", "com.google.android.apps.mediashell", "com.sony.dtv.braviasyncservice",
                "com.sony.dtv.audiooutput", "com.sony.dtv.hbbtvlauncher", "com.sony.dtv.systemupdate",
                "com.spocky.projengmenu", "tv.openshelf.home", "io.github.jqssun.airplay", SELF);
        for (String p : all) {
            check(decide(p, all, set()) == Plan.Decision.DENIED, "denied: " + p);
        }
        check(decide("com.x", set("com.x"), set()) == Plan.Decision.DISABLE, "ordinary installed package disabled");
        check(decide("com.x", set(), set()) == Plan.Decision.NOT_INSTALLED, "missing package not installed");
        check(decide("com.x", set("com.x"), set("com.x")) == Plan.Decision.ALREADY_OFF, "disabled package already off");
        for (String bad : Arrays.asList("a;reboot", "a b", "a&&b", "", "a\nb", "a/b", "$(x)")) {
            check(decide(bad, set(bad), set()) == Plan.Decision.INVALID_NAME, "invalid name rejected: " + bad.replace("\n", "\\n"));
            boolean threw = false;
            try {
                Plan.disableCommand(bad);
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            check(threw, "disableCommand refuses: " + bad.replace("\n", "\\n"));
        }
        check(Plan.disableCommand("com.x_1.Y").equals("pm disable-user --user 0 com.x_1.Y"), "disable command text");
        check(Plan.parsePackageList("package:com.a\r\npackage:com.b\r\n\r\nWARNING junk\r\npackage:bad;x\r\n")
                .equals(set("com.a", "com.b")), "parsePackageList handles CRLF and drops junk/invalid lines");
    }

    static void embeddedList() {
        List<String> pkgs = PackageList.all();
        check(pkgs.size() == new HashSet<>(pkgs).size(), "embedded list has no duplicates (" + pkgs.size() + ")");
        for (String p : pkgs) {
            check(Plan.isValidName(p) && !Plan.isDenied(p, SELF), "embedded entry valid and not denied: " + p);
        }
    }

    static String mode(File f) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath()));
    }

    static AdbKey load(File scratch, List<String> notes) throws Exception {
        return AdbKey.loadOrCreate(new File(scratch, "gen-adbkey"), new File(scratch, "gen-adbkey.pub.der"), notes::add);
    }

    static void generatedKey(File scratch) throws Exception {
        File priv = new File(scratch, "gen-adbkey");
        File pub = new File(scratch, "gen-adbkey.pub.der");
        priv.delete();
        pub.delete();
        List<String> notes = new ArrayList<>();
        AdbKey k1 = load(scratch, notes);
        check(priv.isFile() && pub.isFile(), "loadOrCreate stores private and public key");
        check(mode(priv).equals("rw-------") && mode(pub).equals("rw-------"),
                "key files are 0600 (" + mode(priv) + ", " + mode(pub) + ")");
        check(notes.size() == 1 && notes.get(0).startsWith("new ADB key"), "new key is logged: " + notes);
        check(k1.androidPublicKey().length == 524, "public blob is 524 bytes");

        notes.clear();
        AdbKey k2 = load(scratch, notes);
        check(Arrays.equals(k1.androidPublicKey(), k2.androidPublicKey()) && notes.isEmpty(),
                "stored pair is reused silently");

        // What the broken build left on the TV: a private key file without a public key file.
        pub.delete();
        notes.clear();
        AdbKey k3 = load(scratch, notes);
        check(!Arrays.equals(k1.androidPublicKey(), k3.androidPublicKey()) && pub.isFile()
                        && notes.size() == 2 && notes.get(0).contains("incomplete"),
                "private-only leftover is replaced by a new pair: " + notes);

        Files.write(priv.toPath(), "not a key".getBytes(StandardCharsets.US_ASCII));
        notes.clear();
        AdbKey k4 = load(scratch, notes);
        check(!Arrays.equals(k3.androidPublicKey(), k4.androidPublicKey()) && notes.get(0).contains("unusable"),
                "unparseable key file is replaced: " + notes.get(0));

        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        Files.write(pub.toPath(), gen.generateKeyPair().getPublic().getEncoded());
        notes.clear();
        load(scratch, notes);
        check(notes.get(0).contains("unusable"), "private/public mismatch is replaced: " + notes.get(0));

        KeyPair pair = gen.generateKeyPair();
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) pair.getPrivate();
        PrivateKey plain = new TestKeys.PlainRsaPrivateKey(crt.getModulus(), crt.getPrivateExponent());
        check(!(plain instanceof RSAPrivateCrtKey), "test key is not an RSAPrivateCrtKey");
        AdbKey viaPlain = AdbKey.fromKeys(plain, pair.getPublic());
        AdbKey viaCrt = AdbKey.fromKeys(pair.getPrivate(), pair.getPublic());
        check(Arrays.equals(viaPlain.androidPublicKey(), viaCrt.androidPublicKey()),
                "non-CRT private key gives the same public blob");
        byte[] token = new byte[20];
        new java.security.SecureRandom().nextBytes(token);
        Signature v = Signature.getInstance("NONEwithRSA");
        v.initVerify(pair.getPublic());
        v.update(KeyEncodingCheck.concat(AdbKey.SHA1_DIGEST_INFO, token));
        check(v.verify(viaPlain.sign(token)), "non-CRT private key signs verifiably");
        AdbKey viaOpaque = AdbKey.fromKeys(new TestKeys.OpaquePrivateKey(pair.getPrivate().getEncoded()),
                new TestKeys.OpaquePublicKey(pair.getPublic().getEncoded()));
        check(Arrays.equals(viaOpaque.androidPublicKey(), viaCrt.androidPublicKey()),
                "opaque key objects are read through KeyFactory specs");
        boolean mismatch = false;
        try {
            AdbKey.fromKeys(plain, gen.generateKeyPair().getPublic());
        } catch (InvalidKeySpecException e) {
            mismatch = true;
        }
        check(mismatch, "fromKeys rejects a public key from another pair");
        boolean threw = false;
        try {
            viaPlain.sign(new byte[19]);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "sign rejects a token that is not 20 bytes");
    }

    static final class SinkRecorder implements Job.Sink {
        final List<String> states = new ArrayList<>();
        Result result;

        @Override
        public void state(String state) {
            states.add(state);
        }

        @Override
        public void finished(Result r, long time) {
            result = r;
        }
    }

    static void jobBoundary(File scratch) throws Exception {
        Runner unused = new Runner("127.0.0.1", 1, 100, 100, 100);
        File logFile = new File(scratch, "job.log");
        logFile.delete();
        RunLog log = new RunLog(logFile);

        // The failure seen on the TV: key loading throws before any connection.
        SinkRecorder sink = new SinkRecorder();
        Job.run(notes -> {
            throw new InvalidKeySpecException("not an RSA CRT private key");
        }, unused, KEY_NAME, PackageList.all(), SELF, 1000, "manual", log, sink);
        String run = log.lastRun();
        check(sink.states.size() == 1 && Job.STARTING.equals(sink.states.get(0)), "job reports STARTING first");
        check(sink.result != null && sink.result.outcome == Result.Outcome.FAILED
                        && sink.result.summary().equals("Error: InvalidKeySpecException: not an RSA CRT private key"),
                "key failure ends in FAILED with the error message: " + (sink.result == null ? null : sink.result.summary()));
        check(run.contains("· manual · Error: InvalidKeySpecException")
                        && run.contains("  Error: java.security.spec.InvalidKeySpecException: not an RSA CRT private key"),
                "key failure is a logged run:\n" + run);

        sink = new SinkRecorder();
        Job.run(notes -> {
            throw new NoSuchMethodError("missing platform API");
        }, unused, KEY_NAME, PackageList.all(), SELF, 1000, "boot", log, sink);
        check(sink.result.outcome == Result.Outcome.FAILED && log.lastRun().contains("NoSuchMethodError"),
                "an Error (not Exception) is also logged and reported: " + sink.result.summary());

        sink = new SinkRecorder();
        Job.run(notes -> {
            throw new InvalidKeyException("x");
        }, unused, KEY_NAME, PackageList.all(), SELF, 1000, "manual", new RunLog(scratch), sink);
        check(sink.result != null && sink.result.summary().contains("log not writable"),
                "unwritable log still reaches the sink: " + sink.result.summary());
    }

    static void runLog(File scratch) throws Exception {
        File f = new File(scratch, "run.log");
        f.delete();
        RunLog log = new RunLog(f);
        check(log.lastRun().isEmpty(), "empty log has no last run");
        for (int i = 1; i <= 60; i++) {
            log.append("run " + i, Arrays.asList("line a", "=== looks like a header", "multi\nline"));
        }
        List<String> runs = log.readRuns();
        check(runs.size() == RunLog.MAX_RUNS, "log keeps " + RunLog.MAX_RUNS + " runs (has " + runs.size() + ")");
        check(runs.get(0).startsWith("=== run 11\n"), "oldest kept run is run 11");
        check(log.lastRun().startsWith("=== run 60\n") && log.lastRun().contains("  multi | line\n"),
                "last run is run 60 with flattened multi-line text");
        char[] filler = new char[3000];
        Arrays.fill(filler, 'x');
        for (int i = 0; i < 30; i++) {
            log.append("big " + i, Collections.singletonList(new String(filler)));
        }
        check(f.length() <= RunLog.MAX_BYTES, "log stays under " + RunLog.MAX_BYTES + " bytes (" + f.length() + ")");
        check(log.lastRun().startsWith("=== big 29\n"), "newest big run kept");
    }

    static final class Recorder implements Runner.Listener {
        final List<Runner.State> states = new ArrayList<>();
        final List<String> lines = new ArrayList<>();

        @Override
        public void onState(Runner.State state) {
            states.add(state);
        }

        @Override
        public void onLine(String line) {
            lines.add(line);
        }

        long count(String prefix) {
            return lines.stream().filter(l -> l.startsWith(prefix)).count();
        }
    }

    static void runnerAgainstFakeAdbd(File scratch) throws Exception {
        AdbKey key = load(scratch, new ArrayList<>());
        List<String> wanted = Arrays.asList("com.bloat.one", "com.bloat.two", "com.bloat.off", "com.bloat.missing",
                "com.google.android.tvlauncher", "com.sony.dtv.tvx", "bad;reboot", SELF, "com.bloat.protected");

        try (FakeAdbd adbd = new FakeAdbd()) {
            adbd.installed.addAll(set("com.bloat.one", "com.bloat.two", "com.bloat.off", "com.google.android.tvlauncher",
                    "com.sony.dtv.tvx", SELF, PROJECTIVY, "com.bloat.protected", "com.other"));
            adbd.disabled.add("com.bloat.off");
            adbd.refuseDisable.add("com.bloat.protected");
            adbd.userAccepts = true;
            Runner runner = new Runner("127.0.0.1", adbd.port(), 1000, 3000, 1000);

            // 1. Unknown key, user confirms on the TV.
            Recorder rec = new Recorder();
            Result r = runner.runWithRetry(key, KEY_NAME, wanted, SELF, 5000, rec);
            check(r.outcome == Result.Outcome.DONE, "first run with key confirmation: DONE (" + r.summary() + ")");
            check(rec.states.contains(Runner.State.WAITING_FOR_AUTH), "WAITING_FOR_AUTH reported while key offered");
            check(new String(key.publicKeyPayload(KEY_NAME), StandardCharsets.US_ASCII).equals(adbd.offeredPayload),
                    "adbd received exactly publicKeyPayload()");
            check(r.disabledNow == 3 && r.alreadyOff == 1 && r.notInstalled == 1 && r.skipped == 3 && r.errors == 1,
                    "counts 3 disabled / 1 already off / 1 not installed / 3 skipped / 1 error: " + r.summary());
            check(adbd.disabled.equals(set("com.bloat.one", "com.bloat.two", "com.bloat.off",
                    "com.google.android.tvlauncher")), "fake pm now has exactly the expected disabled set");
            check(adbd.commands.equals(Arrays.asList("shell:pm list packages -d", "shell:pm list packages",
                    "shell:pm list packages -e",
                    "shell:pm disable-user --user 0 com.bloat.one", "shell:pm disable-user --user 0 com.bloat.two",
                    "shell:pm disable-user --user 0 com.google.android.tvlauncher",
                    "shell:pm disable-user --user 0 com.bloat.protected")),
                    "only list commands, then disable-user for planned packages: " + adbd.commands);
            check(rec.count("ERROR com.bloat.protected") == 1, "pm error output logged");
            check(adbd.problems.isEmpty(), "fake adbd saw no protocol problems " + adbd.problems);

            // 2. Key now authorized: signature path, nothing offered, everything already off.
            adbd.offeredPayload = null;
            adbd.commands.clear();
            rec = new Recorder();
            r = runner.runWithRetry(key, KEY_NAME, wanted, SELF, 5000, rec);
            check(r.outcome == Result.Outcome.DONE && r.disabledNow == 0 && r.alreadyOff == 4 && r.errors == 1,
                    "second run: nothing new to disable (" + r.summary() + ")");
            check(adbd.offeredPayload == null && !rec.states.contains(Runner.State.WAITING_FOR_AUTH),
                    "authorized key: signature accepted, no key offer");
            check(adbd.problems.isEmpty(), "no protocol problems on second run " + adbd.problems);
        }

        // 3. Replacement launcher disabled: stock launcher must stay.
        try (FakeAdbd adbd = new FakeAdbd()) {
            adbd.installed.addAll(set(STOCK, PROJECTIVY, OWN_HOME));
            adbd.disabled.addAll(set(PROJECTIVY, OWN_HOME));
            adbd.userAccepts = true;
            Recorder rec = new Recorder();
            Result r = new Runner("127.0.0.1", adbd.port(), 1000, 3000, 1000)
                    .runWithRetry(key, KEY_NAME, Collections.singletonList("com.google.android.tvlauncher"), SELF, 5000, rec);
            check(r.outcome == Result.Outcome.DONE && r.skipped == 1 && r.disabledNow == 0,
                    "stock launcher skipped when both replacements disabled (" + r.summary() + ")");
            check(!adbd.disabled.contains("com.google.android.tvlauncher"), "fake pm: stock launcher still enabled");
            check(rec.count("skipped: com.google.android.tvlauncher") == 1, "skip is logged");
        }

        // 4. Unknown key, nobody confirms: AUTH_PENDING, exactly one connection (no prompt spam).
        try (FakeAdbd adbd = new FakeAdbd()) {
            adbd.installed.add("com.bloat.one");
            adbd.userAccepts = false;
            Recorder rec = new Recorder();
            long t0 = System.nanoTime();
            Result r = new Runner("127.0.0.1", adbd.port(), 1000, 3000, 800)
                    .runWithRetry(key, KEY_NAME, wanted, SELF, 30_000, rec);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            check(r.outcome == Result.Outcome.AUTH_PENDING, "unconfirmed key: AUTH_PENDING (" + r.summary() + ")");
            check(adbd.connections == 1, "unconfirmed key: no retry (connections=" + adbd.connections + ")");
            check(ms < 5000, "unconfirmed key: returns after auth wait, not budget (" + ms + " ms)");
            check(adbd.commands.isEmpty(), "unconfirmed key: no shell command sent");
        }

        // 5. Nothing listening: retried with backoff until the budget is used, then UNREACHABLE.
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        Recorder rec = new Recorder();
        long t0 = System.nanoTime();
        Result r = new Runner("127.0.0.1", closedPort, 1000, 3000, 800)
                .runWithRetry(key, KEY_NAME, wanted, SELF, 3000, rec);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check(r.outcome == Result.Outcome.UNREACHABLE, "refused port: UNREACHABLE (" + r.summary() + ")");
        check(rec.count("attempt ") == 2, "refused port: 2 attempts within 3 s budget (" + rec.lines + ")");
        check(ms >= 2000 && ms < 3500, "refused port: waited one 2 s backoff (" + ms + " ms)");

        // 6. adbd up but package manager returns nothing: treated as transient, retried, then FAILED.
        try (FakeAdbd adbd = new FakeAdbd()) {
            adbd.userAccepts = true;
            rec = new Recorder();
            r = new Runner("127.0.0.1", adbd.port(), 1000, 3000, 1000)
                    .runWithRetry(key, KEY_NAME, wanted, SELF, 2500, rec);
            check(r.outcome == Result.Outcome.FAILED && rec.count("attempt ") == 2,
                    "empty pm list: retried then FAILED (" + r.summary() + ")");
            check(adbd.disabled.isEmpty() && adbd.commands.stream().noneMatch(c -> c.contains("disable-user")),
                    "empty pm list: nothing disabled");
        }
        staleStreamTraffic(key);
        failClosed(key);
    }

    static Result runAgainst(FakeAdbd adbd, AdbKey key, List<String> wanted, long budgetMs, Recorder rec)
            throws InterruptedException {
        return new Runner("127.0.0.1", adbd.port(), 1000, 3000, 1000)
                .runWithRetry(key, KEY_NAME, wanted, SELF, budgetMs, rec);
    }

    static String shellError(FakeAdbd adbd, AdbKey key, String command) throws Exception {
        try (AdbConnection c = AdbConnection.open("127.0.0.1", adbd.port(), key, KEY_NAME, 1000, 3000, 1000, () -> { })) {
            c.shell(command);
            return "no exception";
        } catch (ProtocolException e) {
            return "ProtocolException: " + e.getMessage();
        }
    }

    /** Late CLSE/WRTE/OKAY for an already finished stream, as seen on the TV after a reboot, must be ignored. */
    static void staleStreamTraffic(AdbKey key) throws Exception {
        List<String> wanted = Arrays.asList("com.bloat.one", "com.bloat.two", "com.bloat.off", STOCK);
        for (FakeAdbd.StaleClose mode : Arrays.asList(FakeAdbd.StaleClose.AFTER_NEXT_OPEN,
                FakeAdbd.StaleClose.DUPLICATE_IMMEDIATELY, FakeAdbd.StaleClose.AFTER_CLIENT_CLSE)) {
            try (FakeAdbd adbd = new FakeAdbd()) {
                adbd.userAccepts = true;
                adbd.staleClose = mode;
                adbd.installed.addAll(set("com.bloat.one", "com.bloat.two", "com.bloat.off", STOCK, PROJECTIVY));
                adbd.disabled.add("com.bloat.off");
                Recorder rec = new Recorder();
                Result r = runAgainst(adbd, key, wanted, 1000, rec);
                check(r.outcome == Result.Outcome.DONE && r.disabledNow == 3 && r.alreadyOff == 1 && r.errors == 0
                                && rec.count("attempt ") == 0,
                        mode + ": first attempt DONE, 3 disabled / 1 already off (" + r.summary() + ", " + rec.lines + ")");
                check(adbd.commands.size() == 6 && adbd.problems.isEmpty(),
                        mode + ": 6 commands, no protocol problems " + adbd.problems);
            }
        }

        // Stale CLSE for stream 1 arrives, then stream 2 is refused: the refusal must still be detected.
        try (FakeAdbd adbd = new FakeAdbd()) {
            adbd.userAccepts = true;
            adbd.installed.add("com.bloat.one");
            adbd.staleClose = FakeAdbd.StaleClose.AFTER_NEXT_OPEN;
            adbd.refuse.add("pm list packages -d");
            String first;
            String err;
            try (AdbConnection c = AdbConnection.open("127.0.0.1", adbd.port(), key, KEY_NAME, 1000, 3000, 1000, () -> { })) {
                first = c.shell("pm list packages");
                try {
                    c.shell("pm list packages -d");
                    err = "no exception";
                } catch (ProtocolException e) {
                    err = e.getMessage();
                }
            }
            check(first.contains("package:com.bloat.one") && err.equals("adbd refused shell:pm list packages -d"),
                    "refused OPEN after stale traffic still detected: " + err);
        }
    }

    /** Situations where the TV's state is unknown must never lead to disabling anything. */
    static void failClosed(AdbKey key) throws Exception {
        List<String> stockOnly = Collections.singletonList(STOCK);

        // Reviewer's case: replacement launcher really disabled, but the "-d" query is refused by adbd.
        try (FakeAdbd adbd = new FakeAdbd()) {
            adbd.userAccepts = true;
            adbd.installed.addAll(set(STOCK, PROJECTIVY));
            adbd.disabled.add(PROJECTIVY);
            adbd.refuse.add("pm list packages -d");
            Recorder rec = new Recorder();
            Result r = runAgainst(adbd, key, stockOnly, 2500, rec);
            check(r.outcome == Result.Outcome.FAILED && !adbd.disabled.contains(STOCK)
                            && adbd.commands.stream().noneMatch(c -> c.contains("disable-user")),
                    "refused OPEN: FAILED, stock launcher untouched (" + r.summary() + ")");
            check(rec.lines.stream().anyMatch(l -> l.contains("adbd refused shell:pm list packages -d")),
                    "refused OPEN is logged: " + rec.lines);
        }

        // pm prints an error instead of a list.
        try (FakeAdbd adbd = new FakeAdbd()) {
            adbd.userAccepts = true;
            adbd.installed.addAll(set(STOCK, PROJECTIVY));
            adbd.disabled.add(PROJECTIVY);
            adbd.outputOverride.put("pm list packages -d",
                    "Error: Could not access the Package Manager.  Is the system running?\r\n");
            Recorder rec = new Recorder();
            Result r = runAgainst(adbd, key, stockOnly, 2500, rec);
            check(r.outcome == Result.Outcome.FAILED && !adbd.disabled.contains(STOCK),
                    "pm error text on -d: FAILED, nothing disabled (" + r.summary() + ")");
            check(rec.lines.stream().anyMatch(l -> l.contains("Could not access the Package Manager")),
                    "pm error text is logged");
        }

        // pm error on the enabled list.
        try (FakeAdbd adbd = new FakeAdbd()) {
            adbd.userAccepts = true;
            adbd.installed.addAll(set(STOCK, PROJECTIVY));
            adbd.outputOverride.put("pm list packages -e", "Exception occurred while executing:\r\nboom\r\n");
            Result r = runAgainst(adbd, key, stockOnly, 1000, new Recorder());
            check(r.outcome == Result.Outcome.FAILED && !adbd.disabled.contains(STOCK),
                    "pm error text on -e: FAILED, nothing disabled (" + r.summary() + ")");
        }

        // Empty enabled list: no positive evidence for the replacement, so the stock launcher stays.
        try (FakeAdbd adbd = new FakeAdbd()) {
            adbd.userAccepts = true;
            adbd.installed.addAll(set(STOCK, PROJECTIVY));
            adbd.outputOverride.put("pm list packages -e", "");
            Result r = runAgainst(adbd, key, stockOnly, 1000, new Recorder());
            check(r.outcome == Result.Outcome.DONE && r.skipped == 1 && !adbd.disabled.contains(STOCK),
                    "empty -e list: stock launcher kept (" + r.summary() + ")");
        }

        try (FakeAdbd adbd = new FakeAdbd()) {
            adbd.userAccepts = true;
            adbd.frame = FakeAdbd.Frame.BAD_MAGIC;
            String err = shellError(adbd, key, "pm list packages");
            check(err.equals("ProtocolException: bad message magic"), "bad magic rejected: " + err);
        }
        try (FakeAdbd adbd = new FakeAdbd()) {
            adbd.userAccepts = true;
            adbd.frame = FakeAdbd.Frame.HUGE_LENGTH;
            String err = shellError(adbd, key, "pm list packages");
            check(err.equals("ProtocolException: payload length 67108864"), "oversized length rejected: " + err);
        }
        try (FakeAdbd adbd = new FakeAdbd()) {
            adbd.userAccepts = true;
            adbd.chunk = 64 * 1024;
            StringBuilder big = new StringBuilder();
            while (big.length() <= AdbConnection.MAX_SHELL_OUTPUT) {
                big.append("package:com.example.filler\n");
            }
            adbd.outputOverride.put("pm list packages", big.toString());
            String err = shellError(adbd, key, "pm list packages");
            check(err.equals("ProtocolException: shell output exceeds 4194304 bytes"), "oversized output rejected: " + err);
        }
        try (FakeAdbd adbd = new FakeAdbd()) {
            adbd.userAccepts = true;
            adbd.refuse.add("pm list packages");
            String err = shellError(adbd, key, "pm list packages");
            check(err.equals("ProtocolException: adbd refused shell:pm list packages"), "refused OPEN throws: " + err);
        }
    }
}
