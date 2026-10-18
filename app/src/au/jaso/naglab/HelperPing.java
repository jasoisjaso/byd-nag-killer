package au.jaso.naglab;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

/**
 * Heartbeat from the shell watchdog (am broadcast -n au.jaso.naglab/.HelperPing).
 * Stores elapsedRealtime plus a boot id (wall clock minus uptime, constant within one boot),
 * so a ping from before a head-unit reboot never reads as "running" - even if the new uptime
 * happens to pass the old value within the freshness window.
 */
public class HelperPing extends BroadcastReceiver {
    static final String KEY_PING = "helper_ping_elapsed";
    static final String KEY_BOOT = "helper_ping_boot";
    static final long FRESH_MS = 6 * 60_000L;      // watchdog pings every ~2 min
    static final long BOOT_SLOP_MS = 60_000L;      // wall clock may get nudged (GPS/NTP)

    @Override
    public void onReceive(Context c, Intent i) {
        prefs(c).edit()
                .putLong(KEY_PING, SystemClock.elapsedRealtime())
                .putLong(KEY_BOOT, bootId())
                .apply();
        Log.w(DmsClient.TAG, "helper ping");
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(NagService.PREFS, Context.MODE_PRIVATE);
    }

    static long bootId() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    /** True if the watchdog pinged us recently in this boot. */
    static boolean isAlive(Context c) {
        SharedPreferences p = prefs(c);
        long t = p.getLong(KEY_PING, 0);
        long boot = p.getLong(KEY_BOOT, 0);
        long now = SystemClock.elapsedRealtime();
        boolean sameBoot = Math.abs(boot - bootId()) < BOOT_SLOP_MS;
        return sameBoot && t > 0 && t <= now && now - t < FRESH_MS;
    }
}
