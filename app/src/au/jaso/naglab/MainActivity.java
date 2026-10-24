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
    private Toggle dmsToggle, quickToggle, laneToggle, toneToggle, cpdToggle;
    private TextView dmsState, quickSub, laneState, toneState, cpdState;
    private final java.util.Map<String, TextView> chips = new java.util.HashMap<>();
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
            NagService.killNow(this);
            killFlashUntil = System.currentTimeMillis() + 2500;
            v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(140));
            render();
        });

        stats = text("", 16, DIM, false);
        stats.setGravity(Gravity.CENTER);
        stats.setPadding(0, dp(14), 0, 0);
        col.addView(stats, new LinearLayout.LayoutParams(-1, -2));
        // Background helper: one quiet line at the bottom (a card only when it needs you).
        LinearLayout hr = new LinearLayout(this);
        hr.setGravity(Gravity.CENTER_VERTICAL);
        helperDot = dot(10);
        hr.addView(helperDot);
        LinearLayout ht = new LinearLayout(this);
        ht.setOrientation(LinearLayout.VERTICAL);
        ht.setPadding(dp(12), 0, 0, 0);
        helperTitle = text("", 15, SUB, true);
        helperSub = text("", 13, DIM, false);
        ht.addView(helperTitle);
        ht.addView(helperSub);
        hr.addView(ht, new LinearLayout.LayoutParams(0, -2, 1f));
        helperBtn = text("Start", 16, Color.WHITE, true);
        helperBtn.setGravity(Gravity.CENTER);
        helperBtn.setPadding(dp(20), 0, dp(20), 0);
        helperBtn.setBackground(round(0xFF2F333A, dp(20)));
        hr.addView(helperBtn, new LinearLayout.LayoutParams(-2, dp(40)));
        helperBtn.setOnClickListener(v -> startHelper());
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = dp(36);
        col.addView(hr, hlp);
        return col;
    }

    private TextView section(String s) {
        TextView t = text(s.toUpperCase(Locale.US), 13, DIM, true);
        t.setLetterSpacing(0.12f);
        t.setPadding(dp(4), dp(14), 0, dp(8));
        return t;
    }

    /** Card row: title + subtitle on the left, optional state pill, toggle on the right. */
    private LinearLayout row(String title, String sub, TextView[] subOut, TextView pill, Toggle t) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(24), dp(13), dp(20), dp(13));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.addView(text(title, 21, TEXT, true));
        TextView s = text(sub, 15, SUB, false);
        s.setPadding(0, dp(3), 0, 0);
        txt.addView(s);
        if (subOut != null) subOut[0] = s;
        r.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
        if (pill != null) {
            pill.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(96), -2);
            pp.rightMargin = dp(14);
            r.addView(pill, pp);
        }
        if (t != null) r.addView(t, new LinearLayout.LayoutParams(dp(76), dp(42)));
        return r;
    }

    private TextView pill() {
        TextView p = text("", 14, TEXT, true);
        p.setPadding(dp(6), dp(5), dp(6), dp(5));
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

        // Header with column labels so the pill (car's current state) is not read as the switch.
        LinearLayout hd = new LinearLayout(this);
        hd.setGravity(Gravity.BOTTOM);
        hd.addView(section("Nags"), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView c1 = section("Car now");
        c1.setGravity(Gravity.CENTER);
        hd.addView(c1, new LinearLayout.LayoutParams(dp(84), -2));
        TextView c2 = section("Kill");
        c2.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams c2p = new LinearLayout.LayoutParams(dp(116), -2);
        hd.addView(c2, c2p);
        col.addView(hd);
        LinearLayout nags = card();
        dmsToggle = new Toggle(this);
        dmsState = pill();
        nags.addView(row("Driver monitoring", "Fatigue and distraction alerts. The car re-arms it every start.",
                null, dmsState, dmsToggle));
        dmsToggle.setOnChange(on -> {
            NagService.setKilled(this, NagService.NAG_DMS, on);
            render();
        });
        nags.addView(divider());
        laneToggle = new Toggle(this);
        laneState = pill();
        nags.addView(row("Lane assist", "Steering nudges and lane-departure buzz.",
                null, laneState, laneToggle));
        laneToggle.setOnChange(on -> {
            NagService.setKilled(this, NagService.NAG_LANE, on);
            render();
        });
        nags.addView(divider());
        toneToggle = new Toggle(this);
        toneState = pill();
        nags.addView(row("Speed limit chime", "The tone when the limit changes, and the over-limit warning.",
                null, toneState, toneToggle));
        toneToggle.setOnChange(on -> {
            NagService.setKilled(this, NagService.NAG_CHIME, on);
            render();
        });
        nags.addView(divider());
        cpdToggle = new Toggle(this);
        cpdState = pill();
        nags.addView(row("Child detection", "Rear-seat alarm if a child is left in the car.",
                null, cpdState, cpdToggle));
        cpdToggle.setOnChange(on -> {
            NagService.setKilled(this, NagService.NAG_CPD, on);
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
            NagService.sync(this);
            render();
        });
        // Which nags one tap kills (same set for the big button on this screen).
        LinearLayout cr = new LinearLayout(this);
        cr.setGravity(Gravity.CENTER_VERTICAL);
        cr.setPadding(dp(24), 0, dp(20), dp(16));
        cr.addView(text("Kills", 15, DIM, true));
        for (final String n : NagService.NAGS) {
            String short_ = NagService.NAG_DMS.equals(n) ? "Monitoring"
                    : NagService.NAG_LANE.equals(n) ? "Lane assist"
                    : NagService.NAG_CHIME.equals(n) ? "Speed chime" : "Child detection";
            TextView chip = text(short_, 15, TEXT, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), 0, dp(14), 0);
            chip.setBackground(round(LINE, dp(18)));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, dp(36));
            clp.leftMargin = dp(10);
            cr.addView(chip, clp);
            chip.setOnClickListener(v -> {
                NagService.setInButton(this, n, !NagService.inButton(this, n));
                render();
            });
            chips.put(n, chip);
        }
        qc.addView(cr);
        col.addView(qc);

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
        } else {
            NagService.sync(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        NagService.uiOpen = true;
        NagService.sync(this);   // live car states while the screen is open
        ui.post(refresh);
        // Opening the app after a screen reboot: try to bring the helper back by itself.
        if (!autoTried && NagService.shouldRun(this) && !HelperPing.isAlive(this)) {
            autoTried = true;
            ui.postDelayed(() -> {
                if (!HelperPing.isAlive(MainActivity.this)) startHelper();
            }, 4000);   // a running watchdog pings within ~2 s of restarting us
        }
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresh);
        NagService.uiOpen = false;
        NagService.sync(this);   // stops the service again if nothing else needs it
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

    /** text null = unknown (grey "..."); otherwise calm when off, warm when on. */
    private void setPill(TextView p, String text, boolean isOff) {
        if (text == null) {
            p.setText("...");
            p.setTextColor(SUB);
            tint(p, LINE);
        } else if (isOff) {
            p.setText(text);
            p.setTextColor(OK);
            tint(p, 0xFF1E3A2E);
        } else {
            p.setText(text);
            p.setTextColor(ACCENT2);
            tint(p, 0xFF40231B);
        }
    }

    private static void tint(View v, int color) {
        ((GradientDrawable) v.getBackground()).setColor(color);
    }

    private void render() {
        boolean on = NagService.isEnabled(this);
        boolean connected = NagService.connected;
        int dms = NagService.dms;
        boolean flashing = System.currentTimeMillis() < killFlashUntil;

        // Status (left): a summary of every nag the car reports right now.
        boolean adasKnown = NagService.lane != Integer.MIN_VALUE;
        java.util.List<String> onNow = new java.util.ArrayList<>();
        for (String n : NagService.NAGS) if (NagService.state(n) == 1) onNow.add(NagService.label(n));
        if (!NagService.running || (!connected && !adasKnown)) {
            headline.setText("Connecting");
            subline.setText("Talking to the car...");
            tint(statusDot, WARN);
        } else if (onNow.isEmpty()) {
            headline.setText("All quiet");
            subline.setText(on ? "Every nag is off. Driver monitoring is kept off."
                    : "Every nag is off right now.");
            tint(statusDot, OK);
        } else {
            headline.setText(onNow.size() == 1 ? "1 nag on" : onNow.size() + " nags on");
            subline.setText(android.text.TextUtils.join(", ", onNow) + ".");
            tint(statusDot, ACCENT);
        }
        boolean setKilled = true, anyInSet = false;
        for (String n : NagService.NAGS) {
            if (!NagService.inButton(this, n)) continue;
            anyInSet = true;
            if (!NagService.killed(this, n) || NagService.state(n) == 1) setKilled = false;
        }
        killBtn.setText(flashing ? "Working..." : !anyInSet ? "Pick nags below"
                : setKilled ? "Bring nags back" : "Kill nags now");

        StringBuilder s = new StringBuilder();
        String last = NagService.lastKillSummary;
        if (last != null && System.currentTimeMillis() - NagService.lastKillAllWall < 120_000) {
            s.append(last);
        } else if (NagService.fixCount > 0) {
            s.append("Switched off ").append(NagService.fixCount)
                    .append(NagService.fixCount == 1 ? " time" : " times").append(" since start, last ")
                    .append(new SimpleDateFormat("HH:mm", Locale.US).format(new Date(NagService.lastFixWall)));
        } else if (on && connected) {
            s.append("Driver monitoring: nothing to switch off yet");
        }
        stats.setText(s);

        // Driver monitoring row
        dmsToggle.setOn(on, false);
        if (!connected || dms < 0) {
            dmsState.setText("...");
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

        // Lane assist + speed limit rows (the switch = keep it off automatically)
        laneToggle.setOn(NagService.pref(this, NagService.KEY_LANE_OFF), false);
        int ln = NagService.lane;
        setPill(laneState, ln == Integer.MIN_VALUE ? null
                : ln == 0 ? "Off" : ln == 1 ? "Warning" : ln == 2 ? "Prevent" : ln == 3 ? "Both" : "?", ln == 0);
        toneToggle.setOn(NagService.pref(this, NagService.KEY_TONE_OFF), false);
        int tn = NagService.tone, sl = NagService.slw;
        boolean chimeOff = tn == 0 && sl != 1;
        setPill(toneState, (tn < 0 && sl < 0) ? null : chimeOff ? "Off" : "On", chimeOff);

        cpdToggle.setOn(NagService.pref(this, NagService.KEY_CPD_OFF), false);
        int cp = NagService.cpd;
        boolean cpdHere = NagService.available(NagService.NAG_CPD);
        if (!cpdHere) {
            cpdState.setText("Not fitted");
            cpdState.setTextColor(DIM);
            tint(cpdState, LINE);
        } else {
            setPill(cpdState, cp < 0 ? null : cp == 0 ? "Off"
                    : NagService.cpdRaw == 3 ? "Delay" : "Standard", cp == 0);
        }
        cpdToggle.setEnabled(cpdHere);
        chips.get(NagService.NAG_CPD).setVisibility(cpdHere ? View.VISIBLE : View.GONE);

        for (String n : NagService.NAGS) {
            TextView chip = chips.get(n);
            boolean in = NagService.inButton(this, n);
            tint(chip, in ? 0xFF40231B : LINE);
            chip.setTextColor(in ? ACCENT2 : DIM);
        }

        // Quick button row
        boolean wantQuick = QuickButton.wanted(this);
        quickToggle.setOn(wantQuick, false);
        if (!QuickButton.allowed(this)) {
            quickSub.setText("Needs the \"display over other apps\" permission: re-run install.sh from the computer.");
            quickSub.setTextColor(WARN);
            quickToggle.setEnabled(false);
        } else {
            quickSub.setText("Floats over CarPlay. Tap: kill the nags below. Hold, then drag: move it.");
            quickSub.setTextColor(SUB);
            quickToggle.setEnabled(true);
        }

        // Background helper row
        boolean alive = HelperPing.isAlive(this);
        boolean busy = HelperSetup.isBusy();
        if (alive) {
            tint(helperDot, OK);
            helperTitle.setText("Background helper running");
            helperSub.setText("Comes back by itself after a restart.");
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
