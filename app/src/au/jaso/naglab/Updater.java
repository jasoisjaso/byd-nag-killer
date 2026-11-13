package au.jaso.naglab;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Update from the car's own screen. Only ever runs when the owner taps "Check for update":
 * the app goes online for nothing else.
 *
 * It reads GitHub's release feed for the newest release, downloads its naglab.apk, and installs
 * it through the car's own debugging connection on 127.0.0.1:5555 (the same one the background
 * helper uses), because the car blocks ordinary installs from its screen. So debugging has to
 * be on. The system refuses an APK that isn't signed with the same key as the installed app,
 * and refuses an older version, so a wrong or tampered file can't replace the app.
 *
 * The feed, not GitHub's API: the API allows 60 anonymous requests an hour per network address,
 * shared by everything behind the same router, and at home that was already used up
 * (13 Nov 2026). The feed has no such ration.
 */
final class Updater {
    interface Found {
        /** tag null = already up to date. error non-null = couldn't check. */
        void result(String tag, String url, String name, String error);
    }

    private static final String TAG = DmsClient.TAG;
    private static final String REPO = "https://github.com/jasoisjaso/byd-nag-killer";
    private static final String FEED = REPO + "/releases.atom";
    private static final String TMP = "/data/local/tmp/naglab-update.apk";
    private static final String LOG = "/data/local/tmp/naglab-update.log";

    private Updater() {
    }

    static void check(final Context ctx, final String installed, final Found cb) {
        new Thread(() -> {
            try {
                String tag = Version.newest(new String(get(FEED, 1 << 20), StandardCharsets.UTF_8), installed);
                if (tag == null) {
                    cb.result(null, null, null, null);
                } else {
                    String name = "Nag Killer " + (tag.startsWith("v") ? tag.substring(1) : tag);
                    cb.result(tag, REPO + "/releases/download/" + tag + "/naglab.apk", name, null);
                }
            } catch (java.net.UnknownHostException | java.net.SocketTimeoutException | java.net.ConnectException e) {
                cb.result(null, null, null, "The car isn't online. Connect it to Wi-Fi or a phone hotspot and try again.");
            } catch (Throwable t) {
                Log.w(TAG, "update check failed", t);
                String m = String.valueOf(t.getMessage());
                cb.result(null, null, null, m.contains("HTTP 403") || m.contains("HTTP 429")
                        ? "GitHub is turning checks away for now. Try again in an hour."
                        : "Couldn't check for an update (" + m + ").");
            }
        }, "naglab-update-check").start();
    }

    /** Downloads and installs. The app is replaced and reopened by the car, so on success this
     *  never reports back: the screen simply comes back on the new version. */
    static void install(final Context ctx, final String url, final HelperSetup.Progress p) {
        new Thread(() -> {
            AdbLocal adb = null;
            try {
                p.step("Downloading the update...");
                byte[] apk = get(url, 20 << 20);
                File dir = ctx.getExternalFilesDir(null);
                if (dir == null) throw new Exception("no storage");
                File f = new File(dir, "update.apk");
                try (FileOutputStream o = new FileOutputStream(f)) {
                    o.write(apk);
                }
                // Is it this app, and not older? (The system checks the signature itself.)
                PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(f.getPath(), 0);
                PackageInfo mine = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
                if (pi == null || !ctx.getPackageName().equals(pi.packageName)) {
                    f.delete();
                    p.done(false, "The downloaded file isn't Nag Killer. Nothing was changed.");
                    return;
                }
                if (pi.getLongVersionCode() < mine.getLongVersionCode()) {
                    f.delete();
                    p.done(false, "The download is older than what's installed. Nothing was changed.");
                    return;
                }
                p.step("Connecting to the car's debugging...");
                adb = AdbLocal.connect("127.0.0.1", 5555, HelperSetup.loadKey(ctx), "naglab@sealion7", 3000,
                        true, 90_000, () -> p.step("The car is asking \"Allow USB debugging?\"\n"
                                + "Tick \"Always allow\" and tap Allow."));
                String copied = adb.shell("rm -f " + LOG + "; cp '" + f.getPath() + "' " + TMP + " && wc -c < " + TMP).trim();
                f.delete();   // the car's shell has its own copy now
                if (!copied.equals(String.valueOf(apk.length))) {
                    throw new Exception("copy failed (" + copied + " vs " + apk.length + ")");
                }
                p.step("Installing " + pi.versionName + ". The app will close and come back by itself...");
                // Detached: installing kills this app, and the command has to outlive it.
                String pkg = ctx.getPackageName();
                adb.shell("setsid nohup sh -c 'pm install -r " + TMP + " > " + LOG + " 2>&1; rm -f "
                        + TMP + "; am start -n " + pkg + "/.MainActivity' >/dev/null 2>&1 </dev/null &");
                // A good install kills this app part way, so this thread simply stops. Still
                // here means it is slow or it was refused: read what the car's installer said.
                String log = "";
                for (int i = 0; i < 45 && !log.contains("Failure"); i++) {   // up to a minute and a half
                    Thread.sleep(2_000);
                    log = adb.shell("cat " + LOG + " 2>/dev/null").trim();
                }
                p.done(false, log.contains("Failure") ? "The car didn't accept the update: " + log
                        : "The install is taking longer than it should. Give it a minute. If the app doesn't "
                        + "come back by itself, open it and check the version in About.");
            } catch (java.net.ConnectException e) {
                p.done(false, "Debugging is off. Turn it on the way you did to install, then try again.");
            } catch (AdbLocal.KeyNotTrustedException e) {
                p.done(false, "The car hasn't allowed this app yet. Try again and tap Allow when the car asks.");
            } catch (java.net.UnknownHostException | java.net.SocketTimeoutException e) {
                p.done(false, "The car isn't online. Connect it to Wi-Fi or a phone hotspot and try again.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                Log.w(TAG, "update failed", t);
                p.done(false, String.valueOf(t.getMessage()).contains("HTTP 404")
                        ? "That version's app file isn't on GitHub yet. Try again later. Nothing was changed."
                        : "The update didn't go through (" + t.getMessage() + "). Nothing was changed.");
            } finally {
                if (adb != null) adb.close();
            }
        }, "naglab-update").start();
    }

    private static byte[] get(String url, int max) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "NagKiller");
        c.setRequestProperty("Accept", "*/*");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    o.write(buf, 0, n);
                    if (o.size() > max) throw new Exception("too large");
                }
                return o.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
