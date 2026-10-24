package au.jaso.naglab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

/**
 * Keeps BYD driver monitoring (DMS) OFF while "enabled" is set.
 *
 * DMS switches itself back ON at every car start (seen on the car 2026-10-18: off before
 * ACC off, all three switches back to 1 after ACC on, head unit did not reboot). So we:
 *  - stay bound to BYD's SettingsService and subscribe to DMS callbacks: any switch
 *    reported as 1 -> turn all three off again;
 *  - re-check on SCREEN_ON / USER_PRESENT (car waking up) and on every start (the shell
 *    watchdog starts us again after BYD's ACC-off force-stop and kicks us at power-on);
 *  - poll every 10 s for 3 min after start/wake, then every 60 s, as a safety net
 *    in case the reset happens without a callback.
 *
 * Overdrive wake-up (ACC_ON to its BootReceiver after a short stop at home) lives in
 * watchdog.sh only: this car never broadcasts com.byd.action.ACC_ON, so an in-app
 * receiver for it would never fire.
 */
public class NagService extends Service implements DmsClient.Listener {
    static final String TAG = DmsClient.TAG;
    static final String PREFS = "naglab";
    static final String KEY_ENABLED = "dms_off";
    /** Kill every nag right now (quick button, app button). */
    static final String ACTION_KILL_ALL = "au.jaso.naglab.KILL_ALL";
    /** Re-read the quick-button choice and show/hide the overlay. */
    static final String ACTION_SYNC = "au.jaso.naglab.SYNC";
    private static final String CHANNEL = "naglab";

    private static final long FAST_POLL_MS = 10_000;
    private static final long SLOW_POLL_MS = 60_000;
    private static final long FAST_WINDOW_MS = 180_000;
    private static final long MIN_ENFORCE_GAP_MS = 4_000;

    // Read by MainActivity for the status screen (same process).
    static volatile boolean running;
    static volatile boolean connected;
    static volatile int dms = -1, fatigue = -1, attention = -1;
    static volatile long lastFixWall;
    static volatile int fixCount;
    static volatile long lastKillAllWall;

    private HandlerThread thread;
    private Handler h;
    private DmsClient client;
    private long fastUntil;
    private long lastEnforce;
    private QuickButton quick;

    /** Send an action to the running service (starts it if Nag Killer is on). */
    static void send(Context c, String action) {
        if (!isEnabled(c)) return;
        c.startForegroundService(new Intent(c, NagService.class).setAction(action));
    }

    static boolean isEnabled(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply();
        Intent i = new Intent(c, NagService.class);
        if (on) {
            c.startForegroundService(i);
        } else {
            c.stopService(i);
        }
    }

    private final BroadcastReceiver wake = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Log.w(TAG, "wake event " + i.getAction());
            checkHelperLater(20_000);
            h.post(new Runnable() {
                @Override
                public void run() {
                    fastUntil = SystemClock.elapsedRealtime() + FAST_WINDOW_MS;
                    tick();
                }
            });
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            tick();
            long next = SystemClock.elapsedRealtime() < fastUntil ? FAST_POLL_MS : SLOW_POLL_MS;
            h.postDelayed(this, next);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Nag Killer",
                NotificationManager.IMPORTANCE_MIN));
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Nag Killer")
                .setContentText("Keeping driver monitoring off")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(1, n);

        thread = new HandlerThread("naglab");
        thread.start();
        h = new Handler(thread.getLooper());
        client = new DmsClient(this, this);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(wake, f);

        quick = new QuickButton(this, new QuickButton.Action() {
            @Override
            public void killAll() {
                killAllNow("quick button");
            }
        });
        quick.sync();

        running = true;
        fastUntil = SystemClock.elapsedRealtime() + FAST_WINDOW_MS;
        Log.w(TAG, "service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isEnabled(this)) {
            Log.w(TAG, "started but disabled -> stopping");
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_SYNC.equals(action)) {
            quick.sync();
            return START_STICKY;
        }
        if (ACTION_KILL_ALL.equals(action)) {
            killAllNow("app button");
            return START_STICKY;
        }
        quick.sync();
        // Every start (watchdog revive after ACC off, watchdog's power-on kick, app button)
        // reopens the fast-poll window: DMS resets to ON right after power level 0->2.
        h.removeCallbacks(poll);
        h.post(new Runnable() {
            @Override
            public void run() {
                fastUntil = SystemClock.elapsedRealtime() + FAST_WINDOW_MS;
                poll.run();
            }
        });
        checkHelperLater(20_000);   // a live watchdog pings us within ~2 s of starting us
        return START_STICKY;
    }

    private void tick() {
        if (!client.isReady()) {
            client.connect();
            return;
        }
        client.query();
    }

    private static final long HELPER_RETRY_MS = 10 * 60_000L;
    private long lastHelperTry;

    /** No heartbeat from the shell watchdog (e.g. after a screen reboot): try to start it via
     *  Wireless ADB on 127.0.0.1. Quietly fails if ADB is off; the app screen shows the state. */
    private void checkHelperLater(long delayMs) {
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isEnabled(NagService.this) || HelperPing.isAlive(NagService.this)) return;
                long now = SystemClock.elapsedRealtime();
                if (lastHelperTry != 0 && now - lastHelperTry < HELPER_RETRY_MS) return;
                lastHelperTry = now;
                Log.w(TAG, "no helper heartbeat -> trying to start it via local ADB");
                HelperSetup.start(NagService.this, false, new HelperSetup.Progress() {
                    @Override
                    public void step(String text) {
                    }

                    @Override
                    public void done(boolean ok, String text) {
                        Log.w(TAG, "helper auto-start: " + text);
                    }
                });
            }
        }, delayMs);
    }

    private void enforce(final String why) {
        h.post(new Runnable() {
            @Override
            public void run() {
                if (!isEnabled(NagService.this) || !client.isReady()) return;
                long now = SystemClock.elapsedRealtime();
                if (now - lastEnforce < MIN_ENFORCE_GAP_MS) return;
                lastEnforce = now;
                Log.w(TAG, "turning driver monitoring OFF (" + why + ")");
                if (client.setAll(0)) {
                    fixCount++;
                    lastFixWall = System.currentTimeMillis();
                }
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        client.query();
                    }
                }, 2500);
            }
        });
    }

    /** One tap, every nag off now: no rate limit, no waiting for a callback. */
    private void killAllNow(final String why) {
        lastKillAllWall = System.currentTimeMillis();
        h.post(new Runnable() {
            @Override
            public void run() {
                Log.w(TAG, "kill all nags (" + why + ")");
                if (!client.isReady()) {
                    client.connect();   // DMS gets enforced by the callbacks once connected
                } else if (client.setAll(0)) {
                    lastEnforce = SystemClock.elapsedRealtime();
                    fixCount++;
                    lastFixWall = System.currentTimeMillis();
                }
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (client.isReady()) client.query();
                    }
                }, 1500);
            }
        });
    }

    @Override
    public void onConnected() {
        connected = true;
        h.post(new Runnable() {
            @Override
            public void run() {
                client.query();
            }
        });
    }

    @Override
    public void onDisconnected() {
        connected = false;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                client.connect();
            }
        }, 2000);
    }

    @Override
    public void onSwitch(String which, int value) {
        if ("dms".equals(which)) dms = value;
        else if ("fatigue".equals(which)) fatigue = value;
        else if ("attention".equals(which)) attention = value;
        Log.w(TAG, "state " + which + "=" + value);
        if (value == 1) enforce(which + "=1");
    }

    @Override
    public void onDestroy() {
        running = false;
        connected = false;
        quick.hide();
        try {
            unregisterReceiver(wake);
        } catch (Throwable ignored) {
        }
        h.removeCallbacksAndMessages(null);
        client.disconnect();
        thread.quitSafely();
        Log.w(TAG, "service destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
