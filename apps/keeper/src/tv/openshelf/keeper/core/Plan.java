package tv.openshelf.keeper.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Decides per package what to do, and builds the only shell commands this app ever sends. */
public final class Plan {
    public static final String LIST_DISABLED = "pm list packages -d";
    public static final String LIST_INSTALLED = "pm list packages";
    public static final String LIST_ENABLED = "pm list packages -e";

    static final Pattern PACKAGE_NAME = Pattern.compile("^[a-zA-Z0-9_.]+$");
    // Stock home apps: Android TV 9+, Android TV 5-8, Google TV. Same set as STOCK_LAUNCHERS in tools/openshelf.py.
    static final Set<String> STOCK_LAUNCHERS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "com.google.android.tvlauncher",
            "com.google.android.leanbacklauncher",
            "com.google.android.apps.tv.launcherx")));
    static final List<String> REPLACEMENT_LAUNCHERS = Collections.unmodifiableList(Arrays.asList(
            "com.spocky.projengmenu",
            "tv.openshelf.home"));

    // Same packages as PROTECTED and PROTECTED_PREFIXES in tools/openshelf.py (the keeper adds itself).
    private static final List<String> DENIED_PREFIXES = Collections.unmodifiableList(Arrays.asList(
            "com.sony.dtv.tvinput",
            "com.android.providers."));
    private static final Set<String> DENIED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "com.sony.dtv.tvx",
            "com.android.tv.settings",
            "com.android.vending",
            "com.google.android.gms",
            "com.android.systemui",
            "com.google.android.katniss",
            "com.google.android.apps.mediashell",
            "com.sony.dtv.braviasyncservice",
            "com.sony.dtv.audiooutput",
            "com.sony.dtv.hbbtvlauncher",
            "com.sony.dtv.systemupdate",
            "com.spocky.projengmenu",
            "tv.openshelf.home",
            "io.github.jqssun.airplay",
            "android",
            "com.android.shell",
            "com.google.android.leanback.ime",
            "com.google.android.tvsetup")));

    public enum Decision { DISABLE, ALREADY_OFF, NOT_INSTALLED, DENIED, INVALID_NAME, KEPT_FOR_HOME }

    public static final class Step {
        public final String pkg;
        public final Decision decision;

        Step(String pkg, Decision decision) {
            this.pkg = pkg;
            this.decision = decision;
        }
    }

    private Plan() {
    }

    public static boolean isValidName(String pkg) {
        return PACKAGE_NAME.matcher(pkg).matches();
    }

    public static boolean isDenied(String pkg, String selfPackage) {
        if (DENIED.contains(pkg) || pkg.equals(selfPackage)) {
            return true;
        }
        for (String prefix : DENIED_PREFIXES) {
            if (pkg.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public static List<Step> build(List<String> wanted, Set<String> installed, Set<String> disabled,
                                   Set<String> enabled, String selfPackage) {
        // Without an enabled replacement, disabling the stock launcher leaves Home on a Sony settings screen.
        // Requires positive evidence (listed by "pm list packages -e"), not just absence from the disabled list.
        boolean replacementHome = false;
        for (String launcher : REPLACEMENT_LAUNCHERS) {
            replacementHome |= installed.contains(launcher) && enabled.contains(launcher) && !disabled.contains(launcher);
        }
        List<Step> steps = new ArrayList<>();
        for (String pkg : wanted) {
            Decision d;
            if (!isValidName(pkg)) {
                d = Decision.INVALID_NAME;
            } else if (isDenied(pkg, selfPackage)) {
                d = Decision.DENIED;
            } else if (!installed.contains(pkg)) {
                d = Decision.NOT_INSTALLED;
            } else if (disabled.contains(pkg)) {
                d = Decision.ALREADY_OFF;
            } else if (STOCK_LAUNCHERS.contains(pkg) && !replacementHome) {
                d = Decision.KEPT_FOR_HOME;
            } else {
                d = Decision.DISABLE;
            }
            steps.add(new Step(pkg, d));
        }
        return steps;
    }

    public static String disableCommand(String pkg) {
        if (!isValidName(pkg)) {
            throw new IllegalArgumentException("invalid package name: " + pkg);
        }
        return "pm disable-user --user 0 " + pkg;
    }

    /** Parses "package:<name>" lines; anything else, including names outside the allowed charset, is ignored. */
    public static Set<String> parsePackageList(String output) {
        Set<String> out = new HashSet<>();
        for (String line : output.split("\n")) {
            String t = line.trim();
            if (t.startsWith("package:")) {
                String pkg = t.substring("package:".length());
                if (isValidName(pkg)) {
                    out.add(pkg);
                }
            }
        }
        return out;
    }
}
