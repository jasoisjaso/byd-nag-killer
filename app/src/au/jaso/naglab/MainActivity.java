package au.jaso.naglab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
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
import java.util.List;
import java.util.Locale;

/**
 * Landscape screen for the car display (1920x990 px, 240 dpi = 1280x660 dp).
 * Left: icon, status, the "Switch off now" button (switches off the button set), the
 * background helper. Right: one row per nag (car's state + Keep off switch) and the floating
 * quick button. Keep off = switched off at every start; "Buttons switch off" = what both
 * buttons switch off on a tap (its own set, chosen with chips).
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

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView headline, subline, stats, killBtn;
    private Mark statusMark, helperMark;
    private Toggle dmsToggle, quickToggle, laneToggle, toneToggle, cpdToggle, fctbToggle, elkaToggle, aebToggle;
    private TextView dmsState, laneState, toneState, cpdState, fctbState, elkaState, aebState, laneSub, cpdSub, quickSub;
    private TextView dmsWord, laneWord, toneWord, cpdWord, fctbWord, elkaWord, aebWord, quickWord;
    private final java.util.Map<String, TextView> chips = new java.util.HashMap<>();
    private TextView helperTitle, helperSub, helperBtn;
    private String helperMsg;
    private boolean autoTried;
    /** Wall time of the last big-button tap; "Switching off..." until a newer report lands. */
    private long killSentWall;
    private static final long KILL_TIMEOUT_MS = 10_000;

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

    private View buildLeft() {
        LinearLayout col = new LinearLayout(this);
        ScrollView sv = scroller(col);

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
        kp.topMargin = dp(40);
        col.addView(killBtn, kp);
        killBtn.setOnClickListener(v -> {
            NagService.killNow(this);
            killSentWall = System.currentTimeMillis();
            v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(140));
            ui.removeCallbacks(refresh);
            ui.post(refresh);   // faster refresh until the report lands
        });

        // Under the button: the hint when nothing is kept off, else the last result.
        stats = text("", 16, SUB, false);
        stats.setGravity(Gravity.CENTER);
        stats.setPadding(0, dp(14), 0, 0);
        col.addView(stats, new LinearLayout.LayoutParams(-1, -2));

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
        hlp.topMargin = dp(28);
        col.addView(hr, hlp);
        return sv;
    }

    // Right column geometry (dp), shared by the header and the rows so the labels sit over
    // what they name.
    private static final int ROW_PAD_L = 24, ROW_PAD_R = 20, PILL_W = 96, PILL_M = 16,
            WORD_W = 84, WORD_GAP = 12, SWITCH_W = 76, SWITCH_H = 42;

    private TextView section(String s) {
        TextView t = text(s.toUpperCase(Locale.US), 15, SUB, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, dp(16), 0, dp(8));
        return t;
    }

    /** Card row: title + subtitle, optional state pill, state word, switch. The whole row
     *  flips the switch. */
    private LinearLayout row(String title, String sub, TextView[] subOut, TextView pill,
                             TextView word, final Toggle t) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(ROW_PAD_L), dp(12), dp(ROW_PAD_R), dp(12));
        r.setMinimumHeight(dp(88));
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
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(PILL_W), dp(36));
            pp.leftMargin = dp(PILL_M);   // keep long subtitles off the pill
            pp.rightMargin = dp(PILL_M);
            r.addView(pill, pp);
        }
        word.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(dp(WORD_W), -2);
        wp.rightMargin = dp(WORD_GAP);
        r.addView(word, wp);
        r.addView(t, new LinearLayout.LayoutParams(dp(SWITCH_W), dp(SWITCH_H)));
        r.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null,
                new ColorDrawable(0xFFFFFFFF)));
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

    private void nagRow(LinearLayout card, String nag, String sub, TextView[] subOut,
                        TextView pill, TextView word, Toggle t) {
        t.setLabel(NagService.label(nag) + ", Keep off");
        card.addView(row(NagService.label(nag), sub, subOut, pill, word, t));
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

        // Column labels over the pill (what the car says) and the switch (what you asked for).
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
        TextView c2 = section("Keep off");
        c2.setGravity(Gravity.END);
        hd.addView(c2, new LinearLayout.LayoutParams(dp(WORD_W + WORD_GAP + SWITCH_W), -2));
        col.addView(hd);

        LinearLayout nags = card();
        dmsToggle = new Toggle(this);
        dmsState = pill();
        dmsWord = word();
        nagRow(nags, NagService.NAG_DMS, "Fatigue and distraction alerts.", null,
                dmsState, dmsWord, dmsToggle);
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
        nagRow(nags, NagService.NAG_ELKA, "Steers you back near the lane edge or another car.", null,
                elkaState, elkaWord, elkaToggle);
        nags.addView(divider());
        fctbToggle = new Toggle(this);
        fctbState = pill();
        fctbWord = word();
        nagRow(nags, NagService.NAG_FCTB, "Brakes for traffic crossing in front at low speed.", null,
                fctbState, fctbWord, fctbToggle);
        nags.addView(divider());
        aebToggle = new Toggle(this);
        aebState = pill();
        aebWord = word();
        nagRow(nags, NagService.NAG_AEB, "Brakes by itself when a crash is coming.", null,
                aebState, aebWord, aebToggle);
        nags.addView(divider());
        toneToggle = new Toggle(this);
        toneState = pill();
        toneWord = word();
        nagRow(nags, NagService.NAG_CHIME, "Limit-change tone and over-limit warning.", null,
                toneState, toneWord, toneToggle);
        nags.addView(divider());
        cpdToggle = new Toggle(this);
        cpdState = pill();
        cpdWord = word();
        TextView[] cs = new TextView[1];
        nagRow(nags, NagService.NAG_CPD, CPD_SUB, cs, cpdState, cpdWord, cpdToggle);
        cpdSub = cs[0];
        col.addView(nags);

        TextView os = section("On screen");
        os.setPadding(dp(ROW_PAD_L), dp(24), 0, dp(8));
        col.addView(os);
        LinearLayout qc = card();
        quickToggle = new Toggle(this);
        quickToggle.setLabel("Quick button");
        quickWord = word();
        TextView[] qs = new TextView[1];
        qc.addView(row("Quick button", "", qs, null, quickWord, quickToggle));
        quickSub = qs[0];
        qc.addView(divider());
        qc.addView(buttonSetRow());
        quickToggle.setOnChange(on -> {
            getSharedPreferences(NagService.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(QuickButton.KEY_SHOW, on).apply();
            NagService.sync(this);
            render();
        });
        col.addView(qc);

        return sv;
    }

    /** "Buttons switch off": one chip per nag, tap to put it in the button set or take it
     *  out. Separate from Keep off, so the quick button can be a one-tap fix for a nag you
     *  only sometimes want gone. */
    private View buttonSetRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(ROW_PAD_L), dp(14), dp(ROW_PAD_R), dp(16));
        r.addView(text("Buttons switch off", 21, TEXT, true));
        TextView s = text("For the quick button and Switch off now. Tap a name to add it or take it out.",
                15, SUB, false);
        s.setPadding(0, dp(3), 0, dp(12));
        r.addView(s);
        LinearLayout cr = null;
        int i = 0;
        for (final String n : NagService.NAGS) {
            if (i++ % 4 == 0) {
                cr = new LinearLayout(this);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
                if (i > 1) rlp.topMargin = dp(10);
                r.addView(cr, rlp);
            }
            TextView chip = text(shortName(n), 17, TEXT, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(16), 0, dp(16), 0);
            chip.setBackground(round(LINE, dp(24)));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, dp(48));
            clp.rightMargin = dp(10);
            cr.addView(chip, clp);
            chip.setOnClickListener(v -> {
                NagService.setInButton(this, n, !NagService.inButton(this, n));
                render();
            });
            chips.put(n, chip);
        }
        return r;
    }

    private static String shortName(String n) {
        switch (n) {
            case NagService.NAG_DMS: return "Monitoring";
            case NagService.NAG_LANE: return "Lane assist";
            case NagService.NAG_CHIME: return "Speed chime";
            case NagService.NAG_ELKA: return "Lane keeping";
            case NagService.NAG_FCTB: return "Cross brake";
            case NagService.NAG_AEB: return "AEB";
            default: return "Child alarm";
        }
    }

    private static final String LANE_SUB = "Steering nudges and lane buzz.";
    private static final String CPD_SUB = "Alarm if a child is left in the car.";

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
        if (!started) helperMsg = "Already starting, hang on...";
        render();
    }

    /** True from a big-button tap until the service reports back (or gives up). */
    private boolean working() {
        long now = System.currentTimeMillis();
        return killSentWall > 0 && NagService.lastSummaryWall < killSentWall
                && now - killSentWall < KILL_TIMEOUT_MS;
    }

    /** Car-state pill, in the same colours as the switch. text null = unknown.
     *  Kept off + car off: orange. Kept off + car on: amber, filled (needs attention).
     *  Not kept off: grey, whatever the car says. */
    private void setPill(TextView p, String text, boolean isOff, boolean keptOff) {
        if (text == null) {
            p.setText("...");
            p.setTextColor(SUB);
            tint(p, LINE);
        } else if (!keptOff) {
            p.setText(text);
            p.setTextColor(SUB);
            tint(p, LINE);
        } else if (isOff) {
            p.setText(text);
            p.setTextColor(ACCENT_TEXT);
            tint(p, ACCENT_DEEP);
        } else {
            p.setText(text);
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

    private void render() {
        boolean connected = NagService.connected;
        boolean working = working();

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
        headline.setTextColor(TEXT);
        // one of the two services still binding: wait rather than flash "Can't read"
        boolean binding = !connected || !adasKnown;
        if (!NagService.running || (!connected && !adasKnown)
                || (binding && fixing.isEmpty() && !unknown.isEmpty())) {
            headline.setText("Connecting");
            subline.setText("Talking to the car...");
            statusMark.set(Mark.BUSY, SUB);
        } else if (!fixing.isEmpty()) {
            headline.setText("Switching off");
            headline.setTextColor(WARN);
            subline.setText(TextUtils.join(", ", fixing) + " came back on. Switching off again...");
            statusMark.set(Mark.ALERT, WARN);
        } else if (keptCount == 0) {
            headline.setText("Nothing kept off");
            subline.setText("Turn on Keep off next to a nag to switch it off at every start.");
            statusMark.set(Mark.IDLE, SUB);
        } else if (!unknown.isEmpty()) {
            // kept off but the car gives no reading (not fitted, or no answer): say so, don't guess
            headline.setText("Can't read");
            headline.setTextColor(WARN);
            subline.setText("No reading from the car for " + TextUtils.join(", ", unknown) + "."
                    + (kept.isEmpty() ? "" : "\nKept off: " + TextUtils.join(", ", kept) + "."));
            statusMark.set(Mark.ALERT, WARN);
        } else {
            headline.setText("All set");
            subline.setText("Kept off: " + TextUtils.join(", ", kept) + "."
                    + (leftOn.isEmpty() ? "" : "\nLeft on: " + TextUtils.join(", ", leftOn) + "."));
            statusMark.set(Mark.OK, ACCENT);
        }

        // The button switches off the button set; it never switches anything on.
        int btnCount = NagService.buttonSet(this).size();
        killBtn.setEnabled(btnCount > 0);   // repeat taps while working are harmless
        killBtn.setText(Report.buttonLabel(btnCount, working));
        killBtn.setContentDescription(btnCount > 0
                ? "Switch off now, " + btnCount + (btnCount == 1 ? " nag" : " nags") : null);
        for (String n : NagService.NAGS) {
            TextView chip = chips.get(n);
            boolean in = NagService.inButton(this, n);
            tint(chip, in ? ACCENT_DEEP : LINE);
            chip.setTextColor(in ? ACCENT_TEXT : SUB);
            chip.setText(in ? "\u2713 " + shortName(n) : shortName(n));
            chip.setContentDescription(shortName(n) + (in ? ", in the buttons" : ", not in the buttons"));
        }

        String s = "";
        int sc = SUB;
        String last = NagService.lastKillSummary;
        if (btnCount == 0) {
            s = Report.NOTHING_HINT;
            sc = TEXT;
        } else if (last != null && System.currentTimeMillis() - NagService.lastSummaryWall < 60_000) {
            s = last;
        } else if (NagService.fixCount > 0) {
            s = "Driver monitoring switched off " + NagService.fixCount
                    + (NagService.fixCount == 1 ? " time" : " times") + " since start, last "
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

        // Quick button row
        boolean wantQuick = QuickButton.wanted(this);
        quickToggle.setOn(wantQuick, false);
        if (!QuickButton.allowed(this)) {
            quickSub.setText("Needs permission to show over other apps. "
                    + "Turn on debugging, then tap Start at the bottom left.");
            quickSub.setTextColor(WARN);
            quickToggle.setEnabled(false);
            quickWord.setText("Off");
            quickWord.setTextColor(SUB);
        } else {
            quickSub.setText("Floats over CarPlay. Tap it to switch off the nags picked below. "
                    + "Hold and drag to move it.");
            quickSub.setTextColor(SUB);
            quickToggle.setEnabled(true);
            quickWord.setText(wantQuick ? "Shown" : "Hidden");
            quickWord.setTextColor(wantQuick ? ACCENT_TEXT : SUB);
        }
        quickToggle.invalidate();   // enabled state changes the track colour

        // Background helper row
        boolean alive = HelperPing.isAlive(this);
        boolean busy = HelperSetup.isBusy();
        if (alive) {
            helperMark.set(Mark.OK, SUB);
            helperTitle.setText("Background helper running");
            helperSub.setText("Brings the app back by itself after the car restarts.");
        } else if (busy) {
            helperMark.set(Mark.BUSY, WARN);
            helperTitle.setText("Starting background helper");
            helperSub.setText(helperMsg != null ? helperMsg : "...");
        } else {
            helperMark.set(Mark.ALERT, WARN);
            helperTitle.setText("Background helper not running");
            helperSub.setText(helperMsg != null ? helperMsg
                    : "Driver monitoring still gets switched off. For the full setup, turn on "
                    + "debugging the way you did to install, then tap Start.");
        }
        helperBtn.setVisibility(alive || busy ? View.GONE : View.VISIBLE);
    }
}
