package au.jaso.naglab;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Big button: keep driver monitoring OFF (green) or leave it alone (grey).
 * Below it: the background helper (shell watchdog that survives BYD's force-stop at ACC off).
 * After a screen reboot the helper is gone: turn on Wireless ADB and open the app (it tries
 * by itself) or tap "Start background helper".
 * adb: am start -n au.jaso.naglab/.MainActivity --ez enable true|false
 */
public class MainActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Button toggle;
    private TextView status;
    private TextView helper;
    private Button helperBtn;
    private String helperMsg;
    private boolean autoTried;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(60, 40, 60, 40);
        root.setBackgroundColor(Color.rgb(18, 18, 20));

        TextView title = new TextView(this);
        title.setText("Driver monitoring");
        title.setTextSize(34);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        toggle = new Button(this);
        toggle.setTextSize(30);
        toggle.setAllCaps(false);
        toggle.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(760, 180);
        lp.topMargin = 30;
        lp.bottomMargin = 30;
        root.addView(toggle, lp);
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                NagService.setEnabled(MainActivity.this, !NagService.isEnabled(MainActivity.this));
                render();
            }
        });

        status = new TextView(this);
        status.setTextSize(20);
        status.setTextColor(Color.rgb(170, 170, 175));
        status.setGravity(Gravity.CENTER);
        root.addView(status);

        helper = new TextView(this);
        helper.setTextSize(20);
        helper.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hp.topMargin = 40;
        root.addView(helper, hp);

        helperBtn = new Button(this);
        helperBtn.setTextSize(22);
        helperBtn.setAllCaps(false);
        helperBtn.setTextColor(Color.WHITE);
        helperBtn.setBackgroundColor(Color.rgb(40, 90, 160));
        helperBtn.setText("Start background helper");
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(600, 120);
        bp.topMargin = 20;
        root.addView(helperBtn, bp);
        helperBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startHelper();
            }
        });

        setContentView(root);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handle(i);
    }

    private void handle(Intent i) {
        if (i != null && i.hasExtra("enable")) {
            NagService.setEnabled(this, i.getBooleanExtra("enable", false));
            i.removeExtra("enable");   // a recreated activity must not re-apply a stale extra
        } else if (NagService.isEnabled(this) && !NagService.running) {
            NagService.setEnabled(this, true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresh);
        // Opening the app after a screen reboot: try to bring the helper back by itself.
        if (!autoTried && NagService.isEnabled(this) && !HelperPing.isAlive(this)) {
            autoTried = true;
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!HelperPing.isAlive(MainActivity.this)) startHelper();
                }
            }, 4000);   // a running watchdog pings within ~2 s of restarting us
        }
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }

    private void startHelper() {
        boolean started = HelperSetup.start(this, true, new HelperSetup.Progress() {
            @Override
            public void step(final String text) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        helperMsg = text;
                        render();
                    }
                });
            }

            @Override
            public void done(boolean ok, String text) {
                step(text);
            }
        });
        if (!started) helperMsg = "Already working on it...";
        render();
    }

    private static String sw(int v) {
        return v == 0 ? "off" : v == 1 ? "ON" : "?";
    }

    private void render() {
        boolean on = NagService.isEnabled(this);
        toggle.setText(on ? "Keep it OFF: active" : "Not managed (tap to keep it off)");
        toggle.setBackgroundColor(on ? Color.rgb(32, 128, 72) : Color.rgb(70, 70, 76));
        StringBuilder s = new StringBuilder();
        if (!on) {
            s.append("The car's own setting is left alone.");
        } else if (!NagService.connected) {
            s.append("Connecting to the car...");
        } else {
            s.append("Car now: monitoring ").append(sw(NagService.dms))
                    .append(", fatigue ").append(sw(NagService.fatigue))
                    .append(", distraction ").append(sw(NagService.attention));
        }
        if (NagService.fixCount > 0) {
            s.append("\nTurned off ").append(NagService.fixCount).append("x, last at ")
                    .append(new SimpleDateFormat("HH:mm:ss", Locale.US)
                            .format(new Date(NagService.lastFixWall)));
        }
        status.setText(s);

        boolean alive = HelperPing.isAlive(this);
        boolean busy = HelperSetup.isBusy();
        StringBuilder h = new StringBuilder();
        if (alive) {
            h.append("Background helper: running");
            helper.setTextColor(Color.rgb(110, 200, 140));
        } else {
            h.append("Background helper: NOT running\n"
                    + "Without it, this stops working once the car is switched off.\n"
                    + "Turn on Wireless ADB, then tap Start.");
            helper.setTextColor(Color.rgb(235, 170, 80));
        }
        if (helperMsg != null && (busy || !alive)) {
            h.append("\n\n").append(helperMsg);
        } else if (helperMsg != null && alive) {
            h.append("\n").append(helperMsg);
        }
        helper.setText(h);
        helperBtn.setVisibility(alive || busy ? View.GONE : View.VISIBLE);
    }
}
