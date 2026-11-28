package au.jaso.naglab;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

/**
 * See-through placeholder for Open at start: opened in the left half to make the split when the
 * app meant for that half is already running, then the app's task is moved in on top of it.
 * Closes itself after 4.5 s. GhostRightActivity is the same for the right half (a separate
 * class so each half gets its own task and the dump can tell them apart).
 */
public class GhostLeftActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable done = this::finish;

    @Override
    protected void onResume() {
        super.onResume();
        h.removeCallbacks(done);
        h.postDelayed(done, 4500);
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacks(done);
        super.onDestroy();
    }
}
