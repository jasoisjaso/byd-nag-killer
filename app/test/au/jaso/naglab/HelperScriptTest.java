package au.jaso.naglab;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/** Runs HelperScript's commands with the Mac's /bin/sh (POSIX) to check upload + ensure logic. */
public class HelperScriptTest {
    static int fails = 0;
    static File bin;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) fails++;
    }

    static String sh(String cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", cmd).redirectErrorStream(true);
        pb.environment().put("PATH", bin.getAbsolutePath() + ":/usr/bin:/bin:/usr/sbin:/sbin");
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        p.waitFor();
        return new String(out, StandardCharsets.UTF_8);
    }

    static void upload(String script, String path) throws Exception {
        for (String c : HelperScript.upload(script, path)) sh(c);
    }

    static boolean alive(String pid) throws Exception {
        return sh("kill -0 " + pid + " 2>/dev/null && echo yes").trim().equals("yes");
    }

    public static void main(String[] a) throws Exception {
        File dir = Files.createTempDirectory("naglab").toFile();
        bin = new File(dir, "bin");
        bin.mkdirs();
        // macOS has no setsid(1); shim it (on the car toybox provides it)
        File setsid = new File(bin, "setsid");
        Files.write(setsid.toPath(), "#!/bin/sh\nexec \"$@\"\n".getBytes(StandardCharsets.UTF_8));
        setsid.setExecutable(true);
        String d = dir.getAbsolutePath();

        // 1. byte-exact upload of the real watchdog
        String real = new String(Files.readAllBytes(new File("watchdog.sh").toPath()), StandardCharsets.UTF_8);
        upload(real, d + "/up1");
        check(Arrays.equals(Files.readAllBytes(new File(d, "up1").toPath()),
                real.getBytes(StandardCharsets.UTF_8)), "upload watchdog.sh byte-exact ("
                + real.length() + " bytes, " + (HelperScript.upload(real, "x").size() - 1) + " chunks)");

        // 2. nasty content across chunk boundaries
        StringBuilder nasty = new StringBuilder();
        String soup = "'\"$`\\%!*?;&|<>(){}\n\t";
        for (int i = 0; i < 4000; i++) nasty.append(soup.charAt(i % soup.length()));
        upload(nasty.toString(), d + "/up2");
        check(new String(Files.readAllBytes(new File(d, "up2").toPath()), StandardCharsets.UTF_8)
                .equals(nasty.toString()), "upload of quote/meta-char soup byte-exact");
        int max = 0;
        for (String c : HelperScript.upload(nasty.toString(), "/data/local/tmp/naglab_watchdog.sh.new")) {
            max = Math.max(max, ("shell:" + c).getBytes(StandardCharsets.UTF_8).length + 1);
        }
        check(max <= 4096, "largest OPEN payload " + max + " bytes <= adb's 4096 (worst-case content)");

        // 3. ensure(): fake watchdog writes its pid and sleeps
        String fakeV1 = "#!/bin/sh\necho $$ > " + d + "/naglab_watchdog.pid\nsleep 60 # v1\n";
        String fakeV2 = fakeV1.replace("v1", "v2");
        String n = d + "/naglab_watchdog.sh.new";

        upload(fakeV1, n);
        String r1 = sh(HelperScript.ensure(d, true)).trim();
        check(r1.startsWith("NAGLAB_STARTED"), "fresh start -> " + r1);
        String pid1 = r1.replaceAll("\\D+", "");

        upload(fakeV1, n);
        String r2 = sh(HelperScript.ensure(d, false)).trim();
        check(r2.equals("NAGLAB_ALREADY_RUNNING " + pid1), "same script running -> " + r2);
        check(!new File(n).exists(), "temp .new removed when already running");

        upload(fakeV2, n);
        String r3 = sh(HelperScript.ensure(d, false)).trim();
        String pid3 = r3.replaceAll("\\D+", "");
        check(r3.startsWith("NAGLAB_STARTED") && !pid3.equals(pid1), "new version -> restarted (" + r3 + ")");
        Thread.sleep(300);
        check(!alive(pid1), "old watchdog pid " + pid1 + " was stopped");
        check(alive(pid3), "new watchdog pid " + pid3 + " running");

        // owner's stop marker: the automatic path must leave it alone, the button overrides it
        sh("kill " + pid3);
        Files.write(new File(d, "naglab_watchdog.disabled").toPath(), new byte[0]);
        upload(fakeV2, n);
        String r4 = sh(HelperScript.ensure(d, false)).trim();
        check(r4.equals("NAGLAB_DISABLED") && new File(d, "naglab_watchdog.disabled").exists()
                && !new File(n).exists(), "disabled marker, automatic -> NAGLAB_DISABLED, marker kept (" + r4 + ")");
        upload(fakeV2, n);
        String r5 = sh(HelperScript.ensure(d, true)).trim();
        check(r5.startsWith("NAGLAB_STARTED") && !new File(d, "naglab_watchdog.disabled").exists(),
                "disabled marker, button -> marker cleared, started (" + r5 + ")");
        sh("kill " + r5.replaceAll("\\D+", ""));

        // a path with a space must survive the quoting
        File spaced = new File(dir, "with space");
        spaced.mkdirs();
        String fakeSpaced = "#!/bin/sh\necho $$ > '" + spaced.getAbsolutePath() + "/naglab_watchdog.pid'\nsleep 60\n";
        upload(fakeSpaced, spaced.getAbsolutePath() + "/naglab_watchdog.sh.new");
        String r6 = sh(HelperScript.ensure(spaced.getAbsolutePath(), true)).trim();
        check(r6.startsWith("NAGLAB_STARTED"), "dir with a space -> " + r6);
        sh("kill " + r6.replaceAll("\\D+", ""));

        // grants(): same component + app-op as install.sh, and it always reports (no cmd/appops on the Mac)
        String g = HelperScript.grants("au.jaso.naglab");
        check(g.contains("allow_listener 'au.jaso.naglab/au.jaso.naglab.NagListener'"), "grants names the NagListener component");
        check(g.contains("appops set 'au.jaso.naglab' SYSTEM_ALERT_WINDOW allow"), "grants sets the overlay app-op");
        String gr = sh(g).trim();
        check(gr.startsWith("NAGLAB_GRANTS "), "grants reports -> " + gr);

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
