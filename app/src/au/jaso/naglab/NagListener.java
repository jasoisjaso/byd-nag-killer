package au.jaso.naglab;

import android.content.Intent;
import android.service.notification.NotificationListenerService;
import android.util.Log;

/**
 * Revive hook, not a notification reader. Once granted notification access
 * (cmd notification allow_listener au.jaso.naglab/au.jaso.naglab.NagListener), the system
 * itself binds this service at boot and re-binds it after the process dies. That starts our
 * process with no ADB and no watchdog, even after BYD's force-stop or a head-unit reboot.
 * We only use the moment to start NagService, which re-applies DMS off and restarts the
 * shell watchdog over local ADB if it is gone.
 */
public class NagListener extends NotificationListenerService {
    @Override
    public void onListenerConnected() {
        Log.w(DmsClient.TAG, "listener connected (system revive)");
        if (NagService.shouldRun(this)) {
            try {
                startForegroundService(new Intent(this, NagService.class));
            } catch (RuntimeException e) {
                Log.w(DmsClient.TAG, "listener: could not start NagService: " + e);
            }
        }
    }

    @Override
    public void onListenerDisconnected() {
        Log.w(DmsClient.TAG, "listener disconnected, asking for rebind");
        requestRebind(new android.content.ComponentName(this, NagListener.class));
    }
}
