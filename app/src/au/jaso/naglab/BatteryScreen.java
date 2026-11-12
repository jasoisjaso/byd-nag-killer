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
    private TextView priceText;
    private View priceRow;
    private TextView capVal, capSub, healthNote, carLine;
    private TextView rightLabel, rightSum, livePct, liveKw, liveTime, liveFoot, rightNote;
    private View liveBox, monthBox, rightParked;
    private WeekBars months;
    private LinearLayout list;
    private int shownVersion = -1, shownHealth = -2, shownCurSoc = -2;
    private List<Trips.Trip> done;

    BatteryScreen(MainActivity a) {
        this.a = a;
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(buildLeft(), new LinearLayout.LayoutParams(0, -1, 0.95f));
        row.addView(new View(a), new LinearLayout.LayoutParams(a.dp(32), 1));
        row.addView(buildRight(), new LinearLayout.LayoutParams(0, -1, 1.05f));
        root = row;
    }

    View view() {
        return root;
    }

    private TextView label(String s) {
        TextView t = a.text(s, 16, MainActivity.SUB, true);
        t.setLetterSpacing(0.08f);
        return t;
    }

    private View buildLeft() {
        LinearLayout card = a.card();
        card.setPadding(a.dp(32), a.dp(24), a.dp(32), a.dp(20));
        card.addView(label("BATTERY HEALTH"));

        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ring = new BatteryViews.Ring(a);
        top.addView(ring, new LinearLayout.LayoutParams(a.dp(200), a.dp(200)));
        LinearLayout tx = new LinearLayout(a);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(a.dp(28), 0, 0, 0);
        capVal = a.text("–", 44, MainActivity.TEXT, true);
        capVal.setSingleLine(true);
        tx.addView(capVal);
        capSub = a.text("", 18, MainActivity.SUB, false);
        tx.addView(capSub);
        healthNote = a.text("", 16, MainActivity.SUB, false);
        healthNote.setPadding(0, a.dp(12), 0, 0);
        tx.addView(healthNote);
        top.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = a.dp(8);
        card.addView(top, tp);

        trend = new BatteryViews.Trend(a);
        LinearLayout.LayoutParams trp = new LinearLayout.LayoutParams(-1, 0, 1f);
        trp.topMargin = a.dp(12);
        card.addView(trend, trp);
        trend.setMinimumHeight(a.dp(96));

        LinearLayout foot = new LinearLayout(a);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        carLine = a.text("", 15, MainActivity.SUB, false);
        foot.addView(carLine, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView rep = a.text("Battery report", 17, MainActivity.TEXT, true);
        rep.setGravity(Gravity.CENTER);
        rep.setPadding(a.dp(22), 0, a.dp(22), 0);
        rep.setBackground(MainActivity.round(MainActivity.RAISED, a.dp(28)));
        rep.setClickable(true);
        rep.setOnClickListener(v -> report());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, a.dp(56));
        rp.leftMargin = a.dp(12);
        foot.addView(rep, rp);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.topMargin = a.dp(8);
        card.addView(foot, fp);
        return card;
    }

    private View buildRight() {
        FrameLayout f = new FrameLayout(a);
        LinearLayout parked = new LinearLayout(a);
        parked.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = a.card();
        top.setPadding(a.dp(28), a.dp(20), a.dp(28), a.dp(16));
        LinearLayout hd = new LinearLayout(a);
        hd.setGravity(Gravity.CENTER_VERTICAL);
        rightLabel = label("CHARGING");
        hd.addView(rightLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        rightSum = a.text("", 20, MainActivity.TEXT, true);
        hd.addView(rightSum);
        top.addView(hd);

        // Not plugged in: kWh per month.
        months = new WeekBars(a);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, a.dp(96));
        ml.topMargin = a.dp(8);
        top.addView(months, ml);
        // What you pay per kWh: always the first row of the list under this card.
        LinearLayout pr = new LinearLayout(a);
        pr.setGravity(Gravity.CENTER_VERTICAL);
        pr.setPadding(a.dp(28), a.dp(10), a.dp(28), a.dp(10));
        priceText = a.text("", 20, MainActivity.SUB, true);
        pr.addView(priceText, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView minus = pill("−", () -> bumpPrice(-1));
        minus.setContentDescription("Price down one cent");
        pr.addView(minus, new LinearLayout.LayoutParams(a.dp(72), a.dp(52)));
        TextView plus = pill("+", () -> bumpPrice(1));
        plus.setContentDescription("Price up one cent");
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(a.dp(72), a.dp(52));
        pp.leftMargin = a.dp(12);
        pr.addView(plus, pp);
        priceRow = pr;
        monthBox = months;

        // Plugged in with the car on: what is going in right now.
        LinearLayout live = new LinearLayout(a);
        live.setOrientation(LinearLayout.VERTICAL);
        LinearLayout nums = new LinearLayout(a);
        nums.setGravity(Gravity.BOTTOM);
        livePct = a.text("–", 60, MainActivity.TEXT, true);
        livePct.setSingleLine(true);
        nums.addView(livePct, new LinearLayout.LayoutParams(0, -2, 1f));
        liveKw = a.text("", 30, MainActivity.ACCENT_TEXT, true);
        liveKw.setPadding(0, 0, 0, a.dp(10));
        nums.addView(liveKw);
        live.addView(nums);
        liveBar = new BatteryViews.Bar(a);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, a.dp(40));
        bl.topMargin = a.dp(4);
        live.addView(liveBar, bl);
        liveTime = a.text("", 20, MainActivity.TEXT, true);
        liveTime.setPadding(0, a.dp(12), 0, 0);
        live.addView(liveTime);
        liveFoot = a.text("", 17, MainActivity.SUB, false);
        liveFoot.setPadding(0, a.dp(2), 0, 0);
        live.addView(liveFoot);
        top.addView(live, new LinearLayout.LayoutParams(-1, -2));
        liveBox = live;
        parked.addView(top, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout listCard = a.card();
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.setVerticalFadingEdgeEnabled(true);
        sv.setFadingEdgeLength(a.dp(32));
        sv.addView(list, new FrameLayout.LayoutParams(-1, -2));
        listCard.addView(sv, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lp.topMargin = a.dp(16);
        parked.addView(listCard, lp);
        f.addView(parked, new FrameLayout.LayoutParams(-1, -1));
        rightParked = parked;

        rightNote = a.text("Charging history shows when you're parked.", 24, MainActivity.SUB, false);
        rightNote.setGravity(Gravity.CENTER);
        f.addView(rightNote, new FrameLayout.LayoutParams(-1, -1));
        return f;
    }

    private View chargeRow(Trips.Charge c, long now) {
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(a.dp(28), a.dp(12), a.dp(28), a.dp(12));
        r.setMinimumHeight(a.dp(96));

        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        String day = Trips.day(c.to, now, cal);
        l.addView(a.text(day, 22, MainActivity.TEXT, true));
        // The app wasn't watching: all it knows is the window the charge happened in.
        TextView s = a.text(Trips.clock(c.from, cal) + " – " + Trips.clock(c.to, cal), 17, MainActivity.SUB, false);
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
        int cents = price(a);
        if (cents > 0) {
            TextView ct = a.text("about " + Trips.cost(c.kwh(), cents), 16, MainActivity.SUB, false);
            ct.setGravity(Gravity.END);
            kc.addView(ct, new LinearLayout.LayoutParams(-1, -2));
        }
        r.addView(kc);
        r.setContentDescription(day + ", charged from " + c.socFrom + " to " + c.socTo + " percent, "
                + (c.measured() ? "" : "about ") + Trips.km(c.kwh()) + " kilowatt hours");
        return r;
    }

    private void rebuild(long now) {
        done = Trips.load(new File(a.getFilesDir(), NagService.TRIPS_FILE));
        Trips.Trip cur = NagService.tripNow;
        // A charge counts once the car has driven off with it (until then it is still going).
        Trips.Trip moved = cur != null && cur.km >= Trips.MIN_KM ? cur : null;
        List<Trips.Charge> charges = Trips.charges(done, moved);
        List<Trips.HealthReading> health = Trips.health(done, moved);

        // Health
        float full = Trips.healthKwh(health);
        boolean early = health.size() < Trips.HEALTH_SETTLED;
        trend.set(health);
        if (Float.isNaN(full)) {
            ring.set(Float.NaN, true);
            capVal.setVisibility(View.GONE);
            capSub.setText("No figure yet");
            healthNote.setText("Charge " + Trips.HEALTH_MIN_SPAN + "% or more in one go, then drive. The car can be "
                    + "off while it charges and it doesn't need to reach 100%. " + health.size() + " of "
                    + Trips.HEALTH_EARLY + " done.");
        } else {
            ring.set(full / Trips.PACK_KWH * 100f, early);
            capVal.setVisibility(View.VISIBLE);
            capVal.setText(Trips.one(full) + " kWh");
            capSub.setText("full battery now (estimate)  ·  " + Trips.one(Trips.PACK_KWH) + " new");
            healthNote.setText("From " + health.size() + " big charges or drives, by the car's own battery "
                    + "computer. Good to about 2%." + (early ? " Early: settles after "
                    + Trips.HEALTH_SETTLED + "." : ""));
        }
        int car = NagService.batteryHealth;
        carLine.setText("Warranty line " + (int) Trips.WARRANTY_PCT + "%"
                + (car >= 0 ? "  ·  car's own figure " + car + "%" : ""));
        carLine.setMaxLines(2);

        // Months
        float[] m = Trips.months(charges, now, cal, 6);
        months.set(m, Trips.monthLabels(now, cal, 6), "Kilowatt hours charged per month:");
        int cents = price(a);
        monthSum = m[5] <= 0 ? "Nothing this month yet" : Trips.km(m[5]) + " kWh this month"
                + (cents > 0 ? "  ·  about " + Trips.cost(m[5], cents) : "");
        priceText.setText(cents > 0 ? "You pay " + cents + "c per kWh" : "Set what you pay per kWh to see costs");
        priceText.setTextColor(cents > 0 ? MainActivity.TEXT : MainActivity.SUB);

        // List, newest first
        list.removeAllViews();
        list.addView(priceRow);
        list.addView(a.divider());
        if (charges.isEmpty()) {
            TextView e = a.text("No charges seen yet. A charge shows up after the first drive that follows it.",
                    20, MainActivity.SUB, false);
            e.setPadding(a.dp(28), a.dp(28), a.dp(28), a.dp(28));
            list.addView(e);
        }
        int n = 0;
        for (int i = charges.size() - 1; i >= 0; i--) {
            if (n > 0) list.addView(a.divider());
            list.addView(chargeRow(charges.get(i), now));
            if (++n >= 60) break;
        }
        shownVersion = NagService.tripsVersion;
        shownHealth = car;
        shownCurSoc = moved == null ? -1 : moved.socStart;
    }

    static final String REPORT_FILE = "battery-report.html";

    /** Writes the report next to the car report and says where it is. */
    private void report() {
        String msg;
        try {
            File dir = a.getExternalFilesDir(null);
            if (dir == null) throw new java.io.IOException("no storage");
            List<Trips.Trip> all = Trips.load(new File(a.getFilesDir(), NagService.TRIPS_FILE));
            Trips.Trip cur = NagService.tripNow;
            Trips.Trip moved = cur != null && cur.km >= Trips.MIN_KM ? cur : null;
            Trips.Reading r = NagService.live;
            String ver = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
            String html = Trips.report(all, moved, NagService.vin, r == null ? Float.NaN : r.odo,
                    NagService.batteryHealth, ver, System.currentTimeMillis(), cal);
            File f = new File(dir, REPORT_FILE);
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
                o.write(html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            File csvF = new File(dir, "trips.csv");
            Trips.exportCsv(all, csvF, cal);
            List<Trips.HealthReading> hr = Trips.health(all, moved);
            float full = Trips.healthKwh(hr);
            List<String> ips = ReportServer.addresses();
            if (!ips.isEmpty()) {
                server = new ReportServer(html, java.nio.file.Files.readAllBytes(csvF.toPath()));
                if (!server.start()) server = null;
            }
            msg = (Float.isNaN(full) ? "No health figure yet (" + hr.size() + " of " + Trips.HEALTH_EARLY
                    + " readings), so it only has your charging and trips so far.\n\n"
                    : "Battery about " + Math.round(Math.min(100f, full / Trips.PACK_KWH * 100f)) + "% of new, from "
                    + hr.size() + " readings.\n\n")
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

    // ---- Price per kWh: one number, set with two big buttons. 0 = no cost shown anywhere.
    static final String KEY_PRICE = "price_cents";

    static int price(android.content.Context c) {
        return c.getSharedPreferences(NagService.PREFS, android.content.Context.MODE_PRIVATE).getInt(KEY_PRICE, 0);
    }

    private void bumpPrice(int by) {
        int p = price(a);
        p = p == 0 ? (by > 0 ? 30 : 0) : Math.max(0, Math.min(150, p + by));   // first press starts at 30c
        a.getSharedPreferences(NagService.PREFS, android.content.Context.MODE_PRIVATE).edit().putInt(KEY_PRICE, p).apply();
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

    private String monthSum = "";

    /** Called about once a second while the tab is on screen. */
    void render() {
        long now = System.currentTimeMillis();
        Trips.Reading r = NagService.live;
        boolean moving = r != null && r.moving();
        Trips.Trip cur = NagService.tripNow;
        int curSoc = cur == null || cur.km < Trips.MIN_KM ? -1 : cur.socStart;
        if (done == null || shownVersion != NagService.tripsVersion || shownHealth != NagService.batteryHealth
                || shownCurSoc != curSoc) {
            rebuild(now);
        }
        rightParked.setVisibility(moving ? View.GONE : View.VISIBLE);
        rightNote.setVisibility(moving ? View.VISIBLE : View.GONE);

        boolean charging = !moving && NagService.plugged && r != null && r.soc >= 0
                && !Float.isNaN(NagService.chargeKw) && NagService.chargeKw > 0.2f;
        liveBox.setVisibility(charging ? View.VISIBLE : View.GONE);
        monthBox.setVisibility(charging ? View.GONE : View.VISIBLE);
        rightLabel.setText(charging ? "CHARGING NOW" : "CHARGING");
        if (!charging) {
            liveBar.set(-1, 0, false);   // stops the animation
            rightSum.setText(NagService.plugged && !moving ? "Plugged in, not charging" : monthSum);
            return;
        }
        // Where this charge started: the battery at the end of the last trip.
        Trips.Trip last = done.isEmpty() ? null : done.get(done.size() - 1);
        int from = last != null && last.socEnd >= 0 && last.socEnd <= r.soc ? last.socEnd : -1;
        float added = last != null && !Float.isNaN(last.batEnd) && !Float.isNaN(r.bat) ? r.bat - last.batEnd
                : Float.NaN;
        rightSum.setText("");
        livePct.setText(r.soc + "%");
        liveKw.setText(Trips.one(NagService.chargeKw) + " kW");
        liveBar.set(from, r.soc, true);
        int rest = NagService.chargeRestMin;
        liveTime.setText(rest < 0 ? "Charging" : rest == 0 ? "Nearly full"
                : "Full in " + Trips.duration(rest) + "  ·  about " + Trips.clock(now + rest * 60_000L, cal)
                + " (car's estimate)");
        StringBuilder foot = new StringBuilder();
        if (!Float.isNaN(added) && added >= 0.1f) foot.append("+").append(Trips.one(added)).append(" kWh so far");
        else if (from >= 0 && r.soc > from) foot.append("+").append(r.soc - from).append("% so far");
        if (r.range >= 0) {
            if (foot.length() > 0) foot.append("  ·  ");
            foot.append(r.range).append(" km range");
        }
        liveFoot.setText(foot);
    }
}
