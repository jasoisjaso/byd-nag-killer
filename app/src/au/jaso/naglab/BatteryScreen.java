package au.jaso.naglab;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.Calendar;
import java.util.List;

/**
 * Two tabs. Charging: the battery now (live while plugged in with the car on), and on the right
 * this month, the prices, kWh per month and every charge the app has seen. Health: what a full
 * battery holds now against new (everyday readings, one each time the percent moves), the
 * battery test and the battery report.
 *
 * Nothing here is measured by the app. A charge is the difference between the battery at the
 * end of one trip and at the start of the next (the app is not running while the car is off),
 * and the health figure is the car's battery computer's own sums, so it is always worded as an
 * estimate. Drawn from what NagService already read: this screen never talks to the car.
 */
final class BatteryScreen {
    private final MainActivity a;
    private final View root;
    private final Calendar cal = Calendar.getInstance();
    private BatteryViews.Ring ring;
    private BatteryViews.Trend trend;
    private BatteryViews.Bar liveBar;
    private android.widget.ImageView carPic;
    private TextView priceText, monthText, pctSub, timeSub;
    private View priceRow, timeBox, kwBox, chargingRow;
    private String sinceCharge = "";
    private TextView capVal, capSub, healthNote, carLine, packLine;
    private String packText = "";
    private boolean packWarn;
    static final String KEY_PACK = "pack_choice";   // 0 = work it out, 1 = 82.56, 2 = 91.3

    /** Sets Trips.PACK_KWH from the owner's pick, or from the readings when left to the app. */
    private void applyPack(float full) {
        Trips.TestRun done = NagService.testRun(a);
        if (done.state == Trips.TestRun.DONE) full = done.fullKwh;   // a finished test is the better evidence
        int pick = prefs(a).getInt(KEY_PACK, 0);
        float odo = NagService.live == null ? Float.NaN : NagService.live.odo;
        float guess = prefs(a).getBoolean(Trips.KEY_SEEN_BIG, false) ? Trips.PACK_BIG : Trips.guessPack(full, odo);
        packWarn = false;
        if (pick == 0) {
            Trips.PACK_KWH = Float.isNaN(guess) ? Trips.PACK_STD : guess;
            packText = "Battery: " + Trips.one(Trips.PACK_KWH) + " kWh new, "
                    + (Float.isNaN(full) ? "assumed until the first readings"
                    : Float.isNaN(guess) ? "assumed, the readings can't tell on a car this age: please check"
                    : "worked out from the car's readings");
            packWarn = !Float.isNaN(full) && Float.isNaN(guess);
            Trips.PACK_NOTE = Float.isNaN(guess) ? "The app assumed this size; the owner has not confirmed it."
                    : "The app worked this out from the car's own readings; the owner has not confirmed it.";
        } else {
            Trips.PACK_KWH = pick == 2 ? Trips.PACK_BIG : Trips.PACK_STD;
            packWarn = !Float.isNaN(guess) && guess != Trips.PACK_KWH;
            packText = "Battery: " + Trips.one(Trips.PACK_KWH) + " kWh new, your pick"
                    + (packWarn ? ", but the readings look like the " + Trips.one(guess) + " kWh one" : "");
            Trips.PACK_NOTE = "The owner chose this size." + (packWarn ? " The car's readings point to the "
                    + Trips.one(guess) + " kWh battery instead, so treat the percentages with caution."
                    : Float.isNaN(guess) ? " The car's readings can neither confirm nor rule it out."
                    : " The car's readings agree with it.");
        }
    }

    private void packDialog() {
        final android.content.SharedPreferences p = prefs(a);
        final int[] pick = {p.getInt(KEY_PACK, 0)};
        Sheet sh = new Sheet(a, "Which battery?");
        sh.text("Battery health is measured against what the battery held new, and the Sealion 7 comes with two sizes. "
                + "The car doesn't say which it has.");
        final String[] names = {"Work it out for me", "82.5 kWh", "91.3 kWh"};
        final String[] subs = {"From the car's own readings. Best choice on most cars.",
                "Every Australian and NZ car. Comfort and Design in the UK and Europe.",
                "Excellence AWD in the UK and Europe."};
        final TextView[] rows = new TextView[3];
        final Runnable paint = () -> {
            for (int i = 0; i < 3; i++) {
                boolean on = i == pick[0];
                rows[i].setBackground(MainActivity.round(on ? MainActivity.ACCENT : MainActivity.RAISED, a.dp(18)));
                android.text.SpannableString t = new android.text.SpannableString(names[i] + "\n" + subs[i]);
                t.setSpan(new android.text.style.RelativeSizeSpan(0.78f), names[i].length(), t.length(), 0);
                t.setSpan(new android.text.style.ForegroundColorSpan(on ? 0xFF16110D : MainActivity.TEXT), 0, names[i].length(), 0);
                t.setSpan(new android.text.style.ForegroundColorSpan(on ? 0xFF3A2414 : MainActivity.SUB), names[i].length(), t.length(), 0);
                rows[i].setText(t);
            }
        };
        for (int i = 0; i < 3; i++) {
            final int which = i;
            TextView r = a.text("", 20, MainActivity.TEXT, true);
            r.setPadding(a.dp(22), a.dp(12), a.dp(22), a.dp(12));
            r.setLineSpacing(a.dp(3), 1f);
            r.setClickable(true);
            r.setOnClickListener(v -> {
                pick[0] = which;
                paint.run();
            });
            rows[i] = r;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = a.dp(12);
            sh.body().addView(r, lp);
        }
        paint.run();
        sh.button("Cancel", false, null).button("Save", true, () -> {
            // Saving also forgets a "charged at over 165 kW" sighting, so a wrong one can be undone.
            p.edit().putInt(KEY_PACK, pick[0]).remove(Trips.KEY_SEEN_BIG).apply();
            shownVersion = -1;
            render();
        }).show();
    }
    private TextView rightLabel, rightSum, livePct, liveKw, liveTime, liveFoot, rightNote;
    private View monthBox, rightParked;
    private WeekBars months;
    private LinearLayout list;
    private int shownVersion = -1, shownHealth = -2, shownCurSoc = -2, shownTicks = -1;
    private TextView testLine, testBtn;
    private int shownTest = -1;
    private List<Trips.Trip> done;

    BatteryScreen(MainActivity a) {
        this.a = a;
        // Two tabs' worth: Charging (the battery now, big, and every charge) and Health (the
        // ring, big, and what it rests on). One thing to look at per side, nothing boxed in
        // that doesn't need to be.
        root = a.twoPane(buildNow(), 0.85f, buildHistory(), 1.15f);
        health = a.twoPane(buildRing(), 0.85f, buildHealthSide(), 1.15f);
    }

    /** The Charging tab. */
    View view() {
        return root;
    }

    /** A tab was just opened: the battery fills up to its level, the ring sweeps round. */
    void entered(int tab) {
        if (tab == MainActivity.TAB_BATTERY) liveBar.replay();
        if (tab == MainActivity.TAB_HEALTH) ring.replay();
    }

    /** The Health tab. */
    View healthView() {
        return health;
    }

    private final View health;

    /** Big thin numerals: the look of the whole app's headline figures. */
    private TextView thin(String s, float sp, int color) {
        TextView t = a.text(s, sp, color, false);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView label(String s) {
        TextView t = a.text(s, 16, MainActivity.SUB, true);
        t.setLetterSpacing(0.08f);
        return t;
    }

    private LinearLayout stat(TextView val, TextView sub) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.addView(val);
        c.addView(sub);
        return c;
    }

    /** Charging tab, left: the battery itself. No box around it: it is the page. */
    private View buildNow() {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.setPadding(a.dp(8), a.dp(8), a.dp(24), a.dp(8));

        LinearLayout hd = new LinearLayout(a);
        hd.setGravity(Gravity.CENTER_VERTICAL);
        rightLabel = label("BATTERY");
        hd.addView(rightLabel);
        rightSum = a.text("", 17, MainActivity.SUB, false);
        rightSum.setPadding(a.dp(16), 0, 0, 0);
        hd.addView(rightSum);
        col.addView(hd);

        // The car itself, quiet and dark, above its number.
        carPic = new android.widget.ImageView(a);
        carPic.setImageResource(R.drawable.car);
        carPic.setAdjustViewBounds(true);
        carPic.setScaleType(android.widget.ImageView.ScaleType.FIT_START);
        carPic.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams cpp = new LinearLayout.LayoutParams(a.dp(400), -2);
        cpp.topMargin = a.dp(10);
        col.addView(carPic, cpp);

        LinearLayout nums = new LinearLayout(a);
        nums.setGravity(Gravity.BOTTOM);
        livePct = thin("–", 112, MainActivity.TEXT);
        livePct.setTranslationX(-a.dp(6));   // the thin "5" carries its own left gap
        nums.addView(livePct);
        pctSub = a.text("", 22, MainActivity.SUB, false);
        pctSub.setPadding(a.dp(20), 0, 0, a.dp(22));
        nums.addView(pctSub);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = a.dp(4);
        col.addView(nums, np);

        liveBar = new BatteryViews.Bar(a);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, a.dp(14));
        bl.topMargin = a.dp(20);
        col.addView(liveBar, bl);

        // Plugged in: the two numbers people look for next, time left then power.
        LinearLayout two = new LinearLayout(a);
        liveTime = thin("", 44, MainActivity.TEXT);
        timeSub = a.text("to full (car's estimate)", 16, MainActivity.SUB, false);
        timeBox = stat(liveTime, timeSub);
        LinearLayout.LayoutParams tb = new LinearLayout.LayoutParams(-2, -2);
        tb.rightMargin = a.dp(56);
        two.addView(timeBox, tb);
        liveKw = thin("", 44, MainActivity.ACCENT_TEXT);
        kwBox = stat(liveKw, a.text("kW going in", 16, MainActivity.SUB, false));
        two.addView(kwBox);
        LinearLayout.LayoutParams twp = new LinearLayout.LayoutParams(-1, -2);
        twp.topMargin = a.dp(24);
        col.addView(two, twp);
        chargingRow = two;

        liveFoot = a.text("", 19, MainActivity.SUB, false);
        liveFoot.setPadding(0, a.dp(20), 0, 0);
        liveFoot.setLineSpacing(a.dp(6), 1f);
        liveFoot.setMaxLines(3);
        col.addView(liveFoot);
        return col;
    }

    /** Charging tab, right: this month, your prices, kWh per month, every charge. */
    private View buildHistory() {
        FrameLayout f = new FrameLayout(a);

        LinearLayout pr = new LinearLayout(a);
        pr.setGravity(Gravity.CENTER_VERTICAL);
        pr.setPadding(a.dp(28), a.dp(20), a.dp(28), a.dp(12));
        LinearLayout pt = new LinearLayout(a);
        pt.setOrientation(LinearLayout.VERTICAL);
        monthText = a.text("", 22, MainActivity.TEXT, true);
        pt.addView(monthText);
        priceText = a.text("", 16, MainActivity.SUB, false);
        priceText.setPadding(0, a.dp(4), a.dp(16), 0);
        pt.addView(priceText);
        pr.addView(pt, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView costs = pill("Prices", this::pricesDialog);
        costs.setTextSize(18);
        costs.setPadding(a.dp(24), 0, a.dp(24), 0);
        pr.addView(costs, new LinearLayout.LayoutParams(-2, a.dp(56)));
        priceRow = pr;

        months = new WeekBars(a);
        LinearLayout mw = new LinearLayout(a);
        mw.setPadding(a.dp(28), a.dp(8), a.dp(28), a.dp(12));
        mw.addView(months, new LinearLayout.LayoutParams(-1, a.dp(124)));
        monthBox = mw;

        LinearLayout listCard = a.card();
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.setVerticalFadingEdgeEnabled(true);
        sv.setFadingEdgeLength(a.dp(32));
        sv.addView(list, new FrameLayout.LayoutParams(-1, -2));
        listCard.addView(sv, new LinearLayout.LayoutParams(-1, -1));
        f.addView(listCard, new FrameLayout.LayoutParams(-1, -1));
        rightParked = listCard;

        rightNote = a.text("Charging history shows when you're parked.", 24, MainActivity.SUB, false);
        rightNote.setGravity(Gravity.CENTER);
        f.addView(rightNote, new FrameLayout.LayoutParams(-1, -1));
        return f;
    }

    /** Health tab, left: the ring and the one figure. */
    private View buildRing() {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8));
        ring = new BatteryViews.Ring(a);
        col.addView(ring, new LinearLayout.LayoutParams(a.dp(310), a.dp(310)));
        capVal = thin("–", 44, MainActivity.TEXT);
        capVal.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = a.dp(12);
        col.addView(capVal, cp);
        capSub = a.text("", 18, MainActivity.SUB, false);
        capSub.setGravity(Gravity.CENTER);
        capSub.setPadding(0, a.dp(4), 0, 0);
        col.addView(capSub, new LinearLayout.LayoutParams(-1, -2));
        return col;
    }

    /** Health tab, right: what the figure rests on, the full test, and the report. */
    private View buildHealthSide() {
        LinearLayout card = a.card();
        card.setPadding(a.dp(28), a.dp(28), a.dp(28), a.dp(24));
        card.addView(label("BATTERY HEALTH"));
        healthNote = a.text("", 18, MainActivity.SUB, false);
        healthNote.setPadding(0, a.dp(10), 0, 0);
        healthNote.setLineSpacing(a.dp(4), 1f);
        card.addView(healthNote);

        trend = new BatteryViews.Trend(a);
        trend.setMinimumHeight(a.dp(120));
        LinearLayout.LayoutParams trp = new LinearLayout.LayoutParams(-1, 0, 1f);
        trp.topMargin = a.dp(20);
        card.addView(trend, trp);

        testLine = a.text("", 18, MainActivity.SUB, false);
        testLine.setPadding(0, a.dp(20), 0, 0);
        testLine.setMaxLines(4);
        testLine.setLineSpacing(a.dp(4), 1f);
        card.addView(testLine);
        carLine = a.text("", 16, MainActivity.SUB, false);
        carLine.setPadding(0, a.dp(6), 0, 0);
        card.addView(carLine);
        packLine = a.text("", 16, MainActivity.SUB, false);
        packLine.setPadding(0, a.dp(6), 0, a.dp(6));
        packLine.setClickable(true);
        packLine.setOnClickListener(v -> packDialog());
        card.addView(packLine);

        LinearLayout foot = new LinearLayout(a);
        TextView tb = pill("Battery test", this::testDialog);
        tb.setTextSize(18);
        testBtn = tb;
        foot.addView(tb, new LinearLayout.LayoutParams(0, a.dp(60), 1f));
        TextView rep = pill("Battery report", this::report);
        rep.setTextSize(18);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, a.dp(60), 1f);
        rp.leftMargin = a.dp(16);
        foot.addView(rep, rp);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.topMargin = a.dp(20);
        card.addView(foot, fp);
        return card;
    }

    private View chargeRow(Trips.Charge c, long now) {
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(a.dp(28), a.dp(12), a.dp(28), a.dp(12));
        r.setMinimumHeight(a.dp(96));

        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        String day = Trips.day(c.to, now, cal);
        final boolean dc = isDc(a, c);
        LinearLayout l1 = new LinearLayout(a);
        l1.setGravity(Gravity.BOTTOM);
        l1.addView(a.text(day, 22, MainActivity.TEXT, true));
        TextView kind = a.text(dc ? "DC fast charger" : "AC charger", 16, MainActivity.SUB, true);
        kind.setPadding(a.dp(12), 0, 0, a.dp(2));
        l1.addView(kind);
        l.addView(l1);
        // The app wasn't watching: all it knows is the window the charge happened in.
        TextView s = a.text(Trips.clock(c.from, cal) + " – " + Trips.clock(c.seenUntil > c.from ? c.seenUntil : c.to, cal),
                17, MainActivity.SUB, false);
        s.setSingleLine(true);
        s.setPadding(0, a.dp(4), 0, 0);
        l.addView(s);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout mid = new LinearLayout(a);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER);
        BatteryViews.Bar b = new BatteryViews.Bar(a);
        b.set(c.socFrom, c.socTo, false);
        mid.addView(b, new LinearLayout.LayoutParams(a.dp(130), a.dp(14)));
        TextView pc = a.text(c.socFrom + "% → " + c.socTo + "%", 16, MainActivity.SUB, false);
        pc.setPadding(0, a.dp(6), 0, 0);
        mid.addView(pc);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
        mp.rightMargin = a.dp(28);
        r.addView(mid, mp);

        String kwh = (c.measured() ? "+" : "about +") + Trips.km(c.kwh()) + " kWh";
        LinearLayout kc = new LinearLayout(a);
        kc.setOrientation(LinearLayout.VERTICAL);
        kc.setMinimumWidth(a.dp(170));
        TextView k = a.text(kwh, 24, MainActivity.TEXT, true);
        k.setGravity(Gravity.END);
        kc.addView(k, new LinearLayout.LayoutParams(-1, -2));
        float cost = cost(a, c);
        if (!Float.isNaN(cost)) {
            boolean exact = !Float.isNaN(paid(a, c));
            TextView ct = a.text((exact ? "" : "about ") + Trips.dollars(cost), 16, exact ? MainActivity.TEXT : MainActivity.SUB, false);
            ct.setGravity(Gravity.END);
            kc.addView(ct, new LinearLayout.LayoutParams(-1, -2));
        }
        r.addView(kc);
        // Tap a row to say which kind of charger it was (the car can't always tell afterwards).
        r.setClickable(true);
        r.setOnClickListener(v -> kindDialog(c));
        r.setContentDescription(day + ", charged from " + c.socFrom + " to " + c.socTo + " percent, "
                + (c.measured() ? "" : "about ") + Trips.km(c.kwh()) + " kilowatt hours");
        return r;
    }

    private void rebuild(long now) {
        done = Trips.load(new File(a.getFilesDir(), NagService.TRIPS_FILE));
        Trips.Trip cur = NagService.tripNow;
        // A charge shows as soon as the plug is out (while it's in, the card above shows it live).
        Trips.Trip moved = cur != null && (cur.km >= Trips.MIN_KM || !NagService.plugged) ? cur : null;
        List<Trips.Charge> charges = Trips.charges(done, moved);
        List<Trips.Tick> ticks = Trips.loadTicks(new File(a.getFilesDir(), NagService.TICKS_FILE));
        List<Trips.HealthReading> health = Trips.healthDays(ticks, cal);

        // Health: everyday readings (one each time the battery percent moves), a dot per day
        float full = Trips.healthNow(health);
        boolean early = health.size() < Trips.DAYS_SETTLED;
        applyPack(full);
        trend.set(health);
        Trips.TestRun run = NagService.testRun(a);
        boolean tested = run.state == Trips.TestRun.DONE;
        int today = 0;
        long day = Trips.dayStart(now, cal);
        for (Trips.Tick t : ticks) if (t.wall >= day) today++;
        if (tested) {
            // A finished test is the figure: it beats the day-to-day estimate.
            ring.set(run.fullKwh / Trips.PACK_KWH * 100f, false);
            capVal.setVisibility(View.VISIBLE);
            capVal.setText(Trips.one(run.fullKwh) + " kWh");
            capSub.setText("full battery, tested " + Trips.dateLong(run.endWall, cal) + "  ·  "
                    + Trips.one(Trips.PACK_KWH) + " kWh new");
        } else if (Float.isNaN(full)) {
            ring.set(Float.NaN, true);
            capVal.setVisibility(View.GONE);
            int need = Math.max(1, Trips.DAY_MIN_TICKS - today);
            capSub.setText("Drive or charge another " + need + "% today to see your first figure");
        } else {
            ring.set(full / Trips.PACK_KWH * 100f, early);
            capVal.setVisibility(View.VISIBLE);
            capVal.setText(Trips.one(full) + " kWh");
            capSub.setText("full battery now (estimate)  ·  " + Trips.one(Trips.PACK_KWH) + " kWh new");
        }
        healthNote.setText(Float.isNaN(full)
                ? "The app works out what a full battery holds each time the battery percent moves by one. "
                + "It needs three of those in a day for a figure. " + today + " so far today."
                : "Day to day: " + Trips.one(full) + " kWh, " + Math.round(Math.min(100f, full / Trips.PACK_KWH * 100f))
                + "% of new, from " + health.size() + (health.size() == 1 ? " day" : " days") + " of driving and charging"
                + (early ? " (early, settles after " + Trips.DAYS_SETTLED + " days)." : "."));

        // The battery test: always says where it is up to.
        int soc = NagService.live == null ? -1 : NagService.live.soc;
        String st;
        boolean hot = false;
        switch (run.state) {
            case Trips.TestRun.CHARGE:
                st = "Battery test running\n" + (NagService.plugged
                        ? "Charging" + (soc >= 0 ? ", " + soc + "%" : "") + ". Leave it until 100%, then another hour."
                        : run.lastSoc > run.lowSoc + 1 ? "Part way: " + run.lastSoc + "%. Plug back in and leave it until 100%. Don't drive."
                        : "Started at " + run.lowSoc + "%. Plug into an AC charger now and charge to 100%. Don't drive until it's full.");
                hot = true;
                break;
            case Trips.TestRun.DONE:
                float chg = testCharger(run.endWall);
                st = "Battery test " + Trips.dateLong(run.endWall, cal) + ": " + run.lowSoc + "% to 100% took "
                        + Trips.one(run.kwhIn()) + " kWh" + (Float.isNaN(chg) ? "" : "  ·  " + Math.round(run.kwhIn() / chg * 100f)
                        + "% of the charger's " + Trips.one(chg) + " kWh");
                break;
            case Trips.TestRun.FAILED:
                st = "Battery test stopped\n" + run.reason;
                hot = true;
                break;
            default:
                st = "Battery test: not done yet. It is the figure to show a buyer.\nIt can be started once the battery is at "
                        + Trips.TEST_FROM_PCT + "% or less." + (soc >= 0 ? " Now " + soc + "%." : "");
        }
        testLine.setText(st);
        testLine.setTextColor(hot ? MainActivity.ACCENT_TEXT : run.state == Trips.TestRun.DONE ? MainActivity.TEXT : MainActivity.SUB);
        testBtn.setText(run.running() ? "Test running" : "Battery test");
        int car = NagService.batteryHealth;
        Trips.Reading lr = NagService.live;
        // Cycles the way battery makers count them: energy used since new over one full battery.
        String used = lr != null && !Float.isNaN(lr.life) ? Math.round(lr.life / Trips.PACK_KWH) + " charge cycles so far" : "";
        // The car's own health figure, said as exactly that. BYD's own screen only shows it when
        // a setting reads 2 and this car reads 1, so it may not be a live value: never the headline.
        carLine.setText(used + (car >= 0 ? (used.isEmpty() ? "" : "  ·  ") + "the car's own figure: " + car + "%" : ""));
        carLine.setMaxLines(2);
        packLine.setText(packText + "  \u203A");
        packLine.setTextColor(packWarn ? MainActivity.WARN : MainActivity.SUB);

        // This month, the price, and kWh per month
        float[] m = Trips.months(charges, now, cal, 6);
        String[] ml = Trips.monthLabels(now, cal, 6);
        months.set(m, ml, "Kilowatt hours charged per month:");
        int inMonth = 0;
        float spent = 0;
        boolean anyCost = false;
        cal.setTimeInMillis(now);
        int ym = cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH);
        for (Trips.Charge c : charges) {
            cal.setTimeInMillis(c.to);
            if (cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH) != ym) continue;
            inMonth++;
            float cs = cost(a, c);
            if (!Float.isNaN(cs)) {
                spent += cs;
                anyCost = true;
            }
        }
        monthText.setText(inMonth == 0 ? ml[5] + ": no charges yet"
                : ml[5] + ": " + Trips.km(m[5]) + " kWh  ·  " + inMonth + (inMonth == 1 ? " charge" : " charges")
                + (anyCost ? "  ·  about " + Trips.dollars(spent) : ""));
        android.content.SharedPreferences pp = prefs(a);
        int ac = pp.getInt(KEY_PRICE, 0), dcp = pp.getInt(KEY_PRICE_DC, 0);
        priceText.setText(ac + dcp == 0 ? "Set what you pay to see costs"
                : (ac > 0 ? "AC " + ac + "c" : "") + (ac > 0 && dcp > 0 ? "  ·  " : "") + (dcp > 0 ? "DC fast " + dcp + "c" : "")
                + " per kWh");

        // Since the last charge, for the battery card when nothing is plugged in
        Trips.Charge lastC = charges.isEmpty() ? null : charges.get(charges.size() - 1);
        float km = 0, kwh = 0;
        for (Trips.Trip t : done) {
            if (lastC != null && t.start < lastC.to) continue;
            km += t.km;
            if (!Float.isNaN(t.kwh)) kwh += t.kwh;
        }
        sinceCharge = lastC == null || km < 0.1f ? "" : Trips.km(km) + " km since the last charge"
                + (kwh > 0 ? "  ·  " + Trips.km(kwh) + " kWh used" : "");

        // List, newest first
        list.removeAllViews();
        list.addView(priceRow);
        list.addView(monthBox);
        if (charges.isEmpty()) {
            TextView e = a.text("No charges seen yet. A charge shows up after the first drive that follows it.",
                    20, MainActivity.SUB, false);
            e.setPadding(a.dp(28), a.dp(20), a.dp(28), a.dp(28));
            list.addView(e);
        }
        int n = 0;
        for (int i = charges.size() - 1; i >= 0; i--) {
            list.addView(chargeRow(charges.get(i), now));
            if (++n >= 60) break;
        }
        if (list.getParent() instanceof ScrollView) ((ScrollView) list.getParent()).scrollTo(0, 0);
        shownVersion = NagService.tripsVersion;
        shownHealth = car;
        shownTicks = NagService.ticksVersion;
        shownTest = NagService.testVersion;
        shownCurSoc = moved == null ? -1 : moved.socStart;
        shownPlugged = NagService.plugged;
    }

    private boolean shownPlugged;

    static final String REPORT_FILE = "battery-report.html";

    /** Writes the report next to the car report and offers it to a phone. The page is put
     *  together here; the files are written and the little server started off the main thread. */
    private void report() {
        if (reportBusy) return;
        final File dir = a.getExternalFilesDir(null);
        final List<Trips.Trip> all;
        final Trips.TestRun test;
        final float full;
        final String html;
        try {
            if (dir == null) throw new java.io.IOException("no storage");
            all = Trips.load(new File(a.getFilesDir(), NagService.TRIPS_FILE));
            Trips.Trip cur = NagService.tripNow;
            Trips.Trip moved = cur != null && (cur.km >= Trips.MIN_KM || !NagService.plugged) ? cur : null;
            Trips.Reading r = NagService.live;
            String ver = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
            List<Trips.HealthReading> hr = Trips.healthDays(
                    Trips.loadTicks(new File(a.getFilesDir(), NagService.TICKS_FILE)), cal);
            full = Trips.healthNow(hr);
            applyPack(full);
            test = NagService.testRun(a);
            html = Trips.report(all, moved, hr, full, test, testCharger(test.endWall),
                    NagService.vin, r == null ? Float.NaN : r.odo, r == null ? Float.NaN : r.life,
                    NagService.batteryHealth, ver, System.currentTimeMillis(), cal);
        } catch (Exception e) {
            new Sheet(a, "Battery report").text("Couldn't write the report: " + e.getMessage())
                    .button("Done", true, null).show();
            return;
        }
        reportBusy = true;
        new Thread(() -> {
            final File f = new File(dir, REPORT_FILE);
            String err = null;
            ReportServer srv = null;
            List<String> ips = new java.util.ArrayList<>();
            try {
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
                    o.write(html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                File csvF = new File(dir, "trips.csv");
                Trips.exportCsv(all, csvF, Calendar.getInstance());
                ips = ReportServer.addresses();
                if (!ips.isEmpty()) {
                    srv = new ReportServer(html, java.nio.file.Files.readAllBytes(csvF.toPath()));
                    if (!srv.start()) srv = null;
                }
            } catch (Exception e) {
                err = String.valueOf(e.getMessage());
            }
            final String fErr = err;
            final ReportServer fSrv = srv;
            final List<String> fIps = ips;
            a.runOnUiThread(() -> showReport(test, full, f, fErr, fSrv, fIps));
        }, "naglab-battery-report").start();
    }

    private void showReport(Trips.TestRun test, float full, File f, String err, ReportServer srv, List<String> ips) {
        reportBusy = false;
        if (a.isFinishing() || a.isDestroyed()) {   // the screen was turned or left meanwhile
            if (srv != null) srv.stop();
            return;
        }
        closeReport();
        server = srv;
        Sheet sh = new Sheet(a, "Battery report");
        if (err != null) {
            sh.text("Couldn't write the report: " + err);
        } else {
            sh.text(test.state == Trips.TestRun.DONE ? "Battery tested at " + Math.round(Math.min(100f,
                    test.fullKwh / Trips.PACK_KWH * 100f)) + "% of new on " + Trips.dateLong(test.endWall, cal) + "."
                    : Float.isNaN(full) ? "No health figure yet, so it only has your charging and trips so far. "
                    + "For a report worth showing a buyer, run the battery test first."
                    : "Day-to-day estimate: about " + Math.round(Math.min(100f, full / Trips.PACK_KWH * 100f))
                    + "% of new. For a report worth showing a buyer, run the battery test first.");
            if (server != null) {
                sh.text("On a phone on the same Wi-Fi as the car, open:").big("http://" + ips.get(0) + ":" + ReportServer.PORT);
                if (ips.size() > 1) {
                    sh.text("Or join the car's own hotspot and open:").big("http://" + ips.get(1) + ":" + ReportServer.PORT);
                }
                sh.note("Keep this open while you do. From there you can save, print or send it. It has your VIN on it.");
            } else {
                sh.text("The car isn't on Wi-Fi, so it can't hand it to your phone right now. Connect the car to your "
                        + "phone's hotspot or home Wi-Fi and press Battery report again.").note("Saved on the car as " + f.getPath());
            }
        }
        reportSheet = sh;
        sh.button("Done", true, null).onDismiss(() -> {
            if (server != null) server.stop();
            server = null;
            reportSheet = null;
        }).show();
    }

    /** The report box and the server behind it go away together: on Done, and whenever the
     *  screen is left or turned (MainActivity.onPause), so the report with the VIN on it is
     *  never left being served with nothing on screen saying so. */
    void closeReport() {
        if (reportSheet != null) reportSheet.dismiss();
        if (server != null) server.stop();
        server = null;
        reportSheet = null;
    }

    private ReportServer server;
    private Sheet reportSheet;
    private boolean reportBusy;

    // ---- Prices: what the owner pays. Nothing is assumed; no price = no dollar figures.
    static final String KEY_PRICE = "price_cents", KEY_PRICE_DC = "price_dc_cents",
            KEY_PETROL = "petrol_cents", KEY_L100 = "petrol_l100_x10";

    static android.content.SharedPreferences prefs(android.content.Context c) {
        return c.getSharedPreferences(NagService.PREFS, android.content.Context.MODE_PRIVATE);
    }

    /** Fast (DC) or not: what the owner said for this charge if they said, else what the car
     *  showed (more than AC can do, seen live or worked out from the time it had). */
    static boolean isDc(android.content.Context c, Trips.Charge ch) {
        android.content.SharedPreferences p = prefs(c);
        String k = "chg_dc_" + ch.from;   // keyed on when the charge began: that never moves
        if (p.contains(k)) return p.getBoolean(k, false);
        long seen = p.getLong(NagService.KEY_DC_SEEN, 0);
        return ch.mustBeDc() || (seen >= ch.from && seen <= ch.until());
    }

    /** Dollars for this charge, NaN if the price for its kind isn't set. */
    static float cost(android.content.Context c, Trips.Charge ch) {
        android.content.SharedPreferences p = prefs(c);
        float paid = p.getFloat("chg_paid_" + ch.from, Float.NaN);
        if (!Float.isNaN(paid)) return paid;   // what it really cost beats any sum
        return Trips.chargeCost(ch, isDc(c, ch), p.getInt(KEY_PRICE, 0), p.getInt(KEY_PRICE_DC, 0));
    }

    private float testCharger(long to) {
        return prefs(a).getFloat("test_kwh_" + to, Float.NaN);
    }

    private android.widget.EditText field(LinearLayout into, String label, String hint, String value, boolean decimal) {
        TextView l = a.text(label, 18, MainActivity.TEXT, true);
        l.setPadding(0, a.dp(8), 0, 0);
        into.addView(l);
        android.widget.EditText e = new android.widget.EditText(a);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | (decimal ? android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(22);
        e.setTextColor(MainActivity.TEXT);
        e.setHintTextColor(MainActivity.SUB);
        e.setSingleLine(true);
        into.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    private static int toInt(android.widget.EditText e, float times) {
        try {
            return Math.max(0, Math.round(Float.parseFloat(e.getText().toString().trim().replace(',', '.')) * times));
        } catch (NumberFormatException x) {
            return 0;   // empty = not set
        }
    }

    private void pricesDialog() {
        final android.content.SharedPreferences p = prefs(a);
        Sheet sh = new Sheet(a, "Your prices");
        LinearLayout box = sh.body();
        int ac = p.getInt(KEY_PRICE, 0), dc = p.getInt(KEY_PRICE_DC, 0), pet = p.getInt(KEY_PETROL, 0),
                l100 = p.getInt(KEY_L100, 0);
        final android.widget.EditText eAc = field(box, "AC charging (home, wallbox, power point): cents per kWh",
                "your power bill rate, e.g. 28", ac > 0 ? String.valueOf(ac) : "", false);
        final android.widget.EditText eDc = field(box, "DC fast chargers: cents per kWh", "what the fast charger shows, e.g. 65",
                dc > 0 ? String.valueOf(dc) : "", false);
        final android.widget.EditText ePet = field(box, "Petrol: cents per litre", "what you'd pay at the pump, e.g. 210",
                pet > 0 ? String.valueOf(pet) : "", false);
        final android.widget.EditText eL = field(box, "Fuel a petrol car would use: litres per 100 km (not engine size)",
                "a medium SUV uses about 7.4, a hybrid about 4.5", l100 > 0 ? Trips.one(l100 / 10f) : "", true);
        sh.note("Leave one empty and its figures don't show. The real bill runs a little higher (charging losses).");
        sh.button("Cancel", false, null).button("Save", true, () -> {
            p.edit().putInt(KEY_PRICE, Math.min(500, toInt(eAc, 1))).putInt(KEY_PRICE_DC, Math.min(500, toInt(eDc, 1)))
                    .putInt(KEY_PETROL, Math.min(1000, toInt(ePet, 1))).putInt(KEY_L100, Math.min(300, toInt(eL, 10)))
                    .apply();
            shownVersion = -1;
            render();
        }).show();
    }

    /** Start it, see where it is up to, stop it, or (once done) type in what the charger showed. */
    private void testDialog() {
        final Trips.TestRun run = NagService.testRun(a);
        String[] steps = {
                "Plug into an AC charger, 7 kW (32 amp) if you can. Not a fast charger. A power point works but "
                        + "takes about a day and a half.",
                "Let it charge to 100% without stopping, then leave it plugged in another hour so the cells balance. "
                        + "The car can be off. Don't drive it until it's full.",
                "Start the car. The result is here, and on the battery report."};
        final boolean ready = Trips.TestRun.canStart(NagService.live);
        int socNow = NagService.live == null ? -1 : NagService.live.soc;
        if (run.running()) {
            new Sheet(a, "Battery test is running")
                    .text("Started " + Trips.dateLong(run.startWall, cal) + " at " + Trips.clock(run.startWall, cal)
                            + " from " + run.lowSoc + "%. The app is following it, and the Health tab says where it is up to.")
                    .steps(steps)
                    .button("Stop the test", false, () -> {
                        run.stop();
                        saveTest();
                    }).button("Keep going", true, null).show();
        } else if (run.state == Trips.TestRun.DONE) {
            Sheet sh = new Sheet(a, "Battery test result")
                    .text(Trips.dateLong(run.endWall, cal) + ": from " + run.lowSoc + "% to 100% the battery took "
                            + Trips.one(run.kwhIn()) + " kWh. A full battery is " + Trips.one(run.fullKwh) + " kWh, "
                            + Math.round(Math.min(100f, run.fullKwh / Trips.PACK_KWH * 100f)) + "% of new.")
                    .note("If the charger showed how many kWh it delivered, type it in. The battery should have taken "
                            + "80 to 95% of it, and that goes on the report as an outside check.");
            float ch = testCharger(run.endWall);
            final android.widget.EditText eK = field(sh.body(), "kWh the charger showed", "e.g. 86.4",
                    Float.isNaN(ch) ? "" : Trips.one(ch), true);
            if (ready) sh.button("Run a new test", false, () -> startTest(run));
            sh.button("Save", true, () -> {
                float v = toInt(eK, 10) / 10f;
                android.content.SharedPreferences.Editor e = prefs(a).edit();
                if (v > 0) e.putFloat("test_kwh_" + run.endWall, v);
                else e.remove("test_kwh_" + run.endWall);
                e.apply();
                shownVersion = -1;
                render();
            }).show();
        } else {
            Sheet sh = new Sheet(a, "Battery test");
            if (run.state == Trips.TestRun.FAILED) sh.text("The last test stopped: " + run.reason);
            sh.text("The proper way to measure the battery: one uninterrupted charge from nearly empty to full. It "
                    + "gives the figure that goes on the battery report, the one to show a buyer.");
            if (ready) {
                sh.text("The battery is at " + socNow + "%, low enough to start now.").steps(steps)
                        .note("Press Start and the app follows it from here. Nothing is counted unless you start it.")
                        .button("Not now", false, null).button("Start the test", true, () -> startTest(run)).show();
            } else {
                sh.text("It starts from " + Trips.TEST_FROM_PCT + "% or less" + (socNow >= 0 ? ", and the battery is at "
                        + socNow + "%" : "") + ". Come back here when it's that low and the Start button will be here. Then:")
                        .steps(steps).button("OK", true, null).show();
            }
        }
    }

    private void startTest(Trips.TestRun run) {
        if (!run.start(System.currentTimeMillis(), NagService.live)) return;
        saveTest();
    }

    /** The test changed on a tap: show it now, write its small file off the main thread. */
    private void saveTest() {
        final android.content.Context c = a.getApplicationContext();
        new Thread(() -> NagService.saveTest(c), "naglab-test-save").start();
        shownVersion = -1;
        render();
    }

    private TextView pill(String s, Runnable r) {
        TextView t = a.text(s, 26, MainActivity.TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(MainActivity.round(MainActivity.RAISED, a.dp(28)));
        t.setClickable(true);
        t.setOnClickListener(v -> r.run());
        return t;
    }

    /** The car can't always tell afterwards which kind of charger it was, and the two cost very
     *  different money. A question, not a flip, so a stray touch changes nothing. */
    private void kindDialog(final Trips.Charge c) {
        final boolean[] dc = {isDc(a, c)};
        final android.content.SharedPreferences p = prefs(a);
        Sheet sh = new Sheet(a, "This charge");
        sh.text(Trips.day(c.to, System.currentTimeMillis(), cal) + ", " + c.socFrom + "% → " + c.socTo + "%, "
                + (c.measured() ? "" : "about ") + Trips.km(c.kwh()) + " kWh into the battery");
        // Which kind: two pills, the chosen one lit. Starts on what the car showed.
        LinearLayout kinds = new LinearLayout(a);
        final TextView acB = a.text("AC charger", 19, MainActivity.TEXT, true);
        final TextView dcB = a.text("DC fast charger", 19, MainActivity.TEXT, true);
        final Runnable paint = () -> {
            acB.setBackground(MainActivity.round(dc[0] ? MainActivity.RAISED : MainActivity.ACCENT, a.dp(30)));
            dcB.setBackground(MainActivity.round(dc[0] ? MainActivity.ACCENT : MainActivity.RAISED, a.dp(30)));
            acB.setTextColor(dc[0] ? MainActivity.TEXT : 0xFF16110D);
            dcB.setTextColor(dc[0] ? 0xFF16110D : MainActivity.TEXT);
        };
        for (final TextView b : new TextView[]{acB, dcB}) {
            b.setGravity(Gravity.CENTER);
            b.setClickable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, a.dp(60), 1f);
            if (b == dcB) lp.leftMargin = a.dp(16);
            kinds.addView(b, lp);
        }
        acB.setOnClickListener(v -> {
            dc[0] = false;
            paint.run();
        });
        dcB.setOnClickListener(v -> {
            dc[0] = true;
            paint.run();
        });
        paint.run();
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(-1, -2);
        kp.topMargin = a.dp(16);
        sh.body().addView(kinds, kp);
        sh.note("AC: a wallbox, a portable charger or a power point. DC: a public fast charger, the thick cable.");
        float paid = p.getFloat("chg_paid_" + c.from, Float.NaN);
        final android.widget.EditText eP = field(sh.body(), "What this charge cost you, in dollars",
                "leave empty to use your usual price", Float.isNaN(paid) ? "" : String.format(java.util.Locale.US, "%.2f", paid), true);
        sh.button("Cancel", false, null).button("Save", true, () -> {
            android.content.SharedPreferences.Editor e = p.edit().putBoolean("chg_dc_" + c.from, dc[0]);
            float v = toInt(eP, 100) / 100f;
            if (v > 0) e.putFloat("chg_paid_" + c.from, v);
            else e.remove("chg_paid_" + c.from);
            e.apply();
            shownVersion = -1;
            render();
        }).show();
    }

    /** What the owner typed for this one charge, else NaN. */
    static float paid(android.content.Context c, Trips.Charge ch) {
        return prefs(c).getFloat("chg_paid_" + ch.from, Float.NaN);
    }

    /** Called about once a second while one of the two tabs is on screen. */
    void render() {
        long now = System.currentTimeMillis();
        Trips.Reading r = NagService.live;
        boolean moving = r != null && r.moving();
        Trips.Trip cur = NagService.tripNow;
        int curSoc = cur == null || (cur.km < Trips.MIN_KM && NagService.plugged) ? -1 : cur.socStart;
        if (done == null || shownVersion != NagService.tripsVersion || shownHealth != NagService.batteryHealth
                || shownTicks != NagService.ticksVersion || shownPlugged != NagService.plugged
                || shownTest != NagService.testVersion
                || shownCurSoc != curSoc) {
            rebuild(now);
        }
        rightParked.setVisibility(moving ? View.GONE : View.VISIBLE);
        rightNote.setVisibility(moving ? View.VISIBLE : View.GONE);

        boolean plugged = NagService.plugged && !moving;
        boolean charging = plugged && r != null && r.soc >= 0 && !Float.isNaN(NagService.chargeKw)
                && NagService.chargeKw > 0.2f;
        chargingRow.setVisibility(charging ? View.VISIBLE : View.GONE);
        int cw = a.dp(charging ? 250 : 400);   // charging adds two rows: the picture gives them room
        if (carPic.getLayoutParams().width != cw) {
            carPic.getLayoutParams().width = cw;
            carPic.requestLayout();
        }
        int soc = r == null ? -1 : r.soc;
        livePct.setText(soc < 0 ? "–" : soc + "%");
        String range = r != null && r.range >= 0 ? r.range + " km range" : "";
        if (!charging) {
            rightLabel.setText("BATTERY");
            rightSum.setText(plugged ? (soc >= 100 || NagService.chargeDone ? "Charge complete" : "Plugged in, not charging")
                    : NagService.testRun(a).running() ? "Battery test running (see Health)" : "");
            pctSub.setText(range);
            liveBar.set(-1, Math.max(0, soc), false);
            liveFoot.setText(sinceCharge);
            liveFoot.setVisibility(sinceCharge.isEmpty() ? View.GONE : View.VISIBLE);
            return;
        }
        // Where this charge started: the battery at the end of the last trip.
        Trips.Trip last = done.isEmpty() ? null : done.get(done.size() - 1);
        int from = last != null && last.socEnd >= 0 && last.socEnd <= soc ? last.socEnd : -1;
        float added = last != null && !Float.isNaN(last.batEnd) && !Float.isNaN(r.bat) ? r.bat - last.batEnd
                : Float.NaN;
        rightLabel.setText("CHARGING");
        rightSum.setText("");
        pctSub.setText(range);
        liveKw.setText(Trips.one(NagService.chargeKw));
        liveBar.set(from, soc, true);
        int rest = NagService.chargeRestMin;
        timeBox.setVisibility(rest > 0 ? View.VISIBLE : View.GONE);
        if (rest > 0) liveTime.setText(Trips.duration(rest));
        StringBuilder foot = new StringBuilder();
        if (rest > 0) foot.append("Full about ").append(Trips.clock(now + rest * 60_000L, cal));
        else if (rest == 0) foot.append("Nearly full");
        String sep = "  ·  ";
        if (!Float.isNaN(added) && added >= 0.1f) {
            foot.append(foot.length() > 0 ? sep : "").append("+").append(Trips.one(added)).append(" kWh added");
        } else if (from >= 0 && soc > from) {
            foot.append(foot.length() > 0 ? sep : "").append("+").append(soc - from).append("% added");
        }
        // Range this charge has added: the car's own range figure, shared out by battery %.
        if (from >= 0 && soc > from && r.range > 0) {
            foot.append(sep).append("about +").append(Math.round(r.range * (soc - from) / (float) soc)).append(" km");
        }
        liveFoot.setText(foot);
        liveFoot.setVisibility(View.VISIBLE);
    }
}
