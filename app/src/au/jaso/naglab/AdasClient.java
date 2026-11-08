package au.jaso.naglab;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

/**
 * Raw-binder client for the lane-assist, speed-limit chime and child-detection nags on the
 * Sealion 7.
 *
 * Path (reverse-engineered 2026-10-24 from the car's own ts-framework.jar, md5 b4aece64...,
 * and BydCarSettings.apk; the car's settings screen calls exactly these):
 * bind com.ts.appservice.caradapter/.CarAdapterService (action
 * com.ts.lib.caradapter.ICarAdapterService), call 2 getCarAdapterService("assist"), then
 * com.ts.lib.caradapter.assist.ICarAssistAdapterService:
 *   11 getLaneAssistType                             READ 0 Off, 1 Warning, 2 Prevent, 3 Both
 *   12 setLaneAssistType                             WRITE 1 = Off (t1.l bs_lane_assist2:
 *                                                     v0(on ? 1 + tag : 1); modes write 2/4/5).
 *                                                     Writing 0 is silently ignored (seen on car).
 *   64 getTsrLitSpdChgSwitchState                    speed-limit change tone: READ 0 on, 1 off
 *   65 setTsrLitSpdChgSwitchState                    WRITE 2 on, 1 off (BydCarSettings z1.d.U0/V)
 *   22 setIntelligentSpeedLimitAlertState            speed-limit warning: WRITE 2 on, 1 off
 *   71 / 72 getIntelligentSpeedLimitAlert2D5/2B4State READ 1 on (t1.l.V picks one bus by
 *                                                     car config 141; we treat either == 1 as on)
 *   187 getCpdStatus / 185 setCpdStatus            child presence detection: READ and WRITE
 *                                                     1 Standard, 3 Delay, 2 Off
 *   53 getFrontTrafficBrakeAlertState / 54 set      front cross traffic brake (car menu
 *                                                     "front lateral approach braking"): WRITE 2 on,
 *                                                     1 off (t1.l switch_front_lateral_approach_braking).
 *                                                     READ: the car menu treats 1 or 2 as on, 0 off,
 *                                                     3 not available (DriverAssistModel).
 *   35 setEmergencyLaneAssistanceState               emergency lane keeping (ELKA): WRITE 2 on, 1 off
 *   79 / 80 getEmergencyLaneAssistance32F/2F0State   READ: 1 or 2 on, else off, 3 not available
 *                                                     (t1.l.U; bus picked by car config 143).
 *   46 getAiEmergencyBrakeState / 47 set             automatic emergency braking (AEB): WRITE 2 on,
 *                                                     1 off (t1.l switch_ai_emergency_brake: on -> g0(2),
 *                                                     confirmed off -> g0(1)). READ: the car menu shows
 *                                                     the switch on only when it reads 1 (t1.l.o); the
 *                                                     off reading is unverified (2 or 0).
 * Exported service, no permission, no caller checks.
 *
 * The numbers above are the owner's AU car. At runtime every number is looked up by name in
 * the car's own ts-framework.jar (Codes), because they move between firmware builds.
 */
final class AdasClient {
    static final String TAG = DmsClient.TAG;

    private static final String ADAPTER_DESC = "com.ts.lib.caradapter.ICarAdapterService";
    private static final String ASSIST_DESC = "com.ts.lib.caradapter.assist.ICarAssistAdapterService";
    /** Built-in numbers from the owner's AU car. Only used when the car's own
     *  ts-framework.jar can't be read; normally every number is looked up by name on the car
     *  itself (see Codes), because BYD moves them between firmware builds (issue #2: EU cars
     *  have setCpdStatus at 190, not 185). */
    static final String[][] BUILT_IN = {
            {"getLaneAssistType", "11"}, {"setLaneAssistType", "12"},
            {"getTsrLitSpdChgSwitchState", "64"}, {"setTsrLitSpdChgSwitchState", "65"},
            {"setIntelligentSpeedLimitAlertState", "22"},
            {"getIntelligentSpeedLimitAlert2D5State", "71"}, {"getIntelligentSpeedLimitAlert2B4State", "72"},
            {"getCpdStatus", "187"}, {"setCpdStatus", "185"},
            {"getFrontTrafficBrakeAlertState", "53"}, {"setFrontTrafficBrakeAlertState", "54"},
            {"setEmergencyLaneAssistanceState", "35"},
            {"getEmergencyLaneAssistance32FState", "79"}, {"getEmergencyLaneAssistance2F0State", "80"},
            {"getAiEmergencyBrakeState", "46"}, {"setAiEmergencyBrakeState", "47"},
    };
    static final int CPD_ON = 1, CPD_OFF = 2, CPD_DELAY = 3;

    static final int LANE_OFF_READ = 0, LANE_OFF_WRITE = 1;
    static final int OFF = 1, ON = 2;   // WRITE values for tone, speed-limit warning, FCTB, ELKA, AEB

    private final Context ctx;
    private volatile IBinder assist;
    private boolean bound;
    /** Bumped by disconnect(): an attach thread from an older binding must not publish. */
    private int generation;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName n, final IBinder adapter) {
            final int gen;
            synchronized (AdasClient.this) {
                gen = generation;
            }
            new Thread(() -> attach(adapter, gen), "naglab-adas").start();
        }

        @Override
        public void onServiceDisconnected(ComponentName n) {
            Log.w(TAG, "CarAdapterService disconnected");
            assist = null;
        }
    };

    AdasClient(Context c) {
        ctx = c.getApplicationContext();
    }

    boolean isReady() {
        return assist != null;
    }

    synchronized void connect() {
        if (assist != null || bound) return;
        Intent i = new Intent("com.ts.lib.caradapter.ICarAdapterService");
        i.setClassName("com.ts.appservice.caradapter", "com.ts.appservice.caradapter.CarAdapterService");
        try {
            bound = ctx.bindService(i, conn, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException e) {
            Log.w(TAG, "adas bind failed: " + e);
            bound = false;
        }
        Log.w(TAG, "adas bindService -> " + bound);
    }

    synchronized void disconnect() {
        generation++;
        assist = null;
        if (bound) {
            try {
                ctx.unbindService(conn);
            } catch (RuntimeException ignored) {
            }
            bound = false;
        }
    }

    private void attach(IBinder adapter, int gen) {
        Codes.get();                            // read the car's command numbers before first use
        IBinder b = sub(adapter, "assist");   // binder call: off the main thread
        synchronized (this) {
            if (gen != generation) return;      // disconnect() ran meanwhile
            if (b != null) {
                assist = b;
            } else {
                disconnect();                   // lookup failed: unbind so connect() retries
            }
        }
    }

    private static IBinder sub(IBinder adapter, String name) {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(ADAPTER_DESC);
            d.writeString(name);
            adapter.transact(Codes.get().adapterLookup, d, r, 0);
            r.readException();
            IBinder b = r.readStrongBinder();
            Log.w(TAG, "car adapter '" + name + "' binder " + (b != null ? "attached" : "NULL"));
            return b;
        } catch (Exception e) {
            Log.w(TAG, "car adapter '" + name + "' attach failed: " + e);
            return null;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    /** Returns the int reply, or Integer.MIN_VALUE on any failure. */
    private int call(String method, Integer arg) {
        IBinder b = assist;
        if (b == null) return Integer.MIN_VALUE;
        int code = Codes.get().code(method);
        if (code <= 0) return Integer.MIN_VALUE;   // this firmware has no such command: send nothing
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(ASSIST_DESC);
            if (arg != null) d.writeInt(arg);
            if (!b.transact(code, d, r, 0)) return Integer.MIN_VALUE;
            r.readException();
            return r.readInt();
        } catch (Exception e) {
            Log.w(TAG, "adas call " + method + "(" + code + ") failed: " + e);
            return Integer.MIN_VALUE;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    int lane() {
        return call("getLaneAssistType", null);
    }

    int tone() {
        return call("getTsrLitSpdChgSwitchState", null);
    }

    int slw2d5() {
        return call("getIntelligentSpeedLimitAlert2D5State", null);
    }

    int slw2b4() {
        return call("getIntelligentSpeedLimitAlert2B4State", null);
    }

    int setLane(int v) {
        return call("setLaneAssistType", v);
    }

    int setTone(int v) {
        return call("setTsrLitSpdChgSwitchState", v);
    }

    int setSlw(int v) {
        return call("setIntelligentSpeedLimitAlertState", v);
    }

    int cpd() {
        return call("getCpdStatus", null);
    }

    int setCpd(int v) {
        return call("setCpdStatus", v);
    }

    int fctb() {
        return call("getFrontTrafficBrakeAlertState", null);
    }

    int setFctb(int v) {
        return call("setFrontTrafficBrakeAlertState", v);
    }

    int elka32f() {
        return call("getEmergencyLaneAssistance32FState", null);
    }

    int elka2f0() {
        return call("getEmergencyLaneAssistance2F0State", null);
    }

    int setElka(int v) {
        return call("setEmergencyLaneAssistanceState", v);
    }

    int aeb() {
        return call("getAiEmergencyBrakeState", null);
    }

    int setAeb(int v) {
        return call("setAiEmergencyBrakeState", v);
    }

    /** For the car report: raw int reply of any no-argument getter by name. */
    int read(String method) {
        return call(method, null);
    }
}
