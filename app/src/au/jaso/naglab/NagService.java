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
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the nags the owner picked OFF: driver monitoring (DMS, via BYD's SettingsService) and
 * lane assist, speed-limit chime and child detection (via CarAdapterService, see AdasClient).
 * Also hosts the floating quick button. Runs while any keep-off switch is on, the quick button
 * is enabled, or the app screen is open (shouldRun).
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
    /** Switch one nag: extras nag (NAG_*), kill (true = off + keep off, false = back on). */
    static final String ACTION_SET = "au.jaso.naglab.SET";
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
    static volatile long lastSummaryWall;
    /** Lane assist mode (0 off .. 3 both, MIN = unknown); tone / speed-limit warning:
     *  1 on, 0 off, -1 unknown (normalised: the car's read and write encodings differ). */
    static volatile int lane = Integer.MIN_VALUE, tone = -1, slw = -1;
    static final String KEY_LANE_OFF = "lane_off", KEY_TONE_OFF = "tone_off", KEY_CPD_OFF = "cpd_off";
    static volatile int cpd = -1, cpdRaw = Integer.MIN_VALUE;
    /** A switch/button action is still being applied + read back: don't stop until it reports. */
    private static volatile long busyUntil;
    /** DMS write asked for before the DMS binder was ready: -1 none, else the value to set. */
    private int pendingDms = -1;

    private HandlerThread thread;
    private Handler h;
    private DmsClient client;
    private AdasClient adas;
    private long fastUntil;
    private long lastEnforce;
    private QuickButton quick;
    private final Handler mainH = new Handler(Looper.getMainLooper());

    /** Send an action to the service if it should be running. */
    static void send(Context c, String action) {
        if (!shouldRun(c)) return;
        c.startForegroundService(new Intent(c, NagService.class).setAction(action));
    }

    /** One-off "kill the button's set now", even if nothing else needs the service. */
    static void killNow(Context c) {
        c.startForegroundService(new Intent(c, NagService.class).setAction(ACTION_KILL_ALL));
    }

    /** Keep DRIVER MONITORING off (only that; each other nag has its own switch). */
    static boolean isEnabled(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, false);
    }

    /** The app screen is open: run so it can show live car states. */
    static volatile boolean uiOpen;

    /** Run when anything needs us: a keep-off switch, the quick button, or the open app screen. */
    static boolean shouldRun(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, MODE_PRIVATE);
        return uiOpen || SystemClock.elapsedRealtime() < busyUntil || p.getBoolean(KEY_ENABLED, false) || p.getBoolean(KEY_LANE_OFF, false)
                || p.getBoolean(KEY_TONE_OFF, false) || p.getBoolean(KEY_CPD_OFF, false)
                || p.getBoolean(QuickButton.KEY_SHOW, false);
    }

    /** Start or stop the service to match the saved choices. */
    static void sync(Context c) {
        Intent i = new Intent(c, NagService.class);
        if (shouldRun(c)) {
            c.startForegroundService(i.setAction(ACTION_SYNC));
        } else {
            c.stopService(i);
        }
    }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply();
        sync(c);
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
                .setContentText("Keeping the nags you picked off")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(1, n);

        thread = new HandlerThread("naglab");
        thread.start();
        h = new Handler(thread.getLooper());
        client = new DmsClient(this, this);
        adas = new AdasClient(this);
        adas.connect();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(wake, f);

        quick = new QuickButton(this, new QuickButton.Action() {
            @Override
            public void killAll() {
                toggleButtonSet("quick button", true);
            }
        });
        quick.sync();

        running = true;
        fastUntil = SystemClock.elapsedRealtime() + FAST_WINDOW_MS;
        Log.w(TAG, "service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (!ACTION_KILL_ALL.equals(action) && !ACTION_SET.equals(action) && !shouldRun(this)) {
            Log.w(TAG, "started but nothing to do -> stopping");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SYNC.equals(action)) {
            quick.sync();
            h.post(new Runnable() {
                @Override
                public void run() {
                    adasTick();
                }
            });
            return START_STICKY;
        }
        if (ACTION_KILL_ALL.equals(action)) {
            toggleButtonSet("app button", false);
            return START_STICKY;
        }
        if (ACTION_SET.equals(action)) {
            String nag = intent.getStringExtra("nag");
            boolean kill = intent.getBooleanExtra("kill", true);
            if (nag != null) {
                List<String> one = new ArrayList<>();
                one.add(nag);
                apply(one, kill, "switch", false);
            }
            quick.sync();
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

    static boolean pref(Context c, String key) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(key, false);
    }

    static void setPref(Context c, String key, boolean v) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(key, v).apply();
        sync(c);
    }

    // ---- The nags. Each one: can be kept off automatically, and can be in the quick button's set.
    static final String NAG_DMS = "dms", NAG_LANE = "lane", NAG_CHIME = "chime", NAG_CPD = "cpd";
    static final String[] NAGS = {NAG_DMS, NAG_LANE, NAG_CHIME, NAG_CPD};

    static String label(String nag) {
        switch (nag) {
            case NAG_DMS: return "Driver monitoring";
            case NAG_LANE: return "Lane assist";
            case NAG_CHIME: return "Speed limit chime";
            default: return "Child detection";
        }
    }

    /** Is this nag in the quick button's set? Child detection is opt-in (it is a safety alert). */
    static boolean inButton(Context c, String nag) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("btn_" + nag, !NAG_CPD.equals(nag));
    }

    static void setInButton(Context c, String nag, boolean v) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("btn_" + nag, v).apply();
        send(c, ACTION_SYNC);
    }

    /** 1 on, 0 off, -1 unknown, for any nag. */
    static int state(String nag) {
        switch (nag) {
            case NAG_DMS:
                if (dms < 0) return -1;
                return dms == 1 || fatigue == 1 || attention == 1 ? 1 : 0;
            case NAG_LANE:
                return lane == Integer.MIN_VALUE ? -1 : (lane == 0 ? 0 : 1);
            case NAG_CHIME:
                if (tone < 0 && slw < 0) return -1;
                return tone == 1 || slw == 1 ? 1 : 0;
            default:
                return cpd;
        }
    }

    /** Read lane / chime / child detection into the statics. */
    private void adasRead() {
        int oldLane = lane, oldTone = tone, oldSlw = slw, oldCpd = cpd;
        final int U = Integer.MIN_VALUE;
        lane = adas.lane();
        int t = adas.tone();                        // read: 0 = on, 1 = off
        tone = t == U ? -1 : (t == 0 ? 1 : 0);
        int a = adas.slw2d5(), b = adas.slw2b4();   // read: 1 = on (either bus)
        slw = a == U && b == U ? -1 : (a == 1 || b == 1 ? 1 : 0);
        int c = adas.cpd();   // read: 1 Standard, 3 Delay (both on), 2 off
        cpdRaw = c;
        cpd = c == AdasClient.CPD_ON || c == AdasClient.CPD_DELAY ? 1 : c == AdasClient.CPD_OFF ? 0 : -1;
        if (lane != oldLane || tone != oldTone || slw != oldSlw || cpd != oldCpd) {
            Log.w(TAG, "adas lane=" + lane + " tone=" + tone + " slw=" + slw + " cpd=" + cpd
                    + " (2d5=" + a + " 2b4=" + b + " cpdRaw=" + c + ")");
        }
        badge();
    }

    /** Switch off one non-DMS nag if it is on. Returns true if a write was sent. */
    private boolean killAdas(String nag) {
        switch (nag) {
            case NAG_LANE:
                if (state(NAG_LANE) != 1) return false;
                Log.w(TAG, "lane assist " + lane + " -> off: " + adas.setLane(AdasClient.LANE_OFF_WRITE));
                return true;
            case NAG_CHIME:
                boolean sent = false;
                if (tone == 1) {
                    Log.w(TAG, "speed-limit tone -> off: " + adas.setTone(AdasClient.OFF));
                    sent = true;
                }
                if (slw == 1) {
                    Log.w(TAG, "speed-limit warning -> off: " + adas.setSlw(AdasClient.OFF));
                    sent = true;
                }
                return sent;
            case NAG_CPD:
                if (cpd != 1) return false;
                Log.w(TAG, "child detection -> off: " + adas.setCpd(AdasClient.CPD_OFF));
                return true;
            default:
                return false;
        }
    }

    /** Poll: read, then keep off the ones the owner asked to keep off automatically. */
    private void adasTick() {
        if (!adas.isReady()) {
            adas.connect();
            return;
        }
        adasRead();
        boolean wrote = false;
        if (pref(this, KEY_LANE_OFF)) wrote |= killAdas(NAG_LANE);
        if (pref(this, KEY_TONE_OFF)) wrote |= killAdas(NAG_CHIME);
        if (pref(this, KEY_CPD_OFF)) wrote |= killAdas(NAG_CPD);
        if (wrote) {
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    adasRead();
                }
            }, 1500);
        }
    }

    private int lastBadge = -2;   // touched on the handler thread h only

    /** Quick button dot: 0 = its whole set is killed and off (green), 1 = not (orange). */
    private void badge() {
        int b = 0;
        boolean any = false;
        for (String n : NAGS) {
            if (!inButton(this, n)) continue;
            any = true;
            if (!killed(this, n) || state(n) == 1) b = 1;
        }
        if (!any) b = -1;
        if (b == lastBadge) return;
        lastBadge = b;
        final int fb = b;
        mainH.post(new Runnable() {
            @Override
            public void run() {
                quick.setBadge(fb);
            }
        });
    }

    private void tick() {
        adasTick();
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
                if (!shouldRun(NagService.this) || HelperPing.isAlive(NagService.this)) return;
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

    /** Last switch result, for the app screen. */
    static volatile String lastKillSummary;

    static String killKey(String nag) {
        switch (nag) {
            case NAG_DMS: return KEY_ENABLED;
            case NAG_LANE: return KEY_LANE_OFF;
            case NAG_CHIME: return KEY_TONE_OFF;
            default: return KEY_CPD_OFF;
        }
    }

    /** Is this nag killed (switched off and kept off)? = its switch in the app. */
    static boolean killed(Context c, String nag) {
        return pref(c, killKey(nag));
    }

    /** The app switch: true = switch it off now and keep it off; false = switch it back on. */
    static void setKilled(Context c, String nag, boolean kill) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(killKey(nag), kill).apply();
        c.startForegroundService(new Intent(c, NagService.class).setAction(ACTION_SET)
                .putExtra("nag", nag).putExtra("kill", kill));
    }

    /** Overlay / big button: all of the set killed -> bring them back; otherwise kill them all. */
    private void toggleButtonSet(final String why, final boolean card) {
        List<String> set = new ArrayList<>();
        boolean allKilled = true;
        for (String n : NAGS) {
            if (!inButton(this, n)) continue;
            set.add(n);
            if (!killed(this, n) || state(n) == 1) allKilled = false;
        }
        boolean kill = !allKilled;
        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        for (String n : set) e.putBoolean(killKey(n), kill);
        e.apply();
        apply(set, kill, why, card);
    }

    // Lane assist WRITE codes from the car's own mode buttons (t1.l sbg_lane_assist, left to right
    // Warning, Prevent, Both, Off): 2, 5, 4, 1. READ codes: 1 Warning, 2 Prevent, 3 Both, 0 Off.
    private static int laneWriteFor(int readMode) {
        switch (readMode) {
            case 1: return 2;
            case 2: return 5;
            default: return 4;   // Both: what the car came with
        }
    }

    /** Remember how a nag was set before we kill it, so "back on" restores the same mode.
     *  Called on every kill with a known reading: a mode is only overwritten by another real
     *  mode, and the chime's two halves are recorded as they are (so a chime that was already
     *  off when killed is not switched on by "back on"). */
    private void snapshot(String nag) {
        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        if (NAG_LANE.equals(nag) && lane >= 1 && lane <= 3) e.putInt("snap_lane", lane);
        if (NAG_CPD.equals(nag) && (cpdRaw == AdasClient.CPD_ON || cpdRaw == AdasClient.CPD_DELAY)) {
            e.putInt("snap_cpd", cpdRaw);
        }
        if (NAG_CHIME.equals(nag) && tone >= 0 && slw >= 0) {
            e.putBoolean("snap_tone", tone == 1).putBoolean("snap_slw", slw == 1);
        }
        e.apply();
    }

    private void restore(String nag) {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        switch (nag) {
            case NAG_DMS:
                if (client.isReady()) {
                    client.setAll(1);
                } else {
                    pendingDms = 1;   // sent from onConnected()
                    client.connect();
                }
                break;
            case NAG_LANE: {
                int w = laneWriteFor(p.getInt("snap_lane", 3));
                Log.w(TAG, "lane assist -> on (write " + w + "): " + adas.setLane(w));
                break;
            }
            case NAG_CHIME:
                if (p.getBoolean("snap_tone", true)) {
                    Log.w(TAG, "speed-limit tone -> on: " + adas.setTone(AdasClient.ON));
                }
                if (p.getBoolean("snap_slw", false)) {
                    Log.w(TAG, "speed-limit warning -> on: " + adas.setSlw(AdasClient.ON));
                }
                break;
            default:
                int m = p.getInt("snap_cpd", AdasClient.CPD_ON);
                Log.w(TAG, "child detection -> on (" + (m == 3 ? "Delay" : "Standard") + "): " + adas.setCpd(m));
                break;
        }
    }

    /**
     * Switch a set of nags off (kill = true, then kept off by the poll) or back on (false), then
     * read the car back and report what really happened. card = show it beside the overlay.
     */
    private void apply(final List<String> set, final boolean kill, final String why,
                       final boolean card) {
        h.post(new Runnable() {
            @Override
            public void run() {
                busyUntil = SystemClock.elapsedRealtime() + 4_000;   // report() clears it
                Log.w(TAG, (kill ? "kill " : "restore ") + set + " (" + why + ")");
                if (!adas.isReady()) adas.connect();
                else adasRead();
                final Map<String, Integer> before = new HashMap<>();
                for (String n : set) before.put(n, state(n));
                for (String n : set) act(n, kill);
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (client.isReady()) client.query();
                        if (adas.isReady()) {
                            adasRead();
                            // first tap while still binding: the writes could not go out yet
                            boolean again = false;
                            for (String n : set) {
                                if (!NAG_DMS.equals(n) && state(n) == (kill ? 1 : 0)) {
                                    act(n, kill);
                                    again = true;
                                }
                            }
                            if (again) adasRead();
                        }
                    }
                }, 1200);
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        report(set, before, kill, card);
                    }
                }, 2600);
            }
        });
    }

    private void act(String n, boolean kill) {
        if (NAG_DMS.equals(n)) {
            if (!kill) {
                restore(n);
            } else if (client.isReady() && client.setAll(0)) {
                lastEnforce = SystemClock.elapsedRealtime();
                if (state(n) == 1) {
                    fixCount++;
                    lastFixWall = System.currentTimeMillis();
                }
            } else {
                pendingDms = 0;   // sent from onConnected()
                client.connect();
            }
        } else if (adas.isReady()) {
            if (kill) {
                if (state(n) >= 0) snapshot(n);
                killAdas(n);
            } else {
                restore(n);
            }
        }
    }

    private void report(List<String> set, Map<String, Integer> before,
                        boolean kill, boolean card) {
        final List<String[]> lines = new ArrayList<>();
        StringBuilder sum = new StringBuilder();
        boolean ok = true;
        int want = kill ? 0 : 1;
        for (String n : set) {
            int b = before.get(n), a = state(n);
            String what;
            if (a == want) what = b == want ? (kill ? "already off" : "already on")
                    : (kill ? "switched off" : "switched on");
            else if (a < 0) what = "no answer";
            else what = kill ? "still on" : "still off";
            if (a != want) ok = false;
            lines.add(new String[]{label(n), what});
            if (sum.length() > 0) sum.append(" · ");
            sum.append(label(n)).append(" ").append(what);
        }
        if (set.isEmpty()) sum.append("Nothing selected for the quick button");
        lastKillSummary = sum.toString();
        lastSummaryWall = System.currentTimeMillis();   // the screen shows the summary for 2 min
        busyUntil = 0;
        Log.w(TAG, "result: " + lastKillSummary);
        if (card) {
            final boolean fok = ok, fkill = kill;
            mainH.post(new Runnable() {
                @Override
                public void run() {
                    quick.showResult(lines, fok, fkill);
                }
            });
        }
        lastBadge = -2;
        badge();
        if (!shouldRun(this)) stopSelf();   // e.g. last nag switched back on from the app
    }

    @Override
    public void onConnected() {
        connected = true;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (pendingDms >= 0) {
                    Log.w(TAG, "late DMS write " + pendingDms + ": " + client.setAll(pendingDms));
                    pendingDms = -1;
                }
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
        h.post(this::badge);   // binder thread -> h, where lastBadge lives
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
        mainH.removeCallbacksAndMessages(null);
        client.disconnect();
        adas.disconnect();
        thread.quitSafely();
        Log.w(TAG, "service destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
