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

import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Landscape screen for the car display (1920x990 px, 240 dpi = 1280x660 dp).
 * Left: icon, status against the owner's choices, the kill button + which nags it kills,
 * the background helper. Right: one row per nag (car's state + "Keep off" switch) and the
 * floating quick button.
 * Colours: green = silenced (switch on, pill Off, chip in the button), orange = a nag you
 * asked to keep off is on (being fixed / failed), grey = left on by your choice / unknown.
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
    private TextView dmsState, quickSub, laneState, toneState, cpdState, laneSub, cpdSub;
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
        st.setPadding(0, dp(28), 0, 0);
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

        // Which nags the button kills (the floating quick button kills the same set).
        LinearLayout cr = new LinearLayout(this);
        cr.setGravity(Gravity.CENTER);
        cr.setPadding(0, dp(16), 0, 0);
        for (final String n : NagService.NAGS) {
            TextView chip = text(shortName(n), 16, TEXT, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), 0, dp(14), 0);
            chip.setBackground(round(LINE, dp(20)));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, dp(40));
            clp.leftMargin = dp(5);
            clp.rightMargin = dp(5);
            cr.addView(chip, clp);
            chip.setOnClickListener(v -> {
                NagService.setInButton(this, n, !NagService.inButton(this, n));
                render();
            });
            chips.put(n, chip);
        }
        col.addView(cr, new LinearLayout.LayoutParams(-1, -2));
        TextView chipHint = text("Tap a name to add it to the button or take it out.", 14, DIM, false);
        chipHint.setGravity(Gravity.CENTER);
        chipHint.setPadding(0, dp(6), 0, 0);
        col.addView(chipHint, new LinearLayout.LayoutParams(-1, -2));

        stats = text("", 16, SUB, false);
        stats.setGravity(Gravity.CENTER);
        stats.setPadding(0, dp(12), 0, 0);
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
        hlp.topMargin = dp(28);
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
            pp.leftMargin = dp(16);   // keep long subtitles off the pill
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
        TextView c2 = section("Keep off");
        c2.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams c2p = new LinearLayout.LayoutParams(dp(116), -2);
        hd.addView(c2, c2p);
        col.addView(hd);
        LinearLayout nags = card();
        dmsToggle = new Toggle(this);
        dmsState = pill();
        nags.addView(row("Driver monitoring", "Fatigue and distraction alerts. Back on every start.",
                null, dmsState, dmsToggle));
        dmsToggle.setOnChange(on -> {
            NagService.setKilled(this, NagService.NAG_DMS, on);
            render();
        });
        nags.addView(divider());
        laneToggle = new Toggle(this);
        laneState = pill();
        TextView[] ls = new TextView[1];
        nags.addView(row("Lane assist", LANE_SUB, ls, laneState, laneToggle));
        laneSub = ls[0];
        laneToggle.setOnChange(on -> {
            NagService.setKilled(this, NagService.NAG_LANE, on);
            render();
        });
        nags.addView(divider());
        toneToggle = new Toggle(this);
        toneState = pill();
        nags.addView(row("Speed limit chime", "Limit-change tone and over-limit warning.",
                null, toneState, toneToggle));
        toneToggle.setOnChange(on -> {
            NagService.setKilled(this, NagService.NAG_CHIME, on);
            render();
        });
        nags.addView(divider());
        cpdToggle = new Toggle(this);
        cpdState = pill();
        TextView[] cs = new TextView[1];
        nags.addView(row("Child detection", CPD_SUB, cs, cpdState, cpdToggle));
        cpdSub = cs[0];
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
        col.addView(qc);

        return sv;
    }

    private static final String LANE_SUB = "Steering nudges and lane buzz.";
    private static final String CPD_SUB = "Alarm if a child is left in the car.";

    private static String shortName(String n) {
        switch (n) {
            case NagService.NAG_DMS: return "Monitoring";
            case NagService.NAG_LANE: return "Lane assist";
            case NagService.NAG_CHIME: return "Speed chime";
            default: return "Child alarm";
        }
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

    /** Car-state pill. text null = unknown. Off = green; on = orange only if the owner asked to
     *  keep it off (we are fixing it, or failed), else grey: on by the owner's choice. */
    private void setPill(TextView p, String text, boolean isOff, boolean wantOff) {
        if (text == null) {
            p.setText("...");
            p.setTextColor(SUB);
            tint(p, LINE);
        } else if (!isOff && !wantOff) {
            p.setText(text);
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
        boolean flashing = System.currentTimeMillis() < killFlashUntil;

        // Status (left), measured against what the owner asked for, not against "everything".
        boolean adasKnown = NagService.lane != Integer.MIN_VALUE;
        List<String> kept = new ArrayList<>(), fixing = new ArrayList<>(), leftOn = new ArrayList<>();
        for (String n : NagService.NAGS) {
            int st = NagService.state(n);
            boolean k = NagService.killed(this, n);
            if (k && st == 1) fixing.add(NagService.label(n));
            else if (k) kept.add(NagService.label(n));
            else if (st == 1) leftOn.add(NagService.label(n));
        }
        if (!NagService.running || (!connected && !adasKnown)) {
            headline.setText("Connecting");
            subline.setText("Talking to the car...");
            tint(statusDot, WARN);
        } else if (!fixing.isEmpty()) {
            headline.setText("Switching off");
            subline.setText(TextUtils.join(", ", fixing) + " came back on. Switching it off again...");
            tint(statusDot, ACCENT);
        } else if (kept.isEmpty()) {
            headline.setText("Not keeping anything off");
            subline.setText("Turn on a Keep off switch, or tap the button.");
            tint(statusDot, DIM);
        } else {
            headline.setText("All set");
            subline.setText("Kept off: " + TextUtils.join(", ", kept) + "."
                    + (leftOn.isEmpty() ? "" : "\nLeft on: " + TextUtils.join(", ", leftOn) + "."));
            tint(statusDot, OK);
        }

        // The button kills its set; it never switches anything on.
        List<String> set = new ArrayList<>();
        boolean setDone = true;
        for (String n : NagService.NAGS) {
            if (!NagService.inButton(this, n)) continue;
            set.add(shortName(n));
            if (!NagService.killed(this, n) || NagService.state(n) == 1) setDone = false;
        }
        String what = set.size() <= 2 ? TextUtils.join(" + ", set) : set.size() + " nags";
        killBtn.setText(flashing ? "Working..." : set.isEmpty() ? "Pick nags for the button"
                : setDone ? what + " off \u2713" : "Kill " + what);
        killBtn.setAlpha(setDone && !flashing ? 0.85f : 1f);

        String s = "";
        String last = NagService.lastKillSummary;
        if (last != null && System.currentTimeMillis() - NagService.lastSummaryWall < 60_000) {
            s = last;
        } else if (NagService.fixCount > 0) {
            s = "Driver monitoring switched off " + NagService.fixCount
                    + (NagService.fixCount == 1 ? " time" : " times") + " since start, last "
                    + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(NagService.lastFixWall));
        }
        stats.setText(s);

        // Rows: pill = what the car says, switch = Keep off.
        int dms = NagService.dms;
        dmsToggle.setOn(on, false);
        setPill(dmsState, !connected || dms < 0 ? null : NagService.state(NagService.NAG_DMS) == 0 ? "Off" : "On",
                NagService.state(NagService.NAG_DMS) == 0, on);

        boolean laneK = NagService.killed(this, NagService.NAG_LANE);
        laneToggle.setOn(laneK, false);
        int ln = NagService.lane;
        setPill(laneState, ln == Integer.MIN_VALUE ? null : ln == 0 ? "Off" : "On", ln == 0, laneK);
        String mode = ln == 1 ? "Warning" : ln == 2 ? "Prevent" : ln == 3 ? "Both" : null;
        laneSub.setText(mode != null ? LANE_SUB + " Mode: " + mode + "." : LANE_SUB);

        boolean toneK = NagService.killed(this, NagService.NAG_CHIME);
        toneToggle.setOn(toneK, false);
        int tn = NagService.tone, sl = NagService.slw;
        boolean chimeOff = tn == 0 && sl != 1;
        setPill(toneState, (tn < 0 && sl < 0) ? null : chimeOff ? "Off" : "On", chimeOff, toneK);

        boolean cpdK = NagService.killed(this, NagService.NAG_CPD);
        cpdToggle.setOn(cpdK, false);
        int cp = NagService.cpd;
        setPill(cpdState, cp < 0 ? null : cp == 0 ? "Off" : "On", cp == 0, cpdK);
        cpdSub.setText(cp == 1 ? CPD_SUB + " Mode: "
                + (NagService.cpdRaw == AdasClient.CPD_DELAY ? "Delay" : "Standard") + "." : CPD_SUB);

        for (String n : NagService.NAGS) {
            TextView chip = chips.get(n);
            boolean in = NagService.inButton(this, n);
            tint(chip, in ? 0xFF1E3A2E : LINE);
            chip.setTextColor(in ? OK : DIM);
            chip.setText(in ? "\u2713 " + shortName(n) : shortName(n));
        }

        // Quick button row
        boolean wantQuick = QuickButton.wanted(this);
        quickToggle.setOn(wantQuick, false);
        if (!QuickButton.allowed(this)) {
            quickSub.setText("Needs \"display over other apps\": turn Wireless ADB on and tap Start (background helper).");
            quickSub.setTextColor(WARN);
            quickToggle.setEnabled(false);
        } else {
            quickSub.setText("The same button, floating over CarPlay. Tap: kill. Hold, then drag: move it.");
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
