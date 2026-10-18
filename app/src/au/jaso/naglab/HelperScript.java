package au.jaso.naglab;

import java.util.ArrayList;
import java.util.List;

/** Shell commands HelperSetup sends over ADB. Pure Java so test/run.sh checks them on the Mac. */
final class HelperScript {
    static final int CHUNK = 1500;   // keeps each "shell:" OPEN well under adb's 4096-byte max

    private HelperScript() {
    }

    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** rm + printf chunks appending the script to newPath. */
    static List<String> upload(String script, String newPath) {
        List<String> cmds = new ArrayList<>();
        cmds.add("rm -f " + quote(newPath));
        for (int i = 0; i < script.length(); i += CHUNK) {
            String part = script.substring(i, Math.min(script.length(), i + CHUNK));
            cmds.add("printf '%s' " + quote(part) + " >> " + quote(newPath));
        }
        return cmds;
    }

    /**
     * Idempotent start. Prints NAGLAB_ALREADY_RUNNING <pid> (same script already running),
     * NAGLAB_STARTED <pid>, NAGLAB_DISABLED (owner's stop marker present and !force) or
     * NAGLAB_FAILED.
     *
     * The pid file survives a head-unit reboot, so a live pid is only trusted when
     * /proc/<pid>/cmdline names the watchdog (skipped where /proc is absent: the Mac tests).
     */
    static String ensure(String dir, boolean force) {
        return "D=" + quote(dir) + "; S=\"$D/naglab_watchdog.sh\"; N=\"$S.new\"; "
                + "alive() { [ -n \"$1\" ] && kill -0 \"$1\" 2>/dev/null && "
                + "{ [ ! -d /proc ] || grep -q naglab_watchdog \"/proc/$1/cmdline\" 2>/dev/null; }; }; "
                + (force ? "" : "if [ -f \"$D/naglab_watchdog.disabled\" ]; then "
                        + "rm -f \"$N\"; echo NAGLAB_DISABLED; exit 0; fi; ")
                + "P=$(cat \"$D/naglab_watchdog.pid\" 2>/dev/null); "
                + "if alive \"$P\" && cmp -s \"$N\" \"$S\"; then "
                + "rm -f \"$N\"; echo NAGLAB_ALREADY_RUNNING \"$P\"; exit 0; fi; "
                + "if alive \"$P\"; then kill -- \"-$P\" 2>/dev/null || kill \"$P\" 2>/dev/null; fi; "
                + "rm -f \"$D/naglab_watchdog.pid\" \"$D/naglab_watchdog.disabled\"; "
                + "mv \"$N\" \"$S\" && chmod 755 \"$S\"; "
                + "setsid nohup \"$S\" >/dev/null 2>&1 </dev/null & "
                + "sleep 2; P=$(cat \"$D/naglab_watchdog.pid\" 2>/dev/null); "
                + "if alive \"$P\"; then echo NAGLAB_STARTED \"$P\"; "
                + "else echo NAGLAB_FAILED; fi";
    }
}
