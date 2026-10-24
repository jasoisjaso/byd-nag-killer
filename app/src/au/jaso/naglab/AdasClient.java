package au.jaso.naglab;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

/**
 * Raw-binder client for the lane-assist and speed-limit nags on the Sealion 7.
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
 * Child presence detection lives on the "setting" sub-binder of the same service
 * (com.ts.lib.caradapter.setting.ISettingService): 837 getCuraSwitchState / 845 set, READ and
 * WRITE both 1 = on, 2 = off (BydCarSettings CockpitPerFragment: switch checked when get == 1,
 * off writes 2 after the car's "are you sure" dialog; carType 182/208 = Sealion 7).
 * Exported service, no permission, no caller checks. Never touches braking or steering
 * assistance beyond the lane-assist mode the driver can pick on the car's own quick tile.
 */
final class AdasClient {
    static final String TAG = DmsClient.TAG;

    private static final String ADAPTER_DESC = "com.ts.lib.caradapter.ICarAdapterService";
    private static final String ASSIST_DESC = "com.ts.lib.caradapter.assist.ICarAssistAdapterService";
    static final int GET_LANE = 11, SET_LANE = 12;
    static final int GET_TONE = 64, SET_TONE = 65;
    static final int SET_SLW = 22, GET_SLW_2D5 = 71, GET_SLW_2B4 = 72;
    private static final String SETTING_DESC = "com.ts.lib.caradapter.setting.ISettingService";
    static final int GET_CPD = 837, SET_CPD = 845, CPD_ON = 1, CPD_OFF = 2;

    static final int LANE_OFF_READ = 0, LANE_OFF_WRITE = 1;
    static final int OFF = 1;   // WRITE value for tone + speed-limit warning off

    private final Context ctx;
    private volatile IBinder assist, setting;
    private boolean bound;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName n, final IBinder adapter) {
            new Thread(() -> attach(adapter), "naglab-adas").start();
        }

        @Override
        public void onServiceDisconnected(ComponentName n) {
            Log.w(TAG, "CarAdapterService disconnected");
            assist = null;
            setting = null;
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
        assist = null;
        setting = null;
        if (bound) {
            try {
                ctx.unbindService(conn);
            } catch (RuntimeException ignored) {
            }
            bound = false;
        }
    }

    private void attach(IBinder adapter) {
        setting = sub(adapter, "setting");
        assist = sub(adapter, "assist");   // last: isReady() means both lookups are done
    }

    private static IBinder sub(IBinder adapter, String name) {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(ADAPTER_DESC);
            d.writeString(name);
            adapter.transact(2, d, r, 0);
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
    private int call(int code, Integer arg) {
        return call(assist, ASSIST_DESC, code, arg);
    }

    private static int call(IBinder b, String desc, int code, Integer arg) {
        if (b == null) return Integer.MIN_VALUE;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(desc);
            if (arg != null) d.writeInt(arg);
            if (!b.transact(code, d, r, 0)) return Integer.MIN_VALUE;
            r.readException();
            return r.readInt();
        } catch (Exception e) {
            Log.w(TAG, "adas call " + code + " failed: " + e);
            return Integer.MIN_VALUE;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    int lane() {
        return call(GET_LANE, null);
    }

    int tone() {
        return call(GET_TONE, null);
    }

    int slw2d5() {
        return call(GET_SLW_2D5, null);
    }

    int slw2b4() {
        return call(GET_SLW_2B4, null);
    }

    int setLane(int v) {
        return call(SET_LANE, v);
    }

    int setTone(int v) {
        return call(SET_TONE, v);
    }

    int setSlw(int v) {
        return call(SET_SLW, v);
    }

    int cpd() {
        return call(setting, SETTING_DESC, GET_CPD, null);
    }

    int setCpd(int v) {
        return call(setting, SETTING_DESC, SET_CPD, v);
    }
}
