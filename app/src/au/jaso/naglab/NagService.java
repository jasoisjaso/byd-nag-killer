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
 * the rest (lane assist, speed-limit chime, child detection, emergency lane keeping, cross
 * traffic brake, emergency braking) via CarAdapterService, see AdasClient.
 * Also hosts the floating quick button, sets the seat fans or heat at each car start and keeps the
 * trip log. Runs while any of those is switched on or the app screen is open (shouldRun).
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
    /** Switch the button set off right now (quick button, app button). Never switches anything on. */
    static final String ACTION_KILL_ALL = "au.jaso.naglab.KILL_ALL";
    /** Re-read the quick-button choice and show/hide the overlay. */
    static final String ACTION_SYNC = "au.jaso.naglab.SYNC";
    /** Switch one nag: extras nag (NAG_*), kill (true = off + keep off, false = back on). */
    static final String ACTION_SET = "au.jaso.naglab.SET";
    /** A seat's choice (fan or heat) changed in the app: apply the saved choice now (car on). */
    static final String ACTION_SEATS = "au.jaso.naglab.SEATS";
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
    static final String KEY_LANE_OFF = "lane_off", KEY_TONE_OFF = "tone_off", KEY_CPD_OFF = "cpd_off",
            KEY_FCTB_OFF = "fctb_off", KEY_ELKA_OFF = "elka_off", KEY_AEB_OFF = "aeb_off";
    static volatile int cpd = -1, cpdRaw = Integer.MIN_VALUE;
    /** Front cross traffic brake / emergency lane keeping: 1 on, 0 off, -1 unknown or not fitted. */
    static volatile int fctb = -1, elka = -1, aeb = -1;
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

    /** One-off "switch the kept-off nags off now", even if nothing else needs the service. */
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
        return uiOpen || SystemClock.elapsedRealtime() < busyUntil || !kept(c).isEmpty()
                || p.getBoolean(QuickButton.KEY_SHOW, false) || seatsPicked(c) || tripsOn(c) || openOn(c);
    }

    // ---- Trips: copies of the car's own trip counters (see Trips). No extra polling: one
    // reading per normal poll (10 s for 3 min after a wake, then every 60 s), plus every 2 s
    // only while the Trips tab is on screen.
    static final String KEY_TRIPS = "trips";
    static final String TRIPS_FILE = "trips.tsv", TRIP_NOW_FILE = "trip-current.tsv";
    private static final long LIVE_MS = 2_000, SAVE_GAP_MS = 30_000;
    /** Latest reading and the trip in progress, for the screen. */
    static volatile Trips.Reading live;
    /** A copy of the trip in progress, for the screens (the tracker's own one changes on the
     *  service thread). Swapped whole, never changed in place. */
    static volatile Trips.Trip tripNow;
    /** Bumped when a trip is finished and written (the screen reloads the list). */
    static volatile int tripsVersion;
    /** A tab that shows live readings (Trips, Charging or Health) is on screen: read every LIVE_MS. */
    static volatile boolean tripsVisible;
    private final Trips.Tracker tracker = new Trips.Tracker();
    private boolean trackerLoaded;
    private long lastTripSave;
    /** For the owner's battery report only. Never logged. */
    static volatile String vin;
    private int vinTries;
    static final String KEY_DC_SEEN = "dc_seen_wall";
    /** Everyday health readings, written by 0.9.0-exp1 to exp6. Health is the battery test only
     *  now; the old file is deleted at start. */
    static final String OLD_TICKS_FILE = "battery-readings.tsv";

    // ---- The battery test the owner started (see Trips.TestRun). One object for the service and
    // the screen, kept in a small file so it survives the car switching off for the night.
    static final String TEST_FILE = "battery-test.tsv";
    static volatile int testVersion;
    private static Trips.TestRun testRun;

    static synchronized Trips.TestRun testRun(Context c) {
        if (testRun == null) testRun = Trips.TestRun.load(new java.io.File(c.getFilesDir(), TEST_FILE));
        return testRun;
    }

    static void saveTest(Context c) {
        try {
            testRun(c).save(new java.io.File(c.getFilesDir(), TEST_FILE));
        } catch (java.io.IOException e) {
            Log.w(TAG, "battery test save failed: " + e);
        }
        testVersion++;
    }
    /** Charging as the car reports it right now (only known while the car is on). */
    static volatile boolean plugged;
    static volatile float chargeKw = Float.NaN;
    static volatile int chargeRestMin = -1;
    static volatile boolean chargeDone;
    private int lastPlug;
    /** The last look was over what AC can do / over what the standard battery can take. */
    private boolean wasOverAc, wasBigKw;
    /** This plug-in has already been noted as a fast charge. */
    private boolean dcStamped;

    static boolean tripsOn(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_TRIPS, true);
    }

    /** On the handler thread. */
    private void tripTick() {
        if (!tripsOn(this) || !adas.isReady()) return;
        if (!trackerLoaded) {
            trackerLoaded = true;
            tracker.current = Trips.loadCurrent(new java.io.File(getFilesDir(), TRIP_NOW_FILE));
            tripNow = tracker.current == null ? null : tracker.current.copy();
        }
        Trips.Reading r = adas.readTrip();
        live = r;
        if (vin == null && vinTries < 5) {   // doesn't change: read it once
            vinTries++;
            vin = adas.vin();
        }
        float[] ch = adas.readCharge();
        int plug = (int) ch[0];
        plugged = plug >= 1 && plug <= 3;
        // BYD's own constants: 2 = DC, 3 = both. Only 1 (AC) has been seen on a car so far.
        boolean dcPlug = plug == 2 || plug == 3;
        chargeKw = plugged ? ch[1] : Float.NaN;
        // The power figure is a filler when nothing is charging (359.4 unplugged), so a kW
        // reading only counts while the car says it is charging (state 1, seen on the car on
        // AC) and when two looks in a row agree.
        boolean overAc = plugged && ch[3] == 1 && !Float.isNaN(chargeKw) && chargeKw > Trips.AC_MAX_KW + 0.5f;
        boolean fast = dcPlug || (overAc && wasOverAc);
        wasOverAc = overAc;
        // Only the 91.3 kWh car charges faster than 150 kW, so such a charge settles which
        // battery it is. Remembered for good, so it has to be on a DC plug as well.
        boolean bigKw = dcPlug && ch[3] == 1 && !Float.isNaN(chargeKw) && chargeKw > Trips.BIG_PACK_KW && chargeKw < 300f;
        if (bigKw && wasBigKw && !getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(Trips.KEY_SEEN_BIG, false)) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(Trips.KEY_SEEN_BIG, true).apply();
            Log.w(TAG, "charging at " + chargeKw + " kW: this is the 91.3 kWh battery");
        }
        wasBigKw = bigKw;
        chargeRestMin = plugged ? (int) ch[2] : -1;
        chargeDone = plugged && ch[3] == 2;
        if (plugged && plug != lastPlug) Log.w(TAG, "charge plug " + plug + " state " + (int) ch[3] + " kW " + chargeKw);
        lastPlug = plugged ? plug : 0;
        if (!plugged) dcStamped = false;
        if (fast && !dcStamped) {
            // Seen with our own eyes: the DC plug, or more than the car can take on AC. Once
            // per plug-in is enough to mark the charge.
            dcStamped = true;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong(KEY_DC_SEEN, r.wall).apply();
            Log.w(TAG, "fast charge seen: plug " + plug + ", " + chargeKw + " kW");
        }
        Trips.TestRun run = testRun(this);
        if (run.running() && run.update(r, dcPlug)) {
            saveTest(this);
            Log.w(TAG, "battery test: state " + run.state + " at " + r.soc + "%"
                    + (run.state == Trips.TestRun.FAILED ? " (" + run.reason + ")" : ""));
        }
        Trips.Trip done = tracker.update(r);
        tripNow = tracker.current == null ? null : tracker.current.copy();
        long now = SystemClock.elapsedRealtime();
        try {
            java.io.File all = new java.io.File(getFilesDir(), TRIPS_FILE);
            if (done != null) {
                java.util.List<Trips.Trip> stored = Trips.load(all);
                if (Trips.ghost(stored.isEmpty() ? null : stored.get(stored.size() - 1), done)) {
                    Log.w(TAG, "not a trip (odometer didn't move): " + done.km + " km, " + done.min + " min, odo "
                            + done.odoStart + " -> " + done.odoEnd);
                    done = null;
                }
            }
            if (done != null) {
                Trips.append(all, done);
                tripsVersion++;
                Log.w(TAG, "trip done: " + done.km + " km, " + done.min + " min, " + done.kwh + " kWh");
                // A copy you can pull off the car (same folder as the car report).
                java.io.File ext = getExternalFilesDir(null);
                if (ext != null) {
                    Trips.exportCsv(Trips.load(all), new java.io.File(ext, "trips.csv"),
                            java.util.Calendar.getInstance());
                }
            }
            if (tracker.current != null && (done != null || now - lastTripSave >= SAVE_GAP_MS)) {
                Trips.saveCurrent(new java.io.File(getFilesDir(), TRIP_NOW_FILE), tracker.current);
                lastTripSave = now;
            }
        } catch (java.io.IOException e) {
            Log.w(TAG, "trip save failed: " + e);
        }
    }

    private final Runnable liveLoop = new Runnable() {
        @Override
        public void run() {
            if (!tripsVisible) return;
            tripTick();
            h.postDelayed(this, LIVE_MS);
        }
    };

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
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                // The screen going dark is not always the car switching off (the screen-off
                // button mid-drive), and the seats are set once per start. So only arm them
                // for the next start when the car really reads off. A real power-off also kills
                // the app (a fresh process is armed), and every poll checks the same thing.
                h.post(() -> {
                    if (!adas.isReady()) return;
                    int power = adas.powerRaw();
                    if (power != Integer.MIN_VALUE && power != AdasClient.POWER_ON_RAW) seatDone = false;
                });
                return;
            }
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
        new java.io.File(getFilesDir(), OLD_TICKS_FILE).delete();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Nag Killer",
                NotificationManager.IMPORTANCE_MIN));
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Nag Killer")
                .setContentText("Running in the background")
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
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(wake, f);

        quick = new QuickButton(this, new QuickButton.Action() {
            @Override
            public void killAll() {
                killButtonSet("quick button", true);
            }
        });
        quick.sync();

        running = true;
        self = this;
        fastUntil = SystemClock.elapsedRealtime() + FAST_WINDOW_MS;
        Log.w(TAG, "service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (!ACTION_KILL_ALL.equals(action) && !ACTION_SET.equals(action) && !ACTION_SEATS.equals(action)
                && !shouldRun(this)) {
            Log.w(TAG, "started but nothing to do -> stopping");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SYNC.equals(action)) {
            quick.sync();
            h.removeCallbacks(liveLoop);
            if (tripsVisible) h.post(liveLoop);
            h.post(new Runnable() {
                @Override
                public void run() {
                    adasTick();
                }
            });
            return START_STICKY;
        }
        if (ACTION_KILL_ALL.equals(action)) {
            killButtonSet("app button", false);
            return START_STICKY;
        }
        if (ACTION_SEATS.equals(action)) {
            // Only the saved choice is applied (the service is exported).
            final boolean hasDriver = intent.hasExtra("driver");
            final boolean driver = intent.getBooleanExtra("driver", true);
            if (!hasDriver) {
                // Auto temperatures changed: wait until the taps on - / + stop (2 s), then
                // switch the Auto seats once, instead of a seat command for every tap.
                h.removeCallbacks(autoReapply);
                h.postDelayed(autoReapply, 2_000);
                return START_STICKY;
            }
            h.post(new Runnable() {
                @Override
                public void run() {
                    // A double tap on the car's screen arrives as two picks ~100 ms apart (seen on
                    // the car 13 Nov 2026): the same seat and choice again within a second is one.
                    int pick = seatPick(NagService.this, driver);
                    long now = SystemClock.elapsedRealtime();
                    int i = driver ? 0 : 1;
                    if (lastSeatPick[i] == pick && now - lastSeatAt[i] < 1_000) return;
                    lastSeatPick[i] = pick;
                    lastSeatAt[i] = now;
                    applySeat(driver, "app");
                }
            });
            return START_STICKY;
        }
        if (ACTION_SET.equals(action)) {
            String nag = intent.getStringExtra("nag");
            boolean kill = intent.getBooleanExtra("kill", true);
            // The service is exported (for the watchdog), so only carry out what the app's own
            // switch already saved: another app can't skip the safety confirm or pick a nag.
            if (!isNag(nag) || killed(this, nag) != kill) {
                Log.w(TAG, "ignored SET " + nag + " kill=" + kill + " (not the saved choice)");
            } else {
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

    // ---- The nags. Each one can be kept off; the buttons re-apply that set.
    static final String NAG_DMS = "dms", NAG_LANE = "lane", NAG_CHIME = "chime", NAG_CPD = "cpd",
            NAG_FCTB = "fctb", NAG_ELKA = "elka", NAG_AEB = "aeb";
    static final String[] NAGS = {NAG_DMS, NAG_LANE, NAG_ELKA, NAG_FCTB, NAG_AEB, NAG_CHIME, NAG_CPD};

    static boolean isNag(String nag) {
        for (String n : NAGS) if (n.equals(nag)) return true;
        return false;
    }

    /** Safety systems: off the buttons by default and Keep off asks first. */
    static boolean isSafety(String nag) {
        return NAG_CPD.equals(nag) || NAG_FCTB.equals(nag) || NAG_ELKA.equals(nag)
                || NAG_AEB.equals(nag);
    }

    static String label(String nag) {
        switch (nag) {
            case NAG_DMS: return "Driver monitoring";
            case NAG_LANE: return "Lane assist";
            case NAG_CHIME: return "Speed limit chime";
            case NAG_FCTB: return "Cross traffic brake";
            case NAG_ELKA: return "Emergency lane keeping";
            case NAG_AEB: return "Emergency braking";
            default: return "Child detection";
        }
    }

    /** The nags with Keep off on, in screen order. */
    static List<String> kept(Context c) {
        List<String> set = new ArrayList<>();
        for (String n : NAGS) if (killed(c, n)) set.add(n);
        return set;
    }

    /** Is this nag in the button set (what the quick button and the big button switch off)?
     *  Its own choice, separate from Keep off. Default: everything but the safety systems
     *  (same keys as 0.8, so an owner's earlier pick survives the update). */
    static boolean inButton(Context c, String nag) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("btn_" + nag, !isSafety(nag));
    }

    static void setInButton(Context c, String nag, boolean v) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("btn_" + nag, v).apply();
        send(c, ACTION_SYNC);
    }

    /** The button set, in screen order. */
    static List<String> buttonSet(Context c) {
        List<String> set = new ArrayList<>();
        for (String n : NAGS) if (inButton(c, n)) set.add(n);
        return set;
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
            case NAG_FCTB:
                return fctb;
            case NAG_ELKA:
                return elka;
            case NAG_AEB:
                return aeb;
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
        int oldFctb = fctb, oldElka = elka, oldAeb = aeb;
        int f = adas.fctb();                        // read: 1 or 2 on, 0 off, 3 not fitted
        fctb = assistState(f);
        int e32 = adas.elka32f(), e2f = adas.elka2f0();
        int s32 = assistState(e32), s2f = assistState(e2f);
        elka = s32 == 1 || s2f == 1 ? 1 : s32 == 0 || s2f == 0 ? 0 : -1;
        int ab = adas.aeb();                        // read: 1 on (car menu), 0 or 2 off (unverified)
        aeb = ab == 1 ? 1 : ab == 0 || ab == 2 ? 0 : -1;
        if (lane != oldLane || tone != oldTone || slw != oldSlw || cpd != oldCpd
                || fctb != oldFctb || elka != oldElka || aeb != oldAeb) {
            Log.w(TAG, "adas lane=" + lane + " tone=" + tone + " slw=" + slw + " cpd=" + cpd
                    + " fctb=" + fctb + " elka=" + elka + " aeb=" + aeb
                    + " (2d5=" + a + " 2b4=" + b + " cpdRaw=" + c + " fctbRaw=" + f
                    + " elka32f=" + e32 + " elka2f0=" + e2f + " aebRaw=" + ab + ")");
        }
        badge();
    }

    /** The car menu's reading for an on/off assist switch: 1 or 2 on, 0 off, anything else
     *  (3 = not fitted, or no answer) unknown. */
    private static int assistState(int raw) {
        if (raw == 1 || raw == 2) return 1;
        if (raw == 0) return 0;
        return -1;
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
            case NAG_FCTB:
                if (fctb != 1) return false;
                Log.w(TAG, "cross traffic brake -> off: " + adas.setFctb(AdasClient.OFF));
                return true;
            case NAG_ELKA:
                if (elka != 1) return false;
                Log.w(TAG, "emergency lane keeping -> off: " + adas.setElka(AdasClient.OFF));
                return true;
            case NAG_AEB:
                if (aeb != 1) return false;
                Log.w(TAG, "emergency braking -> off: " + adas.setAeb(AdasClient.OFF));
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
        // Keep-off first: the seat and trip reads below are another 15 or so calls to the car.
        boolean wrote = false;
        for (String n : kept(this)) {   // DMS: killAdas returns false (its own callback path)
            if (killAdas(n)) {
                wrote = true;
                fixCount++;   // the screen's "switched off again N times" counts every nag
                lastFixWall = System.currentTimeMillis();
            }
        }
        if (wrote) {
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    adasRead();
                }
            }, 1500);
        }
        seatTick();
        openTick(adas.powerRaw());
        tripTick();
    }

    // ---- Seats: the owner's choice per front seat (fan, heat, or auto by outside temp, see Seats),
    // switched on once at each car start, then left alone for the drive (turn it off by hand and
    // it stays off). Off = the app doesn't touch that seat.
    static final String KEY_SEAT_DRIVER = "seat_driver", KEY_SEAT_PASS = "seat_pass";
    static final String KEY_SEAT_HEAT_BELOW = "seat_heat_below", KEY_SEAT_FAN_ABOVE = "seat_fan_above";
    static final String KEY_SEAT_AUTO_LEVEL = "seat_auto_level";
    /** This drive's start has been handled. In memory on purpose: BYD force-stops every
     *  third-party app at each ACC off, so a fresh process means a new start (seen on the car
     *  2026-11-08: a quick off/on killed us before we ever read the car as off). Also cleared
     *  when the car reads off (checked every poll, and when the screen goes dark). */
    private static volatile boolean seatDone;
    static volatile int seatDriver = -1, seatPass = -1;   // what each seat is doing now, a Seats choice or -1
    static volatile float outsideTemp = Float.NaN;          // last outside temperature reading (°C), or NaN
    private int seatTries;
    private final int[] lastSeatPick = {-1, -1};
    private final long[] lastSeatAt = {0, 0};
    private final Runnable autoReapply = () -> {
        for (boolean d : new boolean[]{true, false}) {
            if (Seats.isAuto(seatPick(this, d))) applySeat(d, "auto temps changed");
        }
    };

    static int seatPick(Context c, boolean driver) {
        int v = c.getSharedPreferences(PREFS, MODE_PRIVATE).getInt(driver ? KEY_SEAT_DRIVER : KEY_SEAT_PASS, Seats.OFF);
        return Seats.valid(v) ? v : Seats.OFF;
    }

    static boolean seatsPicked(Context c) {
        return seatPick(c, true) != Seats.OFF || seatPick(c, false) != Seats.OFF;
    }

    static int seatHeatBelow(Context c) {
        return Seats.clampTemp(c.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(KEY_SEAT_HEAT_BELOW, Seats.DEFAULT_HEAT_BELOW));
    }

    static int seatFanAbove(Context c) {
        return Seats.clampTemp(c.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(KEY_SEAT_FAN_ABOVE, Seats.DEFAULT_FAN_ABOVE));
    }

    static int seatAutoLevel(Context c) {
        return Seats.clampAutoLevel(c.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(KEY_SEAT_AUTO_LEVEL, Seats.DEFAULT_AUTO_LEVEL));
    }

    /** Save a seat's choice and switch that seat to it now. */
    static void setSeatPick(Context c, boolean driver, int pick) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(driver ? KEY_SEAT_DRIVER : KEY_SEAT_PASS, pick).apply();
        c.startForegroundService(new Intent(c, NagService.class).setAction(ACTION_SEATS)
                .putExtra("driver", driver));
    }

    /** Save auto mode temperatures and level; apply now if either seat is set to Auto. */
    static void setSeatAutoTemps(Context c, int heatBelow, int fanAbove, int autoLevel) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_SEAT_HEAT_BELOW, Seats.clampTemp(heatBelow))
                .putInt(KEY_SEAT_FAN_ABOVE, Seats.clampTemp(fanAbove))
                .putInt(KEY_SEAT_AUTO_LEVEL, Seats.clampAutoLevel(autoLevel)).apply();
        if (Seats.isAuto(seatPick(c, true)) || Seats.isAuto(seatPick(c, false))) {
            c.startForegroundService(new Intent(c, NagService.class).setAction(ACTION_SEATS));
        }
    }

    private static String seatWord(int pick) {
        return Seats.valid(pick) ? Seats.WORDS[pick] : "unknown";
    }

    /** On the handler thread. */
    private void applySeat(boolean driver, String why) {
        if (!adas.isReady()) {
            adas.connect();
            return;
        }
        int pick = seatPick(this, driver);
        String seat = driver ? "driver" : "passenger";
        int want = pick;
        if (Seats.isAuto(pick)) {
            float t = adas.outsideTemp();
            if (!Float.isNaN(t)) outsideTemp = t;
            want = Seats.resolve(pick, outsideTemp, seatHeatBelow(this), seatFanAbove(this), seatAutoLevel(this));
            if (want < 0) {
                Log.w(TAG, "seat " + seat + " auto: no outside temp yet (" + why + ")");
                return;
            }
            Log.w(TAG, "seat " + seat + " auto: outside " + outsideTemp + "C -> " + Seats.WORDS[want] + " (" + why + ")");
        }
        if (want == Seats.OFF) {
            // Switch off whichever is running. The heater only when it reads on.
            if (adas.seatFan(driver) != 0) Log.w(TAG, "seat " + seat + " fan -> off (" + why + ") reply " + adas.setSeatFan(driver, 0));
            if (adas.seatHeat(driver) > 0) Log.w(TAG, "seat " + seat + " heat -> off (" + why + ") reply " + adas.setSeatHeat(driver, 0));
        } else {
            // One write: the car switches the other one off itself.
            int level = Seats.pickLevel(want);
            int r = Seats.heat(want) ? adas.setSeatHeat(driver, level) : adas.setSeatFan(driver, level);
            Log.w(TAG, "seat " + seat + " -> " + Seats.WORDS[want] + " (" + why + ") reply " + r);
        }
        busyUntil = Math.max(busyUntil, SystemClock.elapsedRealtime() + 4000);
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                seatDriver = adas.seatNow(true);
                seatPass = adas.seatNow(false);
                Log.w(TAG, "seat: now driver=" + seatWord(seatDriver) + " passenger=" + seatWord(seatPass));
            }
        }, 2500);
    }

    /** Every poll: car off -> arm for the next start; car on and not done -> switch the picked
     *  seats on, read back, retry a few times (the seat may not answer right at power-on). */
    private void seatTick() {
        seatDriver = adas.seatNow(true);
        seatPass = adas.seatNow(false);
        boolean anyAuto = Seats.isAuto(seatPick(this, true)) || Seats.isAuto(seatPick(this, false));
        int power = adas.powerRaw();
        if (power == Integer.MIN_VALUE) return;
        if (power != AdasClient.POWER_ON_RAW) {
            if (seatDone) Log.w(TAG, "seat: car not on (" + power + "), armed");
            seatDone = false;
            seatTries = 0;
            outsideTemp = Float.NaN;   // a start decides on a fresh reading, never last drive's
            return;
        }
        if (anyAuto || uiOpen) {
            float t = adas.outsideTemp();
            if (!Float.isNaN(t)) outsideTemp = t;
        }
        if (seatDone || !seatsPicked(this)) return;
        int heatBelow = seatHeatBelow(this), fanAbove = seatFanAbove(this), autoLevel = seatAutoLevel(this);
        if (seatTries == 0) {
            Log.w(TAG, "seat: car on, now driver=" + seatDriver + " passenger=" + seatPass
                    + (anyAuto ? " outside=" + outsideTemp + "C" : ""));
        }
        boolean ok = true;
        for (boolean driver : new boolean[]{true, false}) {
            int pick = seatPick(this, driver);
            if (pick == Seats.OFF) continue;
            int want = Seats.resolve(pick, outsideTemp, heatBelow, fanAbove, autoLevel);
            if (want < 0) {
                ok = false;   // waiting for the outside temperature reading at power-on
                continue;
            }
            if (want == Seats.OFF) continue;   // auto in deadband: leave seat off
            int now = driver ? seatDriver : seatPass;
            if (now == want) continue;
            ok = false;
            applySeat(driver, "car start");
        }
        if (ok || ++seatTries > 3) {
            Log.w(TAG, "seat: start done (" + (ok ? "set" : "gave up") + ") driver=" + seatDriver + " passenger=" + seatPass);
            seatDone = true;
            seatTries = 0;
        }
    }

    // ---- Open at start (issue #8 tester build): one app, or two side by side, a few seconds
    // after the car comes on. Once per start, like the seats (in memory: BYD's force-stop at
    // ACC off makes a fresh process, and a poll that reads the car off re-arms it).
    private static volatile boolean openDone;
    private long openSeenOn;

    static String openPick(Context c, boolean first) {
        android.content.SharedPreferences p = c.getSharedPreferences(PREFS, MODE_PRIVATE);
        String a = p.getString(AutoOpen.KEY_FIRST, null), b = p.getString(AutoOpen.KEY_SECOND, null);
        if (!AutoOpen.validComponent(a)) a = null;
        if (!AutoOpen.validComponent(b)) b = null;
        if (a == null) {
            a = b;
            b = null;
        }
        return first ? a : b;
    }

    /**
     * An update (or install) restarts the service with the car already on, which looks just
     * like a start (BYD kills the app at ACC off, so a real start is a fresh process too). Seen
     * on the car 28 Nov: installing a build opened the apps.
     */
    static boolean justUpdated(Context c) {
        try {
            long t = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime;
            return Math.abs(System.currentTimeMillis() - t) < 3 * 60_000;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean openOn(Context c) {
        return openPick(c, true) != null;
    }

    static void setOpenPick(Context c, String first, String second) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(AutoOpen.KEY_FIRST, first).putString(AutoOpen.KEY_SECOND, first == null ? null : second).apply();
        openDone = true;   // picking it now is not a car start: the next start opens them
        sync(c);
    }

    interface Opened {
        /** On the main thread. */
        void done(String words);
    }

    private void openTick(int power) {
        if (power == Integer.MIN_VALUE) return;
        if (power != AdasClient.POWER_ON_RAW) {
            if (openDone) Log.w(TAG, "open at start: car not on (" + power + "), armed");
            openDone = false;
            openSeenOn = 0;
            return;
        }
        if (openDone || !openOn(this)) return;
        if (justUpdated(this)) {
            Log.w(TAG, "open at start: app just updated, not a car start");
            openDone = true;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (openSeenOn == 0) {
            openSeenOn = now;
            Log.w(TAG, "open at start: car on, opening in " + AutoOpen.DELAY_MS / 1000 + " s");
            busyUntil = Math.max(busyUntil, now + AutoOpen.DELAY_MS + 20_000);
            h.postDelayed(this::openTickLater, AutoOpen.DELAY_MS);
        }
    }

    private void openTickLater() {
        if (openDone || openSeenOn == 0) return;
        if (adas.isReady() && adas.powerRaw() != AdasClient.POWER_ON_RAW) return;   // switched off again
        openDone = true;
        runOpen(this, true, null);
    }

    /**
     * Open the picked app(s). Through the car's debugging (the only way to ask for split
     * screen). atStart: never pops "Allow USB debugging?" on the driver's screen and steps
     * back if CarPlay or Android Auto is up. One app with debugging off: opened directly.
     */
    static void runOpen(final Context ctx, final boolean atStart, final Opened cb) {
        final Context c = ctx.getApplicationContext();
        final String first = openPick(c, true), second = openPick(c, false);
        if (first == null) return;
        new Thread(() -> {
            String words;
            AdbLocal adb = null;
            StringBuilder log = new StringBuilder(new java.util.Date() + (atStart ? " car start\n" : " try now\n"));
            try {
                // At a start the car's debugging can take a moment to come up: three tries.
                for (int i = 0; ; i++) {
                    try {
                        adb = AdbLocal.connect("127.0.0.1", 5555, HelperSetup.loadKey(c), "naglab@sealion7", 3000,
                                !atStart, atStart ? 10_000 : 90_000, () -> { });
                        break;
                    } catch (AdbLocal.KeyNotTrustedException e) {
                        throw e;
                    } catch (Exception e) {
                        log.append("# debugging not answering: ").append(e).append('\n');
                        if (!atStart || i >= 2) throw e;
                        Thread.sleep(5_000);
                    }
                }
                final AdbLocal a = adb;
                AutoOpener op = new AutoOpener(a::shell, Thread::sleep);
                AutoOpener.Result r;
                try {
                    r = op.open(first, second, atStart);
                } finally {
                    log.append(op.log);
                }
                log.append("# result ").append(r).append('\n');
                words = r.words;
                if (r == AutoOpener.Result.FAILED && second == null && openDirect(c, first)) words = "Opened.";
            } catch (Throwable t) {
                log.append("# ").append(t).append('\n');
                if (second == null) {
                    words = openDirect(c, first) ? "Opened." : "Couldn't open it.";
                } else {
                    words = (openDirect(c, first) ? "Opened the first app only: " : "")
                            + "side by side needs debugging on (and Always allow tapped once).";
                }
            } finally {
                if (adb != null) adb.close();
            }
            Log.w(TAG, "open at start: " + log.toString().replace("\n", " | "));
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(c.getFilesDir(), AutoOpen.LOG_FILE))) {
                o.write(log.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // only the report misses out
            }
            String stamp = new java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault())
                    .format(new java.util.Date());
            c.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(AutoOpen.KEY_LAST, stamp + (atStart ? " (car start): " : " (try now): ") + words).apply();
            if (cb != null) {
                final String w = words;
                new Handler(android.os.Looper.getMainLooper()).post(() -> cb.done(w));
            }
        }, "naglab-open").start();
    }

    private static boolean openDirect(Context c, String component) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(android.content.ComponentName.unflattenFromString(component))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "open at start: direct open failed: " + t);
            return false;
        }
    }

    // ---- Seat memory: move the driver's seat to one of the car's own saved positions, or save
    // where it is now as one. Only
    // ever from a tap on the app's own screen (never at a start, never from another app: this
    // is a plain method, not a service action), and only with the car in Park, standing still.
    static final int MOVE_SENT = 0, MOVE_NOT_PARKED = 1, MOVE_NO_CAR = 2;

    interface SeatMoved {
        /** On the main thread: MOVE_SENT, MOVE_NOT_PARKED or MOVE_NO_CAR. */
        void done(int result);
    }

    private static volatile NagService self;

    /** Move the driver's seat to position 1 or 2. */
    static void recallSeat(int slot, SeatMoved cb) {
        seatMemory(slot, false, cb);
    }

    /** Save where the driver's seat is now as position 1 or 2 (replaces what was there). */
    static void saveSeat(int slot, SeatMoved cb) {
        seatMemory(slot, true, cb);
    }

    private static void seatMemory(final int slot, final boolean save, final SeatMoved cb) {
        final NagService s = self;
        if (s == null || !Seats.slot(slot)) {
            cb.done(MOVE_NO_CAR);
            return;
        }
        boolean posted = s.h.post(new Runnable() {
            @Override
            public void run() {
                int result;
                if (!s.adas.isReady()) {
                    s.adas.connect();
                    result = MOVE_NO_CAR;
                } else {
                    int gear = s.adas.gear();
                    float speed = s.adas.speed();
                    if (gear < 0) {
                        result = MOVE_NO_CAR;
                    } else if (!Seats.mayMove(gear, speed)) {
                        result = MOVE_NOT_PARKED;
                    } else {
                        int r = save ? s.adas.saveDriverSeat(slot) : s.adas.recallDriverSeat(slot);
                        result = r == Integer.MIN_VALUE ? MOVE_NO_CAR : MOVE_SENT;
                    }
                    Log.w(TAG, "seat memory: " + (save ? "save as" : "move to") + " position " + slot + " gear " + gear + " speed " + speed
                            + " -> " + (result == MOVE_SENT ? "sent" : result == MOVE_NOT_PARKED ? "not in Park" : "no answer"));
                }
                final int res = result;
                s.mainH.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.done(res);
                    }
                });
            }
        });
        if (!posted) cb.done(MOVE_NO_CAR);   // the service is shutting down
    }

    private int lastBadge = -2;   // touched on the handler thread h only

    /** Quick button: 1 = a nag in the button set is on right now (a tap will do something),
     *  0 = all off, -1 = the button set is empty (button shown dimmed). */
    private void badge() {
        List<String> set = buttonSet(this);
        int b = set.isEmpty() ? -1 : 0;
        for (String n : set) if (state(n) == 1) b = 1;
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
            case NAG_FCTB: return KEY_FCTB_OFF;
            case NAG_ELKA: return KEY_ELKA_OFF;
            case NAG_AEB: return KEY_AEB_OFF;
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

    /**
     * Overlay / big button: switch the button set off now (chosen under "Buttons switch off",
     * separate from Keep off; the buttons never change Keep off). One direction only and safe to repeat: a tap while
     * driving can never switch a nag back ON (it used to toggle, and a second tap brought DMS
     * back, seen on the car 2026-10-24). Bringing a nag back is a deliberate act: its switch in
     * the app, or the car's own settings.
     */
    private void killButtonSet(final String why, final boolean card) {
        final List<String> set = buttonSet(this);
        if (set.isEmpty()) {   // nothing to do: answer straight away
            h.post(new Runnable() {
                @Override
                public void run() {
                    report(set, new HashMap<String, Integer>(), true, card);
                }
            });
            return;
        }
        apply(set, true, why, card);
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
            case NAG_FCTB:
                Log.w(TAG, "cross traffic brake -> on: " + adas.setFctb(AdasClient.ON));
                break;
            case NAG_ELKA:
                Log.w(TAG, "emergency lane keeping -> on: " + adas.setElka(AdasClient.ON));
                break;
            case NAG_AEB:
                Log.w(TAG, "emergency braking -> on: " + adas.setAeb(AdasClient.ON));
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
        final List<String> outcomes = new ArrayList<>();
        StringBuilder sum = new StringBuilder();
        for (String n : set) {
            String what = Report.outcome(before.get(n), state(n), kill);
            outcomes.add(what);
            lines.add(new String[]{label(n), what});
            if (sum.length() > 0) sum.append(" · ");
            sum.append(label(n)).append(" ").append(what);
        }
        if (set.isEmpty()) sum.append("Nothing picked for the buttons");
        lastKillSummary = sum.toString();
        lastSummaryWall = System.currentTimeMillis();   // the screen shows the summary for 1 min
        busyUntil = 0;
        Log.w(TAG, "result: " + lastKillSummary);
        if (card) {
            final int verdict = Report.verdict(outcomes);
            mainH.post(new Runnable() {
                @Override
                public void run() {
                    quick.showResult(lines, verdict);
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
        int old;
        if ("dms".equals(which)) { old = dms; dms = value; }
        else if ("fatigue".equals(which)) { old = fatigue; fatigue = value; }
        else if ("attention".equals(which)) { old = attention; attention = value; }
        else return;
        if (value != old) Log.w(TAG, "state " + which + "=" + value);   // changes only: small log buffer
        h.post(this::badge);   // binder thread -> h, where lastBadge lives
        if (value == 1) enforce(which + "=1");
    }

    @Override
    public void onDestroy() {
        running = false;
        self = null;
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
