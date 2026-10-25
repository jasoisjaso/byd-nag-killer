package au.jaso.naglab;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Starts the shell watchdog from inside the car, no Mac needed: connect to the car's own
 * adbd on 127.0.0.1:5555 (Wireless ADB must be on), upload the bundled watchdog script to
 * /data/local/tmp and start it with setsid. Idempotent: if the same script is already running
 * it is left alone. Interactive (button): first time the car asks "Allow USB debugging?" for
 * this app's key, and an owner's naglab_watchdog.disabled marker is overridden. Automatic
 * (service): never offers the key (no dialog while driving) and honours the marker.
 */
final class HelperSetup {
    interface Progress {
        void step(String text);

        void done(boolean ok, String text);
    }

    private static final String TAG = DmsClient.TAG;
    private static final String DIR = "/data/local/tmp";
    private static final String NEW = DIR + "/naglab_watchdog.sh.new";
    private static volatile boolean busy;

    static boolean isBusy() {
        return busy;
    }

    /** Runs on its own thread. Returns false if a setup is already in progress. */
    static boolean start(final Context ctx, final boolean interactive, final Progress p) {
        synchronized (HelperSetup.class) {
            if (busy) return false;
            busy = true;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    run0(ctx.getApplicationContext(), interactive, p);
                } finally {
                    busy = false;
                }
            }
        }, "naglab-helper").start();
        return true;
    }

    private static void run0(Context ctx, boolean interactive, final Progress p) {
        AdbLocal adb = null;
        try {
            p.step("Connecting to the car's debugging...");
            AdbLocal.Key key = loadKey(ctx);
            adb = AdbLocal.connect("127.0.0.1", 5555, key, "naglab@sealion7", 3000,
                    interactive, interactive ? 90_000 : 30_000, new AdbLocal.AuthPrompt() {
                        @Override
                        public void onWaitingForUser() {
                            p.step("The car is asking \"Allow USB debugging?\"\n"
                                    + "Tick \"Always allow\" and tap Allow.");
                        }
                    });
            p.step("Connected. Installing the background helper...");
            Log.w(TAG, "grants: " + adb.shell(HelperScript.grants(ctx.getPackageName())).trim());

            String script = readAsset(ctx, "naglab_watchdog.sh");
            byte[] bytes = script.getBytes(StandardCharsets.UTF_8);
            for (String cmd : HelperScript.upload(script, NEW)) {
                adb.shell(cmd);
            }
            String size = adb.shell("wc -c < " + NEW).trim();
            if (!size.equals(String.valueOf(bytes.length))) {
                throw new Exception("upload size mismatch (" + size + " vs " + bytes.length + ")");
            }

            String out = adb.shell(HelperScript.ensure(DIR, interactive)).trim();
            Log.w(TAG, "helper setup: " + out);
            if (out.contains("NAGLAB_ALREADY_RUNNING")) {
                p.done(true, "Background helper was already running.");
            } else if (out.contains("NAGLAB_STARTED")) {
                p.done(true, "Background helper started. You can turn debugging off again.");
            } else if (out.contains("NAGLAB_DISABLED")) {
                p.done(false, "The background helper was switched off on purpose. "
                        + "Tap Start to bring it back.");
            } else {
                p.done(false, "The helper didn't start. Tap Start to try again.");
            }
        } catch (java.net.ConnectException e) {
            p.done(false, "Debugging is off. Turn it on the way you did to install, then tap Start again.");
        } catch (AdbLocal.KeyNotTrustedException e) {
            p.done(false, "The car hasn't allowed this app yet. With debugging on, "
                    + "tap Start, then tap Allow when the car asks.");
        } catch (Throwable t) {
            Log.w(TAG, "helper setup failed", t);
            p.done(false, "Couldn't start the helper. Tap Start to try again.");
        } finally {
            if (adb != null) adb.close();
        }
    }

    private static AdbLocal.Key loadKey(Context ctx) throws Exception {
        File priv = new File(ctx.getFilesDir(), "adbkey");
        File pub = new File(ctx.getFilesDir(), "adbkey.x509");
        if (priv.isFile() && pub.isFile()) {
            try {
                return AdbLocal.Key.load(Files.readAllBytes(priv.toPath()),
                        Files.readAllBytes(pub.toPath()));
            } catch (Exception e) {
                Log.w(TAG, "stored adb key unreadable, making a new one", e);
            }
        }
        AdbLocal.Key k = AdbLocal.Key.generate();
        write(priv, k.privateBytes());
        write(pub, k.publicBytes());
        return k;
    }

    private static void write(File f, byte[] b) throws Exception {
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(b);
        }
    }

    private static String readAsset(Context ctx, String name) throws Exception {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return new String(o.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
