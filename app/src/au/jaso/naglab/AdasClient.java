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
 * Seat fans (proven on the car 2026-11-08), "setting" sub-binder,
 * com.ts.lib.caradapter.setting.ISettingService:
 *   358 setSeatVentilatingStateDriver / 359 ...Passenger   WRITE 1 High, 2 Low, 3 Off (BYD's
 *                                                     SettingManagerImpl swaps 1<->3 from the UI's values)
 *   354 getSeatVentilatingState (driver) / 462 getSeatVentilatingNotL1PState (passenger)
 *                                                     READ 1 Off, 2 Low, 3 High. (355, the "Passenger"
 *                                                     getter, reads 0 on this car: the climate app uses 462.)
 * Power level, "body" sub-binder com.ts.lib.caradapter.body.ICarBodyService 304 getPowerLevel:
 *   raw 1 off, 2 ACC, 3 on (BodyworkManagerImpl maps them to 0/1/2). Read 3 with the car on.
 * Exported service, no permission, no caller checks.
 *
 * The numbers above are the owner's AU car. At runtime every number is looked up by name in
 * the car's own ts-framework.jar (Codes), because they move between firmware builds.
 */
final class AdasClient {
    static final String TAG = DmsClient.TAG;

    private static final String ADAPTER_DESC = "com.ts.lib.caradapter.ICarAdapterService";
    private static final String ASSIST_DESC = "com.ts.lib.caradapter.assist.ICarAssistAdapterService";
    private static final String SETTING_DESC = "com.ts.lib.caradapter.setting.ISettingService";
    private static final String BODY_DESC = "com.ts.lib.caradapter.body.ICarBodyService";
    private static final String GENERAL_DESC = "com.ts.lib.caradapter.general.ICarGeneralService";
    private static final String CABIN_DESC = "com.ts.lib.caradapter.cabin.ICabinService";
    private static final String SENSOR_DESC = "com.ts.lib.caradapter.sensor.ICarSensorService";
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
    static final String[][] BUILT_IN_SEAT = {
            {"getSeatVentilatingState", "354"}, {"getSeatVentilatingNotL1PState", "462"},
            {"setSeatVentilatingStateDriver", "358"}, {"setSeatVentilatingStatePassenger", "359"},
            {"getPowerLevel", "304"},
    };
    /** Trips (read only, proven on the owner's car 2026-11-11 while driving). */
    static final String[][] BUILT_IN_TRIP = {
            {"getCurrentTripMileage", "149"}, {"getCurrentTripTime", "151"}, {"getCurrentTripElec", "152"},
            {"getTotalMileageValue", "139"}, {"getElecDrivingRangeValue", "126"},
            {"getSocValue", "21"}, {"getGear", "116"}, {"getCurrentSpeed", "12"},
    };
    /** Seat fan levels as the app stores them (and as the car READS them, minus one). */
    static final int FAN_OFF = 0, FAN_LOW = 1, FAN_HIGH = 2;
    static final int POWER_ON_RAW = 3;
    static final int CPD_ON = 1, CPD_OFF = 2, CPD_DELAY = 3;

    static final int LANE_OFF_READ = 0, LANE_OFF_WRITE = 1;
    static final int OFF = 1, ON = 2;   // WRITE values for tone, speed-limit warning, FCTB, ELKA, AEB

    private final Context ctx;
    private volatile IBinder assist, setting, body, general, cabin, sensor;
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
            setting = null;
            body = null;
            general = null;
            cabin = null;
            sensor = null;
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
        setting = null;
        body = null;
        general = null;
        cabin = null;
        sensor = null;
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
        IBinder st = sub(adapter, "setting");
        IBinder bo = sub(adapter, "body");
        IBinder ge = sub(adapter, "general");
        IBinder ca = sub(adapter, "cabin");
        IBinder se = sub(adapter, "sensor");
        synchronized (this) {
            if (gen != generation) return;      // disconnect() ran meanwhile
            if (b != null) {
                assist = b;
                setting = st;
                body = bo;
                general = ge;
                cabin = ca;
                sensor = se;
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
        return call(assist, ASSIST_DESC, Codes.get().code(method), method, arg);
    }

    private static int call(IBinder b, String desc, int code, String method, Integer arg) {
        if (b == null) return Integer.MIN_VALUE;
        if (code <= 0) return Integer.MIN_VALUE;   // this firmware has no such command: send nothing
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(desc);
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

    private int settingCall(String method, Integer arg) {
        Codes c = Codes.get();
        return call(setting, SETTING_DESC, c.code(c.setting, method), method, arg);
    }

    /** Seat fan level FAN_OFF/LOW/HIGH, or -1 unknown. driver true = driver's seat. */
    int seatFan(boolean driver) {
        int raw = settingCall(driver ? "getSeatVentilatingState" : "getSeatVentilatingNotL1PState", null);
        return raw >= 1 && raw <= 3 ? raw - 1 : -1;
    }

    /** Write a seat fan level (FAN_OFF/LOW/HIGH). */
    int setSeatFan(boolean driver, int level) {
        int raw = level == FAN_HIGH ? 1 : level == FAN_LOW ? 2 : 3;
        return settingCall(driver ? "setSeatVentilatingStateDriver" : "setSeatVentilatingStatePassenger", raw);
    }

    /** Raw power level (3 = car on), Integer.MIN_VALUE when it can't be read. */
    int powerRaw() {
        Codes c = Codes.get();
        return call(body, BODY_DESC, c.code(c.body, "getPowerLevel"), "getPowerLevel", null);
    }

    /** Float reply of a no-argument getter, NaN on any failure or missing command. */
    private static float callFloat(IBinder b, String desc, int code, String method) {
        if (b == null || code <= 0) return Float.NaN;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(desc);
            if (!b.transact(code, d, r, 0)) return Float.NaN;
            r.readException();
            return r.readFloat();
        } catch (Exception e) {
            Log.w(TAG, "trip call " + method + "(" + code + ") failed: " + e);
            return Float.NaN;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    private static int orMinus(int v) {
        return v == Integer.MIN_VALUE ? -1 : v;
    }

    /** One trip reading: 9 read-only calls. Values the car can't give come back NaN / -1. */
    Trips.Reading readTrip() {
        Codes c = Codes.get();
        Trips.Reading r = new Trips.Reading();
        r.wall = System.currentTimeMillis();
        r.km = Trips.Reading.km(callFloat(general, GENERAL_DESC,
                c.code(c.general, "getCurrentTripMileage"), "getCurrentTripMileage"));
        r.min = Trips.Reading.min(orMinus(call(general, GENERAL_DESC,
                c.code(c.general, "getCurrentTripTime"), "getCurrentTripTime", null)));
        r.kwh = Trips.Reading.kwh(callFloat(general, GENERAL_DESC,
                c.code(c.general, "getCurrentTripElec"), "getCurrentTripElec"));
        r.odo = Trips.Reading.odo(callFloat(general, GENERAL_DESC,
                c.code(c.general, "getTotalMileageValue"), "getTotalMileageValue"));
        r.range = Trips.Reading.range(orMinus(call(general, GENERAL_DESC,
                c.code(c.general, "getElecDrivingRangeValue"), "getElecDrivingRangeValue", null)));
        r.bat = Trips.Reading.bat(callFloat(general, GENERAL_DESC,
                c.code(c.general, "getRemainingBatteryPower"), "getRemainingBatteryPower"));
        r.soc = Trips.Reading.soc(orMinus(settingCall("getSocValue", null)));
        r.gear = orMinus(call(cabin, CABIN_DESC, c.code(c.cabin, "getGear"), "getGear", null));
        r.speed = Trips.Reading.speed(callFloat(sensor, SENSOR_DESC,
                c.code(c.sensor, "getCurrentSpeed"), "getCurrentSpeed"));
        return r;
    }

    /** The car's own battery health figure in percent (cabin getBatteryHealthStatus; read 100 on
     *  the owner's car at 5,290 km), or -1. What BYD puts behind it is not documented. */
    int batteryHealth() {
        Codes c = Codes.get();
        int v = call(cabin, CABIN_DESC, c.code(c.cabin, "getBatteryHealthStatus"), "getBatteryHealthStatus", null);
        return v >= 1 && v <= 100 ? v : -1;
    }

    /** Charging, read-only: {plug (1 = plugged in), kW, minutes to full}; NaN / -1 = not given.
     *  Seen on the car 12 Nov on a 7 kW wallbox: plug 1, 5.7 kW, [7, 37]. Unplugged the power
     *  reads a filler value (359.4) and the time [255, 255]. */
    float[] readCharge() {
        Codes c = Codes.get();
        int plug = call(body, BODY_DESC, c.code(c.body, "getChargingGunState"), "getChargingGunState", null);
        float kw = Float.NaN;
        int rest = -1;
        if (plug == 1) {
            kw = callFloat(body, BODY_DESC, c.code(c.body, "getChargingPower"), "getChargingPower");
            if (!(kw >= 0 && kw < 350)) kw = Float.NaN;
            int code = c.code(c.body, "getChargingRestTime");
            if (body != null && code > 0) {
                Parcel d = Parcel.obtain(), r = Parcel.obtain();
                try {
                    d.writeInterfaceToken(BODY_DESC);
                    if (body.transact(code, d, r, 0)) {
                        r.readException();
                        int[] hm = r.createIntArray();
                        if (hm != null && hm.length >= 2 && hm[0] >= 0 && hm[0] < 100 && hm[1] >= 0 && hm[1] < 60) {
                            rest = hm[0] * 60 + hm[1];
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "charge time read failed: " + e);
                } finally {
                    d.recycle();
                    r.recycle();
                }
            }
        }
        return new float[]{plug, kw, rest};
    }

    /** The car's VIN, or null. Only for the owner's own battery report, which stays on the car
     *  until they pull it off; it never goes in the car report people post on GitHub. */
    String vin() {
        Codes c = Codes.get();
        int code = c.code(c.body, "getRealAutoVIN");
        if (body == null || code <= 0) return null;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(BODY_DESC);
            if (!body.transact(code, d, r, 0)) return null;
            r.readException();
            String v = r.readString();
            return v != null && v.matches("[A-HJ-NPR-Z0-9]{17}") ? v : null;
        } catch (Exception e) {
            return null;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    /** For the car report: raw int reply of any no-argument getter by name. */
    int read(String method) {
        return call(method, null);
    }
}
