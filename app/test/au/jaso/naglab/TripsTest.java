package au.jaso.naglab;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/** Trips from the car's counters. Readings below are from the owner's drive on 2026-11-11. */
public class TripsTest {
    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) fails++;
    }

    static final Calendar CAL = Calendar.getInstance(TimeZone.getTimeZone("Australia/Brisbane"));

    static long at(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Australia/Brisbane"));
        c.clear();
        c.set(y, mo - 1, d, h, mi, 0);
        return c.getTimeInMillis();
    }

    static Trips.Reading r(long wall, float km, int min, float kwh, float odo, int soc, int gear) {
        Trips.Reading x = new Trips.Reading();
        x.wall = wall;
        x.km = Trips.Reading.km(km);
        x.min = Trips.Reading.min(min);
        x.kwh = Trips.Reading.kwh(kwh);
        x.odo = Trips.Reading.odo(odo);
        x.soc = Trips.Reading.soc(soc);
        x.gear = gear;
        return x;
    }

    public static void main(String[] a) throws Exception {
        // Sentinels
        check(Float.isNaN(Trips.Reading.km(-1f)) && Float.isNaN(Trips.Reading.km(6553.5f)), "negative / huge trip km = no data");
        check(Float.isNaN(Trips.Reading.odo(1048575f)), "odo 1048575 = no data");
        check(Float.isNaN(Trips.Reading.kwh(2047f)), "kwh 2047 = no data");
        check(Trips.Reading.soc(255) == -1, "soc 255 = no data");

        // The real drive: parked 23 min, then 3.3 km.
        Trips.Tracker t = new Trips.Tracker();
        check(t.update(r(at(2026, 11, 11, 6, 28), 0.0f, 23, 0.3f, 5290.0f, 40, 1)) == null, "first reading starts a trip");
        check(t.current.start == at(2026, 11, 11, 6, 5), "start = now - trip time (6:05)");
        t.update(r(at(2026, 11, 11, 6, 31), 0.2f, 26, 0.4f, 5290.2f, 40, 4));
        t.update(r(at(2026, 11, 11, 6, 35), 3.3f, 30, 1.0f, 5293.3f, 39, 4));
        check(t.current.km == 3.3f && t.current.min == 30, "trip grows");
        check(t.current.socStart == 40 && t.current.socEnd == 39, "battery 40 -> 39");
        check(Math.abs(t.current.odoStart - 5290.0f) < 0.01, "odo at start");
        check(Math.abs(t.current.efficiency() - 30.3f) < 0.1, "1.0 kWh / 3.3 km = 30.3 kWh/100km");

        // Next start: counters reset -> the drive is finished.
        Trips.Trip done = t.update(r(at(2026, 11, 11, 17, 10), 0.0f, 0, 0.0f, 5293.3f, 39, 1));
        check(done != null && done.km == 3.3f, "counter reset ends the trip");
        check(t.current.km == 0f, "new trip starts at 0");

        // Driveway on/off: too short, dropped.
        t.update(r(at(2026, 11, 11, 17, 12), 0.05f, 2, 0.0f, 5293.35f, 39, 1));
        check(t.update(r(at(2026, 11, 11, 18, 0), 0.0f, 0, 0.0f, 5293.35f, 39, 1)) == null, "tiny trip dropped");

        // App missed the reset: odometer moved further than the trip counter says.
        Trips.Tracker t2 = new Trips.Tracker();
        t2.update(r(at(2026, 11, 12, 7, 0), 12.0f, 20, 2.0f, 5300f, 38, 4));
        Trips.Trip missed = t2.update(r(at(2026, 11, 12, 18, 0), 15.0f, 25, 2.5f, 5330f, 34, 4));
        check(missed != null && missed.km == 12.0f, "odo jump closes the old trip");
        check(t2.current.km == 15.0f, "and starts the one we see now");

        // Real car 11 Nov 15:30: minutes reset ~10 s before km/kWh. Must not make a 2nd trip.
        Trips.Tracker t4 = new Trips.Tracker();
        t4.update(r(at(2026, 11, 11, 15, 29), 9.7f, 26, 2.3f, 5400f, 32, 1));
        Trips.Trip first = t4.update(r(at(2026, 11, 11, 15, 30), 9.7f, 0, 2.3f, 5400f, 32, 1));
        check(first != null && first.km == 9.7f && first.min == 26, "minutes reset ends the trip once");
        check(t4.update(r(at(2026, 11, 11, 15, 30) + 5_000, 9.7f, 0, 2.3f, 5400f, 32, 1)) == null,
                "half-reset counters ignored");
        check(t4.update(r(at(2026, 11, 11, 15, 30) + 10_000, 0f, 0, 0f, 5400f, 32, 1)) == null
                && t4.current != null && t4.current.km == 0f, "no phantom trip when km resets 10 s later");
        // Car never finishes the reset: give up waiting after a minute.
        Trips.Tracker t5 = new Trips.Tracker();
        t5.update(r(at(2026, 11, 11, 15, 29), 9.7f, 26, 2.3f, 5400f, 32, 1));
        t5.update(r(at(2026, 11, 11, 15, 30), 9.7f, 0, 2.3f, 5400f, 32, 1));
        t5.update(r(at(2026, 11, 11, 15, 32), 9.9f, 2, 2.4f, 5400.2f, 32, 4));
        check(t5.current != null && t5.current.km == 9.9f, "hold ends after a minute");

        // Real car 12 Nov: at the next start the car shows the old trip for a moment before the
        // reset. That reading must not move the trip's end (it glued Wed evening + Thu together).
        Trips.Tracker t6 = new Trips.Tracker();
        t6.update(r(at(2026, 11, 11, 18, 40), 3.0f, 6, 0.6f, 5410f, 30, 4));
        t6.update(r(at(2026, 11, 11, 18, 54), 9.1f, 20, 1.9f, 5416.1f, 29, 4));   // last poll before power-off
        // next morning the car still shows the old trip, a bit further along than our last poll
        t6.update(r(at(2026, 11, 12, 6, 20), 9.5f, 21, 2.0f, 5416.5f, 54, 1));
        Trips.Trip eve = t6.update(r(at(2026, 11, 12, 6, 20) + 15_000, 0f, 0, 0f, 5416.5f, 54, 1));
        check(eve != null && eve.km == 9.5f && eve.end >= at(2026, 11, 11, 18, 55) && eve.end <= at(2026, 11, 11, 18, 56),
                "stale reading at next start: final km kept, end stays the evening before");
        check(eve.socEnd == 29, "overnight charge doesn't show as the trip's end battery");
        t6.update(r(at(2026, 11, 12, 6, 45), 9.6f, 25, 2.1f, 5426.1f, 52, 1));
        List<Trips.Trip> two = Trips.newestFirst(Arrays.asList(eve), t6.current);
        check(two.size() == 2 && !two.get(0).merged, "evening and next morning stay two trips");
        check(Trips.week(two, at(2026, 11, 12, 20, 0), CAL)[6] == 9.6f, "morning drive counts for Thursday");
        // Trips already stored by 0.8.5 with the late end are repaired on load.
        Trips.Trip bad = Trips.Trip.parse(eve.line());
        bad.end = at(2026, 11, 12, 6, 20);
        File bf = new File(Files.createTempDirectory("tripsbad").toFile(), "trips.tsv");
        Trips.append(bf, bad);
        long fixedEnd = Trips.load(bf).get(0).end;
        check(fixedEnd >= at(2026, 11, 11, 18, 55) && fixedEnd <= at(2026, 11, 11, 18, 57), "stored late end repaired");

        // Charging = battery % higher at the next trip's start than at the last trip's end.
        List<Trips.Charge> ch = Trips.charges(Arrays.asList(eve), t6.current);
        check(ch.size() == 1 && ch.get(0).socFrom == 29 && ch.get(0).socTo == 54, "overnight charge 29% -> 54% found");
        check(ch.get(0).to == t6.current.start && Math.abs(ch.get(0).kwh() - 20.64f) < 0.01, "charge sits before the morning trip, about 20.6 kWh");
        float[] cw = Trips.chargeWeek(ch, at(2026, 11, 12, 20, 0), CAL);
        check(cw[0] == 1 && cw[1] == 25 && cw[3] == 0, "one charge this week, +25%, kWh estimated");
        check(Trips.chargeWeek(ch, at(2026, 11, 25, 20, 0), CAL)[0] == 0, "old charge not in this week");
        Trips.Trip c1 = new Trips.Trip();
        c1.start = at(2026, 11, 13, 7, 0); c1.end = at(2026, 11, 13, 7, 30); c1.km = 30; c1.kwh = 5; c1.min = 30; c1.socStart = 30; c1.socEnd = 24;
        Trips.Trip c2 = new Trips.Trip();
        c2.start = at(2026, 11, 13, 7, 38); c2.end = at(2026, 11, 13, 8, 0); c2.km = 20; c2.kwh = 3; c2.min = 22; c2.socStart = 45; c2.socEnd = 41;
        check(Trips.merge(Arrays.asList(c1, c2)).size() == 2, "8 min fast-charge stop is not merged away");
        // Battery kWh from the car (real values 12 Nov: 50.1 kWh at 52%). Drive 6% down, charge
        // 21% up, drive 4% down: 31% moved 25.5 kWh -> a full battery is about 82 kWh.
        c1.batStart = 32.4f; c1.batEnd = 27.5f;
        c2.socStart = 45; c2.socEnd = 41; c2.batStart = 44.8f; c2.batEnd = 41.5f;
        Trips.Charge fast = Trips.charges(Arrays.asList(c1, c2), null).get(0);
        check(fast.measured() && Math.abs(fast.kwh() - 17.3f) < 0.01, "charge measured from the car: 17.3 kWh");
        float[] cap = Trips.capacity(Arrays.asList(c1, c2), null);
        check(cap[1] == 31f && Math.abs(cap[0] - 25.5f) < 0.01 && Math.abs(cap[0] / cap[1] * 100f - 82.3f) < 0.1,
                "full battery from 31% moved = 82.3 kWh");
        Trips.Trip rt = Trips.Trip.parse(c2.line());
        check(rt.batStart == 44.8f && rt.batEnd == 41.5f, "battery kWh stored and loaded");
        check(Float.isNaN(Trips.Trip.parse("1\t2\t3.0\t4\t1.0\tNaN\tNaN\t50\t49").batStart), "0.8.5 line still loads");
        c2.socStart = 25; c2.socEnd = 21;
        check(Trips.charges(Arrays.asList(c1, c2), null).isEmpty(), "1% up is not a charge");
        check(Trips.lastShown(Arrays.asList(c1, c2)).km == 50f, "last trip card = the merged trip the list shows");
        File csv = new File(Files.createTempDirectory("tripscsv").toFile(), "trips.csv");
        Trips.exportCsv(Arrays.asList(c1), csv, CAL);
        List<String> rows = Files.readAllLines(csv.toPath());
        check(rows.size() == 2 && rows.get(1).equals("2026-11-13,07:00,07:30,30.0,30,5.0,16.7,30,24,,,32.4,27.5"), "csv copy: " + rows.get(1));

        // Battery health: one reading per swing of 30% or more; the figure is the middle one.
        List<Trips.Trip> hl = new ArrayList<>();
        float[] fulls = {82.0f, 80.0f, 95.0f, 81.0f};   // 95 = one odd charge
        long day0 = at(2026, 9, 1, 7, 0);
        float bat = 30f, odoH = 1000f;
        for (int i = 0; i < fulls.length; i++) {
            Trips.Trip dn = new Trips.Trip();   // drive 80% -> 40%
            dn.start = day0 + i * 86_400_000L; dn.end = dn.start + 3_600_000L; dn.km = 200; dn.min = 60; dn.kwh = 30;
            dn.socStart = i == 0 ? 60 : 80; dn.socEnd = 40;
            dn.batStart = bat; dn.batEnd = bat - (dn.socStart - 40) * 0.82f;
            dn.odoStart = odoH; dn.odoEnd = odoH + 200; odoH += 200;
            hl.add(dn);
            bat = dn.batEnd + 40 * fulls[i] / 100f;   // charge 40% -> 80%
        }
        Trips.Trip nx = new Trips.Trip();
        nx.start = day0 + 4 * 86_400_000L; nx.end = nx.start + 600_000L; nx.km = 5; nx.min = 10;
        nx.socStart = 80; nx.socEnd = 79; nx.batStart = bat; nx.batEnd = bat - 0.8f; nx.odoStart = odoH;
        hl.add(nx);
        List<Trips.HealthReading> hr = Trips.health(hl, null);
        int chargesSeen = 0, drives = 0;
        for (Trips.HealthReading x : hr) { if (x.charge) chargesSeen++; else drives++; }
        check(chargesSeen == 4 && drives == 3, "health: 4 charges + 3 long drives count (20% drive and 1% drive don't): " + hr.size());
        float hk = Trips.healthKwh(hr);
        check(Math.abs(hk - 82.0f) < 0.05, "health figure = the middle reading, the odd 95 doesn't move it: " + hk);
        check(Float.isNaN(Trips.healthKwh(hr.subList(0, 2))), "no figure from two readings");
        check(Math.abs(hr.get(0).pct() - 99.3f) < 0.2 && hr.get(0).odo == 1200f, "reading: % of new and odometer");
        String rep = Trips.report(hl, null, "LGXTESTVIN0000001", 1805f, 100, "0.8.6", at(2026, 11, 12, 20, 0), CAL);
        check(rep.contains("about 99% of new") && rep.contains("82.0 kWh") && rep.contains("VIN LGXTESTVIN0000001")
                && rep.contains("1,805 km") && rep.contains("not a certificate") && rep.contains("4 charges logged")
                && rep.contains("ranged from 80.0 to 95.0"), "battery report has the figure, the range, the car and the disclaimer");
        check(Trips.report(new ArrayList<Trips.Trip>(), null, null, Float.NaN, -1, "0.8.6", at(2026, 11, 12, 20, 0), CAL)
                .contains("No figure yet"), "empty battery report says so");
        float[] mo = Trips.months(Trips.charges(hl, null), at(2026, 11, 12, 20, 0), CAL, 6);
        check(mo[5] == 0 && Math.abs(mo[3] - (32.8f + 32f + 38f + 32.4f)) < 0.05, "kWh per month: all four charges in September");
        check(Arrays.equals(Trips.monthLabels(at(2026, 2, 3, 9, 0), CAL, 4), new String[]{"Nov", "Dec", "Jan", "Feb"}), "month labels wrap the year");
        // Charged with the car on, then driven off without a restart.
        Trips.Tracker t7 = new Trips.Tracker();
        Trips.Reading on = r(at(2026, 11, 14, 9, 0), 0f, 1, 0f, 6000f, 50, 1); on.bat = 48f;
        t7.update(on);
        Trips.Reading up = r(at(2026, 11, 14, 9, 40), 0f, 41, 0.2f, 6000f, 70, 1); up.bat = 64.5f;
        t7.update(up);
        Trips.Reading go = r(at(2026, 11, 14, 9, 55), 9f, 56, 1.9f, 6009f, 68, 4); go.bat = 62.8f;
        t7.update(go);
        check(t7.current.socStart == 70 && t7.current.batStart == 64.5f, "charging with the car on moves the trip's start battery up");
        check(t7.current.rise == at(2026, 11, 14, 9, 40) && Trips.Trip.parse(t7.current.line()).rise == t7.current.rise, "and remembers when the charge was last seen going up");

        // No data from the car = no trip.
        Trips.Tracker t3 = new Trips.Tracker();
        check(t3.update(r(at(2026, 11, 12, 7, 0), Float.NaN, -1, Float.NaN, Float.NaN, -1, -1)) == null
                && t3.current == null, "unreadable reading ignored");

        // Moving
        check(!r(0, 0, 0, 0, 0, 0, 1).moving(), "P = parked");
        check(r(0, 0, 0, 0, 0, 0, 4).moving(), "D = moving");
        Trips.Reading sp = r(0, 0, 0, 0, 0, 0, -1);
        sp.speed = 40;
        check(sp.moving(), "unknown gear but 40 km/h = moving");

        // Storage round trip
        File dir = Files.createTempDirectory("trips").toFile();
        File f = new File(dir, "trips.tsv");
        Trips.append(f, done);
        Trips.append(f, missed);
        List<Trips.Trip> back = Trips.load(f);
        check(back.size() == 2 && back.get(0).km == 3.3f && back.get(1).socEnd == 38, "append + load");
        File cur = new File(dir, "trip-current.tsv");
        Trips.saveCurrent(cur, t2.current);
        check(Trips.loadCurrent(cur).km == 15.0f, "current trip saved and loaded");
        Files.write(f.toPath(), Arrays.asList("garbage", done.line()));
        check(Trips.load(f).size() == 1, "bad lines skipped");
        Trips.Trip ph = Trips.Trip.parse(first.line());
        ph.start = first.end + 10_000;
        ph.end = ph.start;
        ph.min = 0;
        Files.write(f.toPath(), Arrays.asList(first.line(), ph.line()));
        check(Trips.load(f).size() == 1, "stored phantom copy skipped on load");

        // Summaries
        long now = at(2026, 11, 12, 20, 0);
        List<Trips.Trip> all = new ArrayList<>(Arrays.asList(done, missed));
        float[] wk = Trips.week(all, now, CAL);
        check(wk[6] == 12.0f && wk[5] == 3.3f, "week bars: yesterday 3.3, today 12");
        float[] tot = Trips.weekTotals(all, now, CAL);
        check(Math.abs(tot[0] - 15.3f) < 0.01 && Math.abs(tot[1] - 3.0f / 15.3f * 100f) < 0.1, "week totals");
        check(Trips.newestFirst(all, t2.current).get(0) == t2.current, "current trip first");
        check(Arrays.equals(Trips.weekLabels(now, CAL), new String[]{"F", "S", "S", "M", "T", "W", "T"}),
                "day letters end on Thursday 12 Nov");

        // Week / month / year
        Trips.Period pw = Trips.period(all, now, CAL, Trips.WEEK);
        check(pw.bars.length == 7 && pw.bars[6] == 12.0f && pw.bars[5] == 3.3f && pw.trips == 2 && pw.min == 50 && pw.now == 6,
                "week view: two trips, 50 min");
        Trips.Period pm = Trips.period(all, now, CAL, Trips.MONTH);
        check(pm.bars.length == 30 && pm.bars[10] == 3.3f && pm.bars[11] == 12.0f && pm.now == 11
                && pm.labels[0].equals("1") && pm.labels[7].equals("8") && pm.labels[1].isEmpty(), "month view: November by day");
        Trips.Period py = Trips.period(all, at(2027, 1, 5, 9, 0), CAL, Trips.YEAR);
        check(py.bars.length == 12 && Math.abs(py.bars[9] - 15.3f) < 0.01 && py.labels[11].equals("J") && py.labels[9].equals("N"),
                "year view: November is two bars back from January");
        check(Trips.period(all, at(2027, 1, 5, 9, 0), CAL, Trips.MONTH).trips == 0, "new month starts empty");

        // Short stop merged, long stop not
        Trips.Trip m1 = new Trips.Trip();
        m1.start = at(2026, 11, 13, 7, 0); m1.end = at(2026, 11, 13, 7, 30); m1.km = 30; m1.kwh = 5; m1.socStart = 80; m1.socEnd = 74;
        Trips.Trip m2 = new Trips.Trip();
        m2.start = at(2026, 11, 13, 7, 35); m2.end = at(2026, 11, 13, 8, 0); m2.km = 20; m2.kwh = 3; m2.socStart = 74; m2.socEnd = 70;
        Trips.Trip m3 = new Trips.Trip();
        m3.start = at(2026, 11, 13, 17, 0); m3.end = at(2026, 11, 13, 17, 40); m3.km = 50; m3.kwh = 9;
        List<Trips.Trip> mg = Trips.merge(Arrays.asList(m1, m2, m3));
        check(mg.size() == 2 && mg.get(0).km == 50f && mg.get(0).kwh == 8f && mg.get(0).min == 60,
                "5 min stop merged: 50 km, 8 kWh, 60 min");
        check(mg.get(0).socStart == 80 && mg.get(0).socEnd == 70, "merged battery 80 -> 70");
        check(mg.get(1) == m3, "9 hour gap not merged");
        check(Trips.newestFirst(Arrays.asList(m1, m2), null).size() == 1, "list shows the merged trip");

        // Everyday health readings: the percent ticks exactly as the battery kWh passes n% of full.
        // Real values from the 12 Nov charge: 42.8 kWh at 52%, 43.0 at 53%, 43.8 at 54%, 44.6 at 55%.
        Trips.Reading q1 = r(at(2026, 11, 12, 20, 32), 0, 5, 0, 5368.6f, 52, 1); q1.bat = 42.8f;
        Trips.Reading q2 = r(at(2026, 11, 12, 20, 34), 0, 7, 0, 5368.6f, 53, 1); q2.bat = 43.0f;
        Trips.Tick tk1 = Trips.tick(q1, q2);
        check(tk1 != null && tk1.soc == 53 && Math.abs(tk1.kwh - 42.9f / 53f * 100f) < 0.01 && Math.abs(tk1.kwh - 80.9f) < 0.2,
                "percent 52 -> 53 at 42.9 kWh = a full battery of about 81 kWh: " + (tk1 == null ? null : tk1.kwh));
        Trips.Reading q3 = r(at(2026, 11, 12, 20, 35), 0, 8, 0, 5368.6f, 53, 1); q3.bat = 43.0f;
        check(Trips.tick(q2, q3) == null, "no reading while the percent stays put");
        Trips.Reading q4 = r(at(2026, 11, 12, 20, 50), 0, 23, 0, 5368.6f, 54, 1); q4.bat = 44.4f;
        check(Trips.tick(q3, q4) == null, "no reading when the two looks are too far apart");
        Trips.Reading lo1 = r(at(2026, 11, 12, 20, 32), 0, 5, 0, 5368.6f, 21, 1); lo1.bat = 17.1f;
        Trips.Reading lo2 = r(at(2026, 11, 12, 20, 33), 0, 6, 0, 5368.6f, 20, 1); lo2.bat = 16.9f;
        check(Trips.tick(lo1, lo2) == null, "no reading low in the battery");
        Trips.Reading dn1 = r(at(2026, 11, 13, 8, 0), 5, 10, 1, 5373f, 58, 4); dn1.bat = 47.1f;
        Trips.Reading dn2 = r(at(2026, 11, 13, 8, 1), 6, 11, 1.2f, 5374f, 57, 4); dn2.bat = 46.9f;
        Trips.Tick tk2 = Trips.tick(dn1, dn2);
        check(tk2 != null && tk2.soc == 58 && Math.abs(tk2.kwh - 81.03f) < 0.05, "driving down 58 -> 57 reads the 58% line: " + (tk2 == null ? null : tk2.kwh));
        List<Trips.Tick> tl = new ArrayList<>();
        float[] day1 = {80.9f, 81.2f, 80.7f, 84.0f}, day2 = {81.0f, 81.1f};
        for (float v : day1) { Trips.Tick x = new Trips.Tick(); x.wall = at(2026, 11, 12, 20, 30); x.kwh = v; x.odo = 5368f; tl.add(x); }
        for (float v : day2) { Trips.Tick x = new Trips.Tick(); x.wall = at(2026, 11, 13, 8, 30); x.kwh = v; x.odo = 5380f; tl.add(x); }
        List<Trips.HealthReading> hd = Trips.healthDays(tl, CAL);
        check(hd.size() == 1 && Math.abs(hd.get(0).kwh - 81.05f) < 0.01 && hd.get(0).span == 4, "a day's dot = the middle of its readings; a day with two readings waits");
        check(Math.abs(Trips.healthNow(hd) - 81.05f) < 0.01 && Float.isNaN(Trips.healthNow(new ArrayList<Trips.HealthReading>())), "health figure from the days");
        File tf = new File(Files.createTempDirectory("ticks").toFile(), "t.tsv");
        Trips.appendTick(tf, tk1);
        Trips.appendTick(tf, tk2);
        check(Trips.loadTicks(tf).size() == 2 && Trips.loadTicks(tf).get(1).soc == 58, "readings stored and loaded");

        // Full test and kinds of charger
        Trips.Trip e1 = new Trips.Trip();
        e1.start = at(2026, 12, 1, 17, 0); e1.end = at(2026, 12, 1, 18, 0); e1.km = 60; e1.min = 60; e1.kwh = 11;
        e1.socStart = 22; e1.socEnd = 8; e1.batStart = 18.0f; e1.batEnd = 6.8f;
        Trips.Trip e2 = new Trips.Trip();
        e2.start = at(2026, 12, 2, 7, 0); e2.end = at(2026, 12, 2, 7, 30); e2.km = 20; e2.min = 30; e2.kwh = 4;
        e2.socStart = 100; e2.socEnd = 95; e2.batStart = 81.0f; e2.batEnd = 77.0f;
        List<Trips.Charge> tc = Trips.charges(Arrays.asList(e1, e2), null);
        Trips.Charge full = Trips.lastTest(tc);
        check(full != null && Math.abs(full.fullKwh() - 74.2f / 91.5f * 100f) < 0.01 && Math.abs(full.fullKwh() - 81.1f) < 0.1,
                "full test 8% -> 100%: 74.2 kWh over 91.5% = 81.1 kWh");
        check(!full.mustBeDc() && Math.abs(full.minKw() - 74.2f / 13f) < 0.01, "13 hours for 74 kWh could be AC");
        Trips.Trip e3 = new Trips.Trip();
        e3.start = at(2026, 12, 2, 8, 0); e3.end = e3.start + 600_000L; e3.km = 5; e3.min = 10;
        e3.socStart = 95; e3.socEnd = 94; e3.batStart = 77.0f; e3.batEnd = 76.2f;
        e2.socEnd = 40; e2.batEnd = 32.4f;
        Trips.Charge fastC = Trips.charges(Arrays.asList(e2, e3), null).get(0);
        check(fastC.mustBeDc(), "44.6 kWh in half an hour can only be a fast charger");
        check(Trips.lastTest(Trips.charges(Arrays.asList(e2, e3), null)) == null, "40% -> 95% is not a full test");
        check(Math.abs(Trips.chargeCost(fastC, true, 28, 65) - 44.6f * 0.65f) < 0.01 && Math.abs(Trips.chargeCost(fastC, false, 28, 65) - 44.6f * 0.28f) < 0.01
                && Float.isNaN(Trips.chargeCost(fastC, true, 28, 0)), "cost by kind of charger; no price = no figure");
        check(Math.abs(Trips.petrolCost(100f, 74, 237) - 17.54f) < 0.01 && Float.isNaN(Trips.petrolCost(100f, 0, 237)), "100 km at 7.4 L/100km and 237c = $17.54");
        check(Trips.dollars(17.538f).equals("$17.54") && Trips.dollars(1234.5f).equals("$1,234") || Trips.dollars(1234.5f).equals("$1,235"), "dollars");
        // The battery test: started by the owner, followed step by step.
        Trips.TestRun run = new Trips.TestRun();
        Trips.Reading b0 = r(at(2026, 12, 1, 16, 0), 0, 1, 0, 5990f, 22, 4); b0.bat = 18.0f;
        check(!run.start(b0.wall, b0) && run.state == Trips.TestRun.IDLE, "a test can't start at 22%");
        Trips.Reading b1 = r(at(2026, 12, 1, 17, 30), 40, 60, 8, 6030f, 10, 4); b1.bat = 8.3f;
        check(run.start(b1.wall, b1) && run.state == Trips.TestRun.CHARGE && run.lowSoc == 10, "at 10% it starts, low point recorded");
        Trips.Reading b2 = r(at(2026, 12, 1, 18, 0), 60, 90, 11, 6050f, 8, 4); b2.bat = 6.8f;
        run.update(b2, false);
        check(run.lowSoc == 8 && run.lowBat == 6.8f && run.lowOdo == 6050f, "driving on lower moves the low point down");
        Trips.Reading b3 = r(at(2026, 12, 1, 21, 0), 0, 5, 0, 6050f, 31, 1); b3.bat = 25.2f;
        run.update(b3, false);
        check(run.state == Trips.TestRun.CHARGE && run.lastSoc == 31, "part way up, still running");
        check(Trips.TestRun.parse(run.line()).lowSoc == 8 && Trips.TestRun.parse(run.line()).state == Trips.TestRun.CHARGE, "test state stored and loaded");
        Trips.Reading b4 = r(at(2026, 12, 2, 7, 0), 0, 1, 0, 6050f, 100, 1); b4.bat = 81.0f;
        run.update(b4, false);
        check(run.state == Trips.TestRun.DONE && Math.abs(run.fullKwh - 74.2f / 91.5f * 100f) < 0.01 && Math.abs(run.kwhIn() - 74.2f) < 0.01,
                "full: 74.2 kWh over 91.5% = 81.1 kWh");
        Trips.TestRun drove = new Trips.TestRun();
        drove.start(b2.wall, b2);
        Trips.Reading d1 = r(at(2026, 12, 1, 22, 0), 5, 10, 1, 6056f, 40, 4); d1.bat = 32.5f;
        drove.update(d1, false);
        check(drove.state == Trips.TestRun.FAILED && drove.reason.contains("driven at 40%"), "driving before full stops the test and says why");
        Trips.TestRun fastT = new Trips.TestRun();
        fastT.start(b2.wall, b2);
        Trips.Reading f1 = r(at(2026, 12, 1, 18, 20), 0, 1, 0, 6050f, 20, 1); f1.bat = 16.4f;
        fastT.update(f1, true);
        check(fastT.state == Trips.TestRun.FAILED && fastT.reason.contains("AC charger"), "a fast charger stops the test");
        Trips.TestRun idle = new Trips.TestRun();
        check(Trips.guessPack(81.0f, 5369f) == Trips.PACK_STD && Trips.guessPack(89.8f, 5369f) == Trips.PACK_BIG
                && Float.isNaN(Trips.guessPack(81.0f, 120000f)) && Float.isNaN(Trips.guessPack(Float.NaN, 100f))
                && Trips.guessPack(88.0f, 150000f) == Trips.PACK_BIG, "pack guess: big, standard, or can't tell");
        check(!idle.update(b4, false) && idle.state == Trips.TestRun.IDLE, "nothing counts unless a test was started");
        check(Trips.TestRun.parse("1\t5\t0\t0\t-1\t57\tNaN\tNaN\tNaN\tNaN\t").state == Trips.TestRun.IDLE, "an old drive-it-down test is dropped");
        String rep2 = Trips.report(Arrays.asList(e1, e2), null, hd, Trips.healthNow(hd), run, 86.0f, null, 6000f, 1100f, 100, "0.8.6", at(2026, 12, 3, 9, 0), CAL);
        check(rep2.contains("Battery test") && rep2.contains("The charger's own display showed 86.0 kWh") && rep2.contains("98% of new")
                && rep2.contains("tested 2 Dec 2026 at 6,050 km") && rep2.contains("Day-to-day readings") && rep2.contains("Use since new"),
                "report leads with the test, the charger check, then day-to-day readings");

        // Formatting
        check(Trips.cost(37.7f, 30).equals("$11.31") && Trips.cost(37.7f, 0).isEmpty(), "cost: 37.7 kWh at 30c = $11.31, nothing without a price");
        check(rep.contains("<svg") && rep.contains("warranty line"), "report has the ring picture");
        check(Trips.km(12.44f).equals("12.4") && Trips.km(148.2f).equals("148"), "km format");
        check(Trips.duration(23).equals("23 min") && Trips.duration(65).equals("1 h 05 min"), "durations");
        check(Trips.clock(at(2026, 11, 11, 7, 42), CAL).equals("7:42 am"), "7:42 am");
        check(Trips.clock(at(2026, 11, 11, 12, 5), CAL).equals("12:05 pm"), "12:05 pm");
        check(Trips.day(at(2026, 11, 12, 7, 0), now, CAL).equals("Today"), "Today");
        check(Trips.day(at(2026, 11, 11, 7, 0), now, CAL).equals("Yesterday"), "Yesterday");
        check(Trips.day(at(2026, 11, 9, 7, 0), now, CAL).equals("Mon 9 Nov"), "Mon 9 Nov");
        check(Trips.one(Float.NaN).equals("–"), "missing value shows a dash");

        if (fails > 0) {
            System.out.println(fails + " FAILED");
            System.exit(1);
        }
        System.out.println("TripsTest OK");
    }
}
