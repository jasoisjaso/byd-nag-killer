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
 * The Battery tab. Left: battery health (what a full battery holds now against new, one reading
 * per big charge). Right: charging now when the car is plugged in and on, else kWh per month;
 * under it every charge the app has seen.
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
    private TextView priceText, monthText, pctSub, timeSub;
    private View priceRow, timeBox, kwBox, chargingRow;
    private String sinceCharge = "";
    private TextView capVal, capSub, healthNote, carLine;
    private TextView rightLabel, rightSum, livePct, liveKw, liveTime, liveFoot, rightNote;
    private View liveBox, monthBox, rightParked;
    private WeekBars months;
    private LinearLayout list;
    private int shownVersion = -1, shownHealth = -2, shownCurSoc = -2, shownTicks = -1;
    private TextView testLine;
    private long lastTestTo;
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

        LinearLayout nums = new LinearLayout(a);
        nums.setGravity(Gravity.BOTTOM);
        livePct = thin("–", 104, MainActivity.TEXT);
        nums.addView(livePct);
        pctSub = a.text("", 22, MainActivity.SUB, false);
        pctSub.setPadding(a.dp(20), 0, 0, a.dp(18));
        nums.addView(pctSub);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = a.dp(4);
        col.addView(nums, np);

        liveBar = new BatteryViews.Bar(a);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, a.dp(72));
        bl.topMargin = a.dp(16);
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
        col.addView(ring, new LinearLayout.LayoutParams(a.dp(300), a.dp(300)));
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

        testLine = a.text("", 19, MainActivity.SUB, true);
        testLine.setPadding(0, a.dp(20), 0, 0);
        testLine.setMaxLines(2);
        card.addView(testLine);
        carLine = a.text("", 16, MainActivity.SUB, false);
        carLine.setPadding(0, a.dp(6), 0, 0);
        card.addView(carLine);

        LinearLayout foot = new LinearLayout(a);
        TextView tb = pill("Battery test", this::testDialog);
        tb.setTextSize(18);
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
        TextView kind = a.text(dc ? "DC fast charger" : "AC charger", 16, dc ? MainActivity.ACCENT_TEXT : MainActivity.SUB, true);
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
            TextView ct = a.text("about " + Trips.dollars(cost), 16, MainActivity.SUB, false);
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
        trend.set(health);
        if (Float.isNaN(full)) {
            ring.set(Float.NaN, true);
            capVal.setVisibility(View.GONE);
            capSub.setText("First figure after " + Trips.DAY_MIN_TICKS + " readings");
            int today = 0;
            long day = Trips.dayStart(now, cal);
            for (Trips.Tick t : ticks) if (t.wall >= day) today++;
            healthNote.setText("Measuring: " + today + " of " + Trips.DAY_MIN_TICKS + " readings today. One is taken "
                    + "each time the battery percent moves.");
        } else {
            ring.set(full / Trips.PACK_KWH * 100f, early);
            capVal.setVisibility(View.VISIBLE);
            capVal.setText(Trips.one(full) + " kWh");
            capSub.setText("full battery now (estimate)  ·  " + Trips.one(Trips.PACK_KWH) + " kWh new");
            healthNote.setText("From " + health.size() + (health.size() == 1 ? " day" : " days") + " of readings since "
                    + Trips.dateLong(health.get(0).wall, cal) + ", by the car's own battery computer."
                    + (early ? " Early: settles after " + Trips.DAYS_SETTLED + " days." : ""));
        }
        Trips.Charge test = Trips.lastTest(charges);
        lastTestTo = test == null ? 0 : test.to;
        if (test == null) {
            testLine.setText("Full test: not done yet");
            testLine.setTextColor(MainActivity.SUB);
        } else {
            float tk = test.fullKwh();
            float ch = testCharger(test.to);
            testLine.setText("Full test " + Trips.dateLong(test.to, cal) + ": " + Trips.one(tk) + " kWh, "
                    + Math.round(Math.min(100f, tk / Trips.PACK_KWH * 100f)) + "% of new"
                    + (Float.isNaN(ch) ? "" : "  ·  " + Math.round(test.kwh() / ch * 100f) + "% of the charger's "
                    + Trips.one(ch) + " kWh"));
            testLine.setTextColor(MainActivity.TEXT);
        }
        int car = NagService.batteryHealth;
        Trips.Reading lr = NagService.live;
        // Cycles the way battery makers count them: energy used since new over one full battery.
        String used = lr != null && !Float.isNaN(lr.life) ? Math.round(lr.life / Trips.PACK_KWH) + " charge cycles so far" : "";
        // The car's own health figure, said as exactly that. BYD's own screen only shows it when
        // a setting reads 2 and this car reads 1, so it may not be a live value: never the headline.
        carLine.setText(used + (car >= 0 ? (used.isEmpty() ? "" : "  ·  ") + "the car's own figure: " + car + "%" : ""));
        carLine.setMaxLines(2);

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
        list.addView(a.divider());
        if (charges.isEmpty()) {
            TextView e = a.text("No charges seen yet. A charge shows up after the first drive that follows it.",
                    20, MainActivity.SUB, false);
            e.setPadding(a.dp(28), a.dp(20), a.dp(28), a.dp(28));
            list.addView(e);
        }
        int n = 0;
        for (int i = charges.size() - 1; i >= 0; i--) {
            if (n > 0) list.addView(a.divider());
            list.addView(chargeRow(charges.get(i), now));
            if (++n >= 60) break;
        }
        if (list.getParent() instanceof ScrollView) ((ScrollView) list.getParent()).scrollTo(0, 0);
        shownVersion = NagService.tripsVersion;
        shownHealth = car;
        shownTicks = NagService.ticksVersion;
        shownCurSoc = moved == null ? -1 : moved.socStart;
        shownPlugged = NagService.plugged;
    }

    private boolean shownPlugged;

    static final String REPORT_FILE = "battery-report.html";

    /** Writes the report next to the car report and says where it is. */
    private void report() {
        String msg;
        try {
            File dir = a.getExternalFilesDir(null);
            if (dir == null) throw new java.io.IOException("no storage");
            List<Trips.Trip> all = Trips.load(new File(a.getFilesDir(), NagService.TRIPS_FILE));
            Trips.Trip cur = NagService.tripNow;
            Trips.Trip moved = cur != null && (cur.km >= Trips.MIN_KM || !NagService.plugged) ? cur : null;
            Trips.Reading r = NagService.live;
            String ver = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
            List<Trips.HealthReading> hr = Trips.healthDays(
                    Trips.loadTicks(new File(a.getFilesDir(), NagService.TICKS_FILE)), cal);
            float full = Trips.healthNow(hr);
            Trips.Charge test = Trips.lastTest(Trips.charges(all, moved));
            String html = Trips.report(all, moved, hr, full, test == null ? Float.NaN : testCharger(test.to),
                    NagService.vin, r == null ? Float.NaN : r.odo, r == null ? Float.NaN : r.life,
                    NagService.batteryHealth, ver, System.currentTimeMillis(), cal);
            File f = new File(dir, REPORT_FILE);
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
                o.write(html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            File csvF = new File(dir, "trips.csv");
            Trips.exportCsv(all, csvF, cal);
            List<String> ips = ReportServer.addresses();
            if (!ips.isEmpty()) {
                server = new ReportServer(html, java.nio.file.Files.readAllBytes(csvF.toPath()));
                if (!server.start()) server = null;
            }
            msg = (Float.isNaN(full) ? "No health figure yet, so it only has your charging and trips so far.\n\n"
                    : "Battery about " + Math.round(Math.min(100f, full / Trips.PACK_KWH * 100f)) + "% of new, from "
                    + hr.size() + (hr.size() == 1 ? " day" : " days") + " of readings.\n\n")
                    + (server != null ? "On a phone on the same Wi-Fi as the car, open:\n\n"
                    + "http://" + ips.get(0) + ":" + ReportServer.PORT
                    + (ips.size() > 1 ? "\n\nOr join the car's own hotspot and open:\n\nhttp://" + ips.get(1) + ":" + ReportServer.PORT : "")
                    + "\n\nKeep this box open while you do. From there you can save, print or send it. "
                    + "It has your VIN on it."
                    : "The car isn't on Wi-Fi, so it can't hand it to your phone right now. Connect the car to your "
                    + "phone's hotspot or home Wi-Fi and press Battery report again.\n\nSaved on the car as " + f.getPath());
        } catch (Exception e) {
            msg = "Couldn't write the report: " + e.getMessage();
        }
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Battery report").setMessage(msg).setPositiveButton("Done", null)
                .setOnDismissListener(x -> {
                    if (server != null) server.stop();
                    server = null;
                }).show();
        TextView body = d.findViewById(android.R.id.message);
        if (body != null) body.setTextSize(20);
        android.widget.Button ok = d.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
        if (ok != null) {   // car-sized target
            ok.setTextSize(20);
            ok.setMinHeight(a.dp(76));
            ok.setMinWidth(a.dp(140));
        }
    }

    private ReportServer server;

    // ---- Prices: what the owner pays. Nothing is assumed; no price = no dollar figures.
    static final String KEY_PRICE = "price_cents", KEY_PRICE_DC = "price_dc_cents",
            KEY_PETROL = "petrol_cents", KEY_L100 = "petrol_l100_x10";

    static android.content.SharedPreferences prefs(android.content.Context c) {
        return c.getSharedPreferences(NagService.PREFS, android.content.Context.MODE_PRIVATE);
    }

    static int price(android.content.Context c) {
        return prefs(c).getInt(KEY_PRICE, 0);
    }

    /** Fast (DC) or not: what the owner said for this charge if they said, else what the car
     *  showed (more than AC can do, seen live or worked out from the time it had). */
    static boolean isDc(android.content.Context c, Trips.Charge ch) {
        android.content.SharedPreferences p = prefs(c);
        String k = "chg_dc_" + ch.to;
        if (p.contains(k)) return p.getBoolean(k, false);
        long seen = p.getLong(NagService.KEY_DC_SEEN, 0);
        return ch.mustBeDc() || (seen >= ch.from && seen <= ch.to);
    }

    /** Dollars for this charge, NaN if the price for its kind isn't set. */
    static float cost(android.content.Context c, Trips.Charge ch) {
        android.content.SharedPreferences p = prefs(c);
        return Trips.chargeCost(ch, isDc(c, ch), p.getInt(KEY_PRICE, 0), p.getInt(KEY_PRICE_DC, 0));
    }

    private float testCharger(long to) {
        return prefs(a).getFloat("test_kwh_" + to, Float.NaN);
    }

    private android.widget.EditText field(LinearLayout into, String label, String hint, String value, boolean decimal) {
        TextView l = a.text(label, 18, MainActivity.TEXT, true);
        l.setPadding(0, a.dp(14), 0, 0);
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

    private void bigButtons(android.app.AlertDialog d) {
        for (int w : new int[]{android.app.AlertDialog.BUTTON_POSITIVE, android.app.AlertDialog.BUTTON_NEGATIVE}) {
            android.widget.Button b = d.getButton(w);
            if (b == null) continue;
            b.setTextSize(20);
            b.setMinHeight(a.dp(76));
            b.setMinWidth(a.dp(140));
        }
    }

    private static int toInt(android.widget.EditText e, float times) {
        try {
            return Math.max(0, Math.round(Float.parseFloat(e.getText().toString().trim().replace(',', '.')) * times));
        } catch (NumberFormatException x) {
            return 0;   // empty = not set
        }
    }

    private void pricesDialog() {
        android.content.SharedPreferences p = prefs(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(a.dp(28), a.dp(4), a.dp(28), 0);
        int ac = p.getInt(KEY_PRICE, 0), dc = p.getInt(KEY_PRICE_DC, 0), pet = p.getInt(KEY_PETROL, 0),
                l100 = p.getInt(KEY_L100, 0);
        final android.widget.EditText eAc = field(box, "AC charging (home, wallbox, power point), cents per kWh", "your power bill rate, e.g. 28",
                ac > 0 ? String.valueOf(ac) : "", false);
        final android.widget.EditText eDc = field(box, "DC fast chargers, cents per kWh", "what the fast charger shows, e.g. 65",
                dc > 0 ? String.valueOf(dc) : "", false);
        final android.widget.EditText ePet = field(box, "Petrol, cents per litre", "what you'd pay at the pump, e.g. 210",
                pet > 0 ? String.valueOf(pet) : "", false);
        final android.widget.EditText eL = field(box, "Fuel a petrol car would use: litres per 100 km (not engine size)",
                "a medium SUV uses about 7.4, a hybrid about 4.5", l100 > 0 ? Trips.one(l100 / 10f) : "", true);
        TextView note = a.text("Leave one empty and its figures just don't show. Costs use the kWh that reached the "
                + "battery, so the real bill is a little higher.", 15, MainActivity.SUB, false);
        note.setPadding(0, a.dp(12), 0, 0);
        box.addView(note);
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Your prices").setView(sv).setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (x, w) -> {
                    p.edit().putInt(KEY_PRICE, Math.min(500, toInt(eAc, 1))).putInt(KEY_PRICE_DC, Math.min(500, toInt(eDc, 1)))
                            .putInt(KEY_PETROL, Math.min(1000, toInt(ePet, 1))).putInt(KEY_L100, Math.min(300, toInt(eL, 10)))
                            .apply();
                    shownVersion = -1;
                    render();
                }).show();
        // keep Cancel / Save above the keyboard
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        bigButtons(d);
    }

    /** What the test is, and (once one is done) where to type what the charger showed. */
    private void testDialog() {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(a.dp(28), a.dp(4), a.dp(28), 0);
        TextView how = a.text("The proper way to measure the battery, the same idea as Tesla's own test: one "
                + "uninterrupted charge from nearly empty to full.\n\n"
                + "1.  Run the battery down to " + Trips.TEST_FROM_PCT + "% or less.\n"
                + "2.  Plug into an AC charger, 7 kW (32 amp) if you can. Not a fast charger. A power point works "
                + "but takes about a day and a half.\n"
                + "3.  Let it charge to 100% without stopping, then leave it plugged in another hour so the cells "
                + "balance. The car can be off.\n"
                + "4.  Drive off. The result shows here by itself.\n\n"
                + "If the charger shows how many kWh it delivered, note it before you unplug and type it in here "
                + "afterwards. The battery should have taken 80 to 95% of it. That is an outside check on the car's "
                + "own numbers.", 19, MainActivity.TEXT, false);
        box.addView(how);
        final long to = lastTestTo;
        final android.widget.EditText eK;
        if (to > 0) {
            float ch = testCharger(to);
            eK = field(box, "Last test (" + Trips.dateLong(to, cal) + "): kWh the charger showed", "e.g. 78.4",
                    Float.isNaN(ch) ? "" : Trips.one(ch), true);
        } else {
            eK = null;
        }
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Battery test").setView(sv);
        if (eK != null) {
            b.setNegativeButton("Cancel", null).setPositiveButton("Save", (x, w) -> {
                float v = toInt(eK, 10) / 10f;
                android.content.SharedPreferences.Editor e = prefs(a).edit();
                if (v > 0) e.putFloat("test_kwh_" + to, v);
                else e.remove("test_kwh_" + to);
                e.apply();
                shownVersion = -1;
                render();
            });
        } else {
            b.setPositiveButton("OK", null);
        }
        bigButtons(b.show());
    }

    private TextView pill(String s, Runnable r) {
        TextView t = a.text(s, 26, MainActivity.TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(MainActivity.round(MainActivity.RAISED, a.dp(28)));
        t.setClickable(true);
        t.setOnClickListener(v -> r.run());
        return t;
    }


    /** Called about once a second while the tab is on screen. */
    /** The car can't always tell afterwards which kind of charger it was, and the two cost very
     *  different money. A question, not a flip, so a stray touch changes nothing. */
    private void kindDialog(final Trips.Charge c) {
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("What kind of charger was this?")
                .setMessage(Trips.day(c.to, System.currentTimeMillis(), cal) + ", " + c.socFrom + "% → " + c.socTo
                        + "%\n\nAC: a wallbox, a portable charger or a power point, at home or out.\n"
                        + "DC: a public fast charger, the kind with the thick cable.")
                .setNegativeButton("AC charger", (x, w) -> setKind(c, false))
                .setPositiveButton("DC fast charger", (x, w) -> setKind(c, true)).show();
        TextView body = d.findViewById(android.R.id.message);
        if (body != null) body.setTextSize(20);
        bigButtons(d);
    }

    private void setKind(Trips.Charge c, boolean dc) {
        prefs(a).edit().putBoolean("chg_dc_" + c.to, dc).apply();
        shownVersion = -1;
        render();
    }

    /** For testing from a computer: am start ... --ez prices true. */
    void openPrices() {
        pricesDialog();
    }

    /** For testing: forget every "this one was a fast charger / home" choice. */
    void clearKinds() {
        android.content.SharedPreferences p = prefs(a);
        android.content.SharedPreferences.Editor e = p.edit();
        for (String k : p.getAll().keySet()) if (k.startsWith("chg_dc_")) e.remove(k);
        e.apply();
        shownVersion = -1;
    }

    void render() {
        long now = System.currentTimeMillis();
        Trips.Reading r = NagService.live;
        boolean moving = r != null && r.moving();
        Trips.Trip cur = NagService.tripNow;
        int curSoc = cur == null || (cur.km < Trips.MIN_KM && NagService.plugged) ? -1 : cur.socStart;
        if (done == null || shownVersion != NagService.tripsVersion || shownHealth != NagService.batteryHealth
                || shownTicks != NagService.ticksVersion || shownPlugged != NagService.plugged
                || shownCurSoc != curSoc) {
            rebuild(now);
        }
        rightParked.setVisibility(moving ? View.GONE : View.VISIBLE);
        rightNote.setVisibility(moving ? View.VISIBLE : View.GONE);

        boolean plugged = NagService.plugged && !moving;
        boolean charging = plugged && r != null && r.soc >= 0 && !Float.isNaN(NagService.chargeKw)
                && NagService.chargeKw > 0.2f;
        chargingRow.setVisibility(charging ? View.VISIBLE : View.GONE);
        int soc = r == null ? -1 : r.soc;
        livePct.setText(soc < 0 ? "–" : soc + "%");
        String range = r != null && r.range >= 0 ? r.range + " km range" : "";
        if (!charging) {
            rightLabel.setText("BATTERY");
            rightSum.setText(plugged ? (soc >= 100 || NagService.chargeDone ? "Charge complete" : "Plugged in, not charging") : "");
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
