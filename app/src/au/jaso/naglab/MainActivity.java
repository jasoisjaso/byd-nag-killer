package au.jaso.naglab;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Landscape screen for the car display (1920x990 px, 240 dpi = 1280x660 dp). One screen,
 * nothing below the fold at the default font size.
 * Left: icon + version (long-press = About), status, the "Switch off now" button (switches
 * off the button set), the quick button switch, the background helper.
 * Right: one row per nag: car's state pill, Button tick (in the button set or not), Keep off
 * word + switch. Keep off = switched off at every start; the Button column = what both buttons
 * switch off on a tap (its own set).
 * Colours: orange = kept off (switch on, pill Off), amber + "!" = a nag you keep off is on
 * (being fixed / failed), grey = normal (left as the car has it) or unknown.
 * adb: am start -n au.jaso.naglab/.MainActivity --ez enable true|false
 */
public class MainActivity extends Activity {
    // Palette: graphite + the icon's orange (#e2620e). Contrast against CARD in brackets.
    static final int BG = 0xFF0F1013, CARD = 0xFF1A1C21, LINE = 0xFF26292F, RAISED = 0xFF2F333A;
    static final int TEXT = 0xFFF4F5F7;       // body text (15.6:1)
    static final int SUB = 0xFF9BA0A8;        // secondary text (6.5:1)
    static final int DIM = 0xFF6B7078;        // UI only, never text: off track, outlines (3.4:1)
    static final int ACCENT = 0xFFE2620E;     // icon orange: fills (4.9:1), dark text on it 5.4:1
    static final int ACCENT_TEXT = 0xFFF2894A; // orange text on dark (6.9:1); also the pressed fill
    static final int ACCENT_DEEP = 0xFF3A2414; // tint behind orange text (5.9:1 with ACCENT_TEXT)
    static final int WARN = 0xFFF2B65A;       // needs attention (9.4:1), always with a word or "!"

    /** "Connecting" / "..." give up after this and say so (unsupported car, service missing). */
    private static final long CONNECT_TIMEOUT_MS = 20_000;
    private static final long FADE_MS = 150;
    private static final String REPO = "github.com/jasoisjaso/byd-nag-killer";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView headline, subline, stats, killBtn;
    private String headlineWant, sublineWant;
    private Mark statusMark, helperMark;
    private Toggle dmsToggle, quickToggle, laneToggle, toneToggle, cpdToggle, fctbToggle, elkaToggle, aebToggle;
    private TextView dmsState, laneState, toneState, cpdState, fctbState, elkaState, aebState, laneSub, cpdSub, quickSub;
    private TextView dmsWord, laneWord, toneWord, cpdWord, fctbWord, elkaWord, aebWord, quickWord;
    private final Map<String, TextView> buttonCells = new HashMap<>();
    private TextView helperTitle, helperSub, helperBtn;
    private TextView seatSub;
    private String helperMsg;
    private boolean autoTried;
    /** Wall time of the last big-button tap; "Switching off..." until a newer report lands. */
    private long killSentWall;
    private static final long KILL_TIMEOUT_MS = 10_000;
    /** When the screen last started waiting for the car (0 = not waiting). */
    private long connectingSince;
    private long resumedWall;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            ui.postDelayed(this, working() ? 250 : 1000);
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

    private Mark mark(int sizeDp) {
        Mark m = new Mark(this);
        m.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return m;
    }

    /** Pressed state for a filled button, plus a dimmer look when disabled. */
    private StateListDrawable buttonBg(int normal, int pressed, int disabled, float radius, int stroke) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable d = round(disabled, radius);
        GradientDrawable p = round(pressed, radius);
        GradientDrawable n = round(normal, radius);
        if (stroke != 0) {
            p.setStroke(dp(2), stroke);
            n.setStroke(dp(2), stroke);
        }
        s.addState(new int[]{-android.R.attr.state_enabled}, d);
        s.addState(new int[]{android.R.attr.state_pressed}, p);
        s.addState(new int[]{}, n);
        return s;
    }

    private RippleDrawable ripple() {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null,
                new ColorDrawable(0xFFFFFFFF));
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

        root.addView(buildLeft(), new LinearLayout.LayoutParams(0, -1, 0.85f));
        View gap = new View(this);
        root.addView(gap, new LinearLayout.LayoutParams(dp(32), 1));
        root.addView(buildRight(), new LinearLayout.LayoutParams(0, -1, 1.15f));

        setContentView(root);
        handle(getIntent());
    }

    /** Vertical column that scrolls only if it has to (large system font). */
    private ScrollView scroller(LinearLayout col) {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(true);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        sv.addView(col, new FrameLayout.LayoutParams(-1, -2));
        return sv;
    }

    private String version() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private View buildLeft() {
        LinearLayout col = new LinearLayout(this);
        ScrollView sv = scroller(col);

        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.hero);
        brand.addView(icon, new LinearLayout.LayoutParams(dp(72), dp(72)));
        LinearLayout nv = new LinearLayout(this);
        nv.setOrientation(LinearLayout.VERTICAL);
        nv.setPadding(dp(18), 0, 0, 0);
        nv.addView(text("Nag Killer", 30, TEXT, true));
        String link = "About & car report";
        android.text.SpannableString vs = new android.text.SpannableString(version() + "   " + link);
        int ls = vs.length() - link.length();
        vs.setSpan(new android.text.style.UnderlineSpan(), ls, vs.length(), 0);
        vs.setSpan(new android.text.style.ForegroundColorSpan(ACCENT), ls, vs.length(), 0);
        TextView ver = text("", 16, SUB, false);
        ver.setText(vs);
        ver.setPadding(dp(1), 0, 0, 0);
        nv.addView(ver);
        brand.addView(nv);
        brand.setContentDescription("Nag Killer " + version() + ", about and car report");
        brand.setClickable(true);
        brand.setOnClickListener(v -> about());
        col.addView(brand);

        LinearLayout st = new LinearLayout(this);
        st.setGravity(Gravity.CENTER_VERTICAL);
        st.setPadding(0, dp(24), 0, 0);
        statusMark = mark(26);
        st.addView(statusMark);
        headline = text("", 40, TEXT, true);
        headline.setPadding(dp(16), 0, 0, 0);
        st.addView(headline);
        col.addView(st);

        subline = text("", 19, SUB, false);
        subline.setPadding(0, dp(8), 0, 0);
        col.addView(subline);

        killBtn = new Button(this);   // Button: TalkBack says "button", disabled state is read out
        killBtn.setAllCaps(false);
        killBtn.setTextSize(24);
        killBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        killBtn.setTextColor(new ColorStateList(
                new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{SUB, BG}));
        killBtn.setGravity(Gravity.CENTER);
        killBtn.setStateListAnimator(null);
        killBtn.setBackground(buttonBg(ACCENT, ACCENT_TEXT, RAISED, dp(44), 0));
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(-1, dp(88));
        kp.topMargin = dp(24);
        col.addView(killBtn, kp);
        killBtn.setOnClickListener(v -> {
            NagService.killNow(this);
            killSentWall = System.currentTimeMillis();
            v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(140));
            ui.removeCallbacks(refresh);
            ui.post(refresh);   // faster refresh until the report lands
        });

        // Under the button: the hint when nothing is picked, else the last result.
        stats = text("", 16, SUB, false);
        stats.setGravity(Gravity.CENTER);
        stats.setPadding(0, dp(12), 0, 0);
        col.addView(stats, new LinearLayout.LayoutParams(-1, -2));

        // Quick button: lives under the big button it floats.
        LinearLayout qr = new LinearLayout(this);
        qr.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout qt = new LinearLayout(this);
        qt.setOrientation(LinearLayout.VERTICAL);
        qt.setPadding(0, 0, dp(12), 0);
        qt.addView(text("Quick button", 17, TEXT, true));
        quickSub = text("", 15, SUB, false);
        quickSub.setPadding(0, dp(2), 0, 0);
        qt.addView(quickSub);
        qr.addView(qt, new LinearLayout.LayoutParams(0, -2, 1f));
        quickWord = word();
        quickWord.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams qw = new LinearLayout.LayoutParams(dp(WORD_W), -2);
        qw.rightMargin = dp(WORD_GAP);
        qr.addView(quickWord, qw);
        quickToggle = new Toggle(this);
        quickToggle.setLabel("Quick button");
        qr.addView(quickToggle, new LinearLayout.LayoutParams(dp(SWITCH_W), dp(SWITCH_H)));
        qr.setOnClickListener(v -> quickToggle.flip());
        qr.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        quickToggle.setOnChange(on -> {
            getSharedPreferences(NagService.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(QuickButton.KEY_SHOW, on).apply();
            NagService.sync(this);
            render();
        });
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-1, -2);
        qlp.topMargin = dp(18);
        col.addView(qr, qlp);

        // Seat fans: one row, the levels are picked in a box (big buttons).
        LinearLayout sr = new LinearLayout(this);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout stt = new LinearLayout(this);
        stt.setOrientation(LinearLayout.VERTICAL);
        stt.setPadding(0, 0, dp(12), 0);
        stt.addView(text("Seat fans", 17, TEXT, true));
        seatSub = text("", 15, SUB, false);
        seatSub.setPadding(0, dp(2), 0, 0);
        stt.addView(seatSub);
        sr.addView(stt, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView seatBtn = text("Change", 18, TEXT, true);
        seatBtn.setGravity(Gravity.CENTER);
        seatBtn.setPadding(dp(28), 0, dp(28), 0);
        seatBtn.setMinWidth(dp(112));
        seatBtn.setBackground(buttonBg(RAISED, LINE, RAISED, dp(28), DIM));
        seatBtn.setClickable(true);
        seatBtn.setContentDescription("Change seat fans");
        sr.addView(seatBtn, new LinearLayout.LayoutParams(-2, dp(56)));
        seatBtn.setOnClickListener(v -> seatDialog());
        sr.setOnClickListener(v -> seatDialog());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(18);
        col.addView(sr, slp);

        // Background helper: one quiet line at the bottom.
        LinearLayout hr = new LinearLayout(this);
        hr.setGravity(Gravity.CENTER_VERTICAL);
        helperMark = mark(20);
        hr.addView(helperMark);
        LinearLayout ht = new LinearLayout(this);
        ht.setOrientation(LinearLayout.VERTICAL);
        ht.setPadding(dp(14), 0, dp(12), 0);
        helperTitle = text("", 17, TEXT, true);
        helperSub = text("", 15, SUB, false);
        helperSub.setPadding(0, dp(2), 0, 0);
        ht.addView(helperTitle);
        ht.addView(helperSub);
        hr.addView(ht, new LinearLayout.LayoutParams(0, -2, 1f));
        helperBtn = text("Start", 18, TEXT, true);
        helperBtn.setGravity(Gravity.CENTER);
        helperBtn.setPadding(dp(28), 0, dp(28), 0);
        helperBtn.setMinWidth(dp(112));
        helperBtn.setBackground(buttonBg(RAISED, LINE, RAISED, dp(28), DIM));
        helperBtn.setClickable(true);
        helperBtn.setContentDescription("Start background helper");
        hr.addView(helperBtn, new LinearLayout.LayoutParams(-2, dp(56)));
        helperBtn.setOnClickListener(v -> startHelper());
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = dp(18);
        col.addView(hr, hlp);

        return sv;
    }

    private static final String[] FAN_WORDS = {"Off", "Low", "High"};

    private String seatSummary() {
        int d = NagService.seatPick(this, true), p = NagService.seatPick(this, false);
        if (d == AdasClient.FAN_OFF && p == AdasClient.FAN_OFF) return "Not switched on at start.";
        return "Driver " + FAN_WORDS[d] + " · Passenger " + FAN_WORDS[p] + ". Switched on at every start.";
    }

    /** Off / Low / High per seat. A tap saves it and switches that seat now. */
    private void seatDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(28), dp(8), dp(28), dp(8));
        TextView note = text("The app switches these on each time you start the car. "
                + "Change a seat yourself during the drive and it stays that way until the next start.",
                17, SUB, false);
        box.addView(note);
        for (final boolean driver : new boolean[]{true, false}) {
            TextView name = text(driver ? "Driver" : "Passenger", 19, TEXT, true);
            name.setPadding(0, dp(20), 0, dp(8));
            box.addView(name);
            LinearLayout seg = new LinearLayout(this);
            final TextView[] opts = new TextView[3];
            for (int i = 0; i < 3; i++) {
                final int level = i;
                TextView o = text(FAN_WORDS[i], 20, TEXT, true);
                o.setGravity(Gravity.CENTER);
                o.setClickable(true);
                o.setContentDescription((driver ? "Driver seat fan " : "Passenger seat fan ") + FAN_WORDS[i]);
                opts[i] = o;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(64), 1f);
                if (i > 0) lp.leftMargin = dp(10);
                seg.addView(o, lp);
                o.setOnClickListener(v -> {
                    NagService.setSeatPick(this, driver, level);
                    paintSeg(opts, level);
                    render();
                });
            }
            paintSeg(opts, NagService.seatPick(this, driver));
            box.addView(seg);
        }
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Seat fans")
                .setView(box)
                .setPositiveButton("Done", null)
                .create();
        d.setOnShowListener(x -> {
            Button btn = d.getButton(AlertDialog.BUTTON_POSITIVE);
            if (btn != null) {
                btn.setTextSize(20);
                btn.setAllCaps(false);
                btn.setMinHeight(dp(64));
                btn.setMinWidth(dp(140));
            }
        });
        d.show();
    }

    private void paintSeg(TextView[] opts, int picked) {
        for (int i = 0; i < opts.length; i++) {
            boolean on = i == picked;
            opts[i].setTextColor(on ? BG : TEXT);
            opts[i].setBackground(buttonBg(on ? ACCENT : RAISED, on ? ACCENT_TEXT : LINE, RAISED, dp(32), on ? 0 : DIM));
            opts[i].setSelected(on);
        }
    }

    /** Version, what it is, where it lives. Long-press on the name. */
    private void about() {
        String v = version();
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Nag Killer " + v)
                .setMessage("Keeps the nags you pick switched off at every start of the BYD Sealion 7.\n\n"
                        + "Tested on a 2025 Australian Sealion 7. European owners have confirmed it on theirs. Other models haven't been tried.\n"
                        + "Free and open source (MIT): " + REPO + "\n"
                        + "Logs: adb logcat -s NagLab:W\n\n"
                        + "Driver monitoring, child detection, emergency braking, cross traffic "
                        + "brake and emergency lane keeping are safety features. You're still the driver.")
                .setPositiveButton("Close", null)
                .setNeutralButton("Car report", (x, w) -> runReport())
                .create();
        d.setOnShowListener(x -> {
            Button rb = d.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (rb != null) {
                rb.setTextSize(20);
                rb.setAllCaps(false);
                rb.setMinHeight(dp(64));
            }
            TextView m = d.findViewById(android.R.id.message);
            if (m != null) m.setTextSize(19);
            Button btn = d.getButton(AlertDialog.BUTTON_POSITIVE);
            if (btn != null) {
                btn.setTextSize(20);
                btn.setAllCaps(false);
                btn.setMinHeight(dp(64));
                btn.setMinWidth(dp(140));
            }
        });
        d.show();
    }

    private boolean reportBusy;

    /** Builds the car report off the main thread, then shows it (people photograph it or
     *  adb pull the file and paste it into a GitHub issue). */
    private void runReport() {
        if (reportBusy) return;
        reportBusy = true;
        android.widget.Toast.makeText(this, "Making car report...", android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String[] r = CarReport.make(getApplicationContext());
            ui.post(() -> {
                reportBusy = false;
                if (isFinishing()) return;
                TextView t = new TextView(this);
                t.setTypeface(Typeface.MONOSPACE);
                t.setTextSize(13);
                t.setTextColor(TEXT);
                t.setTextIsSelectable(true);
                t.setPadding(dp(24), dp(16), dp(24), dp(16));
                t.setText(r[0] + "\nFull report saved to:\n" + r[1]
                        + "\n\nGet it with:\nadb pull " + r[1]);
                ScrollView sv = new ScrollView(this);
                sv.addView(t);
                new AlertDialog.Builder(this).setTitle("Car report").setView(sv)
                        .setPositiveButton("Close", null).show();
            });
        }, "naglab-report").start();
    }

    // Right column geometry (dp), shared by the header and the rows so the labels sit over
    // what they name.
    private static final int ROW_PAD_L = 24, ROW_PAD_R = 20, PILL_W = 80, PILL_M = 12,
            BTN_W = 56, BTN_GAP = 8, WORD_W = 76, WORD_GAP = 8, SWITCH_W = 76, SWITCH_H = 42,
            ROW_MIN = 72, HDR_SPILL = 12;

    private TextView section(String s) {
        TextView t = text(s.toUpperCase(Locale.US), 15, SUB, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, dp(12), 0, dp(8));
        return t;
    }

    /** Card row: title + subtitle, state pill, Button tick, state word, switch. The whole row
     *  flips the switch; the tick is its own target. */
    private LinearLayout row(String title, String sub, TextView[] subOut, TextView pill,
                             TextView cell, TextView word, final Toggle t) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(ROW_PAD_L), dp(8), dp(ROW_PAD_R), dp(8));
        r.setMinimumHeight(dp(ROW_MIN));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.addView(text(title, 21, TEXT, true));
        TextView s = text(sub, 15, SUB, false);
        s.setPadding(0, dp(2), 0, 0);
        txt.addView(s);
        if (subOut != null) subOut[0] = s;
        r.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
        pill.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(PILL_W), dp(36));
        pp.leftMargin = dp(PILL_M);   // keep long subtitles off the pill
        pp.rightMargin = dp(PILL_M);
        r.addView(pill, pp);
        cell.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(BTN_W), dp(56));
        cp.rightMargin = dp(BTN_GAP);
        r.addView(cell, cp);
        word.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(dp(WORD_W), -2);
        wp.rightMargin = dp(WORD_GAP);
        r.addView(word, wp);
        r.addView(t, new LinearLayout.LayoutParams(dp(SWITCH_W), dp(SWITCH_H)));
        r.setBackground(ripple());
        r.setOnClickListener(v -> t.flip());
        // TalkBack: the switch carries the name and state; the row is a touch target only.
        r.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return r;
    }

    private TextView pill() {
        TextView p = text("", 16, TEXT, true);
        p.setBackground(round(LINE, dp(18)));
        return p;
    }

    private TextView word() {
        return text("", 16, SUB, true);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, dp(20)));
        c.setClipToOutline(true);   // row press ripple stays inside the rounded corners
        return c;
    }

    private View divider() {
        View d = new View(this);
        d.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        lp.leftMargin = dp(ROW_PAD_L);
        d.setLayoutParams(lp);
        return d;
    }

    private void nagRow(LinearLayout card, final String nag, String sub, TextView[] subOut,
                        TextView pill, TextView word, Toggle t) {
        t.setLabel(NagService.label(nag) + ", Keep off");
        // Button column: tick = the quick button and Switch off now switch this nag off.
        TextView cell = text("", 22, SUB, true);
        cell.setBackground(ripple());
        cell.setClickable(true);
        cell.setOnClickListener(v -> {
            NagService.setInButton(this, nag, !NagService.inButton(this, nag));
            render();
        });
        buttonCells.put(nag, cell);
        card.addView(row(NagService.label(nag), sub, subOut, pill, cell, word, t));
        if (NagService.isSafety(nag)) {
            t.setOnChange(on -> {
                if (on) {
                    t.setOn(false, false);   // only after the owner says yes
                    confirmSafety(nag, t);
                } else {
                    NagService.setKilled(this, nag, false);
                    render();
                }
            });
        } else {
            t.setOnChange(on -> {
                NagService.setKilled(this, nag, on);
                render();
            });
        }
    }

    /** Safety systems: ask once before keeping one off. */
    private void confirmSafety(final String nag, final Toggle t) {
        String title, msg;
        switch (nag) {
            case NagService.NAG_FCTB:
                title = "Keep cross traffic brake off?";
                msg = "Cross traffic brake can brake the car for traffic crossing in front of you "
                        + "at low speed. With Keep off on, it stays off at every start.";
                break;
            case NagService.NAG_ELKA:
                title = "Keep emergency lane keeping off?";
                msg = "Emergency lane keeping steers you back if you drift toward another car or "
                        + "off the road. With Keep off on, it stays off at every start.";
                break;
            case NagService.NAG_AEB:
                title = "Keep emergency braking off?";
                msg = "Emergency braking brakes the car by itself when it sees a crash coming, "
                        + "including for people and cyclists. With Keep off on, it stays off at every "
                        + "start and the car will not brake for you.";
                break;
            default:
                title = "Keep child detection off?";
                msg = "Child detection warns you if a child is left in the car. "
                        + "With Keep off on, it stays off at every start.";
        }
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setMessage(msg)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Keep it off", (dlg, w) -> {
                    t.setOn(true, true);
                    NagService.setKilled(this, nag, true);
                    render();
                })
                .create();
        d.setOnShowListener(x -> {
            TextView m = d.findViewById(android.R.id.message);
            if (m != null) m.setTextSize(20);
            for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
                Button btn = d.getButton(which);
                if (btn == null) continue;
                btn.setTextSize(20);
                btn.setAllCaps(false);
                btn.setMinHeight(dp(64));
                btn.setMinWidth(dp(140));
            }
            Button yes = d.getButton(AlertDialog.BUTTON_POSITIVE);
            if (yes != null) yes.setTextColor(ACCENT_TEXT);
        });
        d.show();
    }

    private View buildRight() {
        LinearLayout col = new LinearLayout(this);
        ScrollView sv = scroller(col);

        // Column labels over the pill (what the car says), the tick (in the buttons) and the
        // switch (what you asked for).
        LinearLayout hd = new LinearLayout(this);
        hd.setGravity(Gravity.BOTTOM);
        hd.setPadding(dp(ROW_PAD_L), 0, dp(ROW_PAD_R), 0);
        hd.addView(section("Nags"), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView c1 = section("Car now");
        c1.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams c1p = new LinearLayout.LayoutParams(dp(PILL_W), -2);
        c1p.leftMargin = dp(PILL_M);
        c1p.rightMargin = dp(PILL_M);
        hd.addView(c1, c1p);
        TextView c2 = section("Button");
        c2.setGravity(Gravity.CENTER);
        c2.setSingleLine(true);
        // "BUTTON" is wider than the 56 dp tick column and wrapped as "BUTTO/N" on the car.
        // Widen the label by HDR_SPILL each side, still centred on the ticks; the spill only
        // covers empty margin next to "CAR NOW" and the right-aligned "KEEP OFF".
        LinearLayout.LayoutParams c2p =
                new LinearLayout.LayoutParams(dp(BTN_W + 2 * HDR_SPILL), -2);
        c2p.leftMargin = -dp(HDR_SPILL);
        c2p.rightMargin = dp(BTN_GAP - HDR_SPILL);
        hd.addView(c2, c2p);
        TextView c3 = section("Keep off");
        c3.setGravity(Gravity.END);
        hd.addView(c3, new LinearLayout.LayoutParams(dp(WORD_W + WORD_GAP + SWITCH_W), -2));
        col.addView(hd);

        LinearLayout nags = card();
        dmsToggle = new Toggle(this);
        dmsState = pill();
        dmsWord = word();
        nagRow(nags, NagService.NAG_DMS, "Camera alerts.", null, dmsState, dmsWord, dmsToggle);
        nags.addView(divider());
        laneToggle = new Toggle(this);
        laneState = pill();
        laneWord = word();
        TextView[] ls = new TextView[1];
        nagRow(nags, NagService.NAG_LANE, LANE_SUB, ls, laneState, laneWord, laneToggle);
        laneSub = ls[0];
        nags.addView(divider());
        elkaToggle = new Toggle(this);
        elkaState = pill();
        elkaWord = word();
        nagRow(nags, NagService.NAG_ELKA, "Steers back from the edge.", null,
                elkaState, elkaWord, elkaToggle);
        nags.addView(divider());
        fctbToggle = new Toggle(this);
        fctbState = pill();
        fctbWord = word();
        nagRow(nags, NagService.NAG_FCTB, "Brakes for crossing traffic.", null,
                fctbState, fctbWord, fctbToggle);
        nags.addView(divider());
        aebToggle = new Toggle(this);
        aebState = pill();
        aebWord = word();
        nagRow(nags, NagService.NAG_AEB, "Brakes before a crash.", null,
                aebState, aebWord, aebToggle);
        nags.addView(divider());
        toneToggle = new Toggle(this);
        toneState = pill();
        toneWord = word();
        nagRow(nags, NagService.NAG_CHIME, "Limit tone and warning.", null,
                toneState, toneWord, toneToggle);
        nags.addView(divider());
        cpdToggle = new Toggle(this);
        cpdState = pill();
        cpdWord = word();
        TextView[] cs = new TextView[1];
        nagRow(nags, NagService.NAG_CPD, CPD_SUB, cs, cpdState, cpdWord, cpdToggle);
        cpdSub = cs[0];
        col.addView(nags);
        return sv;
    }

    private static final String LANE_SUB = "Nudges and buzz.";
    private static final String CPD_SUB = "Child left in car alarm.";

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handle(i);
    }

    private void handle(Intent i) {
        if (i != null && i.getBooleanExtra("report", false)) {
            i.removeExtra("report");
            runReport();   // adb shell am start -n au.jaso.naglab/.MainActivity --ez report true
        }
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
        resumedWall = System.currentTimeMillis();
        connectingSince = 0;
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
        if (!started) helperMsg = "Already starting, hang on...";
        render();
    }

    /** True from a big-button tap until the service reports back (or gives up). */
    private boolean working() {
        long now = System.currentTimeMillis();
        return killSentWall > 0 && NagService.lastSummaryWall < killSentWall
                && now - killSentWall < KILL_TIMEOUT_MS;
    }

    /** The screen has been open long enough that "no reading" means no reading, not "still
     *  connecting". */
    private boolean settled() {
        return System.currentTimeMillis() - resumedWall > CONNECT_TIMEOUT_MS;
    }

    /** Car-state pill, in the same colours as the switch. text null = unknown.
     *  Kept off + car off: orange. Kept off + car on: amber, filled (needs attention).
     *  Not kept off: grey, whatever the car says. */
    private void setPill(TextView p, String text, boolean isOff, boolean keptOff) {
        if (text == null) {
            boolean none = settled();
            p.setText(none ? "No reading" : "...");
            p.setTextSize(none ? 13 : 16);
            p.setTextColor(SUB);
            tint(p, LINE);
            return;
        }
        p.setTextSize(16);
        p.setText(text);
        if (!keptOff) {
            p.setTextColor(SUB);
            tint(p, LINE);
        } else if (isOff) {
            p.setTextColor(ACCENT_TEXT);
            tint(p, ACCENT_DEEP);
        } else {
            p.setTextColor(BG);
            tint(p, WARN);
        }
    }

    /** The words beside each switch, so its meaning never rests on colour or position. */
    private static void setWord(TextView w, boolean keptOff) {
        w.setText(keptOff ? "Kept off" : "Normal");
        w.setTextColor(keptOff ? ACCENT_TEXT : SUB);
    }

    /** A plain on/off nag row: switch, word, pill from NagService.state. */
    private void onOffRow(String nag, Toggle t, TextView word, TextView pill) {
        boolean k = NagService.killed(this, nag);
        t.setOn(k, false);
        setWord(word, k);
        int st = NagService.state(nag);
        setPill(pill, st < 0 ? null : st == 0 ? "Off" : "On", st == 0, k);
    }

    private static void tint(View v, int color) {
        ((GradientDrawable) v.getBackground()).setColor(color);
    }

    /** Headline and subline change with a short crossfade, so a state change is visible
     *  rather than a snap. Repeated renders with the same text do nothing. */
    private void fadeTo(final TextView v, final String want, final int color, boolean isHeadline) {
        String cur = isHeadline ? headlineWant : sublineWant;
        if (isHeadline) headlineWant = want;
        else sublineWant = want;
        if (want.equals(cur)) {
            v.setTextColor(color);
            return;
        }
        if (cur == null) {   // first paint
            v.setText(want);
            v.setTextColor(color);
            return;
        }
        v.animate().cancel();
        v.animate().alpha(0f).setDuration(FADE_MS / 2).withEndAction(() -> {
            v.setText(want);
            v.setTextColor(color);
            v.animate().alpha(1f).setDuration(FADE_MS / 2);
        });
    }

    private void render() {
        boolean connected = NagService.connected;
        boolean working = working();
        long now = System.currentTimeMillis();

        // Status (left), measured against what the owner asked for, not against "everything".
        boolean adasKnown = NagService.lane != Integer.MIN_VALUE;
        List<String> kept = new ArrayList<>(), fixing = new ArrayList<>(), leftOn = new ArrayList<>(),
                unknown = new ArrayList<>();
        for (String n : NagService.NAGS) {
            int st = NagService.state(n);
            boolean k = NagService.killed(this, n);
            if (k && st == 1) fixing.add(NagService.label(n));
            else if (k && st < 0) unknown.add(NagService.label(n));
            else if (k) kept.add(NagService.label(n));
            else if (st == 1) leftOn.add(NagService.label(n));
        }
        int keptCount = kept.size() + fixing.size() + unknown.size();
        // one of the two services still binding: wait rather than flash "Can't read"
        boolean binding = !connected || !adasKnown;
        boolean waiting = !NagService.running || (!connected && !adasKnown)
                || (binding && fixing.isEmpty() && !unknown.isEmpty());
        if (waiting) {
            if (connectingSince == 0) connectingSince = now;
        } else {
            connectingSince = 0;
        }
        boolean timedOut = waiting && now - connectingSince > CONNECT_TIMEOUT_MS;
        if (waiting && !timedOut) {
            fadeTo(headline, "Connecting", TEXT, true);
            fadeTo(subline, "Talking to the car...", SUB, false);
            statusMark.set(Mark.BUSY, SUB);
        } else if (!connected && !adasKnown) {
            // 20 s and neither BYD service answered: say so instead of spinning forever
            fadeTo(headline, "Can't reach the car", WARN, true);
            fadeTo(subline, "No answer from BYD's settings services. Only the Australian "
                    + "Sealion 7 has been tested; this car may not be supported.", SUB, false);
            statusMark.set(Mark.ALERT, WARN);
        } else if (!fixing.isEmpty()) {
            fadeTo(headline, "Switching off", WARN, true);
            fadeTo(subline, TextUtils.join(", ", fixing) + " came back on. Switching off again...",
                    SUB, false);
            statusMark.set(Mark.ALERT, WARN);
        } else if (keptCount == 0) {
            fadeTo(headline, "Nothing kept off", TEXT, true);
            fadeTo(subline, "Turn on Keep off next to a nag to switch it off at every start.",
                    SUB, false);
            statusMark.set(Mark.IDLE, SUB);
        } else if (!unknown.isEmpty()) {
            // kept off but the car gives no reading (not fitted, or no answer): say so, don't guess
            fadeTo(headline, "Can't read", WARN, true);
            fadeTo(subline, "No reading from the car for " + TextUtils.join(", ", unknown) + "."
                    + (kept.isEmpty() ? "" : "\nKept off: " + TextUtils.join(", ", kept) + "."),
                    SUB, false);
            statusMark.set(Mark.ALERT, WARN);
        } else {
            fadeTo(headline, "All set", TEXT, true);
            fadeTo(subline, "Kept off: " + TextUtils.join(", ", kept) + "."
                    + (leftOn.isEmpty() ? "" : "\nLeft on: " + TextUtils.join(", ", leftOn) + "."),
                    SUB, false);
            statusMark.set(Mark.OK, ACCENT);
        }

        // The button switches off the button set; it never switches anything on.
        int btnCount = NagService.buttonSet(this).size();
        killBtn.setEnabled(btnCount > 0);   // repeat taps while working are harmless
        killBtn.setText(Report.buttonLabel(btnCount, working));
        killBtn.setContentDescription(btnCount > 0
                ? "Switch off now, " + btnCount + (btnCount == 1 ? " nag" : " nags") : null);
        for (String n : NagService.NAGS) {
            TextView cell = buttonCells.get(n);
            boolean in = NagService.inButton(this, n);
            cell.setText(in ? "\u2713" : "\u2013");
            cell.setTextColor(in ? ACCENT_TEXT : DIM);
            cell.setContentDescription(NagService.label(n)
                    + (in ? ", in the buttons, tap to take out" : ", not in the buttons, tap to add"));
        }

        String s = "";
        int sc = SUB;
        String last = NagService.lastKillSummary;
        int fixes = NagService.fixCount;
        if (btnCount == 0) {
            s = Report.NOTHING_HINT;
            sc = TEXT;
        } else if (last != null && now - NagService.lastSummaryWall < 60_000) {
            s = last;
        } else if (fixes > 0) {
            s = "Switched off again " + fixes + (fixes == 1 ? " time" : " times") + " since start, last "
                    + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(NagService.lastFixWall));
        }
        stats.setText(s);
        stats.setTextColor(sc);

        // Rows: pill = what the car says, word + switch = Keep off.
        boolean dmsK = NagService.isEnabled(this);
        dmsToggle.setOn(dmsK, false);
        setWord(dmsWord, dmsK);
        int dms = NagService.dms;
        setPill(dmsState, !connected || dms < 0 ? null : NagService.state(NagService.NAG_DMS) == 0 ? "Off" : "On",
                NagService.state(NagService.NAG_DMS) == 0, dmsK);

        boolean laneK = NagService.killed(this, NagService.NAG_LANE);
        laneToggle.setOn(laneK, false);
        setWord(laneWord, laneK);
        int ln = NagService.lane;
        setPill(laneState, ln == Integer.MIN_VALUE ? null : ln == 0 ? "Off" : "On", ln == 0, laneK);
        String mode = ln == 1 ? "Warning" : ln == 2 ? "Prevent" : ln == 3 ? "Both" : null;
        laneSub.setText(mode != null ? LANE_SUB + " Mode: " + mode + "." : LANE_SUB);

        boolean toneK = NagService.killed(this, NagService.NAG_CHIME);
        toneToggle.setOn(toneK, false);
        setWord(toneWord, toneK);
        int ch = NagService.state(NagService.NAG_CHIME);   // same reading the headline uses
        setPill(toneState, ch < 0 ? null : ch == 0 ? "Off" : "On", ch == 0, toneK);

        onOffRow(NagService.NAG_ELKA, elkaToggle, elkaWord, elkaState);
        onOffRow(NagService.NAG_FCTB, fctbToggle, fctbWord, fctbState);
        onOffRow(NagService.NAG_AEB, aebToggle, aebWord, aebState);

        boolean cpdK = NagService.killed(this, NagService.NAG_CPD);
        cpdToggle.setOn(cpdK, false);
        setWord(cpdWord, cpdK);
        int cp = NagService.cpd;
        setPill(cpdState, cp < 0 ? null : cp == 0 ? "Off" : "On", cp == 0, cpdK);
        cpdSub.setText(cp == 1 ? CPD_SUB + " Mode: "
                + (NagService.cpdRaw == AdasClient.CPD_DELAY ? "Delay" : "Standard") + "." : CPD_SUB);

        // Seat fans row
        seatSub.setText(seatSummary());

        // Quick button row
        boolean wantQuick = QuickButton.wanted(this);
        quickToggle.setOn(wantQuick, false);
        if (!QuickButton.allowed(this)) {
            quickSub.setText("Needs permission to show over other apps. "
                    + "Turn on debugging, then tap Start below.");
            quickSub.setTextColor(WARN);
            quickToggle.setEnabled(false);
            quickWord.setText("Off");
            quickWord.setTextColor(SUB);
        } else {
            quickSub.setText("Floats over CarPlay. Tap: switches off the ticked nags. Hold and drag to move.");
            quickSub.setTextColor(SUB);
            quickToggle.setEnabled(true);
            quickWord.setText(wantQuick ? "Shown" : "Hidden");
            quickWord.setTextColor(wantQuick ? ACCENT_TEXT : SUB);
        }
        quickToggle.invalidate();   // enabled state changes the track colour

        // Background helper row. Since 0.6 the notification-listener grant brings the app back
        // on its own; the shell helper is the fast path (and wakes Overdrive). So a dead helper
        // is only amber when that grant is missing too: a working install must not look broken
        // after every overnight head-unit reboot.
        boolean alive = HelperPing.isAlive(this);
        boolean busy = HelperSetup.isBusy();
        boolean listener = listenerGranted();
        if (alive) {
            helperMark.set(Mark.OK, SUB);
            helperTitle.setText("Background helper running");
            helperSub.setText("Brings the app back within seconds after the car restarts.");
        } else if (busy) {
            helperMark.set(Mark.BUSY, WARN);
            helperTitle.setText("Starting background helper");
            helperSub.setText(helperMsg != null ? helperMsg : "...");
        } else if (listener) {
            helperMark.set(Mark.IDLE, SUB);
            helperTitle.setText("Background helper off");
            helperSub.setText(helperMsg != null ? helperMsg
                    : "The app still comes back by itself. Start makes it faster.");
        } else {
            helperMark.set(Mark.ALERT, WARN);
            helperTitle.setText("Background helper not running");
            helperSub.setText(helperMsg != null ? helperMsg
                    : "Driver monitoring still gets switched off. For the full setup, turn on "
                    + "debugging the way you did to install, then tap Start.");
        }
        helperBtn.setVisibility(alive || busy ? View.GONE : View.VISIBLE);
    }

    /** The revive hook (NagListener) is granted: the system restarts the app after BYD's
     *  force-stop and after a head-unit reboot, with or without the shell helper. */
    private boolean listenerGranted() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            return nm != null && nm.isNotificationListenerAccessGranted(
                    new ComponentName(this, NagListener.class));
        } catch (Throwable t) {
            return false;
        }
    }
}
