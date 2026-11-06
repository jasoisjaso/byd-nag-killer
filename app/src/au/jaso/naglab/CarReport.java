package au.jaso.naglab;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Car report: everything we need to sort out "it doesn't work on my car" from a GitHub issue.
 * Firmware and BYD app versions, which command numbers this car uses (and whether they differ
 * from the AU car the app was built on), the raw readings of every setting the app touches,
 * and the app's own state. Read-only: it never writes to the car.
 *
 * Saved to /sdcard/Android/data/au.jaso.naglab/files/car-report.txt (adb pull) and shown on
 * screen. No VIN, serial numbers, MAC addresses or phone names go in it.
 */
final class CarReport {
    static final String FILE = "car-report.txt";

    /** Readings in the report: the getters the app uses plus two BYD diagnostics. */
    private static final String[] READS = {
            "getLaneAssistType", "getTsrLitSpdChgSwitchState",
            "getIntelligentSpeedLimitAlert2D5State", "getIntelligentSpeedLimitAlert2B4State",
            "getCpdStatus", "getCpdDelayStatus", "getFrontTrafficBrakeAlertState",
            "getEmergencyLaneAssistance32FState", "getEmergencyLaneAssistance2F0State",
            "getAiEmergencyBrakeState", "getDmsSwitchStatus", "getADASOnlineState",
    };

    private static final Pattern PROP_KEEP = Pattern.compile(
            "^(ro\\.build\\.|ro\\.product\\.|ro\\.board\\.|ro\\.hardware|ro\\.vendor\\.build\\.)"
                    + "|version|region|country|market|area|sw|hw|carconfig|car_type|cartype");
    private static final Pattern PROP_DROP = Pattern.compile(
            "serial|vin|imei|iccid|imsi|mac|addr|bt\\.name|ssid|phone|token|key|password|user");

    private CarReport() {
    }

    /** Builds the report (blocking, up to ~10 s), saves it, returns {summary, path or error}. */
    static String[] make(Context c) {
        StringBuilder top = new StringBuilder(), rest = new StringBuilder();
        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date());

        top.append("Nag Killer car report\n");
        top.append("app      ").append(version(c, c.getPackageName())).append('\n');
        top.append("time     ").append(now).append('\n');
        top.append("model    ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" / ").append(Build.PRODUCT).append(" / ").append(Build.DEVICE).append('\n');
        top.append("build    ").append(Build.DISPLAY).append('\n');
        top.append("android  ").append(Build.VERSION.RELEASE).append(" (API ")
                .append(Build.VERSION.SDK_INT).append("), patch ")
                .append(Build.VERSION.SECURITY_PATCH).append('\n');
        top.append("adapter  ").append(version(c, "com.ts.appservice.caradapter")).append('\n');
        File jar = new File(Codes.JAR);
        top.append("library  ").append(Codes.JAR).append(jar.canRead() ? "  " + jar.length() + " bytes, "
                + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(jar.lastModified()))
                : "  NOT READABLE").append('\n');

        Codes codes = Codes.get();
        top.append("\nCommand numbers: ").append("car".equals(codes.source)
                ? "read from this car (" + codes.assist.size() + " commands)"
                : "BUILT-IN AU numbers, couldn't read the car's list: " + codes.error).append('\n');
        int moved = 0;
        for (String[] p : AdasClient.BUILT_IN) {
            int here = codes.code(p[0]), au = codes.builtIn(p[0]);
            String mark = here < 0 ? "  MISSING on this car" : here != au ? "  moved (AU car: " + au + ")" : "";
            if (here != au) moved++;
            top.append(String.format(Locale.US, "  %-40s %4s%s%n", p[0], here < 0 ? "-" : String.valueOf(here), mark));
        }
        top.append(moved == 0 ? "  same as the AU car\n" : "  " + moved + " differ from the AU car\n");

        top.append("\nReadings (raw values from the car, nothing is changed):\n");
        AdasClient adas = new AdasClient(c);
        adas.connect();
        long until = SystemClock.uptimeMillis() + 8000;
        while (!adas.isReady() && SystemClock.uptimeMillis() < until) SystemClock.sleep(100);
        if (!adas.isReady()) {
            top.append("  can't reach the car's ADAS service (CarAdapterService)\n");
        } else {
            for (String m : READS) {
                int v = adas.read(m);
                top.append(String.format(Locale.US, "  %-40s %s%n", m,
                        v == Integer.MIN_VALUE ? "no answer" : String.valueOf(v)));
            }
        }
        adas.disconnect();
        top.append("  DMS (driver monitoring) master/fatigue/attention  ")
                .append(NagService.dms).append('/').append(NagService.fatigue).append('/')
                .append(NagService.attention).append(NagService.connected ? "" : "  (not connected)")
                .append('\n');

        top.append("\nApp state:\n");
        StringBuilder kept = new StringBuilder();
        for (String n : NagService.NAGS) if (NagService.killed(c, n)) kept.append(n).append(' ');
        top.append("  kept off       ").append(kept.length() == 0 ? "nothing" : kept.toString().trim()).append('\n');
        top.append("  service        ").append(NagService.running ? "running" : "not running").append('\n');
        top.append("  helper         ").append(HelperPing.isAlive(c) ? "alive" : "not seen").append('\n');
        String listeners = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        top.append("  revive grant   ").append(listeners != null && listeners.contains(c.getPackageName())
                ? "yes" : "no").append('\n');

        rest.append("\nBYD / TS app versions:\n");
        List<String> pk = new ArrayList<>();
        for (PackageInfo p : c.getPackageManager().getInstalledPackages(0)) {
            if (p.packageName.startsWith("com.byd.") || p.packageName.startsWith("com.ts.")) {
                pk.add(String.format(Locale.US, "  %-45s %s (%d)", p.packageName, p.versionName,
                        p.getLongVersionCode()));
            }
        }
        Collections.sort(pk);
        for (String s : pk) rest.append(s).append('\n');

        rest.append("\nSystem properties (filtered, no serials or addresses):\n");
        rest.append(props());

        rest.append("\nAll assist commands on this car:\n");
        for (Map.Entry<String, Integer> e : codes.assist.entrySet()) {
            rest.append("  ").append(e.getValue()).append(' ').append(e.getKey()).append('\n');
        }

        String summary = top.toString(), full = summary + rest;
        String where;
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) throw new IllegalStateException("no app storage");
            File f = new File(dir, FILE);
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(full.getBytes(StandardCharsets.UTF_8));
            }
            where = f.getAbsolutePath();
        } catch (Exception e) {
            where = "couldn't save: " + e;
        }
        for (String line : summary.split("\n")) Log.w(DmsClient.TAG, "report: " + line);
        return new String[]{summary, where};
    }

    private static String version(Context c, String pkg) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(pkg, 0);
            return p.versionName + " (" + p.getLongVersionCode() + ")";
        } catch (Exception e) {
            return "not installed";
        }
    }

    private static String props() {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = new ProcessBuilder("getprop").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int end = line.indexOf(']');
                    if (!line.startsWith("[") || end < 0) continue;
                    String key = line.substring(1, end).toLowerCase(Locale.US);
                    if (PROP_KEEP.matcher(key).find() && !PROP_DROP.matcher(key).find()) {
                        sb.append("  ").append(line).append('\n');
                    }
                }
            }
            p.waitFor();
        } catch (Exception e) {
            sb.append("  getprop failed: ").append(e).append('\n');
        }
        return sb.toString();
    }
}
