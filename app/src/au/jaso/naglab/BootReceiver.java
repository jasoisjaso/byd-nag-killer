package au.jaso.naglab;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Starts NagService after head-unit boot and after an app update, if enabled. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Log.w(DmsClient.TAG, "boot receiver: " + i.getAction());
        if (NagService.isEnabled(c)) {
            c.startForegroundService(new Intent(c, NagService.class));
        }
    }
}
