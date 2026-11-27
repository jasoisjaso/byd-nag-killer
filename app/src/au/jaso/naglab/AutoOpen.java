package au.jaso.naglab;

import java.util.Locale;

/**
 * Open at start: one app, or two side by side (split screen), a few seconds after the car comes
 * on. This class is the pure part (commands and reading the car's window dump), so
 * test/run.sh checks it on the Mac; AutoOpener runs it over the car's debugging.
 *
 * How split screen is made (only the shell can ask for a window mode):
 *   am start --windowingMode 3 = the left half (split-screen-primary), 4 = the right half.
 * An app that is already running keeps its full-screen task, so for that case a see-through
 * placeholder of ours (GhostActivity / GhostSecondActivity) is opened in the half instead, the
 * half's stack is found in `dumpsys activity activities`, and the running app's task is moved
 * into it with `am stack move-task`. Every step is checked against the dump.
 */
final class AutoOpen {
    static final String KEY_FIRST = "open_first", KEY_SECOND = "open_second";
    /** Last result, for the sheet and the car report: "time (why): words". */
    static final String KEY_LAST = "open_last";
    /** What the car said last time, in the app's own files (for the car report). */
    static final String LOG_FILE = "open-at-start.log";
    /** Wait this long after the car reads on, so the car's own start-up screens settle first. */
    static final long DELAY_MS = 8_000;

    static final int FULLSCREEN = 1, LEFT = 3, RIGHT = 4;   // Android's windowing modes

    private AutoOpen() {
    }

    /** "pkg/cls" with nothing a shell could trip on. */
    static boolean validComponent(String c) {
        return c != null && c.matches("[A-Za-z0-9_.]+/[A-Za-z0-9_.]+");   // no $: the shell would expand it
    }

    static String pkg(String component) {
        return component == null ? null : component.substring(0, component.indexOf('/'));
    }

    // ---- Commands

    static String start(String component, int mode) {
        return mode == FULLSCREEN ? "am start --user 0 --display 0 -n " + component + " 2>&1"
                : "am start --user 0 --display 0 --windowingMode " + mode + " -n " + component + " 2>&1";
    }

    /** The stacks with their modes, plus any line naming the app (null = just the stacks). */
    static String stacksDump(String pkg) {
        return "dumpsys activity activities | grep -E 'Display #|Stack #|ActivityStack|mWindowingMode|"
                + "windowingMode|mode=|mActivityType|activityType|type=" + (pkg != null ? "|" + pkg + "/" : "") + "'";
    }

    /** The tasks, plus any line naming the app. */
    static String tasksDump(String pkg) {
        return "dumpsys activity activities | grep -E 'TaskRecord\\{|Task\\{|" + pkg + "/'";
    }

    static String moveTask(int task, int stack) {
        return "am stack move-task " + task + " " + stack + " true 2>&1";
    }

    static final String RESUMED = "dumpsys activity activities | grep -m1 -E 'mResumedActivity|ResumedActivity'";

    /** CarPlay or Android Auto is what's on screen: a phone just connected, leave it be. */
    static boolean phoneOnScreen(String resumedLine) {
        if (resumedLine == null) return false;
        String l = resumedLine.toLowerCase(Locale.US);
        return l.contains("carplay") || l.contains("gearhead") || l.contains("androidauto") || l.contains("carlife");
    }

    // ---- Reading the dump

    /** The first number after a '#' in the line ("Stack #12: ..." -> 12), or -1. */
    static int hashNumber(String s) {
        int i = s.indexOf('#');
        while (i >= 0) {
            int j = i + 1, k = j;
            while (k < s.length() && Character.isDigit(s.charAt(k))) k++;
            if (k > j) {
                try {
                    return Integer.parseInt(s.substring(j, k));
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
            i = s.indexOf('#', j);
        }
        return -1;
    }

    private static boolean nameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '.' || c == '_';
    }

    /** The line names "pkg/" as a whole word (so com.a/ doesn't match org.com.a/). A marker
     *  without the slash (".GhostLeftActivity") just has to be in the line. */
    static boolean names(String line, String pkgSlash) {
        if (!pkgSlash.endsWith("/")) return line.contains(pkgSlash);
        int i = line.indexOf(pkgSlash);
        while (i >= 0) {
            if (i == 0 || !nameChar(line.charAt(i - 1))) return true;
            i = line.indexOf(pkgSlash, i + 1);
        }
        return false;
    }

    /** key=value where value is exactly the number (mode=3 but not mode=30). */
    private static boolean keyIs(String lower, String key, String value) {
        int i = lower.indexOf(key);
        if (i < 0 || !lower.startsWith(value, i + key.length())) return false;
        int after = i + key.length() + value.length();
        return after >= lower.length() || !Character.isDigit(lower.charAt(after));
    }

    /** The task the app is running in, from tasksDump; -1 none, -2 the dump made no sense. */
    static int taskOf(String dump, String pkg) {
        if (dump == null) return -2;
        int task = -1;
        boolean sawTask = false;
        for (String raw : dump.split("\\r?\\n")) {
            String line = raw.trim();
            int n = line.contains("Task{") || line.contains("TaskRecord{") ? hashNumber(line) : -1;
            if (n >= 0) {
                sawTask = true;
                task = n;
            } else if (task >= 0) {
                String l = line.toLowerCase(Locale.US);
                if (l.startsWith("mlastpausedactivity") || l.startsWith("mresumedactivity")
                        || l.startsWith("mpausingactivity") || l.startsWith("mlastresumedactivity")
                        || l.startsWith("topresumedactivity") || l.startsWith("mfocusedapp")
                        || l.startsWith("mlastnonfullscreenbounds")) continue;
                if (names(line, pkg + "/")) return task;
            }
        }
        return sawTask ? -1 : -2;
    }

    /**
     * The id of a standard stack on the car's main screen (Display #0) in the given mode, and,
     * if marker is given, holding a line that names it. -1 none.
     */
    static int stackFor(String dump, int mode, String marker) {
        if (dump == null || (mode != FULLSCREEN && mode != LEFT && mode != RIGHT)) return -1;
        String word = mode == FULLSCREEN ? "fullscreen" : mode == LEFT ? "split-screen-primary" : "split-screen-secondary";
        String num = Integer.toString(mode);
        boolean onMain = false, modeOk = false, standard = false, has = marker == null;
        int stack = -1;
        for (String raw : dump.split("\\r?\\n")) {
            String line = raw.trim();
            String l = line.toLowerCase(Locale.US);
            int d = l.indexOf("display #");
            if (d >= 0) {
                if (onMain && stack >= 0 && modeOk && standard && has) return stack;
                int after = d + "display #0".length();
                onMain = l.startsWith("display #0", d) && (after >= l.length() || !Character.isDigit(l.charAt(after)));
                stack = -1;
                modeOk = standard = false;
                has = marker == null;
                continue;
            }
            if (!onMain) continue;
            int n = line.contains("Stack #") || line.contains("ActivityStack") ? hashNumber(line) : -1;
            if (n >= 0 && n != stack) {
                if (stack >= 0 && modeOk && standard && has) return stack;
                stack = n;
                modeOk = standard = false;
                has = marker == null;
            }
            if (stack < 0) continue;
            modeOk |= l.contains(word) || keyIs(l, "mwindowingmode=", num) || keyIs(l, "windowingmode=", num);
            standard |= l.contains("type=standard") || keyIs(l, "mactivitytype=", "1") || keyIs(l, "activitytype=", "1");
            has |= marker != null && names(line, marker);
        }
        return onMain && stack >= 0 && modeOk && standard && has ? stack : -1;
    }

    /** The app shows in that half (or full screen). */
    static boolean inMode(String stacksDumpOut, int mode, String pkg) {
        return stackFor(stacksDumpOut, mode, pkg + "/") >= 0;
    }

    /** "Error:" / exceptions from am. */
    static boolean amFailed(String out) {
        return out == null || out.contains("Error") || out.contains("Exception");
    }

    // ---- Words

    /** The line under "Open at start" on the main screen. */
    static String summary(String firstName, String secondName) {
        if (firstName == null) return "Off.";
        if (secondName == null) return firstName + " at each start.";
        return firstName + " and " + secondName + " side by side.";
    }
}
