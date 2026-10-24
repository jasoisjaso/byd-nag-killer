package au.jaso.naglab;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Landscape screen for the car display (1920x990 px, 240 dpi = 1280x660 dp).
 * Left: icon, one-line status, big "Kill all nags now" button.
 * Right: what is kept off automatically, the floating quick button, the background helper.
 * adb: am start -n au.jaso.naglab/.MainActivity --ez enable true|false
 */
public class MainActivity extends Activity {
    // Palette: graphite + one warm accent (the icon's slash). Calm colours for "fine" states.
    static final int BG = 0xFF0F1013, CARD = 0xFF1A1C21, LINE = 0xFF26292F;
    static final int TEXT = 0xFFF4F5F7, SUB = 0xFF9BA0A8, DIM = 0xFF6B7078;
    static final int ACCENT = 0xFFF5552F, ACCENT2 = 0xFFFF7A45, OK = 0xFF7FD1A8, WARN = 0xFFF2B65A;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView headline, subline, stats, killBtn;
    private View statusDot;
    private Toggle dmsToggle, quickToggle;
    private TextView dmsState, quickSub;
    private View helperDot;
    private TextView helperTitle, helperSub, helperBtn;
    private String helperMsg;
    private boolean autoTried;
    private long killFlashUntil;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            ui.postDelayed(this, 1000);
        }
    };

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static GradientDrawable round(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private View dot(int sizeDp) {
        View d = new View(this);
        d.setBackground(round(DIM, dp(sizeDp) / 2f));
        d.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return d;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(48), dp(32), dp(48), dp(32));

        root.addView(buildLeft(), new LinearLayout.LayoutParams(0, -1, 0.92f));
        View gap = new View(this);
        root.addView(gap, new LinearLayout.LayoutParams(dp(40), 1));
        root.addView(buildRight(), new LinearLayout.LayoutParams(0, -1, 1.08f));

        setContentView(root);
        handle(getIntent());
    }

    private View buildLeft() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.hero);
        brand.addView(icon, new LinearLayout.LayoutParams(dp(72), dp(72)));
        TextView name = text("Nag Killer", 30, TEXT, true);
        name.setPadding(dp(18), 0, 0, 0);
        brand.addView(name);
        col.addView(brand);

        LinearLayout st = new LinearLayout(this);
        st.setGravity(Gravity.CENTER_VERTICAL);
        st.setPadding(0, dp(40), 0, 0);
        statusDot = dot(14);
        st.addView(statusDot);
        headline = text("", 40, TEXT, true);
        headline.setPadding(dp(16), 0, 0, 0);
        st.addView(headline);
        col.addView(st);

        subline = text("", 19, SUB, false);
        subline.setPadding(0, dp(8), 0, 0);
        col.addView(subline);

        killBtn = text("Kill all nags now", 24, Color.WHITE, true);
        killBtn.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{ACCENT2, ACCENT});
        g.setCornerRadius(dp(44));
        killBtn.setBackground(g);
        killBtn.setElevation(dp(4));
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(-1, dp(88));
        kp.topMargin = dp(40);
        col.addView(killBtn, kp);
        killBtn.setOnClickListener(v -> {
            if (!NagService.isEnabled(this)) NagService.setEnabled(this, true);
            NagService.send(this, NagService.ACTION_KILL_ALL);
            killFlashUntil = System.currentTimeMillis() + 2500;
            v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(140));
            render();
        });

        stats = text("", 16, DIM, false);
        stats.setGravity(Gravity.CENTER);
        stats.setPadding(0, dp(14), 0, 0);
        col.addView(stats, new LinearLayout.LayoutParams(-1, -2));
        return col;
    }

    private TextView section(String s) {
        TextView t = text(s.toUpperCase(Locale.US), 13, DIM, true);
        t.setLetterSpacing(0.12f);
        t.setPadding(dp(4), dp(22), 0, dp(10));
        return t;
    }

    /** Card row: title + subtitle on the left, optional state pill, toggle on the right. */
    private LinearLayout row(String title, String sub, TextView[] subOut, TextView pill, Toggle t) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(24), dp(18), dp(20), dp(18));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.addView(text(title, 21, TEXT, true));
        TextView s = text(sub, 15, SUB, false);
        s.setPadding(0, dp(3), 0, 0);
        txt.addView(s);
        if (subOut != null) subOut[0] = s;
        r.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
        if (pill != null) {
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2);
            pp.rightMargin = dp(18);
            r.addView(pill, pp);
        }
        if (t != null) r.addView(t, new LinearLayout.LayoutParams(dp(76), dp(42)));
        return r;
    }

    private TextView pill() {
        TextView p = text("", 14, TEXT, true);
        p.setPadding(dp(14), dp(5), dp(14), dp(5));
        p.setBackground(round(LINE, dp(14)));
        return p;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, dp(20)));
        return c;
    }

    private View divider() {
        View d = new View(this);
        d.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        lp.leftMargin = dp(24);
        d.setLayoutParams(lp);
        return d;
    }

    private View buildRight() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        sv.addView(col, new FrameLayout.LayoutParams(-1, -2));
        sv.setFillViewport(true);

        col.addView(section("Keep off automatically"));
        LinearLayout nags = card();
        dmsToggle = new Toggle(this);
        dmsState = pill();
        nags.addView(row("Driver monitoring", "Fatigue and distraction alerts. Back on at every start, so we switch it off again.",
                null, dmsState, dmsToggle));
        dmsToggle.setOnChange(on -> {
            NagService.setEnabled(this, on);
            render();
        });
        col.addView(nags);

        col.addView(section("On screen"));
        LinearLayout qc = card();
        quickToggle = new Toggle(this);
        TextView[] qs = new TextView[1];
        qc.addView(row("Quick button", "", qs, null, quickToggle));
        quickSub = qs[0];
        quickToggle.setOnChange(on -> {
            getSharedPreferences(NagService.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(QuickButton.KEY_SHOW, on).apply();
            NagService.send(this, NagService.ACTION_SYNC);
            render();
        });
        col.addView(qc);

        col.addView(section("Background"));
        LinearLayout hc = card();
        LinearLayout hr = new LinearLayout(this);
        hr.setGravity(Gravity.CENTER_VERTICAL);
        hr.setPadding(dp(24), dp(18), dp(20), dp(18));
        helperDot = dot(12);
        hr.addView(helperDot);
        LinearLayout ht = new LinearLayout(this);
        ht.setOrientation(LinearLayout.VERTICAL);
        ht.setPadding(dp(16), 0, 0, 0);
        helperTitle = text("", 19, TEXT, true);
        helperSub = text("", 15, SUB, false);
        helperSub.setPadding(0, dp(3), 0, 0);
        ht.addView(helperTitle);
        ht.addView(helperSub);
        hr.addView(ht, new LinearLayout.LayoutParams(0, -2, 1f));
        helperBtn = text("Start", 17, Color.WHITE, true);
        helperBtn.setGravity(Gravity.CENTER);
        helperBtn.setPadding(dp(22), 0, dp(22), 0);
        helperBtn.setBackground(round(0xFF2F333A, dp(22)));
        hr.addView(helperBtn, new LinearLayout.LayoutParams(-2, dp(44)));
        helperBtn.setOnClickListener(v -> startHelper());
        hc.addView(hr);
        col.addView(hc);
        return sv;
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
            ui.postDelayed(() -> {
                if (!HelperPing.isAlive(MainActivity.this)) startHelper();
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
                ui.post(() -> {
                    helperMsg = text;
                    render();
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

    private static void tint(View v, int color) {
        ((GradientDrawable) v.getBackground()).setColor(color);
    }

    private void render() {
        boolean on = NagService.isEnabled(this);
        boolean connected = NagService.connected;
        int dms = NagService.dms;
        boolean flashing = System.currentTimeMillis() < killFlashUntil;

        // Status (left)
        if (!on) {
            headline.setText("Paused");
            subline.setText("The car's own settings are left alone.");
            tint(statusDot, DIM);
        } else if (!connected) {
            headline.setText("Connecting");
            subline.setText("Talking to the car's settings service...");
            tint(statusDot, WARN);
        } else if (dms == 1 || NagService.fatigue == 1 || NagService.attention == 1) {
            headline.setText("Switching off");
            subline.setText("The car turned driver monitoring back on. Turning it off.");
            tint(statusDot, ACCENT);
        } else {
            headline.setText("All quiet");
            subline.setText("Driver monitoring is off and stays off.");
            tint(statusDot, OK);
        }
        killBtn.setText(flashing ? "Done" : "Kill all nags now");

        StringBuilder s = new StringBuilder();
        if (NagService.fixCount > 0) {
            s.append("Switched off ").append(NagService.fixCount)
                    .append(NagService.fixCount == 1 ? " time" : " times").append(" since start, last ")
                    .append(new SimpleDateFormat("HH:mm", Locale.US).format(new Date(NagService.lastFixWall)));
        } else if (on && connected) {
            s.append("Nothing to switch off yet");
        }
        stats.setText(s);

        // Driver monitoring row
        dmsToggle.setOn(on, false);
        if (!on || !connected || dms < 0) {
            dmsState.setText(on ? "..." : "Car decides");
            dmsState.setTextColor(SUB);
            tint(dmsState, LINE);
        } else if (dms == 0 && NagService.fatigue != 1 && NagService.attention != 1) {
            dmsState.setText("Off");
            dmsState.setTextColor(OK);
            tint(dmsState, 0xFF1E3A2E);
        } else {
            dmsState.setText("On");
            dmsState.setTextColor(ACCENT2);
            tint(dmsState, 0xFF40231B);
        }

        // Quick button row
        boolean wantQuick = QuickButton.wanted(this);
        quickToggle.setOn(wantQuick, false);
        if (!QuickButton.allowed(this)) {
            quickSub.setText("Needs the \"display over other apps\" permission: re-run install.sh from the computer.");
            quickSub.setTextColor(WARN);
            quickToggle.setEnabled(false);
        } else {
            quickSub.setText(on
                    ? "Floats over CarPlay and maps. Tap: kill all nags. Hold, then drag: move it."
                    : "Floats over CarPlay and maps. Shows while Nag Killer is on.");
            quickSub.setTextColor(SUB);
            quickToggle.setEnabled(true);
        }

        // Background helper row
        boolean alive = HelperPing.isAlive(this);
        boolean busy = HelperSetup.isBusy();
        if (alive) {
            tint(helperDot, OK);
            helperTitle.setText("Background helper running");
            helperSub.setText("Comes back by itself after the car or the screen restarts.");
        } else if (busy) {
            tint(helperDot, WARN);
            helperTitle.setText("Starting background helper");
            helperSub.setText(helperMsg != null ? helperMsg : "...");
        } else {
            tint(helperDot, WARN);
            helperTitle.setText("Background helper not running");
            helperSub.setText(helperMsg != null ? helperMsg
                    : "Driver monitoring still gets switched off. Turn on Wireless ADB and tap Start for the full setup.");
        }
        helperBtn.setVisibility(alive || busy ? View.GONE : View.VISIBLE);
    }
}
