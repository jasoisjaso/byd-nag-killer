package au.jaso.naglab;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

/**
 * Raw-binder client for BYD's DMS (driver monitoring) settings on the Sealion 7.
 *
 * Path (reverse-engineered from ts-framework.jar + SettingsService.apk, proven on the car
 * 2026-10-18): bind com.ts.appservice.settings/.SettingsService with action
 * com.ts.lib.settings.ISettingsAdapterService, call getSettingsAdapterService("dms"),
 * talk to com.ts.lib.settings.dms.IDmsSettingsService. Values: 0 = off, 1 = on.
 * The service is exported with no permission and does no caller checks.
 */
final class DmsClient {
    interface Listener {
        void onConnected();
        void onDisconnected();
        /** which: "dms" (master), "fatigue", "attention". value 0 off, 1 on, -1 unknown. */
        void onSwitch(String which, int value);
    }

    static final String TAG = "NagLab";

    private static final String ADAPTER_DESC = "com.ts.lib.settings.ISettingsAdapterService";
    private static final int ADAPTER_GET_SERVICE = 2;

    private static final String DMS_DESC = "com.ts.lib.settings.dms.IDmsSettingsService";
    private static final int SET_SWITCH = 2;
    private static final int SET_FATIGUE = 4;
    private static final int SET_ATTENTION = 5;
    private static final int GET_SWITCH = 6;
    private static final int QUERY_FATIGUE = 7;
    private static final int QUERY_ATTENTION = 8;
    private static final int SUBSCRIBE = 11;
    private static final int UNSUBSCRIBE = 12;

    private static final String CB_DESC = "com.ts.lib.settings.dms.IDmsSettingsCallback";

    private final Context ctx;
    private final Listener listener;
    private volatile IBinder dms;
    private boolean bound;
    private volatile boolean closed = true;   // true between disconnect() and the next connect()

    private final IBinder.DeathRecipient death = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Log.w(TAG, "SettingsService died");
            dms = null;
            if (!closed) listener.onDisconnected();
        }
    };

    private final Binder callback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(CB_DESC);
                return true;
            }
            try {
                data.enforceInterface(CB_DESC);
            } catch (Throwable t) {
                return false;
            }
            String which = null;
            switch (code) {
                case 1: which = "dms"; break;
                case 2: which = "fatigue"; break;
                case 3: which = "attention"; break;
                default: break;
            }
            if (which != null) {
                int v = data.dataAvail() >= 4 ? data.readInt() : -1;
                if (!closed) listener.onSwitch(which, v);
            }
            if (reply != null) reply.writeNoException();
            return true;
        }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName n, final IBinder adapter) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    attach(adapter);
                }
            }, "naglab-attach").start();
        }

        @Override
        public void onServiceDisconnected(ComponentName n) {
            Log.w(TAG, "SettingsService disconnected");
            dms = null;
            if (!closed) listener.onDisconnected();
        }
    };

    DmsClient(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    boolean isReady() {
        return dms != null;
    }

    /** Bind (or re-bind). Safe to call repeatedly. */
    synchronized void connect() {
        if (dms != null) return;
        closed = false;
        if (bound) {
            try {
                ctx.unbindService(conn);
            } catch (Throwable ignored) {
            }
            bound = false;
        }
        Intent i = new Intent(ADAPTER_DESC);
        i.setClassName("com.ts.appservice.settings", "com.ts.appservice.settings.SettingsService");
        try {
            bound = ctx.bindService(i, conn, Context.BIND_AUTO_CREATE);
            Log.w(TAG, "bindService -> " + bound);
        } catch (Throwable t) {
            Log.w(TAG, "bindService failed: " + t);
        }
    }

    synchronized void disconnect() {
        closed = true;
        IBinder b = dms;
        dms = null;
        if (b != null) unsubscribe(b);
        if (bound) {
            try {
                ctx.unbindService(conn);
            } catch (Throwable ignored) {
            }
            bound = false;
        }
    }

    private void unsubscribe(IBinder b) {
        Parcel d = Parcel.obtain();
        try {
            d.writeInterfaceToken(DMS_DESC);
            d.writeStrongBinder(callback);
            b.transact(UNSUBSCRIBE, d, null, IBinder.FLAG_ONEWAY);
            b.unlinkToDeath(death, 0);
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
        }
    }

    private void attach(IBinder adapter) {
        IBinder b;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(ADAPTER_DESC);
            d.writeString("dms");
            adapter.transact(ADAPTER_GET_SERVICE, d, r, 0);
            r.readException();
            b = r.readStrongBinder();
        } catch (Throwable t) {
            Log.w(TAG, "getSettingsAdapterService(dms) failed: " + t);
            return;
        } finally {
            d.recycle();
            r.recycle();
        }
        if (b == null) {
            Log.w(TAG, "getSettingsAdapterService(dms) returned null");
            return;
        }
        d = Parcel.obtain();
        try {
            b.linkToDeath(death, 0);
            d.writeInterfaceToken(DMS_DESC);
            d.writeStrongBinder(callback);
            b.transact(SUBSCRIBE, d, null, IBinder.FLAG_ONEWAY);
        } catch (Throwable t) {
            Log.w(TAG, "subscribe failed: " + t);
            return;
        } finally {
            d.recycle();
        }
        synchronized (this) {
            // disconnect() may have run while we were attaching, or a second
            // onServiceConnected may have beaten us: drop this one quietly.
            if (closed || dms != null) {
                unsubscribe(b);
                return;
            }
            dms = b;
        }
        Log.w(TAG, "DMS service attached");
        listener.onConnected();
    }

    /** Ask for the current state; answers arrive through Listener.onSwitch.
     *  Non-short-circuit & on purpose: fire all three even if one fails. */
    boolean query() {
        return call(GET_SWITCH, null) & call(QUERY_FATIGUE, null) & call(QUERY_ATTENTION, null);
    }

    /** Same order as a hand toggle in BYD's menu ends up: sub-switches, then master. */
    boolean setAll(int v) {
        boolean ok = call(SET_FATIGUE, v);
        ok &= call(SET_ATTENTION, v);
        ok &= call(SET_SWITCH, v);
        Log.w(TAG, "setAll(" + v + ") ok=" + ok);
        return ok;
    }

    private boolean call(int code, Integer arg) {
        IBinder b = dms;
        if (b == null) return false;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DMS_DESC);
            if (arg != null) d.writeInt(arg);
            b.transact(code, d, r, 0);
            r.readException();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "transact " + code + " failed: " + t);
            return false;
        } finally {
            d.recycle();
            r.recycle();
        }
    }
}
