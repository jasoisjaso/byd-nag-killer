package au.jaso.naglab;

import android.content.Context;
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
 * The Trips tab (layout A in the design brief): left = this trip / last trip in three big
 * numbers, right = this week (bars + total) and the trip list.
 *
 * While the car is moving only the left card is shown (one glance, three numbers); the week and
 * the list come back in P. Everything is drawn from what NagService already read: this screen
 * never talks to the car itself.
 */
final class TripsScreen {
    private final MainActivity a;
    private final View root;
    private TextView heroLabel, heroFoot, kmVal, effVal, timeVal, timeUnit, weekSum, rightNote;
    private View rightParked;
    private LinearLayout list;
    private WeekBars bars;
    private Toggle recToggle;
    private TextView recWord;
    private int shownVersion = -1;
    private long listBuiltWall;
    private float shownCurKm = -2;
    private List<Trips.Trip> done;
    private final Calendar cal = Calendar.getInstance();

    TripsScreen(MainActivity a) {
        this.a = a;
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(buildLeft(), new LinearLayout.LayoutParams(0, -1, 0.85f));
        row.addView(new View(a), new LinearLayout.LayoutParams(a.dp(32), 1));
        row.addView(buildRight(), new LinearLayout.LayoutParams(0, -1, 1.15f));
        root = row;
    }

    View view() {
        return root;
    }

    private LinearLayout stat(TextView[] valOut, TextView[] unitOut, String unit) {
        LinearLayout s = new LinearLayout(a);
        s.setOrientation(LinearLayout.VERTICAL);
        TextView v = a.text("–", 52, MainActivity.TEXT, true);
        v.setSingleLine(true);
        TextView u = a.text(unit, 20, MainActivity.SUB, false);
        s.addView(v);
        s.addView(u);
        valOut[0] = v;
        if (unitOut != null) unitOut[0] = u;
        return s;
    }

    private View buildLeft() {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout card = a.card();
        card.setPadding(a.dp(32), a.dp(28), a.dp(32), a.dp(28));
        heroLabel = a.text("THIS TRIP", 16, MainActivity.SUB, true);
        heroLabel.setLetterSpacing(0.08f);
        card.addView(heroLabel);

        LinearLayout nums = new LinearLayout(a);
        nums.setPadding(0, a.dp(16), 0, 0);
        TextView[] v = new TextView[1], u = new TextView[1];
        nums.addView(stat(v, null, "km"), new LinearLayout.LayoutParams(0, -2, 1f));
        kmVal = v[0];
        nums.addView(stat(v, null, "kWh/100km"), new LinearLayout.LayoutParams(0, -2, 1.25f));
        effVal = v[0];
        nums.addView(stat(v, u, "min"), new LinearLayout.LayoutParams(0, -2, 1f));
        timeVal = v[0];
        timeUnit = u[0];
        card.addView(nums);

        heroFoot = a.text("", 20, MainActivity.SUB, false);
        heroFoot.setPadding(0, a.dp(20), 0, 0);
        card.addView(heroFoot);
        col.addView(card, new LinearLayout.LayoutParams(-1, -2));

        // Record trips on/off (parked only, it's a setting).
        LinearLayout rr = new LinearLayout(a);
        rr.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout rt = new LinearLayout(a);
        rt.setOrientation(LinearLayout.VERTICAL);
        rt.addView(a.text("Record trips", 17, MainActivity.TEXT, true));
        TextView rs = a.text("From the car's own trip counter.", 15, MainActivity.SUB, false);
        rs.setPadding(0, a.dp(2), a.dp(12), 0);
        rt.addView(rs);
        rr.addView(rt, new LinearLayout.LayoutParams(0, -2, 1f));
        recWord = a.text("", 16, MainActivity.SUB, true);
        recWord.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rw = new LinearLayout.LayoutParams(a.dp(76), -2);
        rw.rightMargin = a.dp(8);
        rr.addView(recWord, rw);
        recToggle = new Toggle(a);
        recToggle.setLabel("Record trips");
        rr.addView(recToggle, new LinearLayout.LayoutParams(a.dp(76), a.dp(42)));
        rr.setMinimumHeight(a.dp(76));
        rr.setOnClickListener(x -> recToggle.flip());
        recToggle.setOn(NagService.tripsOn(a), false);
        recToggle.setOnChange(on -> {
            a.getSharedPreferences(NagService.PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(NagService.KEY_TRIPS, on).apply();
            NagService.sync(a);
            render();
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = a.dp(12);
        col.addView(rr, rlp);
        recRow = rr;

        // Since new, straight from the car's own counters.
        LinearLayout life = a.card();
        life.setPadding(a.dp(32), a.dp(18), a.dp(32), a.dp(18));
        TextView ll = a.text("SINCE NEW", 16, MainActivity.SUB, true);
        ll.setLetterSpacing(0.08f);
        life.addView(ll);
        lifeLine = a.text("", 24, MainActivity.TEXT, true);
        lifeLine.setPadding(0, a.dp(6), 0, 0);
        lifeLine.setSingleLine(true);
        life.addView(lifeLine);
        LinearLayout.LayoutParams lfp = new LinearLayout.LayoutParams(-1, -2);
        lfp.topMargin = a.dp(8);
        col.addView(life, lfp);
        lifeCard = life;
        ScrollView sv = new ScrollView(a);   // short screens: scroll rather than clip
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col, new FrameLayout.LayoutParams(-1, -2));
        return sv;
    }

    private View recRow, lifeCard;
    private TextView lifeLine;
    static final String KEY_PERIOD = "trips_period";
    private int period = Trips.WEEK;
    private TextView[] periodTabs;
    private TextView periodStats;
    private Trips.Trip lastShown;

    private View buildRight() {
        FrameLayout f = new FrameLayout(a);

        LinearLayout parked = new LinearLayout(a);
        parked.setOrientation(LinearLayout.VERTICAL);

        LinearLayout week = a.card();
        week.setPadding(a.dp(20), a.dp(12), a.dp(28), a.dp(14));
        LinearLayout wh = new LinearLayout(a);
        wh.setGravity(Gravity.CENTER_VERTICAL);
        // Week | Month | Year
        String[] names = {"Week", "Month", "Year"};
        periodTabs = new TextView[names.length];
        period = a.getSharedPreferences(NagService.PREFS, Context.MODE_PRIVATE).getInt(KEY_PERIOD, Trips.WEEK);
        LinearLayout seg = new LinearLayout(a);
        for (int i = 0; i < names.length; i++) {
            final int which = i;
            TextView t = a.text(names[i], 17, MainActivity.SUB, true);
            t.setGravity(Gravity.CENTER);
            t.setMinWidth(a.dp(92));
            t.setClickable(true);
            t.setOnClickListener(v -> {
                period = which;
                a.getSharedPreferences(NagService.PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_PERIOD, which).apply();
                shownVersion = -1;   // redraw the card
                render();
            });
            seg.addView(t, new LinearLayout.LayoutParams(-2, a.dp(52)));
            periodTabs[i] = t;
        }
        wh.addView(seg);
        weekSum = a.text("", 22, MainActivity.TEXT, true);
        weekSum.setGravity(Gravity.END);
        wh.addView(weekSum, new LinearLayout.LayoutParams(0, -2, 1f));
        week.addView(wh);
        bars = new WeekBars(a);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, a.dp(100));
        bl.topMargin = a.dp(8);
        week.addView(bars, bl);
        periodStats = a.text("", 17, MainActivity.SUB, false);
        periodStats.setPadding(a.dp(8), a.dp(6), 0, 0);
        periodStats.setSingleLine(true);
        week.addView(periodStats);
        parked.addView(week, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout listCard = a.card();
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.setVerticalFadingEdgeEnabled(true);   // a cut row reads as "scroll for more"
        sv.setFadingEdgeLength(a.dp(32));
        sv.addView(list, new FrameLayout.LayoutParams(-1, -2));
        listCard.addView(sv, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lp.topMargin = a.dp(16);
        parked.addView(listCard, lp);
        f.addView(parked, new FrameLayout.LayoutParams(-1, -1));
        rightParked = parked;

        rightNote = a.text("Trip history shows when you're parked.", 24, MainActivity.SUB, false);
        rightNote.setGravity(Gravity.CENTER);
        f.addView(rightNote, new FrameLayout.LayoutParams(-1, -1));
        return f;
    }

    private View tripRow(Trips.Trip t, boolean current, long now) {
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(a.dp(28), a.dp(12), a.dp(28), a.dp(12));
        r.setMinimumHeight(a.dp(96));

        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        String when = Trips.day(t.start, now, cal) + "  " + Trips.clock(t.start, cal);
        l.addView(a.text(current ? when + "  ·  now" : when, 22, MainActivity.TEXT, true));
        String sub = Trips.duration(t.min);
        if (t.socStart >= 0 && t.socEnd >= 0) sub += "  ·  " + t.socStart + "% → " + t.socEnd + "%";
        TextView s = a.text(sub, 18, MainActivity.SUB, false);
        s.setPadding(0, a.dp(4), 0, 0);
        l.addView(s);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout rr = new LinearLayout(a);
        rr.setOrientation(LinearLayout.VERTICAL);
        rr.setGravity(Gravity.END);
        TextView kmT = a.text(Trips.km(t.km) + " km", 28, MainActivity.TEXT, true);
        kmT.setGravity(Gravity.END);
        rr.addView(kmT, new LinearLayout.LayoutParams(-1, -2));
        float e = t.efficiency();
        TextView es = a.text(Float.isNaN(e) ? " " : Trips.one(e) + " kWh/100km", 18, MainActivity.SUB, false);
        es.setGravity(Gravity.END);
        rr.addView(es);
        r.addView(rr);
        r.setContentDescription(when + ", " + Trips.km(t.km) + " kilometres, " + Trips.duration(t.min)
                + (Float.isNaN(e) ? "" : ", " + Trips.one(e) + " kilowatt hours per 100 kilometres"));
        return r;
    }

    private View chargeRow(Trips.Charge c) {
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(a.dp(28), a.dp(8), a.dp(28), a.dp(8));
        r.setMinimumHeight(a.dp(56));
        String what = "Charged  " + c.socFrom + "% → " + c.socTo + "%";
        r.addView(a.text(what, 19, MainActivity.ACCENT_TEXT, true), new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(a.text((c.measured() ? "" : "about ") + Trips.km(c.kwh()) + " kWh", 18, MainActivity.SUB, false));
        r.setContentDescription("Charged from " + c.socFrom + " to " + c.socTo + " percent, about "
                + Trips.km(c.kwh()) + " kilowatt hours");
        return r;
    }

    private void rebuildList(long now) {
        done = Trips.load(new File(a.getFilesDir(), NagService.TRIPS_FILE));
        Trips.Trip cur = NagService.tripNow;
        if (cur != null) cur.live = true;
        List<Trips.Trip> all = Trips.newestFirst(done, cur);
        List<Trips.Charge> charges = Trips.charges(done, cur);
        lastShown = Trips.lastShown(done);
        list.removeAllViews();
        if (all.isEmpty()) {
            TextView e = a.text(NagService.tripsOn(a)
                    ? "No trips yet. Drive somewhere and it shows up here."
                    : "Recording is off. Turn on Record trips to keep a log.", 22, MainActivity.SUB, false);
            e.setPadding(a.dp(28), a.dp(28), a.dp(28), a.dp(28));
            list.addView(e);
        }
        int n = 0;
        for (Trips.Trip t : all) {
            if (n > 0) list.addView(a.divider());
            list.addView(tripRow(t, t == NagService.tripNow || t.live, now));
            for (Trips.Charge c : charges) {   // the charge before this trip sits under it
                if (c.to == t.start) {
                    list.addView(a.divider());
                    list.addView(chargeRow(c));
                }
            }
            if (++n >= 60) break;   // a couple of months is plenty on a car screen
        }
        Trips.Period p = Trips.period(all, now, cal, period);
        String[] spoken = {"Kilometres per day this week:", "Kilometres per day this month:", "Kilometres per month:"};
        bars.set(p.bars, p.labels, spoken[period], p.now);
        weekSum.setText(p.km <= 0 ? "No driving yet"
                : Trips.km(p.km) + " km" + (Float.isNaN(p.eff) ? "" : "  ·  " + Trips.one(p.eff) + " kWh/100km"));
        float charged = 0;
        boolean est = false;
        cal.setTimeInMillis(now);
        int y = cal.get(Calendar.YEAR), m = cal.get(Calendar.MONTH);
        long weekFrom = Trips.dayStart(now, cal) - 6 * 86_400_000L;
        for (Trips.Charge c : charges) {
            cal.setTimeInMillis(c.to);
            int ago = (y * 12 + m) - (cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH));
            boolean in = period == Trips.WEEK ? c.to >= weekFrom : period == Trips.MONTH ? ago == 0 : ago >= 0 && ago < 12;
            if (!in) continue;
            charged += c.kwh();
            est |= !c.measured();
        }
        StringBuilder st = new StringBuilder();
        if (p.trips > 0) {
            st.append(p.trips).append(p.trips == 1 ? " trip" : " trips").append("  ·  ")
                    .append(Trips.duration(p.min)).append(" driving");
            if (p.kwh > 0) st.append("  ·  ").append(Trips.km(p.kwh)).append(" kWh used");
        }
        if (charged > 0) {
            if (st.length() > 0) st.append("  ·  ");
            st.append(est ? "about " : "").append(Trips.km(charged)).append(" kWh charged");
            String cost = Trips.cost(charged, BatteryScreen.price(a));
            if (!cost.isEmpty()) st.append(" (about ").append(cost).append(")");
        }
        periodStats.setText(st);
        for (int i = 0; i < periodTabs.length; i++) {
            boolean on = i == period;
            periodTabs[i].setTextColor(on ? MainActivity.TEXT : MainActivity.SUB);
            periodTabs[i].setBackground(on ? MainActivity.round(MainActivity.RAISED, a.dp(26)) : null);
            periodTabs[i].setContentDescription(periodTabs[i].getText() + (on ? ", selected" : ""));
        }
        shownVersion = NagService.tripsVersion;
        listBuiltWall = now;
    }

    /** Called about once a second while the tab is on screen. */
    void render() {
        long now = System.currentTimeMillis();
        Trips.Reading r = NagService.live;
        boolean moving = r != null && r.moving();
        Trips.Trip cur = NagService.tripNow;

        // Hero: the trip in progress if it has gone anywhere, else the last finished one.
        Trips.Trip hero = cur;
        boolean last = false;
        if ((hero == null || hero.km < Trips.MIN_KM) && !moving) {
            if (done == null) {
                done = Trips.load(new File(a.getFilesDir(), NagService.TRIPS_FILE));
                lastShown = Trips.lastShown(done);
            }
            if (lastShown != null) {   // the same (short stops merged) trip the list shows
                hero = lastShown;
                last = true;
            }
        }
        heroLabel.setText(last ? "LAST TRIP  ·  " + Trips.day(hero.start, now, cal) + " "
                + Trips.clock(hero.start, cal) : "THIS TRIP");
        if (hero == null) {
            kmVal.setText("–");
            effVal.setText("–");
            timeVal.setText("–");
            timeUnit.setText("min");
            heroFoot.setText(NagService.running ? "Waiting for the car..." : "");
        } else {
            kmVal.setText(Trips.km(hero.km));
            effVal.setText(Trips.one(hero.efficiency()));
            if (hero.min >= 60) {
                timeVal.setText(Trips.duration(hero.min).replace(" h ", ":"));
                timeUnit.setText("h:min");
            } else {
                timeVal.setText(hero.min < 0 ? "–" : String.valueOf(hero.min));
                timeUnit.setText("min");
            }
            StringBuilder foot = new StringBuilder();
            if (!Float.isNaN(hero.kwh)) foot.append(Trips.one(hero.kwh)).append(" kWh used");
            if (hero.socStart >= 0 && hero.socEnd >= 0) {
                if (foot.length() > 0) foot.append("  ·  ");
                foot.append(hero.socStart).append("% → ").append(hero.socEnd).append("%");
            }
            if (!last && r != null && r.range >= 0) {
                if (foot.length() > 0) foot.append("  ·  ");
                foot.append(r.range).append(" km left");
            }
            heroFoot.setText(foot);
        }

        // Moving: only the hero card. Parked: settings row, week and list.
        recRow.setVisibility(moving ? View.GONE : View.VISIBLE);
        boolean lifeOk = r != null && !Float.isNaN(r.odo) && !Float.isNaN(r.life) && r.odo >= 1;
        lifeCard.setVisibility(!moving && lifeOk ? View.VISIBLE : View.GONE);
        if (lifeOk) {
            lifeLine.setText(String.format(java.util.Locale.US, "%,.0f km  ·  %,.0f kWh  ·  %.1f kWh/100km",
                    r.odo, r.life, r.life / r.odo * 100f));
        }
        rightParked.setVisibility(moving ? View.GONE : View.VISIBLE);
        rightNote.setVisibility(moving ? View.VISIBLE : View.GONE);
        recWord.setText(recToggle.isOn() ? "On" : "Off");
        float curKm = cur == null ? -1 : cur.km;
        if (!moving && (shownVersion != NagService.tripsVersion
                || (curKm != shownCurKm && now - listBuiltWall > 30_000))) {
            shownCurKm = curKm;
            rebuildList(now);
        }
    }
}
