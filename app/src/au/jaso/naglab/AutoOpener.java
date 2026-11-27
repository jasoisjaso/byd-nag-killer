package au.jaso.naglab;

/**
 * Runs Open at start over a shell (the car's debugging in the app, a fake car in the tests).
 * Pure Java. Steps (each one checked against the car's window dump):
 *
 *   one app:  am start (monkey if am refuses).
 *   two apps: dock the first in the left half, the second in the right, confirm the first is
 *             still on the left; one more go after 3 s if it didn't settle.
 *   dock:     not running -> am start --windowingMode 3|4. Already running (its task would just
 *             come back full screen) -> open our see-through placeholder in that half, find the
 *             half's stack, move the app's task into it (am stack move-task).
 */
final class AutoOpener {
    interface Shell {
        String run(String cmd) throws Exception;
    }

    interface Clock {
        void sleep(long ms) throws InterruptedException;
    }

    static final String SELF = "au.jaso.naglab";
    static final String GHOST_LEFT = SELF + "/" + SELF + ".GhostLeftActivity";
    static final String GHOST_RIGHT = SELF + "/" + SELF + ".GhostRightActivity";

    enum Result {
        OPENED("Opened."),
        SIDE_BY_SIDE("Opened side by side."),
        SKIPPED("Skipped: CarPlay or Android Auto was on screen."),
        NOT_SIDE_BY_SIDE("Opened them, but the car didn't put them side by side."),
        FAILED("The car didn't open it.");

        final String words;

        Result(String w) {
            words = w;
        }
    }

    private final Shell sh;
    private final Clock clock;
    final StringBuilder log = new StringBuilder();

    AutoOpener(Shell sh, Clock clock) {
        this.sh = sh;
        this.clock = clock;
    }

    private String run(String cmd) throws Exception {
        String out = sh.run(cmd);
        log.append("$ ").append(cmd).append('\n');
        if (out != null && out.trim().length() > 0) {
            String t = out.trim();
            log.append(t.length() > 1500 ? t.substring(0, 1500) + "\n[...]" : t).append('\n');
        }
        return out;
    }

    private void note(String s) {
        log.append("# ").append(s).append('\n');
    }

    Result open(String first, String second, boolean atStart) throws Exception {
        if (!AutoOpen.validComponent(first) || (second != null && !AutoOpen.validComponent(second))) {
            note("bad app name");
            return Result.FAILED;
        }
        if (atStart && AutoOpen.phoneOnScreen(run(AutoOpen.RESUMED))) {
            note("phone projection on screen, leaving it");
            return Result.SKIPPED;
        }
        if (second == null) return openOne(first);
        for (int go = 1; go <= 2; go++) {
            note("side by side, go " + go);
            if (dock(first, AutoOpen.LEFT, GHOST_LEFT) && dock(second, AutoOpen.RIGHT, GHOST_RIGHT)
                    && waitIn(AutoOpen.LEFT, AutoOpen.pkg(first))) {
                note("both in place");
                return Result.SIDE_BY_SIDE;
            }
            if (go == 1) clock.sleep(3_000);
        }
        return Result.NOT_SIDE_BY_SIDE;
    }

    private Result openOne(String comp) throws Exception {
        if (!AutoOpen.amFailed(run(AutoOpen.start(comp, AutoOpen.FULLSCREEN)))) return Result.OPENED;
        String out = run("monkey -p " + AutoOpen.pkg(comp) + " -c android.intent.category.LAUNCHER 1 2>&1");
        return out != null && out.contains("Events injected") ? Result.OPENED : Result.FAILED;
    }

    /** Put the app in that half. True once the dump shows it there. */
    boolean dock(String comp, int mode, String ghost) throws Exception {
        String pkg = AutoOpen.pkg(comp);
        int task = AutoOpen.taskOf(run(AutoOpen.tasksDump(pkg)), pkg);
        if (task < 0) {
            if (AutoOpen.amFailed(run(AutoOpen.start(comp, mode)))) return false;
            if (waitIn(mode, pkg)) return true;
            task = AutoOpen.taskOf(run(AutoOpen.tasksDump(pkg)), pkg);
            if (task < 0) return false;
            note(pkg + " opened but not in its half, moving it");
        } else {
            note(pkg + " already running (task " + task + ")");
            // the plain start is enough when its task can be re-docked by the system
            if (mode == AutoOpen.RIGHT && !AutoOpen.amFailed(run(AutoOpen.start(comp, mode))) && waitIn(mode, pkg)) {
                return true;
            }
        }
        int stack = AutoOpen.stackFor(run(AutoOpen.stacksDump(null)), mode, null);
        if (stack < 0) stack = ghostStack(mode, ghost);
        if (stack < 0) {
            note("no stack for that half");
            return false;
        }
        int again = AutoOpen.taskOf(run(AutoOpen.tasksDump(pkg)), pkg);
        if (again >= 0) task = again;
        if (AutoOpen.amFailed(run(AutoOpen.moveTask(task, stack)))) return false;
        return waitIn(mode, pkg);
    }

    /** Open the placeholder in that half and return the half's stack, or -1. */
    private int ghostStack(int mode, String ghost) throws Exception {
        if (AutoOpen.amFailed(run(AutoOpen.start(ghost, mode)))) return -1;
        String marker = ghost.substring(ghost.lastIndexOf('.'));   // ".GhostLeftActivity"
        for (int i = 0; i < 6; i++) {
            clock.sleep(250);
            int s = AutoOpen.stackFor(run(AutoOpen.stacksDump(SELF)), mode, marker);
            if (s >= 0) return s;
        }
        return -1;
    }

    /** Up to 3 s for the dump to show the app in that mode. */
    private boolean waitIn(int mode, String pkg) throws Exception {
        for (int i = 0; i < 12; i++) {
            if (AutoOpen.inMode(run(AutoOpen.stacksDump(pkg)), mode, pkg)) return true;
            clock.sleep(250);
        }
        return false;
    }
}
