package au.jaso.naglab;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

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
 * It asks GitHub for the newest release, downloads its naglab.apk, and installs it through the
 * car's own debugging connection on 127.0.0.1:5555 (the same one the background helper uses),
 * because the car blocks ordinary installs from its screen. So debugging has to be on. The
 * system refuses an APK that isn't signed with the same key as the installed app, and refuses
 * an older version, so a wrong or tampered file can't replace the app.
 */
final class Updater {
    interface Found {
        /** tag null = already up to date. error non-null = couldn't check. */
        void result(String tag, String url, String notes, String error);
    }

    private static final String TAG = DmsClient.TAG;
    private static final String API = "https://api.github.com/repos/jasoisjaso/byd-nag-killer/releases?per_page=10";
    private static final String TMP = "/data/local/tmp/naglab-update.apk";

    private Updater() {
    }

    /** Pure: which release (if any) to offer. releases = GitHub's list, newest first.
     *  Someone on an experimental build is offered the newest release of any kind; everyone else
     *  only full releases. Returns {tag, apk url, notes} or null when there is nothing newer. */
    static String[] pick(String releasesJson, String installed) throws Exception {
        boolean exp = installed.contains("-");
        JSONArray a = new JSONArray(releasesJson);
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.getJSONObject(i);
            if (r.optBoolean("draft")) continue;
            if (r.optBoolean("prerelease") && !exp) continue;
            String tag = r.optString("tag_name");
            if (tag.equals("v" + installed) || tag.equals(installed)) return null;   // newest is what we have
            JSONArray assets = r.optJSONArray("assets");
            for (int j = 0; assets != null && j < assets.length(); j++) {
                JSONObject as = assets.getJSONObject(j);
                if ("naglab.apk".equals(as.optString("name"))) {
                    return new String[]{tag, as.getString("browser_download_url"), r.optString("name", tag)};
                }
            }
            return null;   // newest release has no app file: nothing to offer
        }
        return null;
    }

    static void check(final Context ctx, final String installed, final Found cb) {
        new Thread(() -> {
            try {
                String[] p = pick(new String(get(API, 1 << 20), StandardCharsets.UTF_8), installed);
                if (p == null) cb.result(null, null, null, null);
                else cb.result(p[0], p[1], p[2], null);
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
                String copied = adb.shell("cp '" + f.getPath() + "' " + TMP + " && wc -c < " + TMP).trim();
                if (!copied.equals(String.valueOf(apk.length))) {
                    throw new Exception("copy failed (" + copied + " vs " + apk.length + ")");
                }
                p.step("Installing " + pi.versionName + ". The app will close and come back by itself...");
                // Detached: installing kills this app, and the command has to outlive it.
                String pkg = ctx.getPackageName();
                adb.shell("setsid nohup sh -c 'pm install -r " + TMP + " > /data/local/tmp/naglab-update.log 2>&1; rm -f "
                        + TMP + "; am start -n " + pkg + "/.MainActivity' >/dev/null 2>&1 </dev/null &");
                Thread.sleep(25_000);   // still alive after this long: the install didn't take
                String log = adb.shell("cat /data/local/tmp/naglab-update.log 2>/dev/null").trim();
                f.delete();
                p.done(false, "The car didn't accept the update" + (log.isEmpty() ? "." : ": " + log));
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
                p.done(false, "The update didn't go through (" + t.getMessage() + "). Nothing was changed.");
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
        c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
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
